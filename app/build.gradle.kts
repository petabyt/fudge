plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.danielc"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.danielc"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Nobody uses 32bit anymore, disabling 32bit arm/x86 for now
        //ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
        create("nightly") {
            dimension = "buildType"
            applicationId = "dev.danielc.fantasyfudge.nightly"
            resValue("string", "app_name", "FantasyFudge")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release-key.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
//            isDebuggable = true
//            isJniDebuggable = true
//            ndk {
//                debugSymbolLevel = "FULL"
//            }
//            isMinifyEnabled = false
//            proguardFiles(
//                getDefaultProguardFile("proguard-android-optimize.txt"),
//                "proguard-rules.pro"
//            )
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

// https://developer.android.com/training/data-storage/room/migrating-db-versions
class RoomSchemaArgProvider(
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  val schemaDir: File
) : CommandLineArgumentProvider {

  override fun asArguments(): Iterable<String> {
    return listOf("room.schemaLocation=${schemaDir.path}")
  }
}
ksp {
  arg(RoomSchemaArgProvider(File(projectDir, "schemas")))
}

tasks.register<Exec>("compileModules") {
    group = "verification"
    description = "Compiles and copies modules"
    doNotTrackState("Always run this script during compilation")
    commandLine("bash", "-c", "cd ../modules && make install_fudge")
}
tasks.register<Exec>("compileLibs") {
    group = "verification"
    description = "Compiles and copies modules"
    doNotTrackState("Always run this script during compilation")
    commandLine("bash", "-c", "cd ../libpak/typescript && make")
}
tasks.named("preBuild") {
    dependsOn("compileModules", "compileLibs")
}

dependencies {
    rootProject.extra["noNativeModule"] = true
    implementation(project(":libpak"))
    implementation(project(":library-client-rtsp"))
    //implementation(libs.androidx.material3)

    // libs
    implementation(libs.zoomable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui.graphics)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // Test stuff
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
