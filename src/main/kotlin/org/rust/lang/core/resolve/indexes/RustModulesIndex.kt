package org.rust.lang.core.resolve.indexes

import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import org.rust.cargo.util.getPsiFor
import org.rust.lang.core.psi.RustMod
import org.rust.lang.core.psi.impl.RustFile
import org.rust.lang.core.psi.util.parentOfType

object RustModulesIndex {
    val ID: ID<RustModulesIndexExtension.Key, RustModulesIndexExtension.Value> =
        com.intellij.util.indexing.ID.create("org.rust.lang.indexes.RustModulesIndex")

    fun getSuperFor(mod: RustFile): RustMod? {
        val project = mod.project
        val key = getKey(mod) ?: return null

        var result: RustMod? = null
        FileBasedIndex.getInstance().processValues(ID, key, null, { file, value ->
            val reference = project.getPsiFor(file)?.findReferenceAt(value.referenceOffset)
                ?: return@processValues true

            if (reference.resolve() == mod.originalFile) {
                result = reference.element.parentOfType<RustMod>()
                false
            } else {
                true
            }
        }, GlobalSearchScope.allScope(project) )

        return result
    }

    private fun getKey(file: RustFile): RustModulesIndexExtension.Key? {
        val name = if (file.name != RustMod.MOD_RS)
            FileUtil.getNameWithoutExtension(file.name)
        else
            file.parent?.name

        return name?.let {
            RustModulesIndexExtension.Key(it)
        }
    }
}
