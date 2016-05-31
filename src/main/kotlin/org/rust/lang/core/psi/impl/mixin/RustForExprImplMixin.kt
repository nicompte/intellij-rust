package org.rust.lang.core.psi.impl.mixin

import com.intellij.lang.ASTNode
import org.rust.lang.core.psi.RustDeclaringElement
import org.rust.lang.core.psi.RustForExpr
import org.rust.lang.core.psi.RustNamedElement
import org.rust.lang.core.psi.impl.RustCompositeElementImpl

abstract class RustForExprImplMixin(node: ASTNode)   : RustCompositeElementImpl(node)
                                                     , RustForExpr {

    override val declarations: Collection<RustNamedElement>
        get() = scopedForDecl.boundElements
}

