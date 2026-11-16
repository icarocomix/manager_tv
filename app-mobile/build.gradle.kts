plugins {
    id("com.android.application")
}

android {
    namespace = "com.icarocomix.icaromanagertv.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.icarocomix.icaromanagertv.mobile"
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    // Client WebSocket con ping periodici: rileva in pochi secondi un collegamento caduto.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
