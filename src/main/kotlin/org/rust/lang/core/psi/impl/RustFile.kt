package org.rust.lang.core.psi.impl

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.rust.cargo.util.crateRoots
import org.rust.lang.RustFileType
import org.rust.lang.RustLanguage
import org.rust.lang.core.names.RustFileModuleId
import org.rust.lang.core.names.RustQualifiedName
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.util.module
import org.rust.lang.core.resolve.indexes.RustCratePath
import org.rust.lang.core.resolve.indexes.RustModulesIndex
import org.rust.lang.core.resolve.ref.RustReference

class RustFile(
    fileViewProvider: FileViewProvider
) : PsiFileBase(fileViewProvider, RustLanguage), RustMod, RustInnerAttributeOwner {

    override fun getReference(): RustReference? = null

    override fun getFileType(): FileType = RustFileType

    override val itemList: List<RustItem>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, RustItem::class.java)

    override val `super`: RustMod?
        get() = RustModulesIndex.getSuperFor(this)

    override val ownsDirectory: Boolean
        get() = name == RustMod.MOD_RS || isCrateRoot

    override val ownedDirectory: PsiDirectory?
        get() = originalFile.parent

    override val isCrateRoot: Boolean get() {
        val file = originalFile.virtualFile ?: return false
        return file in (module?.crateRoots ?: emptyList())
    }

    override val isTopLevelInFile: Boolean = true

    override val canonicalNameInFile: RustQualifiedName?
        get() = cratePath?.let { RustFileModuleId(it) }

    override val declarations: Collection<RustNamedElement>
        get() = namedItems

    override val innerAttrList: List<RustInnerAttr>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, RustInnerAttr::class.java)
}


val PsiFile.cratePath: RustCratePath?
    get() = RustCratePath.devise(this)


/**
 * Prepends directory name to this file, if it is `mod.rs`
 */
val PsiFile.usefulName: String get() = when (name) {
    RustMod.MOD_RS -> containingDirectory?.let { dir ->
        FileUtil.join(dir.name, name)
    } ?: name
    else -> name
}

val PsiFile.rustMod: RustMod? get() = this as? RustFile

val VirtualFile.isNotRustFile: Boolean get() = !isRustFile
val VirtualFile.isRustFile: Boolean get() = fileType == RustFileType
