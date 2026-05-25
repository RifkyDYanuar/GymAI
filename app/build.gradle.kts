plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.navigation.safeargs)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.modul.gymai"
    compileSdk = 36


    defaultConfig {
        applicationId = "com.modul.gymai"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
    aaptOptions {
        noCompress += listOf("tflite")
    }
    packaging {
        jniLibs {
            // ML Kit already bundles its own TFLite JNI runtime.
            // Exclude duplicate .so files to prevent JNI registration failure.
            pickFirsts += setOf(
                "lib/x86/libtensorflowlite_jni.so",
                "lib/x86_64/libtensorflowlite_jni.so",
                "lib/armeabi-v7a/libtensorflowlite_jni.so",
                "lib/arm64-v8a/libtensorflowlite_jni.so",
                "lib/x86/libtensorflowlite_gpu_jni.so",
                "lib/x86_64/libtensorflowlite_gpu_jni.so",
                "lib/armeabi-v7a/libtensorflowlite_gpu_jni.so",
                "lib/arm64-v8a/libtensorflowlite_gpu_jni.so"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)

    // Navigation
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // CardView
    implementation(libs.androidx.cardview)

    // ML Kit Pose Detection
    implementation(libs.mlkit.pose.detection)

    // TensorFlow Lite
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)

    // Media3 playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
