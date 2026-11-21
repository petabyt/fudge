import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.application")
    id("com.google.devtools.ksp")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

tasks.register<Exec>("compileModules") {
    group = "verification"
    description = "Compiles and copies modules"
    doNotTrackState("Always run this script during compilation")
    commandLine("bash", "-c", "cd ../modules && make OUT_DIR=../app/src/androidMain/assets install_fudge")
}
tasks.register<Exec>("compileLibs") {
    group = "verification"
    description = "Compiles typescript libraries"
    doNotTrackState("Always run this script during compilation")
    commandLine("bash", "-c", "cd ../libpak/typescript && make")
}
tasks.named("preBuild") {
    dependsOn("compileModules", "compileLibs")
}

kotlin {
    androidTarget()
    iosArm64()
    //iosSimulatorArm64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "Common"
            isStatic = true
        }
    }

    jvmToolchain(21)

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.navigation.compose)
            implementation(libs.ui.tooling.preview)
            implementation(libs.ui.backhandler)
            implementation(libs.zoomable)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.room.runtime)
            implementation(libs.kotlin.reflect)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
        }
        androidMain.dependencies {
            implementation(project(":library-client-rtsp"))
        }
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.core)
            implementation(libs.androidx.rules)
            implementation(libs.core.ktx)
            implementation(libs.androidx.runner)
            implementation(libs.androidx.junit.v121)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    //add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    //add("kspIosX64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
}

android {
    namespace = "dev.danielc.fudge"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "dev.danielc"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (providers.gradleProperty("testRelease").orNull == "true") {
            testBuildType = "release"
        }

        // Nobody uses 32bit anymore, disabling 32bit arm/x86 for now
        //ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes.getByName("debug") {
        buildConfigField("boolean", "DEBUG", "true")
        buildConfigField("Long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }
    buildTypes.getByName("release") {
        buildConfigField("boolean", "DEBUG", "false")
        buildConfigField("Long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    flavorDimensions += "buildType"
    productFlavors {
        create("stable") {
            dimension = "buildType"
            applicationId = "dev.danielc.fantasyfudge"
            resValue("string", "app_name", "FantasyFudge")
            isDefault = true
        }
        // Legacy playstore manifest
        create("fujiapp") {
            dimension = "buildType"
            applicationId = "dev.danielc.fujiapp"
            versionCode = 16
            versionName = "Beta Public release (16)"
            resValue("string", "app_name", "Fudge")
        }
        create("nightly") {
            dimension = "buildType"
            applicationId = "dev.danielc.fantasyfudge.nightly"
            resValue("string", "app_name", "FantasyFudge")
        }
    }

    signingConfigs {
        create("release") {
            val file = System.getenv("KEYSTORE_FILE")
            if (file == null) {
                val keystoreProperties = Properties()
                val keystorePropertiesFile = rootProject.file("/media/daniel/60FF-1900/keys/fujiapp.properties")
                if (keystorePropertiesFile.exists()) {
                    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
                    storeFile = file(keystoreProperties.getProperty("storeFile"))
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                }
            } else {
                storeFile = file(file)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (false) {
                isDebuggable = true
                isJniDebuggable = true
                ndk {
                    debugSymbolLevel = "FULL"
                }
                isMinifyEnabled = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../ndk/CMakeLists.txt")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
//    testImplementation(libs.junit)
//    androidTestImplementation(libs.androidx.junit)
//    androidTestImplementation(libs.androidx.espresso.core)
//    androidTestImplementation(platform(libs.androidx.compose.bom))
//    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
//    androidTestImplementation("androidx.test:runner:1.6.2")
//    androidTestImplementation("androidx.test.ext:junit:1.2.1")
//    debugImplementation(libs.androidx.compose.ui.tooling)
//    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}