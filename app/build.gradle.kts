plugins {
    id("com.android.application")
}

android {
    namespace = "dev.pelopower.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pelopower.bridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.3.3"
    }

    buildFeatures {
        buildConfig = true
    }

    val releaseKeystore = providers.environmentVariable("PELOPOWER_KEYSTORE")
    if (releaseKeystore.isPresent) {
        signingConfigs.create("distribution") {
            storeFile = file(releaseKeystore.get())
            storePassword = providers.environmentVariable("PELOPOWER_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("PELOPOWER_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("PELOPOWER_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-probe"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("headless") {
            initWith(getByName("release"))
            versionNameSuffix = "-headless"
            // Local development builds may use the developer’s signing key.
            signingConfig = signingConfigs.findByName("distribution") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
