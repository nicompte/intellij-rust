package org.rust.lang.core.psi.impl.mixin

import com.intellij.lang.ASTNode
import org.rust.lang.core.psi.RustNamedElement
import org.rust.lang.core.psi.RustScopedLetExpr
import org.rust.lang.core.psi.impl.RustCompositeElementImpl

abstract class RustScopedLetExprImplMixin(node: ASTNode)    : RustCompositeElementImpl(node)
                                                            , RustScopedLetExpr {

    override val declarations: Collection<RustNamedElement> get() = scopedLetDecl.boundElements
}
