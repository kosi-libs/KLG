package org.kodein.klg

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Builds sample projects running KSP with the test processor, which generates `<Name>Generated` for
 * every class annotated `@klg.test.Generate`, and fails on a class whose name starts with `Broken`.
 */
class KeepLastGenerationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val root: File get() = temporaryFolder.root

    private val kotlinVersion = System.getProperty("klg.kotlinVersion")
    private val kspVersion = System.getProperty("klg.kspVersion")
    private val processorFiles = System.getProperty("klg.testProcessor").split(File.pathSeparator)
        .joinToString(", ") { "\"" + it.replace("\\", "\\\\") + "\"" }

    private fun write(path: String, content: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent() + "\n")
    }

    private fun delete(path: String) {
        assertTrue(File(root, path).delete(), "$path should exist")
    }

    private fun runner(vararg tasks: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(root)
            .withPluginClasspath()
            // Every build stores and reuses its configuration cache entry, so that a plugin breaking
            // it fails the test.
            .withArguments(*tasks, "--configuration-cache", "--stacktrace")

    private fun succeeds(vararg tasks: String): BuildResult = runner(*tasks).build()
    private fun fails(vararg tasks: String): BuildResult = runner(*tasks).buildAndFail()

    private fun annotatedClass(packagePath: String, name: String) {
        write("$packagePath/sample/$name.kt", """
            package sample

            @klg.test.Generate
            class $name
        """)
    }

    /** [klgFirst] applies KLG before the Kotlin and KSP plugins, instead of after them. */
    private fun jvmProject(withKsp: Boolean = true, klgFirst: Boolean = false) {
        val klg = "id(\"org.kodein.gradle.klg\")"
        write("settings.gradle.kts", """
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
            }

            rootProject.name = "sample"
        """)
        write("build.gradle.kts", """
            plugins {
                ${if (klgFirst) klg else ""}
                kotlin("jvm") version "$kotlinVersion"
                ${if (withKsp) "id(\"com.google.devtools.ksp\") version \"$kspVersion\"" else ""}
                ${if (klgFirst) "" else klg}
            }

            ${if (withKsp) "dependencies { ksp(files($processorFiles)) }" else ""}
        """)
        write("src/main/kotlin/klg/test/Generate.kt", """
            package klg.test

            annotation class Generate
        """)
    }

    private fun multiplatformProject() {
        write("settings.gradle.kts", """
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
            }

            rootProject.name = "sample"
        """)
        write("build.gradle.kts", """
            plugins {
                kotlin("multiplatform") version "$kotlinVersion"
                id("com.google.devtools.ksp") version "$kspVersion"
                id("org.kodein.gradle.klg")
            }

            // A second target, so that commonMain is compiled to metadata on its own - which is what
            // kspCommonMainKotlinMetadata processes.
            kotlin {
                jvm()
                js { nodejs() }
            }

            dependencies { add("kspCommonMainMetadata", files($processorFiles)) }
        """)
        write("src/commonMain/kotlin/klg/test/Generate.kt", """
            package klg.test

            annotation class Generate
        """)
    }

    private fun generated(outputPath: String, name: String) = File(root, "build/generated/ksp/$outputPath/kotlin/sample/${name}Generated.kt")

    @Test
    fun jvmProject_keepsTheLastGenerationWhileKspFails() {
        jvmProject()
        annotatedClass("src/main/kotlin", "First")
        succeeds("compileKotlin")
        val first = generated("main", "First")
        assertTrue(first.exists())

        annotatedClass("src/main/kotlin", "Broken")
        val failure = fails("compileKotlin")
        assertContains(failure.output, "'Broken' is broken.")
        assertContains(failure.output, "KLG: 'kspKotlin' failed, its last successful generation is back in place.")
        assertTrue(first.exists())
        assertEquals("package sample\n\nobject FirstGenerated {\n    const val NAME: String = \"First\"\n}\n", first.readText())

        // The configuration cache entry is reused, and the restored generation is kept again.
        assertContains(fails("compileKotlin").output, "KLG: 'kspKotlin' failed")
        assertTrue(first.exists())
    }

    @Test
    fun jvmProject_appliedBeforeKsp_keepsTheLastGenerationWhileKspFails() {
        jvmProject(klgFirst = true)
        annotatedClass("src/main/kotlin", "First")
        succeeds("compileKotlin")
        val first = generated("main", "First")
        assertTrue(first.exists())

        annotatedClass("src/main/kotlin", "Broken")
        val failure = fails("compileKotlin")
        assertContains(failure.output, "'Broken' is broken.")
        assertContains(failure.output, "KLG: 'kspKotlin' failed, its last successful generation is back in place.")
        assertTrue(first.exists())
    }

    @Test
    fun jvmProject_regeneratesEverythingOnceKspSucceedsAgain() {
        jvmProject()
        annotatedClass("src/main/kotlin", "First")
        succeeds("compileKotlin")
        annotatedClass("src/main/kotlin", "Broken")
        fails("compileKotlin")

        delete("src/main/kotlin/sample/Broken.kt")
        delete("src/main/kotlin/sample/First.kt")
        annotatedClass("src/main/kotlin", "Second")
        val success = succeeds("compileKotlin")
        assertFalse("KLG:" in success.output)
        assertFalse(generated("main", "First").exists(), "the restored generation should not outlive a successful run")
        assertTrue(generated("main", "Second").exists())

        // The new generation is the one kept from now on.
        annotatedClass("src/main/kotlin", "Broken")
        fails("compileKotlin")
        assertFalse(generated("main", "First").exists())
        assertTrue(generated("main", "Second").exists())
    }

    @Test
    fun jvmProject_keepsTheGenerationOfAnUpToDateTask() {
        jvmProject()
        annotatedClass("src/main/kotlin", "First")
        succeeds("compileKotlin")
        succeeds("compileKotlin")

        annotatedClass("src/main/kotlin", "Broken")
        fails("compileKotlin")
        assertTrue(generated("main", "First").exists())
    }

    @Test
    fun jvmProject_failingBeforeAnySuccess_restoresNothing() {
        jvmProject()
        annotatedClass("src/main/kotlin", "Broken")
        val failure = fails("compileKotlin")
        assertContains(failure.output, "'Broken' is broken.")
        assertFalse("KLG:" in failure.output)
    }

    @Test
    fun multiplatformProject_keepsTheLastGenerationOfTheCommonMetadata() {
        multiplatformProject()
        annotatedClass("src/commonMain/kotlin", "First")
        succeeds("kspCommonMainKotlinMetadata")
        val first = generated("metadata/commonMain", "First")
        assertTrue(first.exists())

        annotatedClass("src/commonMain/kotlin", "Broken")
        val failure = fails("kspCommonMainKotlinMetadata")
        assertContains(failure.output, "KLG: 'kspCommonMainKotlinMetadata' failed, its last successful generation is back in place.")
        assertTrue(first.exists())
    }

    @Test
    fun projectWithoutKsp_isLeftAlone() {
        jvmProject(withKsp = false)
        annotatedClass("src/main/kotlin", "First")
        succeeds("compileKotlin")
        assertContains(fails("klgRestoreLastGeneration").output, "Task 'klgRestoreLastGeneration' not found")
    }
}
