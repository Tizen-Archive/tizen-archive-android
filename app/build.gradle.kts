import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.tizenarchive.companion"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.tizenarchive.companion"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.3.1"
        // The catalog address is fixed in the app; a link can never change it. Override for local development:
        // ./gradlew :app:assembleDebug -PcatalogUrl=http://192.168.0.10:8080
        buildConfigField("String", "CATALOG_URL", "\"${providers.gradleProperty("catalogUrl").orElse("https://tizen-archive.com").get()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing is optional: F-Droid signs its own builds, so without these properties the release APK stays
    // unsigned. For a signed GitHub release pass -PreleaseStoreFile, -PreleaseStorePassword, -PreleaseKeyAlias and
    // -PreleaseKeyPassword on the command line (never commit them).
    val releaseStoreFile = providers.gradleProperty("releaseStoreFile").orNull
    if (releaseStoreFile != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = providers.gradleProperty("releaseStorePassword").get()
            keyAlias = providers.gradleProperty("releaseKeyAlias").get()
            keyPassword = providers.gradleProperty("releaseKeyPassword").get()
        }
    }

    buildTypes {
        debug {
            // Installs next to a release build, so tests on a phone never replace the app that is really used
            applicationIdSuffix = ".debug"
        }
        release {
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and the manifest of the app
            isIncludeAndroidResources = true
        }
    }

    dependenciesInfo {
        // F-Droid builds must not carry the Google-signed dependency metadata blob.
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.bouncycastle.pkix)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.apksig)
}
