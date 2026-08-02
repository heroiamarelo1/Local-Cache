plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.localcache"
    compileSdk = 34

    defaultConfig {
        // Different package from WuPlay Local Cache (app.localcache) so both can stay installed
        // even though both display as "Local Cache".
        applicationId = "app.localcache.release"
        minSdk = 24
        targetSdk = 34
        versionCode = 63
        versionName = "0.4.23"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "audience"
    productFlavors {
        create("standard") {
            dimension = "audience"
            isDefault = true
            // Public: need at least 2 GB free for internal fallback.
            buildConfigField("boolean", "ALLOW_TINY_INTERNAL", "false")
            buildConfigField("long", "INTERNAL_MIN_FREE_BYTES", "${2L * 1024 * 1024 * 1024}L")
        }
        create("personal") {
            dimension = "audience"
            applicationIdSuffix = ".personal"
            resValue("string", "app_name", "Local Cache Personal")
            // Dev TV with little free space — skip the 2 GB gate.
            buildConfigField("boolean", "ALLOW_TINY_INTERNAL", "true")
            buildConfigField("long", "INTERNAL_MIN_FREE_BYTES", "0L")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
