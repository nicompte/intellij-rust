package org.rust.lang.core.psi

import org.rust.lang.core.resolve.scope.RustResolveScope

interface RustItemsOwner : RustResolveScope {
    val itemList: List<RustItem>
}

val RustItemsOwner.useDeclarations: List<RustUseItem> get() = itemList.filterIsInstance<RustUseItem>()
val RustItemsOwner.externCrateDeclarations: List<RustExternCrateItem> get() = itemList.filterIsInstance<RustExternCrateItem>()
val RustItemsOwner.namedItems: List<RustNamedElement> get() = itemList.filterIsInstance<RustNamedElement>()
