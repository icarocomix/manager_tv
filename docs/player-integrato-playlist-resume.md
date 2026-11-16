# Player integrato, miniatura, ripresa e playlist (IcaroManagerTV 1.1.0)

## 1. Obiettivo

Estendere il sistema TV + smartphone con:

1. **Navigazione bidirezionale**: sul telefono, passaggio libero tra telecomando del player e file
   manager senza fermare la TV, con un ritorno rapido al player; sulla TV, video che prosegue in
   una **miniatura in basso a destra** mentre si naviga tra i file, con ritorno a schermo intero
   selezionando la miniatura.
2. **Ripresa della riproduzione**: posizione esatta salvata localmente per ogni file, con proposta
   di ripresa alla riapertura.
3. **Controlli avanzati e playlist**: salto a inizio e fine del file, file precedente e successivo
   nella cartella/elenco corrente, **autoplay** del successivo a fine file.

Stato globale della riproduzione unico e sincronizzato tra TV e telefono.

## 2. Decisioni

| Tema | Scelta | Alternative scartate e motivo |
|---|---|---|
| Motore di riproduzione | **Player integrato Media3 ExoPlayer 1.10.0** (moduli `exoplayer` + `ui`) per video e audio | Player esterno via `ACTION_VIEW` (versione 1.0): un'app non può comandare un'altra app, quindi niente miniatura in-app, posizione, autoplay o salti remoti. VLC-lib/libmpv: decine di MB nativi per ABI, eccessivi per TV economiche |
| Formati non gestiti | **Ripiego automatico sul player esterno**: errori di contenitore/decoder e tracce audio o video scartate perché non decodificabili (tipico DTS/AC3 senza licenza) | Lasciare il film muto o mostrare solo un errore: peggiore del comportamento della 1.0. Immagini, playlist testuali (m3u/pls) e altri file continuano ad andare alle app esterne |
| Miniatura | **Overlay in-app**: la stessa `PlayerView` cambia solo dimensioni e posizione (400×225 dp in basso a destra, dentro l'area sicura per l'overscan) | Picture-in-Picture di sistema: sposta l'activity in una finestra separata, quindi il file manager della stessa app non resta navigabile; inoltre su molte TV il PiP è assente o disattivato. Due `PlayerView` distinte: cambio di superficie con scatto e possibile reinizializzazione del decoder |
| Proprietà del player | Un solo `ExoPlayer` posseduto dall'Application (`PlaybackController`), agganciato all'activity quando visibile | Player nell'activity: si perderebbe a ogni ricreazione e non sarebbe comandabile dal server quando l'activity è chiusa |
| Thread | Tutto lo stato del player sul main thread; metodi pubblici che rimbalzano sul main; archivio su un dispatcher IO **seriale** | Lock espliciti: ExoPlayer impone comunque un thread unico; il dispatcher seriale garantisce che salvataggi e cancellazioni avvengano nell'ordine richiesto |
| Persistenza delle posizioni | **SQLite con SQL esplicito** (`SQLiteOpenHelper`, una tabella con chiave primaria testuale, WAL) | Room: ORM con generazione di codice, contrario alla linea del progetto (accesso esplicito, niente ORM); SharedPreferences: un file XML riscritto per intero a ogni salvataggio periodico, inadatto a migliaia di voci |
| Chiave del file | Percorso assoluto (POSIX) o albero+documento (SAF), più dimensione del file per riconoscere un file sostituito | Solo nome: collisioni tra cartelle diverse (es. "Episodio 01.mkv" di serie diverse) |
| Quando salvare | Ogni 5 s durante la riproduzione, a ogni pausa, al cambio di file, alla chiusura del player e quando l'activity va in background | Solo alla chiusura: un'interruzione di corrente della TV perderebbe tutta la sessione |
| Cosa vale la pena riprendere | Oltre 15 s e prima degli ultimi 30 s (o del 95%); fuori da questo intervallo la voce viene rimossa | Salvare sempre: domande di ripresa inutili su file appena aperti o già finiti |
| Domanda di ripresa | Pubblicata nello stato condiviso: TV (pannello) e telefono (dialog) la mostrano insieme, vince la prima risposta; **senza risposta la TV riprende da sola dopo 15 s** | Domanda solo sulla TV: chi usa il telefono non la vedrebbe; attesa senza limite: con il telefono in tasca la TV resterebbe ferma |
| Playlist | **Istantanea** dei file della stessa famiglia (video o audio) visibili nell'elenco al momento dell'apertura, filtro di ricerca e risultati ricorsivi compresi | Playlist "viva" legata alla cartella mostrata: navigare nel file manager durante la visione cambierebbe il "successivo" in modo imprevedibile |
| Autoplay | Playlist nativa di ExoPlayer; autoplay disattivo = pausa alla fine di ogni file. Preferenza persistente, modificabile da TV e telefono | Gestione manuale del passaggio al file successivo: più codice e niente pre-buffering del file seguente |
| "Fine del file" | Salto agli ultimi 5 s | Salto alla durata esatta: il file terminerebbe all'istante senza mostrare la fine; con gli ultimi secondi l'autoplay prosegue con il flusso naturale |
| Navigazione sul telefono | Cambio di schermata **solo locale** (file manager ↔ player); disposizione della TV (schermo intero/miniatura) comandata da un pulsante esplicito | Legare le due cose: chi cerca il prossimo episodio sul telefono non vuole necessariamente rimpicciolire il film agli altri spettatori |
| Protocollo | Messaggi **aggiuntivi** (`playback` dal server; `player`, `seek`, `autoplay`, `resume`, `view` dal client). `Protocol.VERSION` resta 1 | Nuova versione del protocollo: renderebbe incompatibili telefoni e TV non aggiornati insieme, mentre i decodificatori di entrambi i lati già ignorano i messaggi sconosciuti |
| Versione | **1.1.0** (versionCode 2): MINOR, nuove funzionalità retrocompatibili | MAJOR: non c'è rottura del protocollo |

## 3. Architettura

### Protocollo (modulo `:common`)
- `PlaybackState`: attivo, titolo, tipo (video/audio), posizione e durata in ms, intenzione di
  riproduzione, buffering, indice e lunghezza della playlist, autoplay, disposizione sulla TV,
  posizione proposta per la ripresa (−1 se nessuna domanda). Proprietà derivate: precedente e
  successivo disponibili, domanda di ripresa in corso.
- Viaggia in un messaggio separato (`playback`), circa uno al secondo durante la riproduzione:
  elenco e stato del file manager non vengono ritrasmessi.
- Comandi del client: trasporto (`PlayerAction`: play/pausa, stop, inizio, fine, −10 s, +30 s,
  precedente, successivo), posizionamento assoluto, autoplay, risposta alla ripresa, disposizione
  sulla TV (`TvView`: schermo intero o miniatura). Decodifica tollerante: azioni o viste ignote
  scartate, posizioni negative portate a zero.
- `TimeFormat`: "m:ss" / "h:mm:ss", identico sui due lati.

### App TV
- **`ProgressStore`**: tabella `progress` (chiave, nome, posizione, durata, dimensione, data di
  aggiornamento) con indice sulla data; potatura alle 5000 voci più recenti a ogni avvio; errori
  SQLite registrati senza interrompere la riproduzione.
- **`PlaybackController`**: crea l'`ExoPlayer` alla prima riproduzione (fallback del decoder
  attivo, focus audio gestito) e lo **rilascia alla chiusura** per liberare il decoder. Pubblica
  `player` (per la `PlayerView`) e `state` (per server e UI). Ciclo al secondo: aggiornamento
  della posizione, salvataggio ogni 5 s. Verifica della posizione salvata a ogni cambio di file,
  con token per scartare i risultati superati e una finestra in cui i salvataggi sono sospesi.
  Ripiego sul player esterno in caso di formato non supportato. Se la riproduzione parte dal
  telefono con l'app TV in background, riporta l'activity in primo piano e avvia il video solo
  quando è visibile.
- **`BrowserController`**: l'apertura di un video/audio costruisce la playlist e la passa al
  player; i comandi del player passano dalla stessa coda dei comandi del file manager (ordine di
  arrivo unico) e vengono inoltrati al player.
- **`RemoteServer`**: terzo flusso trasmesso (`playback`), ultimo stato inviato subito ai nuovi
  client.
- **`TvMainActivity`**: radice a strati (file manager sotto, contenitore del player sopra,
  pannello di ripresa al centro). Schermo intero: controlli Media3 con precedente/successivo,
  ±salti e sottotitoli; "Indietro" passa alla miniatura. Miniatura: elemento selezionabile con
  cornice d'accento e didascalia, OK → schermo intero; pulsanti "Torna al player" e "Chiudi il
  player" nel pannello laterale. Tasti multimediali del telecomando gestiti in ogni vista
  (PLAY e PAUSE dedicati non invertono lo stato). In background il player va in pausa e salva.

### App smartphone
- **`RemoteClient`**: flusso `playback`; azzerato solo al collegamento con una TV diversa, così una
  breve caduta del Wi-Fi non viene scambiata per l'avvio di una nuova riproduzione.
- **`MainActivity`**: due schermate nello stesso spazio.
  - *Player*: titolo, "N di M", barra di avanzamento con tempi (un solo salto al rilascio del
    dito, anteprima del tempo durante il trascinamento), precedente / −10 s / play-pausa / +30 s /
    successivo, inizio / ferma / fine, interruttore autoplay, pulsante miniatura/schermo intero
    sulla TV, "File manager" in alto (e tasto Indietro) per tornare all'elenco.
  - *File manager*: invariato, con una **mini-barra** in basso durante la riproduzione (titolo,
    avanzamento, play/pausa diretto) che riporta al player.
  - Passaggio automatico alla schermata Player solo all'avvio di una riproduzione, non al
    passaggio al file successivo; schermata mostrata conservata nelle rotazioni.
  - Dialog di ripresa sincronizzato con lo stato della TV (si chiude da solo se risponde la TV).

## 4. Scoperte durante sviluppo e test

- **Ordine dei salvataggi al cambio di file**: mettere in pausa il nuovo file (per decidere se
  proporre la ripresa) genera un callback di pausa che salverebbe la posizione ~0 del nuovo file;
  la regola "sotto i 15 s rimuovi" cancellerebbe la voce prima della lettura. Introdotta una
  finestra di verifica durante la quale non si salva nulla, e un dispatcher seriale per
  l'archivio.
- **Salto durante la domanda di ripresa**: passare al file successivo mentre la domanda è aperta
  salvava il file lasciato a posizione 0, cancellandone il progresso. Il file lasciato in attesa
  di risposta ora resta invariato.
- **Avvio da telefono con TV in background**: l'activity riportata in primo piano poteva far
  partire il video prima della lettura della posizione salvata; l'avvio differito ora attende la
  verifica.
- **Task `distApk` mai rieseguito** (difetto della 1.0.0): dichiarava solo la cartella di uscita,
  quindi Gradle lo considerava aggiornato e non copiava gli APK nuovi. Aggiunti come ingressi gli
  APK di release e la versione.
- Media3 1.10.0 compila con AGP 9.1 / compileSdk 36 senza avvisi; le API marcate instabili
  (fallback del decoder, pausa a fine file) sono dichiarate con opt-in esplicito e superano il
  lint vitale di release.
- L'APK TV di release passa da ~0,9 MB a ~1,9 MB (Media3 dopo R8): costo accettabile.

## 5. Riepilogo finale

**Consegnato**
- Player integrato su TV con schermo intero e miniatura in-app, tasti multimediali, pannello di
  ripresa con conto alla rovescia, ripiego sul player esterno.
- Posizioni salvate per file su SQLite, con proposta di ripresa su TV e telefono.
- Playlist della cartella/elenco con precedente, successivo, inizio, fine, ±salti, autoplay
  persistente.
- Telefono: schermata Player, mini-barra "Torna al player", ritorno al file manager senza
  effetti sulla TV, comando della disposizione sulla TV.
- Versione **1.1.0** (versionCode 2) per entrambe le app.

**Esito build/test**
- `:common:testDebugUnitTest`: 14 test, 0 falliti (5 nuovi: stato del player, flag della
  playlist, comandi del client, messaggi malformati, formato dei tempi).
- `:app-tv:assembleDebug`, `:app-mobile:assembleDebug`: riusciti.
- `./gradlew distApk` (release con R8 e lint vitale): riuscito; APK verificati con `aapt2`
  (versionName 1.1.0, versionCode 2).
- Non eseguite prove su TV e telefono reali: decodifica dei formati sull'hardware specifico,
  comportamento della miniatura con `SurfaceView` sul chipset della TV, avvio da background e
  dialog di ripresa vanno verificati sui dispositivi.

**Limiti noti**
- Con l'app TV in background (tasto Home) il player va in pausa: niente audio in background
  (richiederebbe un servizio di tipo `mediaPlayback` e una MediaSession).
- La miniatura è interna all'app, non il Picture-in-Picture di sistema: uscendo dall'app il video
  non resta sovrapposto alle altre app.
- Il riavvio dall'esterno richiede ancora il permesso "Mostra sopra altre app" su Android 10+.
- Nessun indicatore di avanzamento nelle righe dell'elenco (superato nella 1.2.0: vedi
  `docs/avanzamento-righe-elenco.md`, realizzato con un messaggio separato anziché con un campo
  nelle voci).
- Telefono v1.1 con TV v1.0: il file manager funziona, la schermata Player non compare; telefono
  v1.0 con TV v1.1: funziona come prima, vede il titolo in riproduzione ma non i nuovi comandi.

**File toccati**
- `version.properties`, `build.gradle.kts`, `README.md`, `TODO.md`
- `docs/player-integrato-playlist-resume.md`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/{Protocol,Messages,ProtocolCodec}.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/util/TimeFormat.kt` (nuovo)
- `common/src/main/res/values/strings.xml`
- `common/src/main/res/drawable/ic_{pause,skip_previous,skip_next,stop,fast_rewind,fast_forward,seek_start,seek_end,pip,fullscreen,playlist_play}.xml` (nuovi)
- `common/src/test/java/com/icarocomix/icaromanagertv/common/PlaybackProtocolTest.kt` (nuovo)
- `app-tv/build.gradle.kts`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/TvApp.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/playback/{PlaybackController,ProgressStore}.kt` (nuovi)
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/controller/{BrowserController,MediaLauncher}.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/storage/MediaTypes.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/server/RemoteServer.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/service/RemoteServerService.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/ui/TvMainActivity.kt`
- `app-tv/src/main/res/layout/activity_tv.xml`, `app-tv/src/main/res/values/strings.xml`
- `app-tv/src/main/res/drawable/{bg_mini_player,bg_resume_panel}.xml` (nuovi)
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/client/RemoteClient.kt`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/ui/MainActivity.kt`
- `app-mobile/src/main/res/layout/{activity_main,screen_player}.xml` (`screen_player` nuovo)
- `app-mobile/src/main/res/drawable/bg_now_bar.xml` (nuovo)
- `app-mobile/src/main/res/values/{strings,styles}.xml`
