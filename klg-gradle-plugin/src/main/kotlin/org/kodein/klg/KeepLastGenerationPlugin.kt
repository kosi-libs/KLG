package org.kodein.klg

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider

/**
 * KLG - Keep Last Generation.
 *
 * Before running, a KSP task deletes everything it generated, and when a processor reports an error,
 * it fails without generating anything again. The compiler doesn't run, so the only error of the
 * build is the processor's - but the IDE, which reads the generated sources, now resolves none of
 * them, and marks every use of generated code as an error.
 *
 * With this plugin, each KSP task of the project keeps a copy of what it generated whenever it
 * succeeds, and a [RestoreLastGeneration] task, run after the KSP tasks whether they succeed or not,
 * puts that copy back in place when one fails. The IDE then still sees the last successful
 * generation, while the build still fails with the processor's error. The next run of the KSP task
 * deletes the restored files and generates everything again, like any other run.
 *
 * Applying it to a project without the KSP plugin does nothing.
 */
public class KeepLastGenerationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin(KSP_PLUGIN_ID) {
            val stateDirectory = project.layout.buildDirectory.dir("klg")
            val restore = project.tasks.register(RESTORE_TASK_NAME, RestoreLastGeneration::class.java) { task ->
                task.stateDirectory.set(stateDirectory)
            }
            project.tasks.configureEach { task ->
                if (!task.isKspTask()) return@configureEach
                val taskName = task.name
                val taskState = stateDirectory.map { it.dir(taskName) }
                task.doFirst(MarkGenerationStarted(taskState))
                task.doLast(KeepGeneration(taskState, task.outputs.files))
                task.finalizedBy(restore)
            }
        }
    }

    private companion object {
        const val KSP_PLUGIN_ID = "com.google.devtools.ksp"
        const val RESTORE_TASK_NAME = "klgRestoreLastGeneration"

        // KspAATask is KSP 2's task; the KspTask interface is implemented by KSP 1's.
        val KSP_TASK_TYPES = setOf("com.google.devtools.ksp.gradle.KspAATask", "com.google.devtools.ksp.gradle.KspTask")

        // Matched by name rather than by type, so that the plugin doesn't depend on a KSP version.
        fun Task.isKspTask(): Boolean =
            generateSequence<Class<*>>(javaClass) { it.superclass }
                .any { type -> type.name in KSP_TASK_TYPES || type.interfaces.any { it.name in KSP_TASK_TYPES } }
    }
}

/** Run first by a KSP task: from here on, the task counts as failed until [KeepGeneration] runs. */
internal class MarkGenerationStarted(private val state: Provider<Directory>) : Action<Task> {
    override fun execute(task: Task) {
        LastGeneration(state.get().asFile).markStarted()
    }
}

/** Run last by a KSP task, which only happens when it succeeded: keeps a copy of its [outputs]. */
internal class KeepGeneration(
    private val state: Provider<Directory>,
    private val outputs: FileCollection,
) : Action<Task> {
    override fun execute(task: Task) {
        LastGeneration(state.get().asFile).keep(outputs.files.toList())
    }
}
