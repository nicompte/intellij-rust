package org.rust.lang.core.psi.impl.mixin

import com.intellij.lang.ASTNode
import org.rust.ide.icons.RustIcons
import org.rust.lang.core.psi.RustFieldDeclElement
import org.rust.lang.core.psi.iconWithVisibility
import org.rust.lang.core.psi.impl.RustNamedElementImpl
import javax.swing.Icon

abstract class RustFieldDeclImplMixin(node: ASTNode) : RustNamedElementImpl(node), RustFieldDeclElement {

    override fun getIcon(flags: Int): Icon =
        iconWithVisibility(flags, RustIcons.FIELD)

}
