package org.rust.lang.core.completion

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.impl.mixin.basePath
import org.rust.lang.core.psi.util.fields
import org.rust.lang.core.psi.util.parentOfType
import org.rust.lang.core.resolve.enumerateScopesFor
import org.rust.lang.core.resolve.scope.RustResolveScope
import org.rust.lang.core.resolve.scope.declarations

object RustCompletionEngine {
    fun complete(ref: RustQualifiedReferenceElement): Array<RustNamedElement> =
        collectNamedElements(ref).toVariantsArray()

    fun completeFieldName(field: RustStructExprFieldElement): Array<RustNamedElement> =
        field.parentOfType<RustStructExprElement>()
                ?.let       { it.fields }
                ?.filter    { it.name != null }
                 .orEmpty()
                 .toTypedArray()

    fun completeUseGlob(glob: RustUseGlobElement): Array<RustNamedElement> =
        glob.basePath?.reference?.resolve()
            .completionsFromResolveScope()
            .toVariantsArray()

    private fun collectNamedElements(ref: RustQualifiedReferenceElement): Collection<RustNamedElement> {
        // TODO: handle aliased items properly
        val qual = ref.qualifier
        if (qual != null) {
            return qual.reference.resolve()
                .completionsFromResolveScope()
        }

        return enumerateScopesFor(ref)
            .flatMap { it.declarations(RustResolveScope.Context(place = ref)) }
            .mapNotNull { it.element }
            .toList()
    }
}

private fun RustNamedElement?.completionsFromResolveScope(): Collection<RustNamedElement> =
    when (this) {
        is RustResolveScope -> declarations(RustResolveScope.Context(place = null)).mapNotNull { it.element }.toList()
        else                -> emptyList()
    }

private fun Collection<RustNamedElement>.toVariantsArray(): Array<RustNamedElement> =
    filter { it.name != null }.toTypedArray()
