# IcaroManagerTV — file manager per Android TV con telecomando da smartphone

## 1. Obiettivo

Realizzare un sistema di due app Android, prodotto da IcaroComix con il nome **IcaroManagerTV**:

- **App TV** (server e player): file manager per Android TV che naviga nei dischi USB collegati al
  televisore e avvia video, musica e foto con il player installato (`Intent.ACTION_VIEW` con MIME
  type corretto).
- **App smartphone** (client e telecomando, "IcaroManagerTV Remote"): rispecchia in tempo reale lo
  stato della TV, offre una ricerca con la tastiera del telefono, la navigazione delle cartelle e il
  controllo remoto completo.

Requisiti chiave: scoperta reciproca senza configurazione (niente IP né QR code), riconnessione
automatica dopo perdite del Wi-Fi, sincronizzazione bidirezionale a bassa latenza, menu
"Info / Crediti" sullo stesso modello di IcaroTube, versione gestita con SemVer, compilazione
automatica dei due APK di rilascio in una cartella dedicata.

## 2. Decisioni

| Tema | Scelta | Alternative scartate e motivo |
|---|---|---|
| Struttura | Progetto Gradle unico con tre moduli: `:common`, `:app-tv`, `:app-mobile` | Due progetti separati: il protocollo e la logica di ricerca devono restare identici nei due lati; un modulo comune lo garantisce a compile-time |
| Server sulla TV | NanoHTTPD + NanoWSD 2.3.1 (HTTP + WebSocket) | Ktor: molto più pesante (coroutine engine, decine di dipendenze) per pochi client in LAN |
| Client sul telefono | OkHttp 4.12 WebSocket con ping ogni 4 s | REST polling/SSE: latenza più alta e nessun canale telefono→TV nella stessa connessione |
| Scoperta | **Doppio canale**: NSD/mDNS (`_icaromgrtv._tcp`) e beacon UDP broadcast sulla porta 47811, con sonda attiva dal telefono | Solo NSD: molti router domestici filtrano il multicast; solo UDP: alcune reti bloccano il broadcast diretto. I due canali coprono i casi reciproci |
| Fonte di verità | La TV possiede lo stato; telefono e telecomando della TV inviano gli **stessi comandi** a un controller seriale | Stato replicato con merge: complessità e conflitti inutili per un solo "schermo" condiviso |
| Identificazione delle voci | Coppia (sequenza dell'elenco, indice), convalidata dal server | Percorsi o URI nei messaggi: un client potrebbe chiedere qualunque file; con gli indici può agire solo su ciò che la TV sta mostrando |
| Ricerca | Filtro condiviso (stessa funzione nei due lati) senza maiuscole/accenti, separatori equivalenti, tutte le parole presenti; ricerca ricorsiva opzionale eseguita dalla TV | Filtro solo lato TV: ogni lettera richiederebbe un giro di rete prima di vedere il risultato sul telefono |
| Accesso ai dischi | POSIX tramite `StorageManager` + alberi SAF autorizzati, affiancati | Solo SAF: molte TV non hanno il selettore documenti; solo POSIX: alcune TV non espongono l'impostazione "accesso a tutti i file" |
| Passaggio al player | URI `content://` (FileProvider con `root-path` per i volumi USB, URI documento per SAF) e permesso di lettura temporaneo | `file://`: vietato da Android 7 (FileUriExposedException) |
| Persistenza del server | Servizio in primo piano (`specialUse`) che possiede server, annuncio e ricevitore USB | Server nell'activity: si fermerebbe appena il player esterno va in primo piano |
| Versione | `version.properties` alla radice, letta dal `build.gradle.kts` radice e applicata a entrambe le app | Versione duplicata nei due moduli: rischio di APK disallineati con lo stesso protocollo |
| Firma release | Chiave di debug locale (app per uso personale installata a mano), come IcaroTube | Keystore di rilascio: non disponibile e da non includere nel repository |

## 3. Architettura

### Modulo `:common`
- **Protocollo**: messaggi JSON su WebSocket. Server → client: `hello`, `listing` (elenco
  completo, solo al cambio di cartella o a blocchi durante la ricerca), `state` (query, flag
  ricorsivo, selezione, messaggio, file in riproduzione: piccolo e frequente), `toast`, `error`.
  Client → server: `hello`, `open`, `select`, `up`, `root`, `crumb`, `query`, `recursive`, `key`
  (pad direzionale), `refresh`. Le voci viaggiano come array posizionali per ridurre il peso delle
  cartelle molto popolate. La decodifica non lancia mai eccezioni: i messaggi sconosciuti sono
  ignorati, la query è troncata a 200 caratteri.
- **Beacon UDP**: `announce` (id stabile della TV, nome, porta TCP, versione protocollo) e `probe`.
- **Ricerca** (`SearchText`) e ordinamento naturale (`NaturalOrder`: "Episodio 2" prima di "Episodio 10").
- **UI condivisa**: adattatore dell'elenco (lavora sempre con gli indici dell'elenco completo, gli
  stessi del protocollo), barra del percorso, dialog **Info / Crediti**, icona adattiva vettoriale,
  tema scuro, logo IcaroComix.

### App TV
- **`BrowserController`**: unico proprietario dello stato. Coda di comandi consumata da una sola
  coroutine (nessun lock, nessuna corsa tra telefono e telecomando). La ricerca ricorsiva gira in
  un job separato, con attesa di 250 ms dopo l'ultima lettera, scansione in ampiezza, risultati
  pubblicati a blocchi (40 voci o 300 ms), limite di 1000 risultati, annullata a ogni cambio di
  query, cartella o modalità.
- **`StorageRepository`**: volumi da `StorageManager` (più i punti di montaggio non standard dei
  box), alberi SAF persistenti, elenchi ordinati con le cartelle prima, file nascosti esclusi.
- **`MediaLauncher`**: `ACTION_VIEW` con MIME specifico e poi generico della famiglia (alcuni
  player accettano solo `video/*`), `ClipData` per propagare il permesso, titolo per VLC/MX Player.
- **`RemoteServer`**: NanoWSD; accetta solo indirizzi della rete locale; un executor a thread
  singolo per client garantisce l'ordine dei messaggi e isola i telefoni lenti; i nuovi client
  ricevono subito saluto, elenco e stato correnti; versione di protocollo diversa → errore
  esplicito e chiusura.
- **`TvAnnouncer`**: registrazione NSD con attributo TXT `id`, beacon UDP ogni 2 s verso il
  broadcast limitato e quelli diretti di ogni interfaccia, risposta immediata alle sonde.
- **`RemoteServerService`**: servizio in primo piano; ripete l'annuncio NSD al cambio di rete;
  ricevitore di montaggio/smontaggio USB (torna alla radice se il disco in uso viene scollegato).
- **UI** (`TvMainActivity`): pannello di stato (telefoni collegati, indirizzo, file in
  riproduzione) e azioni; ricerca, percorso e elenco con focus D-pad evidente a distanza; la
  selezione mossa dal telefono sposta il focus solo se l'utente sta navigando l'elenco.

### App smartphone
- **`TvDiscovery`**: NSD (risoluzioni in coda, una alla volta per le versioni precedenti ad
  Android 14) + ascolto beacon e sonda UDP ogni 4 s, con multicast lock. TV identificate dall'id
  stabile, quindi un cambio di IP aggiorna la voce senza duplicarla.
- **`RemoteClient`**: ciclo di riconnessione con attesa crescente 0,5 → 8 s, interrotta subito
  da rete Wi-Fi tornata, TV (ri)apparsa o scelta dell'utente; ultimo indirizzo memorizzato per un
  collegamento immediato all'avvio; preferenza per l'ultima TV usata, con ripiego su un'altra dopo
  tre fallimenti; socket chiuso subito alla perdita della rete.
- **UI** (`MainActivity`): fascia di stato del collegamento, ricerca con filtro locale immediato,
  percorso toccabile, elenco speculare (tocco = apri sulla TV, pressione prolungata = seleziona),
  pad direzionale a scomparsa, barra comandi, scelta della TV, Info / Crediti. Collegamento tenuto
  aperto 30 s dopo il passaggio in background, poi chiuso.

### Sincronizzazione della ricerca
Entrambi i lati inviano la query a ogni modifica e la ricevono nello stato. Per evitare che l'eco
di una query intermedia (es. "ma" mentre si è già digitato "mat") riporti indietro il testo, ogni
lato ignora le query diverse dall'ultima inviata per 1,5 s dalla digitazione; trascorsa la
finestra, prevale lo stato della TV (così la digitazione sulla TV raggiunge il telefono).

### Versionamento (SemVer)
`version.properties`: MAJOR per modifiche non retrocompatibili (in particolare un cambio di
`Protocol.VERSION`, che rende incompatibili TV e telefono), MINOR per nuove funzionalità
retrocompatibili, PATCH per correzioni e manutenzione; `versionCode` cresce di 1 a ogni rilascio.
Prima versione: **1.0.0** (versionCode 1), trattandosi della prima consegna del sistema.

## 4. Scoperte durante sviluppo e test

- **NanoHTTPD 2.3.1** pubblicato su Maven Central usa ancora il package `fi.iki.elonen` (non
  `org.nanohttpd.*` come nei sorgenti più recenti): verificato ispezionando i jar prima di scrivere
  il server.
- **Interoperabilità NanoWSD ↔ OkHttp** verificata empiricamente su JVM con le stesse versioni e
  lo stesso timeout di lettura del server (15 s): handshake 101, testo UTF-8 con caratteri
  accentati, nessuna caduta dopo 22 s di inattività grazie ai ping di OkHttp (NanoWSD risponde
  automaticamente con i pong), chiusura pulita.
- Nel `build.gradle.kts` radice, all'interno della configurazione di un task `extra` si riferisce
  alle proprietà del task e non del progetto: la versione va catturata in una variabile prima.
- Con `targetSdk 36` la finestra del telefono è sempre edge-to-edge: gestiti gli inset di barre di
  sistema e tastiera sulla radice del layout.
- OkHttp rispetta la policy del traffico in chiaro di Android: per il WebSocket `ws://` verso IP
  privati serve `usesCleartextTraffic` (una network security config non può elencare intervalli
  di IP).
- Da Android 10 l'avvio del player mentre l'app TV è in background viene scartato in silenzio dal
  sistema se manca il permesso "Mostra sopra altre app": la TV lo rileva e lo segnala, e offre un
  pulsante per concederlo (con istruzioni ADB se la TV non ha la schermata).

## 5. Riepilogo finale

**Consegnato**
- App TV `com.icarocomix.icaromanagertv.tv` e app smartphone `com.icarocomix.icaromanagertv.mobile`,
  versione 1.0.0 (versionCode 1), minSdk 26, targetSdk 36, firmate con chiave di debug.
- APK in `build/outputs/apk/`: `app-tv-release.apk` (~0,9 MB), `app-mobile-release.apk` (~1,0 MB)
  e copie con la versione nel nome. Comando: `./gradlew distApk`.
- Menu Info / Crediti in entrambe le app (logo e nome dell'app, versione installata, "Applicazione
  creata da" con logo e nome IcaroComix, descrizione e librerie).

**Esito build/test**
- `./gradlew :common:testDebugUnitTest`: 9 test, 0 falliti (codec del protocollo, messaggi
  malformati, troncamento query, ricerca, ordinamento naturale).
- `./gradlew distApk` (assembleRelease di entrambe con R8 e lint vitale): riuscito.
- Prova di interoperabilità WebSocket su JVM: riuscita.
- Non eseguite prove su dispositivo o emulatore (TV e telefono reali): scoperta in rete, permessi
  di archiviazione e avvio dei player vanno verificati sull'hardware.

**Limiti noti**
- Traffico in chiaro e senza autenticazione nella rete locale (accettati solo indirizzi privati);
  un dispositivo malevolo nella stessa LAN potrebbe comandare la navigazione, ma non leggere né
  aprire file fuori da quanto la TV mostra.
- Da Android 13, senza "accesso a tutti i file" sono visibili solo cartelle e file multimediali.
- Il controllo della riproduzione (pausa, avanti) resta al player esterno: l'app non può inviare
  comandi ad altre app senza servizi di accessibilità. *Superato dalla 1.1.0 con il player
  integrato: vedi [player-integrato-playlist-resume.md](player-integrato-playlist-resume.md).*
- Su Android 17+ potrebbe servire il futuro permesso di accesso alla rete locale quando si alzerà
  il targetSdk.
- Se il servizio TV viene chiuso dal sistema non si riavvia da solo: riparte all'apertura dell'app.

**File toccati**
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `version.properties`, `.gitignore`,
  `gradlew`, `gradle/wrapper/*`, `local.properties`, `TODO.md`, `README.md`
- `art/genera_banner.py`, `art/logo_azienda.png`
- `common/build.gradle.kts`, `common/consumer-rules.pro`, `common/src/main/AndroidManifest.xml`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/{Protocol,Messages,ProtocolCodec,Beacon}.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/search/SearchText.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/net/LocalNetwork.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/ui/{CreditsDialog,EntryAdapter,CrumbBar}.kt`
- `common/src/main/res/**` (colori, temi, stringhe, dimensioni, icone vettoriali, layout crediti/riga/percorso, icona adattiva, logo aziendale)
- `common/src/test/java/com/icarocomix/icaromanagertv/common/{ProtocolCodecTest,SearchTextTest}.kt`
- `app-tv/build.gradle.kts`, `app-tv/proguard-rules.pro`, `app-tv/src/main/AndroidManifest.xml`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/TvApp.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/storage/{MediaTypes,StorageRepository,StorageAccess}.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/controller/{BrowserController,MediaLauncher}.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/server/RemoteServer.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/discovery/TvAnnouncer.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/service/RemoteServerService.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/ui/TvMainActivity.kt`
- `app-tv/src/main/res/**` (stringhe, tema TV, dimensioni, layout, banner, file_paths, icona notifica)
- `app-mobile/build.gradle.kts`, `app-mobile/proguard-rules.pro`, `app-mobile/src/main/AndroidManifest.xml`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/discovery/TvDiscovery.kt`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/client/RemoteClient.kt`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/ui/{MainViewModel,MainActivity}.kt`
- `app-mobile/src/main/res/**` (stringhe, stili, menu, layout, sfondi del pad)
