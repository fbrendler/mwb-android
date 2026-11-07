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
        versionCode = 2
        versionName = "0.2.0"

    }

    // Assinatura de release: os dados ficam fora do projeto, em ~/.gradle/gradle.properties
    // (MWB_RELEASE_STORE_FILE, MWB_RELEASE_STORE_PASSWORD, MWB_RELEASE_KEY_ALIAS, MWB_RELEASE_KEY_PASSWORD).
    // Sem essas propriedades, o release sai sem assinatura (o projeto continua compilando).
    val releaseStoreFile = providers.gradleProperty("MWB_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("MWB_RELEASE_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("MWB_RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("MWB_RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
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