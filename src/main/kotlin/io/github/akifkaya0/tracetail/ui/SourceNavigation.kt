package io.github.akifkaya0.tracetail.ui

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import io.github.akifkaya0.tracetail.model.LogLine

/** Finds where a line was written: the class named by its logger. */
internal object SourceNavigation {

    /** Needs a read action. */
    fun of(project: Project, line: LogLine): Navigatable? {
        val logger = line.logger ?: return null
        if (DumbService.isDumb(project)) return null
        // a nested class is found through the class around it, which is in the same file
        val cls = JavaPsiFacade.getInstance(project).findClass(logger.substringBefore('$'), GlobalSearchScope.allScope(project))
            ?: return null
        return cls.navigationElement as? Navigatable
    }
}
