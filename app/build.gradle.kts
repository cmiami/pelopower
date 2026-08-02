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
        versionCode = 5
        versionName = "1.1.0"
    }

    buildFeatures {
        buildConfig = true
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
            // Convenient for bike-side testing. Replace with a private release key for distribution.
            signingConfig = signingConfigs.getByName("debug")
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
