# Schermo sempre acceso durante la riproduzione ed esportazione dei log (v1.3.0)

## Obiettivo

1. **Salvaschermo / standby durante la riproduzione.** Dopo circa 20 minuti di video senza
   tocchi del telecomando la TV avviava il salvaschermo o il risparmio energetico e il video
   finiva in pausa, pur essendo in riproduzione. Obiettivo: segnalare al sistema Android TV che
   è in corso una riproduzione e impedire salvaschermo e spegnimento finché il video scorre.
2. **Esportazione dei log.** Rendere disponibili i log dell'app (attività, comportamento del
   player, errori) come file salvabili o condivisibili dalla TV, e scaricabili dal telefono
   tramite il collegamento già esistente, per diagnosticare problemi su televisori remoti.

## Analisi della causa (punto 1)

- Android TV avvia il salvaschermo (Dream) o lo standby per inattività in base all'ultimo
  evento di input dell'utente: un video che scorre non conta come attività, a meno che la
  finestra in primo piano chieda di tenere acceso lo schermo.
- Nell'app la pausa non era un effetto diretto del sistema: il salvaschermo copre l'activity,
  che passa in `onStop`; il player integrato, per scelta progettuale (nessun audio da solo in
  background su una TV), in `onStop` si mette in pausa e salva la posizione. Quindi
  salvaschermo ⇒ pausa.
- Nessun punto del codice impostava `FLAG_KEEP_SCREEN_ON`, né il player teneva un wake lock o
  esponeva una sessione multimediale.

## Decisioni

### Schermo acceso

- **`FLAG_KEEP_SCREEN_ON` sulla finestra dell'activity TV**, applicato/rimosso in base allo
  stato del player già pubblicato a ogni variazione: attivo quando il player è aperto e in
  riproduzione (o in buffering, o in attesa della risposta alla domanda di ripresa, fasi
  brevi che precedono la riproduzione); rimosso in pausa e alla chiusura.
  - Scartato: tenerlo sempre attivo con il player aperto, anche in pausa. Una TV accesa per ore
    su un fermo immagine spreca energia ed è un rischio per i pannelli OLED; in pausa il
    salvaschermo è il comportamento atteso (come nelle app di streaming).
  - Scartato: `PowerManager.WakeLock` di tipo `SCREEN_BRIGHT`/`FULL` — deprecati e sostituiti
    proprio dal flag di finestra, che il sistema rilascia da solo quando l'activity non è
    visibile.
  - Scartato: `View.keepScreenOn` sulla PlayerView: vale solo mentre la vista è visibile e
    attaccata, quindi in miniatura sopra il file manager si comporterebbe come il flag ma con
    più casi limite; il flag di finestra guidato dallo stato è esplicito e unico.
- **Wake lock della CPU di ExoPlayer (`WAKE_MODE_LOCAL`)**, con il permesso `WAKE_LOCK`:
  durante la riproduzione impedisce che la CPU venga sospesa o rallentata da politiche di
  risparmio di alcuni firmware; ExoPlayer lo rilascia da solo in pausa e al rilascio.
  `WAKE_MODE_NETWORK` scartato: i file sono locali.
- **MediaSession Media3** (nuova dipendenza `media3-session` 1.10.0, stessa versione del player):
  il sistema vede una sessione multimediale attiva (scheda «In riproduzione», assistente
  vocale, tasti multimediali globali). La sessione espone un `ForwardingPlayer` che instrada i
  comandi esterni nelle regole del controller: «play» con la domanda di ripresa aperta vale
  come «riprendi dal punto salvato», «stop» chiude il player come il pulsante dedicato
  (salvataggio della posizione e rilascio del decoder), rimandato al ciclo successivo del main
  thread per non rilasciare player e sessione dentro una loro callback.
  - Scartato: `MediaSessionService` con notifica: la riproduzione avviene solo con l'activity
    visibile, un servizio multimediale in background non aggiungerebbe nulla.
  - La sessione da sola non impedisce il salvaschermo: è il flag di finestra a farlo. La
    sessione completa l'integrazione con il sistema.

### Log

- **Cattura tramite `logcat --pid` su file con rotazione**, avviato all'inizio del processo
  (Application della TV, activity del telefono; avvio idempotente).
  - Un'app legge senza permessi i log del proprio processo; così entrano nei file anche i log
    di ExoPlayer, NanoHTTPD, OkHttp e del runtime, nello stesso formato di `adb logcat`,
    senza sostituire le chiamate a `Log` in tutto il codice.
  - Rotazione affidata a logcat stesso: file da 1 MB, 4 file storici (≈ 5 MB al massimo nella
    memoria privata dell'app). Più avvii successivi restano nei file, separati da una riga
    di intestazione con versione, pid e dispositivo.
  - Il processo logcat è figlio dell'app e viene terminato con il suo gruppo di processi.
  - Scartato: logger proprio che scrive su file (`Log` sostituito ovunque): non coprirebbe le
    librerie e richiederebbe di toccare ogni classe.
  - Scartato: solo istantanea `logcat -d` all'esportazione: il buffer circolare di sistema è
    piccolo e condiviso, dopo un riavvio dell'app i log del problema sarebbero persi.
- **`crash.log` scritto in modo sincrono** da un gestore delle eccezioni non gestite (che poi
  passa la mano al gestore precedente): l'ultima traccia prima della chiusura del processo non
  deve dipendere dal logcat figlio.
- **Pacchetto ZIP** con `info.txt` (app, data e fuso, dispositivo, Android, memoria, heap,
  spazio, stato della cattura continua + stato applicativo), file ruotati in ordine
  cronologico, `crash.log`, istantanea finale `logcat -d`. I file in scrittura vengono copiati
  fino alla lunghezza rilevata all'apertura, per non leggere un file che cresce.
- **Tracciamento del player**: `EventLogger` di Media3 (tag `PlayerEvents`) registra formati,
  decoder, errori, frame persi; il controller registra avvio playlist, cambi di file, pause con
  il loro motivo (comando, perdita del focus audio, fine file…), passaggio in background,
  chiusura; l'activity registra l'attivazione/disattivazione dello schermo sempre acceso.
  Proprio il motivo delle pause permette di verificare sul campo la correzione del punto 1.
- **Destinazioni sulla TV** (pulsante «Esporta log» nel pannello laterale):
  - `Download/IcaroManagerTV` — da Android 10 tramite MediaStore senza permessi (file
    marcato «in scrittura» fino al completamento, cancellato in caso di errore); fino ad
    Android 9 scrittura diretta con `WRITE_EXTERNAL_STORAGE` (dichiarato con
    `maxSdkVersion=28` e richiesto insieme alla lettura, stesso gruppo di permessi);
  - cartella aperta nel file manager, se POSIX e scrivibile (tipicamente il disco USB, comodo
    da portare al PC); la cartella corrente è pubblicata dal ciclo dei comandi in un campo
    volatile;
  - condivisione `ACTION_SEND` tramite il FileProvider già presente (copre la cache dell'app);
    se nessuna app della TV la gestisce l'utente viene indirizzato alle altre opzioni.
- **Dal telefono** (menu ⋮ › «Esporta log (TV + telefono)»): nuovo endpoint HTTP
  `GET /api/logs?id=<id della TV>` sul server già esistente. Il telefono scarica lo ZIP della
  TV e produce un unico pacchetto con `tv/` e `telefono/`, salvato nella posizione scelta con il
  selettore di sistema (`CreateDocument`).
  - Accesso: oltre al filtro esistente «solo rete locale», l'endpoint richiede l'id della TV,
    noto solo ai client che hanno completato il saluto WebSocket: un dispositivo qualunque
    della rete non scarica i log (contengono nomi di file e cartelle).
  - Lo ZIP della TV viene costruito su un file temporaneo (non in memoria: heap ridotto
    sulle TV economiche) e cancellato alla chiusura del flusso di risposta.
  - Se la TV non risponde, non è collegata o ha un'app precedente (risposta 404) vengono
    salvati comunque i log del telefono con il motivo in `tv/NON-DISPONIBILE.txt`.
  - Le voci dello ZIP ricevuto con percorsi assoluti o `..` vengono scartate prima di
    ricopiarle nel pacchetto finale.
  - Scartato: nuovo messaggio WebSocket con lo ZIP codificato: il canale trasporta JSON
    piccoli e frequenti, un file di alcuni MB lo bloccherebbe per tutti i client.
  - Scartato: comando remoto «salva i log sulla TV»: lascerebbe il file sulla TV, mentre chi
    usa il telefono vuole il file sul telefono.
- **Versione**: nessuna modifica dei messaggi esistenti, `Protocol.VERSION` resta 1; nuove
  funzionalità retrocompatibili → MINOR: 1.3.0 (versionCode 4).

## Piano di implementazione

1. `:common` — `AppLogs` (avvio cattura, gestore crash, scrittura ZIP e voci con prefisso),
   costanti `Protocol.LOGS_PATH` / `LOGS_ID_PARAM`, icona `ic_bug_report`.
2. `:app-tv` — flag di schermo acceso nell'activity; wake mode, `EventLogger`, sessione e log
   di traccia nel controller del player; `LogExporter` (Download, cartella, condivisione, ZIP
   temporaneo); endpoint nel server; pulsante e dialog; permessi `WAKE_LOCK` e
   `WRITE_EXTERNAL_STORAGE` (≤ API 28).
3. `:app-mobile` — download dei log TV nel client, `CombinedLogExporter`, voce di menu, esito
   tramite ViewModel (sopravvive alla ricreazione dell'activity).
4. Versione, README, build sequenziale.

## Scoperte durante lo sviluppo

- La pausa dopo il salvaschermo era causata dalla catena salvaschermo → `onStop` → pausa
  voluta del controller, non da una pausa imposta dal sistema al player: ha confermato che la
  correzione corretta è impedire il salvaschermo (flag di finestra), non rimuovere la pausa in
  `onStop`, che resta giusta per il tasto Home.
- `media3-session` 1.10.0 era già nella cache Gradle locale: build possibile offline.
- `ProcessBuilder.Redirect` e `Process.waitFor(timeout)` sono disponibili da API 26 (minSdk del
  progetto): il controllo lint della release non segnala problemi.

## Limiti noti

- Alcuni produttori aggiungono uno standby proprio (es. «spegnimento dopo N ore senza
  telecomando», previsto da normative di risparmio energetico) indipendente dal flag di
  finestra: va disattivato dalle impostazioni della TV. Il log riporta in quel caso una pausa
  per passaggio in background senza alcuna disattivazione dello schermo sempre acceso.
- In pausa il salvaschermo torna attivo per scelta; alla ripresa dopo il salvaschermo l'utente
  deve premere play (il player era in pausa già prima).
- Su alcune TV manca un'app che gestisca la condivisione: restano Download, cartella del disco
  e telefono.
- La cattura continua dipende dalla presenza del binario `logcat` accessibile all'app; se
  manca, l'esportazione contiene comunque `info.txt`, `crash.log` e l'istantanea (indicato in
  `info.txt` come «Log continuo: non attivo»).
- Verifica su hardware reale ancora da fare: riproduzione oltre la soglia del salvaschermo,
  scrittura in Download su Android 9 e 10+, download dal telefono.

## Esito

- Test unitari `:common`: 17/17 superati.
- `:app-tv:assembleDebug`, `:app-mobile:assembleDebug`: OK.
- `distApk`: OK, release 1.3.0 (versionCode 4) in `build/outputs/apk/`.

## File toccati

- `version.properties`
- `README.md`
- `TODO.md`
- `docs/schermo-acceso-ed-esportazione-log.md` (nuovo)
- `common/src/main/java/com/icarocomix/icaromanagertv/common/log/AppLogs.kt` (nuovo)
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/Protocol.kt`
- `common/src/main/res/drawable/ic_bug_report.xml` (nuovo)
- `app-tv/build.gradle.kts`
- `app-tv/src/main/AndroidManifest.xml`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/TvApp.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/diagnostics/LogExporter.kt` (nuovo)
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/playback/PlaybackController.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/controller/BrowserController.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/server/RemoteServer.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/service/RemoteServerService.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/storage/StorageAccess.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/ui/TvMainActivity.kt`
- `app-tv/src/main/res/layout/activity_tv.xml`
- `app-tv/src/main/res/values/strings.xml`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/client/RemoteClient.kt`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/diagnostics/CombinedLogExporter.kt` (nuovo)
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/ui/MainViewModel.kt`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/ui/MainActivity.kt`
- `app-mobile/src/main/res/menu/main.xml`
- `app-mobile/src/main/res/values/strings.xml`
