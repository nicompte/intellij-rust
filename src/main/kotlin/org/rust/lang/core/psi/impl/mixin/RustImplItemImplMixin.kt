package org.rust.lang.core.psi.impl.mixin

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import org.rust.ide.icons.RustIcons
import org.rust.lang.core.psi.RustImplItemElement
import org.rust.lang.core.psi.RustNamedElement
import org.rust.lang.core.psi.impl.RustItemElementImpl
import org.rust.lang.core.stubs.RustItemStub
import javax.swing.Icon

abstract class RustImplItemImplMixin : RustItemElementImpl, RustImplItemElement {

    constructor(node: ASTNode) : super(node)

    constructor(stub: RustItemStub, nodeType: IStubElementType<*, *>) : super(stub, nodeType)

    override val declarations: Collection<RustNamedElement> get() = genericParams?.typeParamList.orEmpty()

    override fun getIcon(flags: Int): Icon = RustIcons.IMPL
}
