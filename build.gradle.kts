import java.util.Properties

plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
}

// Leggo la versione da version.properties: un'unica fonte per app TV e app mobile, così le due
// app escono sempre con lo stesso numero (il protocollo che le lega è versionato insieme).
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionCode = versionProps.getProperty("versionCode").trim().toInt()
val appVersionName = listOf("versionMajor", "versionMinor", "versionPatch")
    .joinToString(".") { versionProps.getProperty(it).trim() }
extra["appVersionCode"] = appVersionCode
extra["appVersionName"] = appVersionName

// Raccolgo i due APK di rilascio in build/outputs/apk/ alla radice del progetto, con il nome
// atteso (app-tv-release.apk, app-mobile-release.apk) più una copia con la versione nel nome.
val distApk by tasks.registering {
    group = "distribution"
    description = "Compila le release di app-tv e app-mobile e le copia in build/outputs/apk/"
    dependsOn(":app-tv:assembleRelease", ":app-mobile:assembleRelease")
    val outDir = layout.buildDirectory.dir("outputs/apk")
    val versionName = appVersionName
    val modules = listOf("app-tv", "app-mobile")
    val sources = modules.associateWith { rootProject.file("$it/build/outputs/apk/release/$it-release.apk") }
    // Ingressi espliciti: senza, Gradle considera il task "aggiornato" finché la cartella di
    // uscita resta intatta e non ricopia gli APK appena ricompilati (né la nuova versione).
    inputs.files(sources.values)
    inputs.property("versionName", versionName)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.apply { mkdirs() }
        sources.forEach { (module, apk) ->
            check(apk.isFile) { "APK non trovato: $apk" }
            apk.copyTo(File(dir, "$module-release.apk"), overwrite = true)
            apk.copyTo(File(dir, "IcaroManagerTV-$module-$versionName.apk"), overwrite = true)
        }
        println("APK copiati in ${dir.absolutePath} (versione $versionName)")
    }
}
