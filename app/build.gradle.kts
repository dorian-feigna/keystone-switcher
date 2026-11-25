plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keystoneswitcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.keystoneswitcher"
        minSdk = 30
        targetSdk = 34
        // Numéro de build GitHub Actions => chaque APK publié est une mise à jour valide
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        getByName("debug") {
            // Keystore fixe (restauré par le workflow depuis le secret DEBUG_KEYSTORE_BASE64).
            // Sans lui, chaque runner CI signe avec une clé différente => mise à jour impossible sur le projecteur.
            val fixedKeystore = rootProject.file("keystore/debug.keystore")
            if (fixedKeystore.exists()) {
                storeFile = fixedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
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
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Client ADB pur Kotlin (sans serveur adb) : connexion à 127.0.0.1:5555
    implementation("dev.mobile:dadb:1.2.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Persistance : DataStore Preferences contenant des documents JSON (org.json natif Android)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
