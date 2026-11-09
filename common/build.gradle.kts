import org.gradle.kotlin.dsl.implementation

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("com.google.devtools.ksp")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.room)
}

room {
    schemaDirectory("$projectDir/schemas")
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

    sourceSets {
        commonMain.dependencies {
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.navigation.compose)
            implementation(libs.ui.tooling.preview)
            implementation(libs.ui.backhandler)
            implementation(libs.zoomable)
//            implementation(libs.androidx.core.ktx)
//            implementation(libs.androidx.lifecycle.runtime.ktx)
//            implementation(libs.androidx.activity.compose)
//            implementation(libs.androidx.ui.graphics)
//            implementation(libs.androidx.compose.ui.graphics)
//            implementation(libs.androidx.compose.ui.tooling.preview)
//            implementation(libs.androidx.compose.material3)
            implementation(libs.kotlinx.serialization.json)
//            implementation(libs.androidx.activity)
//            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.room.runtime)
//            implementation(libs.androidx.sqlite.bundled)
//            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlin.reflect)
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0") // or latest version
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
        }
        androidMain.dependencies {
            implementation(project(":library-client-rtsp"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
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
    namespace = "dev.danielc.common"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
