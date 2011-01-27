package org.jetbrains.jet.lang.resolve;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.modules.MemberDomain;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.types.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author abreslav
 */
public class TypeResolver {

    @NotNull
    public static final TypeResolver INSTANCE = new TypeResolver();

    private TypeResolver() {}

    @NotNull
    public Type resolveType(@NotNull final JetScope scope, @NotNull final JetTypeReference typeReference) {
        final List<Attribute> attributes = AttributeResolver.INSTANCE.resolveAttributes(typeReference.getAttributes());

        JetTypeElement typeElement = typeReference.getTypeElement();
        return resolveTypeElement(scope, attributes, typeElement, false);
    }

    private Type resolveTypeElement(final JetScope scope, final List<Attribute> attributes, JetTypeElement typeElement, final boolean nullable) {
        final Type[] result = new Type[1];
        typeElement.accept(new JetVisitor() {
            @Override
            public void visitUserType(JetUserType type) {
                ClassDescriptor classDescriptor = resolveClass(scope, type);
                if (classDescriptor != null) {
                    result[0] = new TypeImpl(
                            attributes,
                            classDescriptor.getTypeConstructor(),
                            nullable,
                            resolveTypeProjections(scope, type.getTypeArguments()),
                            JetStandardClasses.STUB
                    );
                }
                else if (type.getTypeArguments().isEmpty()) {
                    TypeParameterDescriptor typeParameterDescriptor = scope.getTypeParameterDescriptor(type.getReferencedName());
                    if (typeParameterDescriptor != null) {
                        result[0] = new TypeImpl(
                                attributes,
                                typeParameterDescriptor.getTypeConstructor(),
                                nullable || hasNullableBound(typeParameterDescriptor),
                                Collections.<TypeProjection>emptyList(),
                                JetStandardClasses.STUB
                        );
                    }
                }
            }

            @Override
            public void visitNullableType(JetNullableType nullableType) {
                result[0] = resolveTypeElement(scope, attributes, nullableType.getInnerType(), true);
            }

            @Override
            public void visitTupleType(JetTupleType type) {
                // TODO labels
                result[0] = JetStandardClasses.getTupleType(resolveTypes(scope, type.getComponentTypeRefs()));
            }

            @Override
            public void visitJetElement(JetElement elem) {
                throw new IllegalArgumentException("Unsupported type: " + elem);
            }
        });
        if (result[0] == null) {
            return ErrorType.createErrorType(typeElement.getText());
        }
        return result[0];
    }

    private boolean hasNullableBound(TypeParameterDescriptor typeParameterDescriptor) {
        for (Type bound : typeParameterDescriptor.getUpperBounds()) {
            if (bound.isNullable()) {
                return true;
            }
        }
        return false;
    }

    private List<Type> resolveTypes(JetScope scope, List<JetTypeReference> argumentElements) {
        final List<Type> arguments = new ArrayList<Type>();
        for (JetTypeReference argumentElement : argumentElements) {
            arguments.add(resolveType(scope, argumentElement));
        }
        return arguments;
    }

    @NotNull
    private List<TypeProjection> resolveTypeProjections(JetScope scope, List<JetTypeProjection> argumentElements) {
        final List<TypeProjection> arguments = new ArrayList<TypeProjection>();
        for (JetTypeProjection argumentElement : argumentElements) {
            ProjectionKind projectionKind = argumentElement.getProjectionKind();
            Type type;
            if (projectionKind == ProjectionKind.NEITHER_OUT_NOR_IN) {
                type = null;
            } else {
                type = resolveType(scope, argumentElement.getTypeReference());
            }
            TypeProjection typeProjection = new TypeProjection(projectionKind, type);
            arguments.add(typeProjection);
        }
        return arguments;
    }

    @Nullable
    public ClassDescriptor resolveClass(JetScope scope, JetUserType userType) {
        return resolveClass(scope, userType.getReferenceExpression());
    }

    @Nullable
    private ClassDescriptor resolveClass(JetScope scope, JetReferenceExpression expression) {
        if (expression.isAbsoluteInRootNamespace()) {
            return resolveClass(JetModuleUtil.getRootNamespaceScope(expression), expression);
        }

        JetReferenceExpression qualifier = expression.getQualifier();
        if (qualifier != null) {
            // TODO: this is slow. The faster way would be to start with the first item in the qualified name
            // TODO: priorities: class of namespace first?
            MemberDomain domain = resolveClass(scope, qualifier);
            if (domain == null) {
                domain = resolveNamespace(scope, qualifier);
            }

            if (domain != null) {
                return domain.getClass(expression.getReferencedName());
            }
            return null;
        }

        assert qualifier == null;

        return scope.getClass(expression.getReferencedName());
    }

    @Nullable
    private NamespaceDescriptor resolveNamespace(JetScope scope, JetReferenceExpression expression) {
        if (expression.isAbsoluteInRootNamespace()) {
            return resolveNamespace(JetModuleUtil.getRootNamespaceScope(expression), expression);
        }

        JetReferenceExpression qualifier = expression.getQualifier();
        if (qualifier != null) {
            NamespaceDescriptor domain = resolveNamespace(scope, qualifier);
            if (domain == null) {
                return null;
            }
            return domain.getNamespace(expression.getReferencedName());
        }

        assert qualifier == null;

        return scope.getNamespace(expression.getReferencedName());
    }

}