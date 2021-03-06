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

package org.jetbrains.jet.lang.psi.stubs.elements;

import com.intellij.psi.StubBuilder;
import com.intellij.psi.stubs.IndexSink;
import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.stubs.StubInputStream;
import com.intellij.psi.stubs.StubOutputStream;
import com.intellij.psi.tree.IStubFileElementType;
import com.intellij.util.io.StringRef;
import org.jetbrains.jet.lang.psi.stubs.PsiJetFileStub;
import org.jetbrains.jet.lang.psi.stubs.impl.PsiJetFileStubImpl;
import org.jetbrains.jet.plugin.JetLanguage;

import java.io.IOException;

/**
 * @author Nikolay Krasko
 */
public class JetFileElementType extends IStubFileElementType<PsiJetFileStub> {
    public static final int STUB_VERSION = 2;

    public JetFileElementType() {
        super("jet.FILE", JetLanguage.INSTANCE);
    }

    @Override
    public StubBuilder getBuilder() {
        return new JetFileStubBuilder();
    }

    @Override
    public int getStubVersion() {
        return STUB_VERSION;
    }

    @Override
    public String getExternalId() {
        return "jet.FILE";
    }

    @Override
    public void serialize(final PsiJetFileStub stub, final StubOutputStream dataStream)
            throws IOException {
        dataStream.writeName(stub.getPackageName());
    }

    @Override
    public PsiJetFileStub deserialize(final StubInputStream dataStream, final StubElement parentStub) throws IOException {
        StringRef packName = dataStream.readName();
        return new PsiJetFileStubImpl(null, packName);
    }

    @Override
    public void indexStub(final PsiJetFileStub stub, final IndexSink sink) {
        // Don't index file
    }
}
