plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.t0msilver.voicebubble"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.t0msilver.voicebubble"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // A throwaway debug key kept in the repo, so every CI build installs over the last one
    // instead of failing on a signature mismatch.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // The report is uploaded by CI; a prototype doesn't fail on it.
        abortOnError = false
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.coroutines.android)
    implementation(libs.mlkit.speech)
}
