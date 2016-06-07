package org.rust.lang.core.psi

import com.intellij.psi.util.PsiTreeUtil
import org.rust.lang.core.resolve.scope.RustResolveScope

interface RustItemsOwner : RustResolveScope

inline fun <reified I : RustItemElement> RustItemsOwner.items(): List<I> =
    PsiTreeUtil.getChildrenOfTypeAsList(this, I::class.java)

val RustItemsOwner.itemDefinitions: List<RustNamedElement>
    get() = listOf<List<RustNamedElement>>(
        items<RustConstItemElement>(),
        items<RustEnumItemElement>(),
        items<RustFnItemElement>(),
        items<RustModItemElement>(),
        items<RustStaticItemElement>(),
        items<RustStructItemElement>(),
        items<RustTraitItemElement>(),
        items<RustTypeItemElement>()
    ).flatten()

val RustItemsOwner.impls: List<RustImplItemElement> get() = items()

