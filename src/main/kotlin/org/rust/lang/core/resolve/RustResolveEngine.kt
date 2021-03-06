package org.rust.lang.core.resolve

import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.rust.cargo.util.AutoInjectedCrates
import org.rust.cargo.util.cargoProject
import org.rust.cargo.util.getPsiFor
import org.rust.cargo.util.preludeModule
import org.rust.ide.utils.recursionGuard
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RustTokenElementTypes.IDENTIFIER
import org.rust.lang.core.psi.impl.mixin.basePath
import org.rust.lang.core.psi.impl.mixin.isStarImport
import org.rust.lang.core.psi.impl.mixin.letDeclarationsVisibleAt
import org.rust.lang.core.psi.impl.mixin.possiblePaths
import org.rust.lang.core.psi.impl.rustMod
import org.rust.lang.core.psi.util.elementType
import org.rust.lang.core.psi.util.fields
import org.rust.lang.core.psi.util.module
import org.rust.lang.core.resolve.ref.RustReferenceBase
import org.rust.lang.core.resolve.scope.RustResolveScope
import org.rust.lang.core.resolve.util.RustResolveUtil
import org.rust.lang.core.types.RustStructType
import org.rust.lang.core.types.RustType
import org.rust.lang.core.types.unresolved.RustUnresolvedType
import org.rust.lang.core.types.util.resolvedType
import org.rust.lang.core.types.visitors.RustTypeResolvingVisitor


object RustResolveEngine {

    open class ResolveResult private constructor(val resolved: RustNamedElement?) : com.intellij.psi.ResolveResult {

        companion object {
            fun buildFrom(candidates: Iterable<RustNamedElement>): ResolveResult {
                return when (candidates.count()) {
                    1       -> ResolveResult.Resolved(candidates.first())
                    0       -> ResolveResult.Unresolved
                    else    -> ResolveResult.Ambiguous(candidates)
                }
            }
        }

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
        class Ambiguous(val candidates: Iterable<RustNamedElement>) : ResolveResult(null)

        /**
         * Designates resolve-engine successfully resolved given target
         */
        class Resolved(resolved: RustNamedElement) : ResolveResult(resolved)
    }

    fun resolve(type: RustUnresolvedType): RustType =
        type.accept(RustTypeResolvingVisitor())


    /**
     * Resolves `qualified-reference` bearing PSI-elements
     *
     * NOTE: This operate on PSI to extract all the necessary (yet implicit) resolving-context
     */
    fun resolve(ref: RustQualifiedReferenceElement): ResolveResult =
        Resolver().resolve(ref)

    /**
     * Resolves references to struct's fields inside destructuring [RustStructExprElement]
     */
    fun resolveStructExprField(structExpr: RustStructExprElement, fieldName: String): ResolveResult {
        val matching = structExpr   .fields
                                    .filter { it.name == fieldName }

        return ResolveResult.buildFrom(matching)
    }

    /**
     * Resolves references to struct's fields inside [RustFieldExprElement]
     */
    fun resolveFieldExpr(fieldExpr: RustFieldExprElement): ResolveResult {
        val receiverType = fieldExpr.expr.resolvedType

        val id = (fieldExpr.fieldId.identifier ?: fieldExpr.fieldId.integerLiteral)!!
        val matching = when (id.elementType) {
            IDENTIFIER -> {
                val name = id.text
                when (receiverType) {
                    is RustStructType -> receiverType.struct.fields.filter { it.name == name }
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }

        return ResolveResult.buildFrom(matching)
    }

    /**
     * Resolves method-call expressions
     */
    fun resolveMethodCallExpr(call: RustMethodCallExprElement): ResolveResult {
        val receiverType = call.expr.resolvedType

        return call.identifier?.let {
            val name = it.text
            val matching: Iterable<RustNamedElement> =
                when (receiverType) {
                    is RustStructType -> receiverType.nonStaticMethods.filter { it.name == name }

                    else -> emptyList()
                }

            ResolveResult.buildFrom(matching)
        } ?: ResolveResult.Unresolved
    }

    //
    // TODO(kudinkin): Unify following?
    //

    fun resolveUseGlob(ref: RustUseGlobElement): ResolveResult =
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
    fun resolveModDecl(ref: RustModDeclItemElement): ResolveResult {
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

    fun resolveExternCrate(crate: RustExternCrateItemElement): ResolveResult {
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
     * For more details check out [RustResolveEngine.resolve]
     */
    fun resolve(ref: RustQualifiedReferenceElement): RustResolveEngine.ResolveResult = recursionGuard(ref) {
        val modulePrefix = ref.relativeModulePrefix
        when (modulePrefix) {
            is RelativeModulePrefix.Invalid        -> RustResolveEngine.ResolveResult.Unresolved
            is RelativeModulePrefix.AncestorModule -> resolveAncestorModule(ref, modulePrefix).asResolveResult()
            is RelativeModulePrefix.NotRelative    -> {
                val qual = ref.qualifier
                if (qual == null) {
                    resolveIn(enumerateScopesFor(ref), ref)
                } else {
                    val parent = resolve(qual).element
                    when (parent) {
                        is RustMod      -> resolveIn(sequenceOf(parent), ref)
                        is RustEnumItemElement -> resolveIn(sequenceOf(parent), ref)
                        else            -> RustResolveEngine.ResolveResult.Unresolved
                    }
                }
            }
        }
    } ?: RustResolveEngine.ResolveResult.Unresolved

    /**
     * Resolves `use-glob`s, ie:
     *
     *  ```
     *  use foo::bar::{baz as boo}
     *  use foo::*
     *  ```
     */
    fun resolveUseGlob(ref: RustUseGlobElement): RustResolveEngine.ResolveResult = recursionGuard(ref) {
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
            baseItem is RustResolveScope -> resolveIn(sequenceOf(baseItem), ref)

            else -> RustResolveEngine.ResolveResult.Unresolved
        }
    } ?: RustResolveEngine.ResolveResult.Unresolved

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

    private fun resolveIn(scopes: Sequence<RustResolveScope>, ref: RustReferenceElement): RustResolveEngine.ResolveResult {
        val visitor = ResolveScopeVisitor(ref.referenceName, ref)
        for (s in scopes) {
            s.accept(visitor)
            if (visitor.result is RustResolveEngine.ResolveResult.Resolved) {
                return visitor.result
            }
        }

        return RustResolveEngine.ResolveResult.Unresolved
    }

    /**
     * Searches for the element with [name] visible at [context] in a single scope.
     * Does not walk the tree of scopes up, see [enumerateScopesFor].
     */
    inner class ResolveScopeVisitor(
        private val name: String,
        private val context: RustCompositeElement
    ) : RustElementVisitor() {

        var result: RustResolveEngine.ResolveResult = RustResolveEngine.ResolveResult.Unresolved

        private fun ok(result: RustResolveEngine.ResolveResult): Boolean =
            result !is RustResolveEngine.ResolveResult.Unresolved

        override fun visitFile(file: PsiFile) {
            file.rustMod?.let { visitMod(it) }
        }

        override fun visitModItem(o: RustModItemElement) {
            visitMod(o)
        }

        override fun visitEnumItem      (o: RustEnumItemElement)   { seek(o.declarations) }
        override fun visitTraitItem     (o: RustTraitItemElement)  { seek(o.declarations) }
        override fun visitStructItem    (o: RustStructItemElement) { seek(o.declarations) }
        override fun visitImplItem      (o: RustImplItemElement)   { seek(o.declarations) }

        private fun visitMod(mod: RustMod) {
            seek(mod.declarations)      .let { if (ok(result)) return }
            seekUseDeclarations(mod)    .let { if (ok(result)) return }
            seekInjectedItems(mod)      .let { if (ok(result)) return }
        }

        private fun seek(element: RustDeclaringElement) {
            element.boundElements.find { matching(it) }?.let { assign(it) }
        }

        private fun seek(elements: Collection<RustNamedElement>) {
            check(result is RustResolveEngine.ResolveResult.Unresolved)
            elements.find { matching(it) }?.let { assign(it) }
        }

        private fun assign(elem: RustNamedElement): RustResolveEngine.ResolveResult {
            result =
                // Check whether resolved element could be further resolved
                when (elem) {
                    is RustModDeclItemElement, is RustExternCrateItemElement -> elem.reference.let { it as RustReferenceBase<*> }
                                                                                    .resolveVerbose()

                    is RustPathElement -> resolve(elem)
                    is RustUseGlobElement -> resolveUseGlob(elem)

                    is RustAliasElement -> {
                        val parent = elem.parent
                        when (parent) {
                            is RustExternCrateItemElement -> parent.reference.let { it as RustReferenceBase<*> }
                                                                   .resolveVerbose()

                            else -> RustResolveEngine.ResolveResult.Resolved(elem)
                        }
                    }

                    else -> RustResolveEngine.ResolveResult.Resolved(elem)
                }

            return result
        }

        private fun matching(elem: RustNamedElement): Boolean = matching(elem.name)
        private fun matching(elemName: String?): Boolean = elemName == name

        private fun seekUseDeclarations(o: RustItemsOwner) {
            for (useDecl in o.useDeclarations) {
                seekUseDeclaration(useDecl)
                if (ok(result)) return
            }
        }

        private fun seekUseDeclaration(useDecl: RustUseItemElement) {
            if (useDecl.isStarImport) {
                // Recursively step into `use foo::*`
                val pathPart = useDecl.path ?: return
                val mod = resolve(pathPart).element ?: return

                recursionGuard(this to mod, memoize = false) {
                    mod.accept(this)
                }

                return
            }

            val globList = useDecl.useGlobList
            if (globList == null) {
                val path = useDecl.path ?: return
                // use foo::bar [as baz];
                if (matching(useDecl.alias?.name ?: path.referenceName)) {
                    result = resolve(path)
                }
                return
            }

            globList.useGlobList.find { glob ->
                val name = listOfNotNull(
                    glob.alias?.name, // {foo as bar};
                    glob.self?.let { glob.basePath?.referenceName }, // {self}
                    glob.referenceName // {foo}
                ).firstOrNull()
                matching(name)
            }?.let {
                result = resolveUseGlob(it)
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
                        it.project.getPsiFor(it.cargoProject?.findExternCrateRootByName(name))?.rustMod
                            ?.let { assign(it) }
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

        override fun visitForExpr             (o: RustForExprElement)            { seek(o.scopedForDecl) }
        override fun visitLambdaExpr          (o: RustLambdaExprElement)         { visitResolveScope(o) }
        override fun visitTraitMethodMember   (o: RustTraitMethodMemberElement)  { visitResolveScope(o) }
        override fun visitImplMethodMember    (o: RustImplMethodMemberElement)   { visitResolveScope(o) }
        override fun visitFnItem              (o: RustFnItemElement)             { visitResolveScope(o) }
        override fun visitTypeItem            (o: RustTypeItemElement)           { visitResolveScope(o) }
        override fun visitResolveScope        (scope: RustResolveScope)   { seek(scope.declarations) }

        override fun visitScopedLetExpr(o: RustScopedLetExprElement) {
            if (!PsiTreeUtil.isAncestor(o.scopedLetDecl, context, true)) {
                seek(o.scopedLetDecl)
            }
        }

        override fun visitBlock(o: RustBlockElement) {
            val letDeclarations = o.letDeclarationsVisibleAt(context).flatMap { it.boundElements.asSequence() }
            val candidates = letDeclarations + o.itemList

            candidates
                .find   { matching(it) }
                ?.let   { assign(it) }
                .let    { if (ok(result)) return }

            seekUseDeclarations(o)
        }
    }
}


fun enumerateScopesFor(ref: RustQualifiedReferenceElement): Sequence<RustResolveScope> {
    if (ref.isRelativeToCrateRoot) {
        return listOfNotNull(RustResolveUtil.getCrateRootModFor(ref)).asSequence()
    }

    return generateSequence(RustResolveUtil.getResolveScopeFor(ref)) { parent ->
        when (parent) {
            is RustModItemElement  -> null
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
