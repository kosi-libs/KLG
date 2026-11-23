import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    // Publishes to the Gradle Plugin Portal, while mavenPublish publishes the same artifacts to Maven
    // Central: mavenPublish detects it, and leaves the sources and javadoc jars to it.
    alias(libs.plugins.gradlePluginPublish)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
    explicitApi()

    // Gradle runs a plugin against the Kotlin standard library it embeds, not the one the plugin was
    // compiled with: targeting Kotlin 2.0 - the one Gradle 8.11 embeds - keeps the plugin from calling
    // a standard library function that an older Gradle doesn't have.
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_2_0
        languageVersion = KotlinVersion.KOTLIN_2_0
    }
}

gradlePlugin {
    website = "https://github.com/kosi-libs/KLG"
    vcsUrl = "https://github.com/kosi-libs/KLG.git"
    plugins {
        create("klg") {
            id = "org.kodein.gradle.klg"
            implementationClass = "org.kodein.klg.KeepLastGenerationPlugin"
            displayName = "KLG - Keep Last Generation"
            description = "Keeps the last successful KSP generation in place when KSP fails, so that the IDE still resolves generated code."
            tags.addAll("kotlin", "ksp", "code-generation", "ide")
        }
    }
}

// The KSP processor the functional tests run: resolved as a runtime classpath, the way KSP loads one.
val testProcessor: Configuration = configurations.create("testProcessor") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit)

    testProcessor(projects.testProcessor)
}

tasks.test {
    // The functional tests build sample projects with the same Kotlin and KSP versions as this build.
    systemProperty("klg.kotlinVersion", libs.versions.kotlin.get())
    systemProperty("klg.kspVersion", libs.versions.ksp.get())

    val processorClasspath: FileCollection = testProcessor
    inputs.files(processorClasspath).withPropertyName("testProcessor").withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dklg.testProcessor=${processorClasspath.asPath}") })
}

mavenPublishing {
    pom {
        name = "klg-gradle-plugin"
        description = "Gradle plugin keeping the last successful KSP generation in place when KSP fails"
    }
}
