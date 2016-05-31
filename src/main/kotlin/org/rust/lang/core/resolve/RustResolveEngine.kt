package org.rust.lang.core.resolve

import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.rust.cargo.util.AutoInjectedCrates
import org.rust.cargo.util.cargoProject
import org.rust.cargo.util.getPsiFor
import org.rust.cargo.util.preludeModule
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.impl.mixin.basePath
import org.rust.lang.core.psi.impl.mixin.isStarImport
import org.rust.lang.core.psi.impl.mixin.letDeclarationsVisibleAt
import org.rust.lang.core.psi.impl.mixin.possiblePaths
import org.rust.lang.core.psi.impl.rustMod
import org.rust.lang.core.psi.util.module
import org.rust.lang.core.resolve.scope.RustResolveScope
import org.rust.lang.core.resolve.util.RustResolveUtil

object RustResolveEngine {
    open class ResolveResult private constructor(val resolved: RustNamedElement?) : com.intellij.psi.ResolveResult {
        override fun getElement():      RustNamedElement? = resolved
        override fun isValidResult():   Boolean           = resolved != null

        /**
         * Designates resolve-engine failure to properly resolve item
         */
        object Unresolved : ResolveResult(null)

        /**
         * Designates resolve-engine failure to properly recognise target item
         * among the possible candidates
         */
        class Ambiguous(val candidates: Collection<RustNamedElement>) : ResolveResult(null)

        /**
         * Designates resolve-engine successfully resolved given target
         */
        class Resolved(resolved: RustNamedElement) : ResolveResult(resolved)
    }

    /**
     * Resolves `qualified-reference` bearing PSI-elements
     *
     * NOTE: This operate on PSI to extract all the necessary (yet implicit) resolving-context
     */
    fun resolve(ref: RustQualifiedReferenceElement): ResolveResult =
        Resolver().resolve(ref)

    //
    // TODO(kudinkin): Unify following?
    //

    fun resolveUseGlob(ref: RustUseGlob): ResolveResult =
        Resolver().resolveUseGlob(ref)

    /**
     * Looks-up file corresponding to particular module designated by `mod-declaration-item`:
     *
     *  ```
     *  // foo.rs
     *  pub mod bar; // looks up `bar.rs` or `bar/mod.rs` in the same dir
     *
     *  pub mod nested {
     *      pub mod baz; // looks up `nested/baz.rs` or `nested/baz/mod.rs`
     *  }
     *
     *  ```
     *
     *  | A module without a body is loaded from an external file, by default with the same name as the module,
     *  | plus the '.rs' extension. When a nested sub-module is loaded from an external file, it is loaded
     *  | from a subdirectory path that mirrors the module hierarchy.
     *
     * Reference:
     *      https://github.com/rust-lang/rust/blob/master/src/doc/reference.md#modules
     */
    fun resolveModDecl(ref: RustModDeclItem): ResolveResult {
        val parent  = ref.containingMod
        val name    = ref.name

        if (parent == null || name == null) {
            return RustResolveEngine.ResolveResult.Unresolved
        }

        val dir = parent.ownedDirectory

        val resolved = ref.possiblePaths.mapNotNull {
            dir?.findFileByRelativePath(it)?.rustMod
        }

        return when (resolved.size) {
            0    -> RustResolveEngine.ResolveResult.Unresolved
            1    -> RustResolveEngine.ResolveResult.Resolved    (resolved.single())
            else -> RustResolveEngine.ResolveResult.Ambiguous   (resolved)
        }
    }

    fun resolveExternCrate(crate: RustExternCrateItem): ResolveResult {
        val name = crate.name ?: return ResolveResult.Unresolved
        val module = crate.module ?: return ResolveResult.Unresolved
        return module.project.getPsiFor(module.cargoProject?.findExternCrateRootByName(name))?.rustMod.asResolveResult()
    }

}

private class Resolver {

    private var visitedPrelude = false

    /**
     * Resolves `qualified-reference` bearing PSI-elements
     *
     * For more details check out `RustResolveEngine.resolve`
     */
    fun resolve(ref: RustQualifiedReferenceElement): RustResolveEngine.ResolveResult = resolvePreventingRecursion(ref) {
        val modulePrefix = ref.relativeModulePrefix
        when (modulePrefix) {
            is RelativeModulePrefix.Invalid        -> RustResolveEngine.ResolveResult.Unresolved
            is RelativeModulePrefix.AncestorModule -> resolveAncestorModule(ref, modulePrefix).asResolveResult()
            is RelativeModulePrefix.NotRelative    -> {
                val qual = ref.qualifier
                if (qual == null) {
                    resolveIn(enumerateScopesFor(ref), by(ref))
                } else {
                    val parent = resolve(qual).element
                    when (parent) {
                        is RustMod -> parent.findByName(ref)
                        is RustEnumItem -> parent.findByName(ref)
                        else -> RustResolveEngine.ResolveResult.Unresolved
                    }
                }
            }
        }
    }

    /**
     * Resolves `use-glob`s, ie:
     *
     *  ```
     *  use foo::bar::{baz as boo}
     *  use foo::*
     *  ```
     */
    fun resolveUseGlob(ref: RustUseGlob): RustResolveEngine.ResolveResult = resolvePreventingRecursion(ref) {
        val basePath = ref.basePath

        // This is not necessarily a module, e.g.
        //
        //   ```
        //   fn foo() {}
        //
        //   mod inner {
        //       use foo::{self};
        //   }
        //   ```
        val baseItem = if (basePath != null)
            resolve(basePath).element
        else
            // `use ::{foo, bar}`
            RustResolveUtil.getCrateRootModFor(ref)

        when {
            // `use foo::{self}`
            ref.self != null && baseItem != null -> RustResolveEngine.ResolveResult.Resolved(baseItem)

            // `use foo::{bar}`
            baseItem is RustResolveScope         -> baseItem.findByName(ref)

            else                                 -> RustResolveEngine.ResolveResult.Unresolved
        }
    }

    private fun resolveAncestorModule(
        ref: RustQualifiedReferenceElement,
        modulePrefix: RelativeModulePrefix.AncestorModule
    ): RustMod? {
        var result: RustMod? = ref.containingMod
        for (i in 0 until modulePrefix.level) {
            result = result?.`super`
        }
        return result
    }

    /**
     * Hook to compose _total_ (ie including both local & non-local) context to resolve
     * any items, taking into account what lexical point we're particularly looking that name up from,
     * therefore effectively ignoring items being declared 'lexically-after' lookup-point
     */
    private fun by(e: RustNamedElement) =
        e.name?.let {
            ResolveContext.Companion.Trivial(ResolveLocalScopesVisitor(e))
        } ?: ResolveContext.Companion.Empty

    /**
     * Resolve-context wrapper
     */
    interface ResolveContext {
        fun accept(scope: RustResolveScope): RustNamedElement?

        companion object {

            class Trivial(val v: ResolveScopeVisitor) : ResolveContext {
                override fun accept(scope: RustResolveScope): RustNamedElement? {
                    scope.accept(v)
                    return v.matched
                }
            }

            object Empty : ResolveContext {
                override fun accept(scope: RustResolveScope): RustNamedElement? = null
            }
        }
    }


    private fun resolveIn(scopes: Sequence<RustResolveScope>, ctx: ResolveContext): RustResolveEngine.ResolveResult {
        for (s in scopes) {
            ctx.accept(s)?.let {
                return RustResolveEngine.ResolveResult.Resolved(it)
            }
        }

        return RustResolveEngine.ResolveResult.Unresolved
    }

    /**
     * Abstract resolving-scope-visitor
     */
    abstract inner class ResolveScopeVisitor : RustVisitor() {

        /**
         * Matched resolve-target
         */
        abstract var matched: RustNamedElement?
    }

    /**
     * This particular visitor visits _non-local_ scopes only (!)
     */
    open inner class ResolveNonLocalScopesVisitor(protected val name: String) : ResolveScopeVisitor() {

        override var matched: RustNamedElement? = null

        override fun visitFile(file: PsiFile) {
            file.rustMod?.let { visitMod(it) }
        }

        override fun visitModItem(o: RustModItem) {
            visitMod(o)
        }

        override fun visitEnumItem(o: RustEnumItem) = seek(o.declarations)
        override fun visitTraitItem(o: RustTraitItem) = seek(o.declarations)
        override fun visitStructItem(o: RustStructItem) = seek(o.declarations)
        override fun visitImplItem(o: RustImplItem) = seek(o.declarations)

        private fun visitMod(mod: RustMod) {
            seek(mod.declarations)
            if (matched != null) return

            seekUseDeclarations(mod)
            if (matched != null) return

            seekInjectedItems(mod)
        }

        protected fun seek(element: RustNamedElement) {
            check(matched == null)
            if (match(element)) {
                found(element)
            }
        }

        protected fun seek(declaringElements: Collection<RustNamedElement>) {
            for (element in declaringElements) {
                seek(element)

                // Check whether the match is already found
                if (matched != null) return
            }
        }

        protected fun found(elem: RustNamedElement) {
            matched =
                // Check whether resolved element could be further resolved
                when (elem) {
                    is RustModDeclItem, is RustExternCrateItem -> elem.reference?.resolve()
                    is RustPath     -> resolve(elem).element
                    is RustUseGlob  -> resolveUseGlob(elem).element
                    is RustAlias    -> {
                        val parent = elem.parent
                        when (parent) {
                            is RustUseItem         -> parent.path?.let { resolve(it).element }
                            is RustUseGlob         -> resolveUseGlob(parent).element
                            is RustExternCrateItem -> parent.reference?.resolve()
                            else                   -> elem
                        }
                    }
                    else            -> elem
                }
        }

        protected fun match(elem: RustNamedElement): Boolean = elem.name == name

        protected fun seekUseDeclarations(o: RustItemsOwner) {
            for (externCrate in o.externCrateDeclarations) {
                seek(externCrate.boundElements)
                if (matched != null) return
            }

            for (useDecl in o.useDeclarations) {
                if (useDecl.isStarImport) {
                    // Recursively step into `use foo::*`
                    val pathPart = useDecl.path ?: continue
                    val mod = resolve(pathPart).element ?: continue
                    RecursionManager.doPreventingRecursion(this to mod, false) {
                        mod.accept(this)
                    }
                } else {
                    for (element in useDecl.boundElements) {
                        seek(element)
                        if (matched != null) return
                    }
                }
                if (matched != null) return
            }
        }

        private fun seekInjectedItems(mod: RustMod) {
            // Rust injects implicit `extern crate std` in every crate root module unless it is
            // a `#![no_std]` crate, in which case `extern crate core` is injected.
            // The stdlib lib itself is `#![no_std]`.
            // We inject both crates for simplicity for now.
            if (name == AutoInjectedCrates.std || name == AutoInjectedCrates.core) {
                if (mod.isCrateRoot) {
                    mod.module?.let {
                        it.project.getPsiFor(it.cargoProject?.findExternCrateRootByName(name))?.rustMod?.let {
                            found(it)
                        }
                    }
                }
            } else {
                // Rust injects implicit `use std::prelude::v1::*` into every module.
                if (!visitedPrelude) {
                    visitedPrelude = true
                    mod.module?.preludeModule?.accept(this)
                }
            }
        }
    }

    /**
     * This particular visitor traverses both local & non-local scopes
     */
    inner class ResolveLocalScopesVisitor(ref: RustNamedElement) : ResolveNonLocalScopesVisitor(ref.name!!) {

        private val context: RustCompositeElement = ref

        override fun visitForExpr             (o: RustForExpr)            = visitResolveScope(o)
        override fun visitLambdaExpr          (o: RustLambdaExpr)         = visitResolveScope(o)
        override fun visitTraitMethodMember   (o: RustTraitMethodMember)  = visitResolveScope(o)
        override fun visitImplMethodMember    (o: RustImplMethodMember)   = visitResolveScope(o)
        override fun visitFnItem              (o: RustFnItem)             = visitResolveScope(o)
        override fun visitTypeItem            (o: RustTypeItem)           = visitResolveScope(o)
        override fun visitResolveScope        (scope: RustResolveScope)   = seek(scope.declarations)

        override fun visitScopedLetExpr(o: RustScopedLetExpr) {
            if (!PsiTreeUtil.isAncestor(o.scopedLetDecl, context, true)) {
                seek(o.declarations)
            }
        }

        override fun visitBlock(o: RustBlock) {
            val letDeclarations = o.letDeclarationsVisibleAt(context).flatMap { it.boundElements.asSequence() }
            val candidates = letDeclarations + o.namedItems

            candidates.find { match(it) }?.let { found(it) }
            if (matched != null) return

            seekUseDeclarations(o)
        }
    }

    private fun RustResolveScope.findByName(name: RustNamedElement): RustResolveEngine.ResolveResult =
        resolveIn(sequenceOf(this), by(name))
}


fun enumerateScopesFor(ref: RustQualifiedReferenceElement): Sequence<RustResolveScope> {
    if (ref.isRelativeToCrateRoot) {
        return listOfNotNull(RustResolveUtil.getCrateRootModFor(ref)).asSequence()
    }

    return generateSequence(RustResolveUtil.getResolveScopeFor(ref)) { parent ->
        when (parent) {
            is RustModItem  -> null
            else            -> RustResolveUtil.getResolveScopeFor(parent)
        }
    }
}


private fun RustNamedElement?.asResolveResult(): RustResolveEngine.ResolveResult =
    if (this == null)
        RustResolveEngine.ResolveResult.Unresolved
    else
        RustResolveEngine.ResolveResult.Resolved(this)


private fun PsiDirectory.findFileByRelativePath(path: String): PsiFile? {
    val parts = path.split("/")
    val fileName = parts.lastOrNull() ?: return null

    var dir = this
    for (part in parts.dropLast(1)) {
        dir = dir.findSubdirectory(part) ?: return null
    }

    return dir.findFile(fileName)
}


private fun resolvePreventingRecursion(
    element: RustCompositeElement,
    block: () -> RustResolveEngine.ResolveResult
): RustResolveEngine.ResolveResult {

    return RecursionManager.doPreventingRecursion(element, /* memoize = */ true, block)
        ?: RustResolveEngine.ResolveResult.Unresolved
}


/**
 * Helper to debug complex iterator pipelines
 */
@Suppress("unused")
private fun<T> Sequence<T>.inspect(f: (T) -> Unit = { println("inspecting $it") }): Sequence<T> {
    return map { it ->
        f(it)
        it
    }
}
