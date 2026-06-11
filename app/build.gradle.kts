import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.businesscard.scanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.businesscard.scanner"
        minSdk = 29
        targetSdk = 34
        versionCode = 5
        versionName = "1.4.0"
        val buildDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
        resourceConfigurations += setOf("en", "zh", "zh-rCN", "zh-rTW", "ja")
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: throw GradleException("KEYSTORE_PASSWORD env var not set")
            keyAlias = System.getenv("KEY_ALIAS")
                ?: throw GradleException("KEY_ALIAS env var not set")
            keyPassword = System.getenv("KEY_PASSWORD")
                ?: throw GradleException("KEY_PASSWORD env var not set")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Keep debug APKs lean: only include the host device's ABI.
            // Release uses App Bundle splitting instead.
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "**/*.kotlin_builtins",
                "**/*.kotlin_metadata",
                "DebugProbesKt.bin"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.activity)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.glide)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.viewpager2)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
