package org.kodein.klg

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Run after the KSP tasks of its project, whether they succeed or not: puts back the last successful
 * generation of every KSP task that started and didn't succeed.
 */
@DisableCachingByDefault(because = "It writes into the outputs of the KSP tasks, which belong to them.")
public abstract class RestoreLastGeneration : DefaultTask() {
    /** Where the KSP tasks of the project keep their last successful generation, one directory each. */
    @get:Internal
    public abstract val stateDirectory: DirectoryProperty

    init {
        description = "Puts back the last successful generation of the KSP tasks that failed."
        doNotTrackState("It writes into the outputs of the KSP tasks, which belong to them.")
    }

    @TaskAction
    public fun restore() {
        val taskDirectories = stateDirectory.get().asFile.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (directory in taskDirectories.sortedBy { it.name }) {
            if (LastGeneration(directory).restoreIfFailed()) {
                logger.lifecycle("KLG: '${directory.name}' failed, its last successful generation is back in place.")
            }
        }
    }
}
