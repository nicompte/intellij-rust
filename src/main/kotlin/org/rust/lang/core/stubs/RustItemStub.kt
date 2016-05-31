package org.rust.lang.core.stubs

import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import com.intellij.util.io.StringRef
import org.rust.lang.core.psi.impl.RustItemImpl

class RustItemStub : RustNamedElementStub<RustItemImpl> {
    constructor(parent: StubElement<*>?, elementType: IStubElementType<*, *>, name: StringRef)
    : super(parent, elementType, name)

    constructor(parent: StubElement<*>?, elementType: IStubElementType<*, *>, name: String)
    : super(parent, elementType, name)
}
