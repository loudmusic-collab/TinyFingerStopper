plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.loudmusic.tinyfingerstopper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.loudmusic.tinyfingerstopper"
        // TYPE_APPLICATION_OVERLAY, notification channels and Notification.Builder
        // with a channel id all land in API 26.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Lint still reports; it just does not gate the build while the app is
        // young. The safety invariants are enforced by LockSafetyTest instead.
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Deliberately no runtime dependencies. Everything this app needs is in the
    // framework at API 26, which keeps an app that holds an overlay permission
    // as small and auditable as possible.
    testImplementation(libs.junit)
}
