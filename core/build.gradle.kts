import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    implementation(libs.apksig)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.bouncycastle.pkix)
}

tasks.test {
    useJUnitPlatform()
    // The optional aapt2 oracle test reads the AAPT2 environment variable (path to the binary).
    inputs.property("aapt2", providers.environmentVariable("AAPT2").orElse(""))
}
