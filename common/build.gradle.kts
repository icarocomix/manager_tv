plugins {
    id("com.android.library")
}

// Codice e risorse condivisi dalle due app: protocollo TV/telefono, ricerca, crediti, grafica.
android {
    namespace = "com.icarocomix.icaromanagertv.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxHeapSize = "512m"
            it.maxParallelForks = 1
        }
    }
}

dependencies {
    api("androidx.appcompat:appcompat:1.7.1")
    api("androidx.recyclerview:recyclerview:1.4.0")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // org.json fa parte di Android, ma nei test JVM è solo uno stub: serve l'implementazione reale.
    testImplementation("org.json:json:20250107")
}
