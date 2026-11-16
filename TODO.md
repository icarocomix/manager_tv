# IcaroManagerTV — avanzamento lavori

Progetto Gradle multi-modulo: `:common` (protocollo, ricerca, crediti), `:app-tv` (server + file
manager + player esterno), `:app-mobile` (telecomando/client). Versione iniziale 1.0.0.

## Fase 1 — Scaffolding
- [x] settings/build Gradle root, gradle.properties a basso consumo, wrapper, version.properties
- [x] Modulo `:common`: build, manifest, risorse condivise (colori, temi, stringhe crediti, logo)
- [x] Grafica: icona adattiva vettoriale, logo crediti
- [x] Banner TV (art/genera_banner.py)

## Fase 2 — Modulo `:common`
- [x] Protocollo (messaggi JSON, versione protocollo, costanti discovery)
- [x] Normalizzazione testo di ricerca + filtro condiviso
- [x] CreditsDialog (stile IcaroTube) + layout
- [x] Test unitari JVM (codec, ricerca)

## Fase 3 — App TV
- [x] Manifest (Leanback, permessi, FileProvider, servizio foreground)
- [x] Storage: volumi POSIX + alberi SAF, MIME, listing, ricerca ricorsiva
- [x] Controller di stato (thread singolo) + apertura file con ACTION_VIEW
- [x] Server NanoHTTPD + WebSocket, broadcast stato
- [x] Discovery: annuncio NSD + beacon UDP
- [x] Servizio foreground + receiver mount/unmount USB
- [x] UI TV: header, ricerca, breadcrumb, lista con focus D-pad, permessi, crediti

> Verifica intermedia: `:app-tv:assembleDebug` OK, test `:common` 9/9 OK.

## Fase 4 — App Mobile
- [x] Discovery NSD + ascolto beacon UDP (multicast lock)
- [x] Client WebSocket OkHttp con riconnessione a backoff + callback di rete
- [x] UI: barra ricerca, breadcrumb, lista, pad direzionale, scelta TV, crediti

## Fase 5 — Build e consegna
- [x] Test unitari `:common`
- [x] assembleRelease di entrambe le app (sequenziale, controllo risorse)
- [x] Copia APK in `build/outputs/apk/` (task `distApk`)
- [x] docs/icaromanagertv-architettura.md + README.md

> Esito finale: `distApk` OK (v1.0.0), test `:common` 9/9, interoperabilità NanoWSD↔OkHttp verificata su JVM.
> Da fare sull'hardware reale: prova su TV + telefono (scoperta, permessi, avvio player).

## Fase 6 — Player integrato, miniatura, resume, playlist (v1.1.0)
Decisioni: Media3 ExoPlayer 1.10.0 (in cache) per video/audio, player esterno per immagini/altro e
come ripiego sui formati non decodificabili; playlist = file della stessa famiglia nell'elenco
visibile al momento dell'apertura (istantanea); progressi su SQLite con SQL esplicito (no Room);
miniatura in-app (stessa PlayerView ridimensionata); messaggi aggiuntivi retrocompatibili
(Protocol.VERSION resta 1 → MINOR).
- [x] common: PlaybackState/PlayerAction/TvView, nuovi ClientMessage, codec, TimeFormat, icone player
- [x] common: test unitari codec playback + TimeFormat
- [x] tv: ProgressStore (SQLiteOpenHelper, SQL esplicito, potatura)
- [x] tv: PlaybackController (ExoPlayer su main, playlist, autoplay, resume, salvataggi, ripiego esterno)
- [x] tv: BrowserController → delega apertura media e comandi player; TvApp wiring
- [x] tv: RemoteServer broadcast `playback`
- [x] tv: UI (layout con contenitore player full/mini, pannello resume, tasti media, pulsante "Torna al player")
- [x] mobile: RemoteClient `playback`, schermata Player + mini-barra "Torna al player", dialog resume
- [x] version.properties 1.1.0 (versionCode 2), crediti/librerie, README, docs
- [x] build sequenziale: test :common, assembleDebug tv, assembleDebug mobile, distApk

> Esito fase 6: test `:common` 14/14, assembleDebug tv+mobile OK, `distApk` OK (v1.1.0, versionCode 2).
> Corretto anche `distApk`, che non ricopiava gli APK (mancavano gli ingressi del task).
> Da fare sull'hardware reale: formati/codec della TV, miniatura, avvio da background, dialog di ripresa.

## Fase 7 — Indicatore di avanzamento nelle righe dell'elenco (v1.2.0)
Decisioni: avanzamento in un messaggio separato `progress` (seq dell'elenco + mappa sparsa
indice→permille), non nelle voci di `listing`: l'elenco non si ritrasmette a ogni salvataggio e
non cambia seq; lettura in blocco da SQLite (IN a gruppi); aggiornamenti puntuali per chiave
notificati da ProgressStore; barra sottile nella riga condivisa TV/telefono.
- [x] common: ListingProgress, ServerMessage.ProgressMsg, codec, test
- [x] common: barra nella riga (item_entry) + EntryAdapter.submitProgress
- [x] tv: ProgressStore.loadMany + flusso modifiche; BrowserController.progress; TvApp wiring
- [x] tv: RemoteServer broadcast `progress`; TvMainActivity collega l'adattatore
- [x] mobile: RemoteClient.progress, MainActivity collega l'adattatore
- [x] version.properties 1.2.0 (versionCode 3), README, docs
- [x] build sequenziale: test :common, assembleDebug tv, assembleDebug mobile, distApk

> Esito fase 7: test `:common` 17/17, assembleDebug tv+mobile OK, `distApk` OK (v1.2.0, versionCode 3).
> Da fare sull'hardware reale: barre nelle righe su TV e telefono, aggiornamento durante la riproduzione.

## Fase 8 — Niente screen saver durante la riproduzione + esportazione log (v1.3.0)
Decisioni: FLAG_KEEP_SCREEN_ON sulla finestra della TV finché il player è attivo e non in pausa
(il salvaschermo fermava l'activity → onStop → pausa); wake lock CPU di ExoPlayer
(WAKE_MODE_LOCAL); MediaSession Media3 (sessione attiva per il sistema, comandi del sistema
instradati nel controller). Log: processo `logcat --pid` con rotazione su file nella memoria
dell'app (avviato a ogni processo), istantanea finale e crash.log; pacchetto ZIP condiviso in
`:common`. Esportazione TV: Download, cartella corrente del disco, Condividi; telefono: endpoint
HTTP `/api/logs` (stessa rete locale, id della TV) → ZIP unico TV + telefono salvato via SAF.
Protocol.VERSION invariato → MINOR.
- [x] common: AppLogs (logcat rotante, crash handler, ZIP), Protocol.LOGS_PATH
- [x] tv: keep-screen-on in TvMainActivity, wake mode + MediaSession + EventLogger in PlaybackController
- [x] tv: LogExporter (Download/MediaStore, cartella corrente, condivisione), endpoint /api/logs, pulsante e dialog
- [x] mobile: download log TV + log telefono → ZIP via CreateDocument, voce di menu
- [x] version.properties 1.3.0 (versionCode 4), README, docs
- [x] build sequenziale: test :common, assembleDebug tv, assembleDebug mobile, distApk

> Esito fase 8: test `:common` 17/17, assembleDebug tv+mobile OK, `distApk` OK (v1.3.0, versionCode 4).
> Da fare sull'hardware reale: riproduzione oltre la soglia del salvaschermo, esportazione in
> Download (Android 9 e 10+), cartella USB, download dal telefono.

## Fase 9 — Controllo del volume della TV dal telefono (v1.4.0)
Decisioni: volume di sistema della TV (AudioManager, STREAM_MUSIC) e non del solo player, così
vale anche per la TV/ampli via HDMI-CEC; comandi relativi (su/giù/muto) come PlayerAction e
livello assoluto con un messaggio `volume`; stato (livello, massimo, muto, fisso) nei campi nuovi
di `playback`, letto a ogni tick → riflette anche i cambi fatti col telecomando della TV. Volume
fisso (box HDMI): solo +/−/muto, niente cursore. Tasti volume del telefono inoltrati alla TV sulla
schermata del player. Protocol.VERSION invariato → MINOR.
- [x] common: PlayerAction volume, ClientMessage.Volume, campi volume in PlaybackState, codec, icone, stringhe, test
- [x] tv: VolumeControl (AudioManager) + PlaybackController/BrowserController
- [x] mobile: riga volume nella schermata player, cursore, tasti volume hardware
- [x] version.properties 1.4.0 (versionCode 5), README, docs
- [x] build sequenziale: test :common, assembleDebug tv, assembleDebug mobile, distApk

> Esito fase 9: test `:common` 18/18, assembleDebug tv+mobile OK, `distApk` OK (v1.4.0, versionCode 5).
> Da fare sull'hardware reale: volume su TV con altoparlanti, box HDMI con volume fisso (CEC),
> indicatore di sistema, tasti volume del telefono.
