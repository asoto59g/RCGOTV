plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

val uploadStoreFile = providers.environmentVariable("RCGOTV_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.environmentVariable("RCGOTV_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("RCGOTV_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("RCGOTV_UPLOAD_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    uploadStoreFile,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.abcgeomag.rcgotv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.abcgeomag.rcgotv"
        minSdk = 23
        targetSdk = 36
        versionCode = providers.gradleProperty("rcgotvVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("rcgotvVersionName").orElse("1.0.0").get()
    }

    signingConfigs {
        create("playUpload") {
            if (!uploadStoreFile.isNullOrBlank()) {
                storeFile = file(uploadStoreFile)
            }
            if (!uploadStorePassword.isNullOrBlank()) {
                storePassword = uploadStorePassword
            }
            if (!uploadKeyAlias.isNullOrBlank()) {
                keyAlias = uploadKeyAlias
            }
            if (!uploadKeyPassword.isNullOrBlank()) {
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }
    buildTypes.getByName("release").signingConfig = signingConfigs.getByName("playUpload")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

gradle.taskGraph.whenReady {
    val releaseBundleRequested = allTasks.any {
        it.path in setOf(
            ":app:bundleRelease",
            ":app:assembleRelease",
            ":app:signReleaseBundle"
        )
    }
    if (releaseBundleRequested) {
        if (!releaseSigningConfigured) {
            throw GradleException(
                "Google Play release signing is not configured. Set RCGOTV_UPLOAD_STORE_FILE, " +
                    "RCGOTV_UPLOAD_STORE_PASSWORD, RCGOTV_UPLOAD_KEY_ALIAS, and " +
                    "RCGOTV_UPLOAD_KEY_PASSWORD in the build environment."
            )
        }
        if (!file(uploadStoreFile!!).isFile) {
            throw GradleException("Upload keystore does not exist: $uploadStoreFile")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.protobuf:protobuf-javalite:4.30.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    testImplementation("junit:junit:4.13.2")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.30.2" }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") { option("lite") }
            }
        }
    }
}