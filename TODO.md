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

## Fase 10 — Controlli del player che si nascondono + pad del telefono come vero telecomando (v1.5.0)
Decisioni: il timeout dei controlli Media3 si azzera se `showController()` è chiamato prima che il
player sia in riproduzione → lo ricalcolo all'avvio effettivo (onIsPlayingChanged). I tasti del pad
del telefono, con la UI della TV in primo piano e fuori dall'elenco (player, pannello, ricerca,
dialog), diventano veri eventi D-pad iniettati nella finestra dell'app; nell'elenco resta la
semantica storica (SU/GIÙ selezione, SINISTRA risale, DESTRA/OK apre). Pad del telefono
disponibile anche dalla schermata del player.
- [x] tv: ricalcolo del timeout dei controlli all'avvio della riproduzione
- [x] tv: instradamento dei tasti remoti verso la UI (BrowserController, RemoteServer, TvMainActivity)
- [x] mobile: pad accessibile anche dalla schermata del player
- [x] version.properties 1.5.0, README, docs
- [x] build sequenziale: assembleDebug tv, assembleDebug mobile, distApk

> Esito fase 10: assembleDebug tv+mobile OK, `distApk` OK (v1.5.0, versionCode 7).
> Da fare sull'hardware reale: scomparsa dei controlli all'avvio, pad del telefono sui controlli
> del player, sul pannello laterale e nei dialog (iniezione dei tasti nelle finestre dell'app).

## Fase 11 — OK = pausa, Giù = timeline, avvio remoto dell'app TV, Impostazioni/Sottotitoli dal telefono (v1.6.0)
Decisioni: causa del "OK chiude il player": quando i controlli Media3 si nascondono il focus
ricade sul primo elemento selezionabile della finestra, il pulsante «Chiudi player» del pannello
sotto il video → OK lo premeva (STOP). A schermo intero blocco il focus del file manager
(FOCUS_BLOCK_DESCENDANTS) e intercetto in dispatchKeyEvent i tasti con i controlli nascosti: OK
alterna play/pausa (pausa → controlli visibili), Giù mostra i controlli con focus sulla timeline,
gli altri D-pad li mostrano col focus su Play/Pausa. Nuovo messaggio retrocompatibile `ui`
(porta in primo piano / impostazioni / sottotitoli) → MINOR. Avvio da background: permesso di
sovrapposizione + finestra overlay temporanea (Android 15+); servizio riavviato al boot se era
acceso; comandi ricevuti con l'app chiusa vengono applicati all'apertura.
- [x] common: UiCommand + ClientMessage.Ui, campo `subtitles` in PlaybackState, codec, test
- [x] tv: tasti a schermo intero (OK play/pausa, Giù timeline, blocco focus del file manager)
- [x] tv: UiLauncher (primo piano da background) + BootReceiver + preferenza servizio acceso
- [x] tv: instradamento `ui` (BrowserController → activity, comandi in attesa) e apertura pannelli Media3
- [x] tv: sottotitoli disponibili nello stato del player
- [x] mobile: pulsanti Sottotitoli/Impostazioni nel player, voce «Apri sulla TV»
- [x] version.properties 1.6.0 (versionCode 8), README, docs
- [x] build sequenziale: test :common, assembleDebug tv, assembleDebug mobile, distApk

> Esito fase 11: test `:common` 19/19, assembleDebug tv+mobile OK, `distApk` OK (v1.6.0, versionCode 8).
> Da fare sull'hardware reale: OK/Giù a controlli nascosti (anche telecomandi che inviano ENTER),
> pannelli impostazioni/sottotitoli aperti dal telefono e navigati col pad, apertura dell'app da
> remoto con altra app in primo piano (Android 10–14 e 15+), riavvio del server al boot.

## Fase 12 — Indietro a due livelli, pad del telefono affidabile, avvio a freddo dal telefono, ricerca ricorsiva di default (v1.7.0)
Decisioni: Indietro a schermo intero nasconde prima i controlli, poi riduce a miniatura. I tasti
del pad del telefono non passano più dall'iniezione di sistema (`Instrumentation`, che può fallire
in silenzio) ma sono consegnati direttamente alla finestra dell'app che ha il focus (activity,
dialog, pannelli Media3), con navigazione del focus replicata (`focusSearch`), dato che senza
`ViewRootImpl` le frecce non spostano il focus. Il tasto Home del pad porta la TV alla schermata
principale dell'app (primo piano, miniatura, radice). Avvio a freddo: l'app TV appena installata
o mai aperta non può ascoltare nulla (stato «stopped»), quindi il telefono usa il servizio di
sistema Android TV Remote (protocollo v2, porte 6466/6467, accoppiamento una tantum con codice
mostrato dalla TV, certificato client nell'AndroidKeyStore, certificato della TV fissato) e apre
l'app con un deep link `icaromanagertv://open`. Ricerca nelle sottocartelle attiva di default,
scelta dell'utente memorizzata sulla TV.
- [x] tv: Indietro a due livelli (controlli visibili → li nasconde; nascosti → miniatura)
- [x] tv: consegna diretta dei tasti remoti alla finestra con il focus + navigazione del focus
- [x] tv/mobile: tasto Home del pad (UiCommand HOME)
- [x] tv: deep link `icaromanagertv://open` sull'activity principale
- [x] common: codifica protobuf minima e messaggi Android TV Remote v2 (pairing + remote), test
- [x] mobile: certificato client, accoppiamento, avvio via servizio di sistema, persistenza TV, UI
- [x] tv/common/mobile: ricerca ricorsiva di default e preferenza persistente
- [x] version.properties 1.7.0 (versionCode 9), README, docs
- [x] build sequenziale: test :common, assembleDebug tv, assembleDebug mobile, distApk

> Esito fase 12: test `:common` 27/27, assembleDebug tv+mobile OK, `distApk` OK (v1.7.0, versionCode 9).
> Da fare sull'hardware reale: Indietro a controlli visibili/nascosti; pad del telefono su
> controlli Media3, pannelli impostazioni/sottotitoli, dialog e pannello laterale; Home del pad;
> abbinamento con il codice della TV; apertura a freddo (app mai avviata, TV riaccesa staccando
> la spina, IP cambiato, TV in standby); deep link sui diversi firmware.

## Fase 13 — Apertura dal telefono senza azioni dell'utente (v1.7.1)
Decisioni: il codice di abbinamento lo genera e lo mostra il servizio di sistema di Google, non
è leggibile da altre app → niente codice nel percorso normale. L'app TV resta sempre in ascolto
(START_STICKY, riavvio a boot/aggiornamento/accensione rapida); script ADB di installazione che
concede i permessi e avvia l'app la prima volta. Telefono: «Apri l'app sulla TV» attende la TV
(ultima nota) e la porta in primo piano; l'abbinamento con codice resta solo voce di menu
facoltativa e ripiego, mai proposto in automatico.
- [x] mobile: TvStarter senza proposta automatica, attesa della TV e ripiego sull'abbinamento
- [x] tv: START_STICKY, BootReceiver con MY_PACKAGE_REPLACED e QUICKBOOT_POWERON
- [x] script installa-tv.sh
- [x] version.properties 1.7.1 (versionCode 10), README, docs
- [x] build sequenziale: test :common, assembleDebug tv, assembleDebug mobile, distApk

> Esito fase 13: test `:common` 27/27, assembleDebug tv+mobile OK, `distApk` OK (v1.7.1, versionCode 10).
> Da fare sull'hardware reale: `installa-tv.sh` su TV pulita (app aperta da sola, telefono che la
> trova); spina staccata e TV riaccesa → «Apri l'app sulla TV» senza codici; server ripartito dopo
> `adb install -r`; primo piano con un'altra app aperta.

## Fase 14 — Nuovo nome «Manager TV» e nuova icona (v1.8.0)
Decisioni: nome visibile «Manager TV» (TV) e «Manager TV Remote» (telefono); IcaroComix solo nei
crediti. Restano invariati gli identificativi tecnici non visibili (pacchetti com.icarocomix…,
schema del deep link, firma del beacon UDP) per non rompere aggiornamenti, permessi concessi,
abbinamenti e compatibilità tra versioni; il prefisso del servizio mDNS cambia ma il telefono
accetta anche quello vecchio. Icona da art/icona.png: script che genera primo piano, monocromo,
logo e banner.
- [x] art: copia della sorgente e script di generazione delle icone; risorse generate
- [x] icona adattiva, logo nell'interfaccia e nei crediti, banner TV
- [x] nome: stringhe TV/telefono, header TV senza «di IcaroComix», mDNS, log, APK, script, codice
- [x] version.properties 1.8.0 (versionCode 11), README, docs
- [x] build sequenziale: test :common, assembleDebug tv, assembleDebug mobile, distApk

> Esito fase 14: test `:common` 28/28, assembleDebug tv+mobile OK, `distApk` OK (v1.8.0, versionCode 11):
> etichette «Manager TV» / «Manager TV Remote» verificate negli APK.
> Da fare sull'hardware reale: aspetto dell'icona nei launcher (maschere diverse, icone a tema),
> banner nel launcher della TV, ricerca della TV da un telefono v1.8 verso una TV ancora v1.7.

## Fase 15 — Scroll sincronizzato, interruttore di sincronizzazione, operazioni sui file e trasferimenti telefono ↔ TV (v1.9.0)
Decisioni: il controller della TV gestisce due sessioni di navigazione (TV e telefono); con la
sincronizzazione attiva (default, preferenza persistente sulla TV) il telefono usa la stessa
sessione della TV, disattivandola riceve una copia indipendente; riattivandola la TV adotta
posizione, ricerca, selezione e scroll del telefono. Scroll del telefono → messaggio `scroll`
(indice della prima riga visibile), applicato dalla TV in testa all'elenco. Operazioni sui file
(copia/taglia/incolla/elimina/rinomina/nuova cartella) eseguite dalla TV su POSIX e SAF, con
appunti unici della TV, avanzamento e annullamento nello stato `status`. Trasferimenti: upload
HTTP in streaming (telefono → cartella aperta, con "sposta" = eliminazione dell'originale a
upload riuscito) e download (file della TV → telefono) in un servizio in primo piano del
telefono. Messaggi nuovi retrocompatibili: Protocol.VERSION resta 1 → MINOR.
- [x] common: TvStatus, FileOpKind, ClientMessage Sync/Scroll/FileOp, scrollTop nello stato, percorsi upload/download, FileNames (validazione e nomi univoci), codec, test
- [x] tv: FileOperations (POSIX + SAF: crea cartella, rinomina, elimina, copia/sposta con avanzamento, scrittura upload)
- [x] tv: BrowserController a sessioni (TV/telefono), sincronizzazione, scroll, appunti, operazioni, indice degli elenchi recenti
- [x] tv: RemoteServer (flussi della sessione telefono, `status`, endpoint upload/download)
- [x] tv: UI (scroll remoto, interruttore, menu operazioni, dialog rinomina/nuova cartella/elimina, avanzamento, permesso SAF in scrittura)
- [x] mobile: RemoteClient (status, upload/download), TransferService in primo piano, UI (interruttore, azioni, appunti, carica/scarica, invio scroll)
- [x] version.properties 1.9.0 (versionCode 12), README, docs
- [x] build sequenziale: test :common, assembleDebug tv, assembleDebug mobile, distApk

> Esito fase 15: test `:common` 35/35, assembleDebug tv+mobile OK, `distApk` OK in 143 s (v1.9.0, versionCode 12).
> Da fare sull'hardware reale: scroll del telefono seguito dalla TV (focus che resta sull'elenco);
> interruttore spento → telecomando e telefono indipendenti, pad del telefono che comanda la TV;
> riattivazione → TV sulla posizione del telefono; copia/taglia/incolla/rinomina/elimina su USB
> (Android 11+ con accesso a tutti i file, Android 9/10 via SAF), annullamento di una copia lunga
> senza file parziali; caricamento di più video dal telefono in copia e in spostamento (originale
> eliminato), notifica e Annulla; scaricamento di un file della TV sul telefono.

## Fase 16 — Interruttore autoplay visibile, permessi di scrittura chiesti all'installazione (v1.9.1)
Diagnosi: l'interruttore «Riproduzione automatica del successivo» c'è ancora ma in fondo alla
schermata del player del telefono, spinto fuori vista dalle righe aggiunte (sottotitoli,
impostazioni, volume) e dal pad aperto. Errore di scrittura: con i soli permessi multimediali
(Android 11+) la TV legge ma non scrive; l'«Accesso a tutti i file» veniva proposto una sola
volta nella vita dell'app (già scartato con le versioni di sola lettura) → mai più richiesto.
Android non concede permessi speciali all'installazione: procedura guidata al primo avvio di ogni
versione (lettura → accesso a tutti i file → sovrapposizione), riproposta quando un'operazione
fallisce per il permesso mancante; `installa-tv.sh` li concede già via ADB.
- [x] mobile: interruttore autoplay subito sotto il titolo (sempre visibile)
- [x] tv: diagnosi precisa del rifiuto di scrittura (permesso mancante / disco in sola lettura / Android 9-10 su USB)
- [x] tv: procedura permessi a ogni nuova versione + richiesta immediata quando manca l'accesso completo
- [x] version.properties 1.9.1 (versionCode 13), README, docs
- [x] build sequenziale: test :common, assembleDebug tv, assembleDebug mobile, distApk

> Esito fase 16: test `:common` OK, assembleDebug tv+mobile OK, `distApk` OK (v1.9.1, versionCode 13).
> Da fare sull'hardware reale: procedura permessi al primo avvio dopo l'aggiornamento; copia/sposta/
> rinomina/elimina e caricamento su USB; richiesta che ricompare dopo un rifiuto; autoplay visibile.
