/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.resolve;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.JetSemanticServices;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.calls.autocasts.DataFlowInfo;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;
import org.jetbrains.jet.lang.resolve.scopes.WritableScope;
import org.jetbrains.jet.lang.resolve.scopes.WritableScopeImpl;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ExtensionReceiver;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ReceiverDescriptor;
import org.jetbrains.jet.lang.types.*;
import org.jetbrains.jet.lexer.JetTokens;
import org.jetbrains.jet.util.lazy.LazyValue;
import org.jetbrains.jet.util.lazy.LazyValueWithDefault;

import java.util.*;

import static org.jetbrains.jet.lang.diagnostics.Errors.*;

/**
 * @author abreslav
 */
public class DescriptorResolver {
    private final JetSemanticServices semanticServices;
    private final TypeResolver typeResolver;
    private final TypeResolver typeResolverNotCheckingBounds;
    private final BindingTrace trace;
    private final AnnotationResolver annotationResolver;

    public DescriptorResolver(JetSemanticServices semanticServices, BindingTrace trace) {
        this.semanticServices = semanticServices;
        this.typeResolver = new TypeResolver(semanticServices, trace, true);
        this.typeResolverNotCheckingBounds = new TypeResolver(semanticServices, trace, false);
        this.trace = trace;
        this.annotationResolver = new AnnotationResolver(semanticServices, trace);
    }

    public void resolveMutableClassDescriptor(@NotNull JetClass classElement, @NotNull MutableClassDescriptor descriptor) {
        // TODO : Where-clause
        List<TypeParameterDescriptor> typeParameters = Lists.newArrayList();
        int index = 0;
        for (JetTypeParameter typeParameter : classElement.getTypeParameters()) {
            TypeParameterDescriptor typeParameterDescriptor = TypeParameterDescriptor.createForFurtherModification(
                    descriptor,
                    annotationResolver.createAnnotationStubs(typeParameter.getModifierList()),
                    !typeParameter.hasModifier(JetTokens.ERASED_KEYWORD),
                    typeParameter.getVariance(),
                    JetPsiUtil.safeName(typeParameter.getName()),
                    index
            );
            trace.record(BindingContext.TYPE_PARAMETER, typeParameter, typeParameterDescriptor);
            typeParameters.add(typeParameterDescriptor);
            index++;
        }
        descriptor.setTypeParameterDescriptors(typeParameters);
        Modality defaultModality = descriptor.getKind() == ClassKind.TRAIT ? Modality.ABSTRACT : Modality.FINAL;
        descriptor.setModality(resolveModalityFromModifiers(classElement.getModifierList(), defaultModality));
        descriptor.setVisibility(resolveVisibilityFromModifiers(classElement.getModifierList()));

        trace.record(BindingContext.CLASS, classElement, descriptor);
    }

    public void resolveSupertypes(@NotNull JetClassOrObject jetClass, @NotNull MutableClassDescriptor descriptor) {
        List<JetDelegationSpecifier> delegationSpecifiers = jetClass.getDelegationSpecifiers();
        if (delegationSpecifiers.isEmpty()) {
            descriptor.addSupertype(getDefaultSupertype(jetClass));
        }
        else {
            Collection<JetType> supertypes = resolveDelegationSpecifiers(
                    descriptor.getScopeForSupertypeResolution(),
                    delegationSpecifiers,
                    typeResolverNotCheckingBounds);
            for (JetType supertype : supertypes) {
                descriptor.addSupertype(supertype);
            }
        }

    }

    private JetType getDefaultSupertype(JetClassOrObject jetClass) {
        // TODO : beautify
        if (jetClass instanceof JetEnumEntry) {
            JetClassOrObject parent = PsiTreeUtil.getParentOfType(jetClass, JetClassOrObject.class);
            ClassDescriptor parentDescriptor = trace.getBindingContext().get(BindingContext.CLASS, parent);
            if (parentDescriptor.getTypeConstructor().getParameters().isEmpty()) {
                return parentDescriptor.getDefaultType();
            }
            else {
                trace.report(NO_GENERICS_IN_SUPERTYPE_SPECIFIER.on(((JetEnumEntry) jetClass).getNameIdentifier()));
                return ErrorUtils.createErrorType("Supertype not specified");
            }
        }
        return JetStandardClasses.getAnyType();
    }

    public Collection<JetType> resolveDelegationSpecifiers(JetScope extensibleScope, List<JetDelegationSpecifier> delegationSpecifiers, @NotNull TypeResolver resolver) {
        if (delegationSpecifiers.isEmpty()) {
            return Collections.emptyList();
        }
        Collection<JetType> result = Lists.newArrayList();
        for (JetDelegationSpecifier delegationSpecifier : delegationSpecifiers) {
            JetTypeReference typeReference = delegationSpecifier.getTypeReference();
            if (typeReference != null) {
                result.add(resolver.resolveType(extensibleScope, typeReference));
                JetTypeElement typeElement = typeReference.getTypeElement();
                while (typeElement instanceof JetNullableType) {
                    JetNullableType nullableType = (JetNullableType) typeElement;
                    trace.report(NULLABLE_SUPERTYPE.on(nullableType));
                    typeElement = nullableType.getInnerType();
                }
                if (typeElement instanceof JetUserType) {
                    JetUserType userType = (JetUserType) typeElement;
                    List<JetTypeProjection> typeArguments = userType.getTypeArguments();
                    for (JetTypeProjection typeArgument : typeArguments) {
                        if (typeArgument.getProjectionKind() != JetProjectionKind.NONE) {
                            trace.report(PROJECTION_IN_IMMEDIATE_ARGUMENT_TO_SUPERTYPE.on(typeArgument));
                        }
                    }
                }
            }
            else {
                result.add(ErrorUtils.createErrorType("No type reference"));
            }
        }
        return result;
    }

    @NotNull
    public SimpleFunctionDescriptor resolveFunctionDescriptor(DeclarationDescriptor containingDescriptor, final JetScope scope, final JetNamedFunction function) {
        final SimpleFunctionDescriptorImpl functionDescriptor = new SimpleFunctionDescriptorImpl(
                containingDescriptor,
                annotationResolver.resolveAnnotations(scope, function.getModifierList()),
                JetPsiUtil.safeName(function.getName()),
                CallableMemberDescriptor.Kind.DECLARATION
        );
        WritableScope innerScope = new WritableScopeImpl(scope, functionDescriptor, new TraceBasedRedeclarationHandler(trace)).setDebugName("Function descriptor header scope");
        innerScope.addLabeledDeclaration(functionDescriptor);

        List<TypeParameterDescriptor> typeParameterDescriptors = resolveTypeParameters(functionDescriptor, innerScope, function.getTypeParameters());
        innerScope.changeLockLevel(WritableScope.LockLevel.BOTH);
        resolveGenericBounds(function, innerScope, typeParameterDescriptors);

        JetType receiverType = null;
        JetTypeReference receiverTypeRef = function.getReceiverTypeRef();
        if (receiverTypeRef != null) {
            JetScope scopeForReceiver =
                    function.hasTypeParameterListBeforeFunctionName()
                            ? innerScope
                            : scope;
            receiverType = typeResolver.resolveType(scopeForReceiver, receiverTypeRef);
        }

        List<ValueParameterDescriptor> valueParameterDescriptors = resolveValueParameters(functionDescriptor, innerScope, function.getValueParameters());

        innerScope.changeLockLevel(WritableScope.LockLevel.READING);

        JetTypeReference returnTypeRef = function.getReturnTypeRef();
        JetType returnType;
        if (returnTypeRef != null) {
            returnType = typeResolver.resolveType(innerScope, returnTypeRef);
        }
        else if (function.hasBlockBody()) {
            returnType = JetStandardClasses.getUnitType();
        }
        else {
            final JetExpression bodyExpression = function.getBodyExpression();
            if (bodyExpression != null) {
                returnType = DeferredType.create(trace, new LazyValueWithDefault<JetType>(ErrorUtils.createErrorType("Recursive dependency")) {
                    @Override
                    protected JetType compute() {
                        //JetFlowInformationProvider flowInformationProvider = computeFlowData(function, bodyExpression);
                        return semanticServices.getTypeInferrerServices(trace).inferFunctionReturnType(scope, function, functionDescriptor);
                    }
                });
            }
            else {
                trace.report(FUNCTION_WITH_NO_TYPE_NO_BODY.on(function.asElement()));
                returnType = ErrorUtils.createErrorType("No type, no body");
            }
        }
        boolean hasBody = function.getBodyExpression() != null;
        Modality defaultModality = getDefaultModality(containingDescriptor, hasBody);
        Modality modality = resolveModalityFromModifiers(function.getModifierList(), defaultModality);
        Visibility visibility = resolveVisibilityFromModifiers(function.getModifierList());
        functionDescriptor.initialize(
                receiverType,
                DescriptorUtils.getExpectedThisObjectIfNeeded(containingDescriptor),
                typeParameterDescriptors,
                valueParameterDescriptors,
                returnType,
                modality,
                visibility);

        trace.record(BindingContext.FUNCTION, function, functionDescriptor);
        return functionDescriptor;
    }

    private Modality getDefaultModality(DeclarationDescriptor containingDescriptor, boolean isBodyPresent) {
        Modality defaultModality;
        if (containingDescriptor instanceof ClassDescriptor) {
            boolean isTrait = ((ClassDescriptor) containingDescriptor).getKind() == ClassKind.TRAIT;
            boolean isDefinitelyAbstract = isTrait && !isBodyPresent;
            Modality basicModality = isTrait ? Modality.OPEN : Modality.FINAL;
            defaultModality = isDefinitelyAbstract ? Modality.ABSTRACT : basicModality;
        }
        else {
            defaultModality = Modality.FINAL;
        }
        return defaultModality;
    }

    @NotNull
    private List<ValueParameterDescriptor> resolveValueParameters(FunctionDescriptor functionDescriptor, WritableScope parameterScope, List<JetParameter> valueParameters) {
        List<ValueParameterDescriptor> result = new ArrayList<ValueParameterDescriptor>();
        for (int i = 0, valueParametersSize = valueParameters.size(); i < valueParametersSize; i++) {
            JetParameter valueParameter = valueParameters.get(i);
            JetTypeReference typeReference = valueParameter.getTypeReference();

            JetType type;
            if (typeReference == null) {
                trace.report(VALUE_PARAMETER_WITH_NO_TYPE_ANNOTATION.on(valueParameter));
                type = ErrorUtils.createErrorType("Type annotation was missing");
            } else {
                type = typeResolver.resolveType(parameterScope, typeReference);
            }

            ValueParameterDescriptor valueParameterDescriptor = resolveValueParameterDescriptor(functionDescriptor, valueParameter, i, type);
            parameterScope.addVariableDescriptor(valueParameterDescriptor);
            result.add(valueParameterDescriptor);
        }
        return result;
    }

    @NotNull
    public MutableValueParameterDescriptor resolveValueParameterDescriptor(DeclarationDescriptor declarationDescriptor, JetParameter valueParameter, int index, JetType type) {
        JetType varargElementType = null;
        JetType variableType = type;
        if (valueParameter.hasModifier(JetTokens.VARARG_KEYWORD)) {
            varargElementType = type;
            variableType = getVarargParameterType(type);
        }
        MutableValueParameterDescriptor valueParameterDescriptor = new ValueParameterDescriptorImpl(
                declarationDescriptor,
                index,
                annotationResolver.createAnnotationStubs(valueParameter.getModifierList()),
                JetPsiUtil.safeName(valueParameter.getName()),
                valueParameter.isMutable(),
                variableType,
                valueParameter.getDefaultValue() != null,
                varargElementType
        );

        trace.record(BindingContext.VALUE_PARAMETER, valueParameter, valueParameterDescriptor);
        return valueParameterDescriptor;
    }

    private JetType getVarargParameterType(JetType type) {
        JetStandardLibrary standardLibrary = semanticServices.getStandardLibrary();
        JetType arrayType = standardLibrary.getPrimitiveArrayJetTypeByPrimitiveJetType(type);
        if (arrayType != null) {
            return arrayType;
        } else {
            return standardLibrary.getArrayType(type);
        }
    }

    public List<TypeParameterDescriptor> resolveTypeParameters(DeclarationDescriptor containingDescriptor, WritableScope extensibleScope, List<JetTypeParameter> typeParameters) {
        List<TypeParameterDescriptor> result = new ArrayList<TypeParameterDescriptor>();
        for (int i = 0, typeParametersSize = typeParameters.size(); i < typeParametersSize; i++) {
            JetTypeParameter typeParameter = typeParameters.get(i);
            result.add(resolveTypeParameter(containingDescriptor, extensibleScope, typeParameter, i));
        }
        return result;
    }

    private TypeParameterDescriptor resolveTypeParameter(DeclarationDescriptor containingDescriptor, WritableScope extensibleScope, JetTypeParameter typeParameter, int index) {
//        JetTypeReference extendsBound = typeParameter.getExtendsBound();
//        JetType bound = extendsBound == null
//                ? JetStandardClasses.getDefaultBound()
//                : typeResolver.resolveType(extensibleScope, extendsBound);
        TypeParameterDescriptor typeParameterDescriptor = TypeParameterDescriptor.createForFurtherModification(
                containingDescriptor,
                annotationResolver.createAnnotationStubs(typeParameter.getModifierList()),
                !typeParameter.hasModifier(JetTokens.ERASED_KEYWORD),
                typeParameter.getVariance(),
                JetPsiUtil.safeName(typeParameter.getName()),
                index
        );
//        typeParameterDescriptor.addUpperBound(bound);
        extensibleScope.addTypeParameterDescriptor(typeParameterDescriptor);
        trace.record(BindingContext.TYPE_PARAMETER, typeParameter, typeParameterDescriptor);
        return typeParameterDescriptor;
    }

    public void resolveGenericBounds(@NotNull JetTypeParameterListOwner declaration, JetScope scope, List<TypeParameterDescriptor> parameters) {
        List<JetTypeParameter> typeParameters = declaration.getTypeParameters();
        Map<String, TypeParameterDescriptor> parameterByName = Maps.newHashMap();
        for (int i = 0, typeParametersSize = typeParameters.size(); i < typeParametersSize; i++) {
            JetTypeParameter jetTypeParameter = typeParameters.get(i);
            TypeParameterDescriptor typeParameterDescriptor = parameters.get(i);
            parameterByName.put(typeParameterDescriptor.getName(), typeParameterDescriptor);
            JetTypeReference extendsBound = jetTypeParameter.getExtendsBound();
            if (extendsBound != null) {
                typeParameterDescriptor.addUpperBound(resolveAndCheckUpperBoundType(extendsBound, scope, false));
            }
        }
        for (JetTypeConstraint constraint : declaration.getTypeConstaints()) {
            JetSimpleNameExpression subjectTypeParameterName = constraint.getSubjectTypeParameterName();
            if (subjectTypeParameterName == null) {
                continue;
            }
            String referencedName = subjectTypeParameterName.getReferencedName();
            if (referencedName == null) {
                continue;
            }
            TypeParameterDescriptor typeParameterDescriptor = parameterByName.get(referencedName);
            JetTypeReference boundTypeReference = constraint.getBoundTypeReference();
            JetType bound = boundTypeReference != null ? resolveAndCheckUpperBoundType(boundTypeReference, scope, constraint.isClassObjectContraint()) : null;
            if (typeParameterDescriptor == null) {
                // To tell the user that we look only for locally defined type parameters
                ClassifierDescriptor classifier = scope.getClassifier(referencedName);
                if (classifier != null) {
                    trace.report(NAME_IN_CONSTRAINT_IS_NOT_A_TYPE_PARAMETER.on(subjectTypeParameterName, constraint, declaration));
                    trace.record(BindingContext.REFERENCE_TARGET, subjectTypeParameterName, classifier);
                }
                else {
                    trace.report(UNRESOLVED_REFERENCE.on(subjectTypeParameterName));
                }
            }
            else {
                trace.record(BindingContext.REFERENCE_TARGET, subjectTypeParameterName, typeParameterDescriptor);
                if (bound != null) {
                    if (constraint.isClassObjectContraint()) {
                        typeParameterDescriptor.addClassObjectBound(bound);
                    }
                    else {
                        typeParameterDescriptor.addUpperBound(bound);
                    }
                }
            }
        }

        for (TypeParameterDescriptor parameter : parameters) {
            parameter.addDefaultUpperBound();

            parameter.setInitialized();

            if (JetStandardClasses.isNothing(parameter.getUpperBoundsAsType())) {
                PsiElement nameIdentifier = typeParameters.get(parameter.getIndex()).getNameIdentifier();
                if (nameIdentifier != null) {
                    trace.report(CONFLICTING_UPPER_BOUNDS.on(nameIdentifier, parameter));
                }
            }

            JetType classObjectType = parameter.getClassObjectType();
            if (classObjectType != null && JetStandardClasses.isNothing(classObjectType)) {
                PsiElement nameIdentifier = typeParameters.get(parameter.getIndex()).getNameIdentifier();
                if (nameIdentifier != null) {
                    trace.report(CONFLICTING_CLASS_OBJECT_UPPER_BOUNDS.on(nameIdentifier, parameter));
                }
            }
        }
    }

    private JetType resolveAndCheckUpperBoundType(@NotNull JetTypeReference upperBound, @NotNull JetScope scope, boolean classObjectConstaint) {
        JetType jetType = typeResolverNotCheckingBounds.resolveType(scope, upperBound);
        if (!TypeUtils.canHaveSubtypes(semanticServices.getTypeChecker(), jetType)) {
            if (classObjectConstaint) {
                trace.report(FINAL_CLASS_OBJECT_UPPER_BOUND.on(upperBound, jetType));
            }
            else {
                trace.report(FINAL_UPPER_BOUND.on(upperBound, jetType));
            }
        }
        return jetType;
    }

    @NotNull
    public VariableDescriptor resolveLocalVariableDescriptor(@NotNull DeclarationDescriptor containingDeclaration, @NotNull JetScope scope, @NotNull JetParameter parameter) {
        JetType type = resolveParameterType(scope, parameter);
        return resolveLocalVariableDescriptor(containingDeclaration, parameter, type);
    }

    private JetType resolveParameterType(JetScope scope, JetParameter parameter) {
        JetTypeReference typeReference = parameter.getTypeReference();
        JetType type;
        if (typeReference != null) {
            type = typeResolver.resolveType(scope, typeReference);
        }
        else {
            // Error is reported by the parser
            type = ErrorUtils.createErrorType("Annotation is absent");
        }
        return type;
    }

    public VariableDescriptor resolveLocalVariableDescriptor(@NotNull DeclarationDescriptor containingDeclaration, @NotNull JetParameter parameter, @NotNull JetType type) {
        VariableDescriptor variableDescriptor = new LocalVariableDescriptor(
                containingDeclaration,
                annotationResolver.createAnnotationStubs(parameter.getModifierList()),
                JetPsiUtil.safeName(parameter.getName()),
                type,
                parameter.isMutable());
        trace.record(BindingContext.VALUE_PARAMETER, parameter, variableDescriptor);
        return variableDescriptor;
    }

    @NotNull
    public VariableDescriptor resolveLocalVariableDescriptor(DeclarationDescriptor containingDeclaration, JetScope scope, JetProperty property, DataFlowInfo dataFlowInfo) {
        VariableDescriptorImpl variableDescriptor = resolveLocalVariableDescriptorWithType(containingDeclaration, property, null);

        JetType type = getVariableType(scope, property, dataFlowInfo, false); // For a local variable the type must not be deferred
        variableDescriptor.setOutType(type);
        return variableDescriptor;
    }

    @NotNull
    public VariableDescriptorImpl resolveLocalVariableDescriptorWithType(DeclarationDescriptor containingDeclaration, JetProperty property, JetType type) {
        VariableDescriptorImpl variableDescriptor = new LocalVariableDescriptor(
                containingDeclaration,
                annotationResolver.createAnnotationStubs(property.getModifierList()),
                JetPsiUtil.safeName(property.getName()),
                type,
                property.isVar());
        trace.record(BindingContext.VARIABLE, property, variableDescriptor);
        return variableDescriptor;
    }

    @NotNull
    public VariableDescriptor resolveObjectDeclaration(@NotNull DeclarationDescriptor containingDeclaration,
                                                       @NotNull JetClassOrObject objectDeclaration,
                                                       @NotNull ClassDescriptor classDescriptor) {
        boolean isProperty = (containingDeclaration instanceof NamespaceDescriptor)
                    || (containingDeclaration instanceof ClassDescriptor);
        if (isProperty) {
            return resolveObjectDeclarationAsPropertyDescriptor(containingDeclaration, objectDeclaration, classDescriptor);
        } else {
            return resolveObjectDeclarationAsLocalVariable(containingDeclaration, objectDeclaration, classDescriptor);
        }
    }

    @NotNull
    public PropertyDescriptor resolveObjectDeclarationAsPropertyDescriptor(@NotNull DeclarationDescriptor containingDeclaration,
                                                                            @NotNull JetClassOrObject objectDeclaration,
                                                                            @NotNull ClassDescriptor classDescriptor) {
        JetModifierList modifierList = objectDeclaration.getModifierList();
        Visibility visibility = resolveVisibilityFromModifiers(objectDeclaration.getModifierList());
        PropertyDescriptor propertyDescriptor = new PropertyDescriptor(
                containingDeclaration,
                annotationResolver.createAnnotationStubs(modifierList),
                Modality.FINAL,
                visibility,
                false,
                true,
                JetPsiUtil.safeName(objectDeclaration.getName()),
                CallableMemberDescriptor.Kind.DECLARATION
        );
        propertyDescriptor.setType(classDescriptor.getDefaultType(), Collections.<TypeParameterDescriptor>emptyList(), DescriptorUtils.getExpectedThisObjectIfNeeded(containingDeclaration), ReceiverDescriptor.NO_RECEIVER);
        propertyDescriptor.initialize(createDefaultGetter(propertyDescriptor), null);
        JetObjectDeclarationName nameAsDeclaration = objectDeclaration.getNameAsDeclaration();
        if (nameAsDeclaration != null) {
            trace.record(BindingContext.OBJECT_DECLARATION, nameAsDeclaration, propertyDescriptor);
        }
        return propertyDescriptor;
    }

    @NotNull
    private VariableDescriptor resolveObjectDeclarationAsLocalVariable(@NotNull DeclarationDescriptor containingDeclaration,
                                                                       @NotNull JetClassOrObject objectDeclaration,
                                                                       @NotNull ClassDescriptor classDescriptor) {
        VariableDescriptorImpl variableDescriptor = new LocalVariableDescriptor(
                        containingDeclaration,
                        annotationResolver.createAnnotationStubs(objectDeclaration.getModifierList()),
                        JetPsiUtil.safeName(objectDeclaration.getName()),
                        classDescriptor.getDefaultType(),
                        /*isVar =*/ false);
        JetObjectDeclarationName nameAsDeclaration = objectDeclaration.getNameAsDeclaration();
        if (nameAsDeclaration != null) {
            trace.record(BindingContext.VARIABLE, nameAsDeclaration, variableDescriptor);
        }
        return variableDescriptor;
    }

    public JetScope getPropertyDeclarationInnerScope(@NotNull JetScope outerScope,
                                                     @NotNull PropertyDescriptor propertyDescriptor, List<TypeParameterDescriptor> typeParameters,
                                                     ReceiverDescriptor receiver) {
        WritableScopeImpl result = new WritableScopeImpl(outerScope, propertyDescriptor, new TraceBasedRedeclarationHandler(trace)).setDebugName("Property declaration inner scope");
        for (TypeParameterDescriptor typeParameterDescriptor : typeParameters) {
            result.addTypeParameterDescriptor(typeParameterDescriptor);
        }
        if (receiver.exists()) {
            result.setImplicitReceiver(receiver);
        }
        result.changeLockLevel(WritableScope.LockLevel.READING);
        return result;
    }

    @NotNull
    public PropertyDescriptor resolvePropertyDescriptor(@NotNull DeclarationDescriptor containingDeclaration, @NotNull JetScope scope, JetProperty property) {

        JetModifierList modifierList = property.getModifierList();
        boolean isVar = property.isVar();

        boolean hasBody = hasBody(property);
        Modality defaultModality = getDefaultModality(containingDeclaration, hasBody);
        PropertyDescriptor propertyDescriptor = new PropertyDescriptor(
                containingDeclaration,
                annotationResolver.resolveAnnotations(scope, modifierList),
                resolveModalityFromModifiers(property.getModifierList(), defaultModality),
                resolveVisibilityFromModifiers(property.getModifierList()),
                isVar,
                false,
                JetPsiUtil.safeName(property.getName()),
                CallableMemberDescriptor.Kind.DECLARATION
        );

        List<TypeParameterDescriptor> typeParameterDescriptors;
        JetScope scopeWithTypeParameters;
        JetType receiverType = null;

        {
            List<JetTypeParameter> typeParameters = property.getTypeParameters();
            if (typeParameters.isEmpty()) {
                scopeWithTypeParameters = scope;
                typeParameterDescriptors = Collections.emptyList();
            }
            else {
                WritableScope writableScope = new WritableScopeImpl(scope, containingDeclaration, new TraceBasedRedeclarationHandler(trace)).setDebugName("Scope with type parameters of a property");
                typeParameterDescriptors = resolveTypeParameters(containingDeclaration, writableScope, typeParameters);
                writableScope.changeLockLevel(WritableScope.LockLevel.READING);
                resolveGenericBounds(property, writableScope, typeParameterDescriptors);
                scopeWithTypeParameters = writableScope;
            }

            JetTypeReference receiverTypeRef = property.getReceiverTypeRef();
            if (receiverTypeRef != null) {
                receiverType = typeResolver.resolveType(scopeWithTypeParameters, receiverTypeRef);
            }
        }

        ReceiverDescriptor receiverDescriptor = receiverType == null
                ? ReceiverDescriptor.NO_RECEIVER
                : new ExtensionReceiver(propertyDescriptor, receiverType);

        JetScope propertyScope = getPropertyDeclarationInnerScope(scope, propertyDescriptor, typeParameterDescriptors, receiverDescriptor);

        JetType type = getVariableType(propertyScope, property, DataFlowInfo.EMPTY, true);

        propertyDescriptor.setType(type, typeParameterDescriptors, DescriptorUtils.getExpectedThisObjectIfNeeded(containingDeclaration), receiverDescriptor);

        PropertyGetterDescriptor getter = resolvePropertyGetterDescriptor(scopeWithTypeParameters, property, propertyDescriptor);
        PropertySetterDescriptor setter = resolvePropertySetterDescriptor(scopeWithTypeParameters, property, propertyDescriptor);

        propertyDescriptor.initialize(getter, setter);

        trace.record(BindingContext.VARIABLE, property, propertyDescriptor);
        return propertyDescriptor;
    }

    /*package*/ static boolean hasBody(JetProperty property) {
        boolean hasBody = property.getInitializer() != null;
        if (!hasBody) {
            JetPropertyAccessor getter = property.getGetter();
            if (getter != null && getter.getBodyExpression() != null) {
                hasBody = true;
            }
            JetPropertyAccessor setter = property.getSetter();
            if (!hasBody && setter != null && setter.getBodyExpression() != null) {
                hasBody = true;
            }
        }
        return hasBody;
    }

    @NotNull
    private JetType getVariableType(@NotNull final JetScope scope, @NotNull final JetProperty property, @NotNull final DataFlowInfo dataFlowInfo, boolean allowDeferred) {
        // TODO : receiver?
        JetTypeReference propertyTypeRef = property.getPropertyTypeRef();

        if (propertyTypeRef == null) {
            final JetExpression initializer = property.getInitializer();
            if (initializer == null) {
                PsiElement nameIdentifier = property.getNameIdentifier();
                if (nameIdentifier != null) {
                    trace.report(PROPERTY_WITH_NO_TYPE_NO_INITIALIZER.on(nameIdentifier));
                }
                return ErrorUtils.createErrorType("No type, no body");
            } else {
                // TODO : a risk of a memory leak
                LazyValue<JetType> lazyValue = new LazyValueWithDefault<JetType>(ErrorUtils.createErrorType("Recursive dependency")) {
                    @Override
                    protected JetType compute() {
                        return semanticServices.getTypeInferrerServices(trace).safeGetType(scope, initializer, TypeUtils.NO_EXPECTED_TYPE, dataFlowInfo);
                    }
                };
                if (allowDeferred) {
                    return DeferredType.create(trace, lazyValue);
                }
                else {
                    return lazyValue.get();
                }
            }
        } else {
            return typeResolver.resolveType(scope, propertyTypeRef);
        }
    }

    @NotNull
    /*package*/ static Modality resolveModalityFromModifiers(@Nullable JetModifierList modifierList, @NotNull Modality defaultModality) {
        if (modifierList == null) return defaultModality;
        boolean hasAbstractModifier = modifierList.hasModifier(JetTokens.ABSTRACT_KEYWORD);
        boolean hasOverrideModifier = modifierList.hasModifier(JetTokens.OVERRIDE_KEYWORD);

        if (modifierList.hasModifier(JetTokens.OPEN_KEYWORD)) {
            if (hasAbstractModifier || defaultModality == Modality.ABSTRACT) {
                return Modality.ABSTRACT;
            }
            return Modality.OPEN;
        }
        if (hasAbstractModifier) {
            return Modality.ABSTRACT;
        }
        boolean hasFinalModifier = modifierList.hasModifier(JetTokens.FINAL_KEYWORD);
        if (hasOverrideModifier && !hasFinalModifier && !(defaultModality == Modality.ABSTRACT)) {
            return Modality.OPEN;
        }
        if (hasFinalModifier) {
            return Modality.FINAL;
        }
        return defaultModality;
    }

    @NotNull
    /*package*/ static Visibility resolveVisibilityFromModifiers(@Nullable JetModifierList modifierList) {
        return resolveVisibilityFromModifiers(modifierList, Visibility.INTERNAL);
    }

    @NotNull
    /*package*/ static Visibility resolveVisibilityFromModifiers(@Nullable JetModifierList modifierList, @NotNull Visibility defaultVisibility) {
        if (modifierList == null) return defaultVisibility;
        if (modifierList.hasModifier(JetTokens.PRIVATE_KEYWORD)) return Visibility.PRIVATE;
        if (modifierList.hasModifier(JetTokens.PUBLIC_KEYWORD)) return Visibility.PUBLIC;
        if (modifierList.hasModifier(JetTokens.PROTECTED_KEYWORD)) {
            if (modifierList.hasModifier(JetTokens.INTERNAL_KEYWORD)) {
                return Visibility.INTERNAL_PROTECTED;
            }
            return Visibility.PROTECTED;
        }
        return defaultVisibility;
    }

    @Nullable
    private PropertySetterDescriptor resolvePropertySetterDescriptor(@NotNull JetScope scope, @NotNull JetProperty property, @NotNull PropertyDescriptor propertyDescriptor) {
        JetPropertyAccessor setter = property.getSetter();
        PropertySetterDescriptor setterDescriptor = null;
        if (setter != null) {
            List<AnnotationDescriptor> annotations = annotationResolver.resolveAnnotations(scope, setter.getModifierList());
            JetParameter parameter = setter.getParameter();

            setterDescriptor = new PropertySetterDescriptor(
                    propertyDescriptor, annotations, resolveModalityFromModifiers(setter.getModifierList(), propertyDescriptor.getModality()),
                    resolveVisibilityFromModifiers(setter.getModifierList(), propertyDescriptor.getVisibility()),
                    setter.getBodyExpression() != null, false, CallableMemberDescriptor.Kind.DECLARATION);
            if (parameter != null) {

                // This check is redundant: the parser does not allow a default value, but we'll keep it just in case
                JetExpression defaultValue = parameter.getDefaultValue();
                if (defaultValue != null) {
                    trace.report(SETTER_PARAMETER_WITH_DEFAULT_VALUE.on(defaultValue));
                }

                JetType type;
                JetTypeReference typeReference = parameter.getTypeReference();
                if (typeReference == null) {
                    type = propertyDescriptor.getType(); // TODO : this maybe unknown at this point
                }
                else {
                    type = typeResolver.resolveType(scope, typeReference);
                    JetType inType = propertyDescriptor.getType();
                    if (inType != null) {
                        if (!TypeUtils.equalTypes(type, inType)) {
                            trace.report(WRONG_SETTER_PARAMETER_TYPE.on(typeReference, inType));
                        }
                    }
                    else {
                        // TODO : the same check may be needed later???
                    }
                }

                MutableValueParameterDescriptor valueParameterDescriptor = resolveValueParameterDescriptor(setterDescriptor, parameter, 0, type);
                setterDescriptor.initialize(valueParameterDescriptor);
            }
            else {
                setterDescriptor.initializeDefault();
            }

            trace.record(BindingContext.PROPERTY_ACCESSOR, setter, setterDescriptor);
        }
        else if (property.isVar()) {
            setterDescriptor = createDefaultSetter(propertyDescriptor);
        }

        if (! property.isVar()) {
            if (setter != null) {
//                trace.getErrorHandler().genericError(setter.asElement().getNode(), "A 'val'-property cannot have a setter");
                trace.report(VAL_WITH_SETTER.on(setter));
            }
        }
        return setterDescriptor;
    }

    private PropertySetterDescriptor createDefaultSetter(PropertyDescriptor propertyDescriptor) {
        PropertySetterDescriptor setterDescriptor;
        setterDescriptor = new PropertySetterDescriptor(
                propertyDescriptor, Collections.<AnnotationDescriptor>emptyList(), propertyDescriptor.getModality(),
                propertyDescriptor.getVisibility(),
                false, true, CallableMemberDescriptor.Kind.DECLARATION);
        setterDescriptor.initializeDefault();
        return setterDescriptor;
    }

    @Nullable
    private PropertyGetterDescriptor resolvePropertyGetterDescriptor(@NotNull JetScope scope, @NotNull JetProperty property, @NotNull PropertyDescriptor propertyDescriptor) {
        PropertyGetterDescriptor getterDescriptor;
        JetPropertyAccessor getter = property.getGetter();
        if (getter != null) {
            List<AnnotationDescriptor> annotations = annotationResolver.resolveAnnotations(scope, getter.getModifierList());

            JetType outType = propertyDescriptor.getType();
            JetType returnType = outType;
            JetTypeReference returnTypeReference = getter.getReturnTypeReference();
            if (returnTypeReference != null) {
                returnType = typeResolver.resolveType(scope, returnTypeReference);
                if (outType != null && !TypeUtils.equalTypes(returnType, outType)) {
                    trace.report(WRONG_GETTER_RETURN_TYPE.on(returnTypeReference, propertyDescriptor.getReturnType()));
                }
            }

            getterDescriptor = new PropertyGetterDescriptor(
                    propertyDescriptor, annotations, resolveModalityFromModifiers(getter.getModifierList(), propertyDescriptor.getModality()),
                    resolveVisibilityFromModifiers(getter.getModifierList(), propertyDescriptor.getVisibility()),
                    getter.getBodyExpression() != null, false, CallableMemberDescriptor.Kind.DECLARATION);
            getterDescriptor.initialize(returnType);
            trace.record(BindingContext.PROPERTY_ACCESSOR, getter, getterDescriptor);
        }
        else {
            getterDescriptor = createDefaultGetter(propertyDescriptor);
            getterDescriptor.initialize(propertyDescriptor.getType());
        }
        return getterDescriptor;
    }

    private PropertyGetterDescriptor createDefaultGetter(PropertyDescriptor propertyDescriptor) {
        PropertyGetterDescriptor getterDescriptor;
        getterDescriptor = new PropertyGetterDescriptor(
                propertyDescriptor, Collections.<AnnotationDescriptor>emptyList(), propertyDescriptor.getModality(),
                propertyDescriptor.getVisibility(),
                false, true, CallableMemberDescriptor.Kind.DECLARATION);
        return getterDescriptor;
    }

    @NotNull
    public ConstructorDescriptorImpl resolveSecondaryConstructorDescriptor(@NotNull JetScope scope, @NotNull ClassDescriptor classDescriptor, @NotNull JetSecondaryConstructor constructor) {
        return createConstructorDescriptor(scope, classDescriptor, false, constructor.getModifierList(), constructor, classDescriptor.getTypeConstructor().getParameters(), constructor.getValueParameters());
    }

    @NotNull
    private ConstructorDescriptorImpl createConstructorDescriptor(
            @NotNull JetScope scope,
            @NotNull ClassDescriptor classDescriptor,
            boolean isPrimary,
            @Nullable JetModifierList modifierList,
            @NotNull JetDeclaration declarationToTrace,
            List<TypeParameterDescriptor> typeParameters, @NotNull List<JetParameter> valueParameters) {
        ConstructorDescriptorImpl constructorDescriptor = new ConstructorDescriptorImpl(
                classDescriptor,
                annotationResolver.resolveAnnotations(scope, modifierList),
                isPrimary
        );
        trace.record(BindingContext.CONSTRUCTOR, declarationToTrace, constructorDescriptor);
        WritableScopeImpl parameterScope = new WritableScopeImpl(scope, classDescriptor, new TraceBasedRedeclarationHandler(trace)).setDebugName("Scope with value parameters of a constructor");
        parameterScope.changeLockLevel(WritableScope.LockLevel.BOTH);
        return constructorDescriptor.initialize(
                typeParameters,
                resolveValueParameters(
                        constructorDescriptor,
                        parameterScope,
                        valueParameters),
                resolveVisibilityFromModifiers(modifierList));
    }

    @Nullable
    public ConstructorDescriptorImpl resolvePrimaryConstructorDescriptor(@NotNull JetScope scope, @NotNull ClassDescriptor classDescriptor, @NotNull JetClass classElement) {
        if (classDescriptor.getKind() == ClassKind.ENUM_ENTRY && !classElement.hasPrimaryConstructor()) return null;
        return createConstructorDescriptor(
                scope,
                classDescriptor,
                true,
                classElement.getPrimaryConstructorModifierList(),
                classElement,
                classDescriptor.getTypeConstructor().getParameters(), classElement.getPrimaryConstructorParameters());
    }

    @NotNull
    public PropertyDescriptor resolvePrimaryConstructorParameterToAProperty(
            @NotNull ClassDescriptor classDescriptor,
            @NotNull JetScope scope,
            @NotNull JetParameter parameter) {
        JetType type = resolveParameterType(scope, parameter);
        String name = parameter.getName();
        boolean isMutable = parameter.isMutable();
        JetModifierList modifierList = parameter.getModifierList();

        if (modifierList != null) {
            ASTNode abstractNode = modifierList.getModifierNode(JetTokens.ABSTRACT_KEYWORD);
            if (abstractNode != null) {
                trace.report(ABSTRACT_PROPERTY_IN_PRIMARY_CONSTRUCTOR_PARAMETERS.on(parameter));
            }
        }

        PropertyDescriptor propertyDescriptor = new PropertyDescriptor(
                classDescriptor,
                annotationResolver.resolveAnnotations(scope, modifierList),
                resolveModalityFromModifiers(parameter.getModifierList(), Modality.FINAL),
                resolveVisibilityFromModifiers(parameter.getModifierList()),
                isMutable,
                false,
                name == null ? "<no name>" : name,
                CallableMemberDescriptor.Kind.DECLARATION
        );
        propertyDescriptor.setType(type, Collections.<TypeParameterDescriptor>emptyList(), DescriptorUtils.getExpectedThisObjectIfNeeded(classDescriptor), ReceiverDescriptor.NO_RECEIVER);

        PropertyGetterDescriptor getter = createDefaultGetter(propertyDescriptor);
        PropertySetterDescriptor setter = createDefaultSetter(propertyDescriptor);

        propertyDescriptor.initialize(getter, setter);
        getter.initialize(propertyDescriptor.getType());

        trace.record(BindingContext.PRIMARY_CONSTRUCTOR_PARAMETER, parameter, propertyDescriptor);
        return propertyDescriptor;
    }

    public void checkBounds(@NotNull JetTypeReference typeReference, @NotNull JetType type) {
        if (ErrorUtils.isErrorType(type)) return;

        JetTypeElement typeElement = typeReference.getTypeElement();
        if (typeElement == null) return;

        List<TypeParameterDescriptor> parameters = type.getConstructor().getParameters();
        List<TypeProjection> arguments = type.getArguments();
        assert parameters.size() == arguments.size();

        List<JetTypeReference> typeReferences = typeElement.getTypeArgumentsAsTypes();
        assert typeReferences.size() == arguments.size() : typeElement.getText();

        TypeSubstitutor substitutor = TypeSubstitutor.create(type);
        for (int i = 0, projectionsSize = typeReferences.size(); i < projectionsSize; i++) {
            JetTypeReference argumentTypeReference = typeReferences.get(i);

            if (argumentTypeReference == null) continue;

            JetType typeArgument = arguments.get(i).getType();
            checkBounds(argumentTypeReference, typeArgument);

            TypeParameterDescriptor typeParameterDescriptor = parameters.get(i);
            checkBounds(argumentTypeReference, typeArgument, typeParameterDescriptor, substitutor);
        }
    }

    public void checkBounds(
            @NotNull JetTypeReference argumentTypeReference,
            @NotNull JetType typeArgument,
            @NotNull TypeParameterDescriptor typeParameterDescriptor,
            @NotNull TypeSubstitutor substitutor) {
        for (JetType bound : typeParameterDescriptor.getUpperBounds()) {
            JetType substitutedBound = substitutor.safeSubstitute(bound, Variance.INVARIANT);
            if (!semanticServices.getTypeChecker().isSubtypeOf(typeArgument, substitutedBound)) {
                trace.report(UPPER_BOUND_VIOLATED.on(argumentTypeReference, substitutedBound));
            }
        }
    }
}
