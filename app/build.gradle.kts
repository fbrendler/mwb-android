plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.brendler.mwb"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.brendler.mwb"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
}