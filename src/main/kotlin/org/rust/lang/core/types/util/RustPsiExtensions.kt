package org.rust.lang.core.types.util

import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.rust.lang.core.psi.RustExprElement
import org.rust.lang.core.psi.RustTypeElement
import org.rust.lang.core.types.visitors.RustTypificationEngine
import org.rust.lang.core.types.RustType
import org.rust.lang.core.types.unresolved.RustUnresolvedType
import org.rust.lang.core.types.visitors.RustTypeResolvingVisitor

val RustExprElement.resolvedType: RustType
    get() =
        CachedValuesManager.getCachedValue(this,
            CachedValueProvider {
                com.intellij.psi.util.CachedValueProvider.Result.create(RustTypificationEngine.typifyExpr(this), PsiModificationTracker.MODIFICATION_COUNT)
            }
        )

val RustTypeElement.type: RustUnresolvedType
    get() =
        CachedValuesManager.getCachedValue(this,
            CachedValueProvider {
                com.intellij.psi.util.CachedValueProvider.Result.create(
                    RustTypificationEngine.typifyType(this),
                    PsiModificationTracker.MODIFICATION_COUNT
                )
            }
        )

val RustTypeElement.resolvedType: RustType
    get() =
        CachedValuesManager.getCachedValue(this,
            CachedValueProvider {
                com.intellij.psi.util.CachedValueProvider.Result.create(type.accept(RustTypeResolvingVisitor()), PsiModificationTracker.MODIFICATION_COUNT)
            }
        )

