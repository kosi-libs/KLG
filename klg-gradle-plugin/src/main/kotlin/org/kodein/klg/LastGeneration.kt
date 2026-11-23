package org.kodein.klg

import java.io.File

/**
 * The state KLG keeps for one KSP task, in [directory]:
 *
 * - a `started` marker, present from the moment the task starts until it succeeds;
 * - a `snapshot` of what it generated the last time it succeeded: one numbered copy per output
 *   directory, and `outputs.txt` listing where each one goes, relative to [directory] so that a
 *   moved project restores into itself.
 */
internal class LastGeneration(private val directory: File) {
    private val startedMarker = File(directory, "started")
    private val snapshotDirectory = File(directory, "snapshot")

    fun markStarted() {
        directory.mkdirs()
        startedMarker.writeText("")
    }

    /**
     * Replaces the snapshot with a copy of [outputs]. The copy is made aside first, so that an
     * interrupted build never leaves a partial snapshot behind.
     */
    fun keep(outputs: List<File>) {
        val next = File(directory, "snapshot.next")
        next.deleteRecursively()
        next.mkdirs()
        val paths = outputs.mapIndexed { index, output ->
            if (output.exists()) output.copyRecursively(File(next, index.toString()))
            output.toRelativeString(directory)
        }
        File(next, OUTPUTS_FILE).writeText(paths.joinToString("\n"))
        snapshotDirectory.deleteRecursively()
        check(next.renameTo(snapshotDirectory)) { "Could not move $next to $snapshotDirectory." }
        startedMarker.delete()
    }

    /**
     * When the task started and didn't succeed, puts the snapshot back in place of its outputs.
     * Returns whether it did.
     */
    fun restoreIfFailed(): Boolean {
        if (!startedMarker.exists()) return false
        val outputsFile = File(snapshotDirectory, OUTPUTS_FILE)
        val restored = outputsFile.exists()
        if (restored) {
            outputsFile.readLines().forEachIndexed { index, path ->
                val output = directory.resolve(path).normalize()
                output.deleteRecursively()
                val saved = File(snapshotDirectory, index.toString())
                if (saved.exists()) saved.copyRecursively(output)
            }
        }
        startedMarker.delete()
        return restored
    }

    private companion object {
        const val OUTPUTS_FILE = "outputs.txt"
    }
}
