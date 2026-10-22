package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContentFactoryEx
import com.intellij.diff.contents.DocumentContent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil

/**
 * Diff contents built the way the IDE builds them for the in-scope diffs (technical specification §1).
 *
 * `ChangeDiffRequestProducer.createContent` turns a VCS revision into `DiffContentFactoryEx.createFromBytes(project, bytes, filePath)`
 * and the current version of a local file into `DiffContentFactory.create(project, virtualFile)`. "Compare with…" between files
 * uses the second form on both sides.
 */
internal object DiffContents {

    fun vcsRevision(project: Project, text: String, path: String): DocumentContent =
        DiffContentFactoryEx.getInstanceEx().createFromBytes(project, text.toByteArray(), VcsUtil.getFilePath(path, false))
            as DocumentContent

    fun localFile(project: Project, file: VirtualFile): DocumentContent =
        DiffContentFactory.getInstance().create(project, file) as DocumentContent
}
