package org.rust.lang.core.psi.impl.mixin

import com.intellij.lang.ASTNode
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import org.rust.lang.core.psi.RustBlock
import org.rust.lang.core.psi.RustCompositeElement
import org.rust.lang.core.psi.RustLetDecl
import org.rust.lang.core.psi.RustNamedElement
import org.rust.lang.core.psi.impl.RustCompositeElementImpl

abstract class RustBlockImplMixin(node: ASTNode) : RustCompositeElementImpl(node)
                                                 , RustBlock {

    override val declarations: Collection<RustNamedElement>
        get() = stmtList.filterIsInstance<RustLetDecl>().flatMap { it.boundElements }

}

/**
 *  Let declarations visible at the `element` according to Rust scoping rules.
 *  More recent declarations come first.
 *
 *  Example:
 *
 *    ```
 *    {
 *        let x = 92; // visible
 *        let x = x;  // not visible
 *                ^ element
 *        let x = 62; // not visible
 *    }
 *    ```
 */
fun RustBlock.letDeclarationsVisibleAt(element: RustCompositeElement): Sequence<RustLetDecl> =
    stmtList.asReversed().asSequence()
        .filterIsInstance<RustLetDecl>()
        .dropWhile { PsiUtilCore.compareElementsByPosition(element, it) < 0 }
        // Drops at most one element
        .dropWhile { PsiTreeUtil.isAncestor(it, element, true) }
