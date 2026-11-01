import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val portraitDepthModel = layout.projectDirectory.file("src/main/assets/fused_model_uint8_256.onnx")
val downloadPortraitDepthModel by tasks.registering {
    outputs.file(portraitDepthModel)
    doLast {
        val modelFile = portraitDepthModel.asFile
        if (!modelFile.exists() || modelFile.length() != 27_342_208L) {
            modelFile.parentFile.mkdirs()
            val temporary = modelFile.resolveSibling("${modelFile.name}.download")
            val url = URL(
                "https://github.com/shubham0204/Depth-Anything-Android/releases/download/model-v2/fused_model_uint8_256.onnx"
            )
            val connection = url.openConnection()
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.getInputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() == 27_342_208L) { "Portrait depth model download was incomplete" }
            check(modelFile.delete() || !modelFile.exists()) { "Could not replace the portrait depth model" }
            check(temporary.renameTo(modelFile)) { "Could not install the portrait depth model" }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(downloadPortraitDepthModel) }

android {
    namespace = "ca.pwalker.ultratone"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.pwalker.ultratone"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.5.2"
    }

    // Build the arm64 APK first. Other Android ABIs can be added after validating
    // the rendering and capture path on the target phone.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
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
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-extensions:1.4.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}

