plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
}

dependencies {
    // KSP provides its API to the processors it loads, so the processor's jar must not carry it.
    compileOnly(libs.ksp.symbolProcessingApi)
}
