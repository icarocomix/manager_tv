plugins {
    id("com.android.application")
}

android {
    namespace = "com.icarocomix.icaromanagertv.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.icarocomix.icaromanagertv.tv"
        minSdk = 26
        targetSdk = 36
        // Versione comune alle due app, letta da version.properties nel build.gradle.kts radice.
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // App per uso personale installata a mano: firmo la release con la chiave di debug locale.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":common"))
    implementation("androidx.activity:activity-ktx:1.13.0")
    // Server HTTP + WebSocket leggero e senza dipendenze: un thread per connessione, adatto ai
    // pochi telefoni collegati a una TV.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    // Player integrato: serve per miniatura in-app, ripresa dal punto salvato e playlist con
    // autoplay, impossibili da ottenere comandando un player esterno. Solo exoplayer + ui:
    // niente moduli DASH/HLS, i file arrivano dai dischi locali.
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")
    // Sessione multimediale: il sistema della TV vede una riproduzione attiva (scheda "In
    // riproduzione", comandi vocali e tasti del telecomando instradati al player integrato).
    implementation("androidx.media3:media3-session:1.10.0")
}
