# Scroll sincronizzato, interruttore di sincronizzazione, operazioni sui file e trasferimenti telefono ↔ TV (v1.9.0)

## Obiettivo

1. Con TV e telefono sincronizzati, lo **scorrimento** dell'elenco sul telefono deve vedersi anche
   sulla TV (prima seguivano solo cartella e selezione).
2. La sincronizzazione diventa **disattivabile** con un interruttore, attivo di default. Spenta, il
   telecomando della TV naviga indipendentemente dal telefono; riattivandola, la TV si porta
   automaticamente sulla posizione del telefono.
3. Il file manager deve permettere di **copiare, tagliare, incollare, eliminare e rinominare** file
   e cartelle del disco collegato alla TV, sia dal telefono sia dal telecomando.
4. Un file presente sul telefono (es. un video scaricato) deve poter essere **copiato o spostato**
   sul disco USB della TV.

## Decisioni

### Due sessioni di navigazione sulla TV

Il controller della TV era l'unico proprietario dello stato di navigazione, condiviso da TV e
telefono. Per la navigazione indipendente lo stato è stato estratto in una "sessione" (percorso,
elenco, ricerca, selezione, scroll, avanzamenti) e il controller ne gestisce due: quella della TV e,
solo con la sincronizzazione spenta, quella del telefono.

- Sincronizzazione attiva: il telefono usa la sessione della TV, comportamento identico alla v1.8.
- Spegnendola, il telefono riceve una copia integrale della sessione della TV (stesso elenco e stessa
  sequenza, nessuna rilettura del disco) e da lì prosegue da solo.
- Riaccendendola, la sessione della TV adotta quella del telefono (cartella, ricerca, selezione,
  scroll); il telefono, prima di inviare il comando, comunica la propria prima riga visibile, così la
  TV si posiziona esattamente sul punto guardato.
- La sequenza degli elenchi è unica tra le sessioni: un comando riferito all'elenco di una sessione
  non può combaciare per caso con quello dell'altra.
- Il pad del telefono resta sempre un telecomando della TV (agisce sulla sessione della TV anche con
  la sincronizzazione spenta); il tasto Indietro del telefono, prima inviato come tasto del pad, ora
  è un comando di navigazione del telefono (svuota la ricerca o risale di cartella).
- Lo stato dell'interruttore vive sulla TV ed è persistente; è visibile e comandabile sia dal
  telefono sia dal pannello laterale della TV.

Alternative scartate: tenere lo stato della navigazione indipendente sul telefono (avrebbe richiesto
al telefono di leggere il disco, cioè un secondo protocollo di listing); un'unica sessione con
"ignora i comandi del telefono" (il telefono non avrebbe potuto sfogliare da solo).

### Scroll

Il telefono invia `scroll` (sequenza dell'elenco, indice della prima riga visibile per intero) solo
per gli scorrimenti fatti col dito, diradati a uno ogni 120 ms più la posizione finale. La TV porta
quella riga in cima. Scelte collegate:

- la TV, se l'elenco aveva il focus, lo sposta su una riga visibile (la selezionata se ancora a
  schermo, altrimenti la prima): la riga con il focus, uscita dallo schermo e riciclata, avrebbe
  lasciato il focus al primo pulsante del pannello laterale;
- il telefono, durante lo scorrimento e per un secondo dopo, non si riporta sulla selezione che
  arriva dalla TV, che altrimenti "combatterebbe" con il dito;
- inviando la prima riga visibile per intero, la selezione che la TV eventualmente sposta su quella
  riga è già visibile sul telefono: nessun rimbalzo.

### Operazioni sui file

Eseguite sempre dalla TV, che possiede i dischi. Appunti unici sulla TV (si copia dal telefono e si
incolla col telecomando o viceversa). Comando `fop` con tipo (copia, taglia, incolla, elimina,
rinomina, nuova cartella, annulla, svuota appunti); la voce è identificata come per gli altri
comandi dalla coppia (sequenza, indice), quindi un client può agire solo su ciò che la TV mostra.

- Le operazioni lunghe girano fuori dal ciclo dei comandi (TV e telefono restano navigabili), con
  avanzamento in millesimi e annullamento nel nuovo messaggio `status`, comune a tutti i client.
- Un'unica "corsia" di scrittura: operazioni e caricamenti dal telefono si escludono a vicenda,
  per non avere due copie concorrenti sulla stessa chiavetta.
- Copia nella stessa cartella o con nome già usato: "nome (1).ext" (confronto senza distinzione di
  maiuscole, come i file system FAT/exFAT/NTFS delle chiavette). Spostamento nella stessa cartella:
  nessuna azione.
- Spostamenti nello stesso volume istantanei (rename POSIX o `moveDocument` SAF); tra volumi
  diversi copia e poi eliminazione dell'originale, solo a copia completata.
- File lasciati a metà da un errore o da un annullamento vengono eliminati.
- Copiare una cartella dentro sé stessa è rifiutato prima di iniziare; spazio libero verificato in
  anticipo sui volumi POSIX.
- Validazione dei nomi condivisa (modulo comune): vietati i caratteri non ammessi da FAT32/exFAT,
  "." e "..", nomi oltre 255 byte UTF-8; spazi e punti finali tolti. Il telefono valida nel dialog,
  la TV rivalida sempre.
- Eliminazione: conferma chiesta dall'interfaccia che la origina; sulla TV il focus parte da
  «Annulla». Dopo un'eliminazione viene selezionata la voce vicina.
- Dopo ogni modifica entrambe le sessioni rileggono la propria cartella; le cartelle del percorso che
  non esistono più (rinominate o eliminate dall'altra sessione) vengono tolte risalendo.

### Trasferimenti telefono ↔ TV

- Caricamento: POST HTTP in streaming verso `/api/upload` della TV, con `Content-Length`
  obbligatorio, parametri id della TV (come per i log: lo conosce solo un telefono collegato),
  sequenza dell'elenco e nome. La TV scrive direttamente dal socket al disco, senza i file
  temporanei di NanoHTTPD (che raddoppierebbero spazio e tempo per un film da qualche GB). La
  destinazione è la cartella dell'elenco indicato: la TV ricorda gli ultimi 48 elenchi pubblicati,
  così una serie di file finisce nella cartella scelta anche se nel frattempo il telefono si sposta.
- "Sposta" dal telefono = caricamento e poi eliminazione dell'originale tramite il provider di
  documenti, solo a caricamento riuscito; se il provider rifiuta, il file resta e l'utente lo sa.
- Scaricamento (TV → telefono, offerto anche se non richiesto esplicitamente perché completa lo
  scambio): GET `/api/download` con la coppia (sequenza, indice); destinazione scelta con il
  selettore di sistema.
- Sul telefono i trasferimenti girano in un servizio in primo piano (tipo `dataSync`) con
  notifica di avanzamento e pulsante Annulla, uno alla volta in coda: proseguono anche uscendo
  dall'app o a schermo spento. I permessi sui documenti scelti vengono resi persistenti per la
  durata del lavoro e poi rilasciati.

Alternative scartate: multipart/form-data (NanoHTTPD lo salva prima in un file temporaneo); far
"tirare" i file alla TV dal telefono (richiederebbe un server anche sul telefono); WorkManager (non
presente tra le dipendenze, e un servizio in primo piano dà all'utente avanzamento e controllo).

### Compatibilità

Tutti i messaggi nuovi sono aggiuntivi: `Protocol.VERSION` resta 1, versione MINOR (1.9.0,
versionCode 12). Un telefono v1.9 con una TV precedente non riceve `status` e nasconde interruttore,
operazioni e trasferimenti; una TV v1.9 con un telefono precedente funziona come prima.

## Piano

1. Modulo comune: messaggi `status`, `sync`, `scroll`, `fop`, campo di scroll nello stato,
   percorsi HTTP, regole sui nomi, test.
2. TV: componente di scrittura sui dischi (POSIX e SAF), controller a sessioni con appunti e
   operazioni, server con endpoint di trasferimento e messaggio `status`, interfaccia (casella di
   sincronizzazione, menu delle operazioni da tasto Menu / OK prolungato / pulsante laterale,
   dialog, barra di avanzamento, scroll remoto).
3. Telefono: client con `status`, servizio di trasferimento, interfaccia (interruttore, azioni con
   pressione prolungata, barra appunti con «Incolla qui», «Carica», «Nuova cartella», invio dello
   scroll).
4. Versione, README, build sequenziale.

## Scoperte e correzioni

- La normalizzazione dei nomi toglieva i punti finali prima di riconoscere "." e "..", che
  diventavano "nome vuoto": il controllo dei nomi riservati è stato anticipato (emerso dai test).
- `CancellationException` di Kotlin è una sottoclasse di `IllegalStateException`: il filtro che
  traduce gli errori dei provider SAF avrebbe trasformato un "Annulla" in un errore. Viene ora
  rilanciata esplicitamente.
- Scrivere sui dischi richiede più permessi della sola lettura: `WRITE_EXTERNAL_STORAGE` è stato
  esteso ad Android 10 (con `requestLegacyExternalStorage`), da Android 11 serve l'accesso a tutti i
  file; le cartelle SAF vengono ora autorizzate anche in scrittura, ma quelle aggiunte prima della
  v1.9 restano in sola lettura e vanno riaggiunte. In tutti questi casi l'errore mostrato dice
  all'utente cosa fare.
- Sui dischi USB rimovibili, fino ad Android 10, la scrittura con percorsi POSIX non è consentita
  dal sistema: lì resta la via SAF («Aggiungi cartella»).

## Riepilogo finale

Consegnato:

- scroll del telefono riprodotto sulla TV con la sincronizzazione attiva;
- interruttore di sincronizzazione (telefono e TV), default attivo e persistente, con adozione della
  posizione del telefono alla riattivazione;
- copia, taglia/incolla (spostamento), elimina, rinomina e nuova cartella da telefono e TV, su
  volumi POSIX e alberi SAF, con avanzamento e annullamento;
- caricamento di uno o più file dal telefono nella cartella aperta della TV, in copia o
  spostamento, e scaricamento di un file della TV sul telefono, in un servizio in primo piano.

Limiti noti:

- operazioni su una voce alla volta (niente selezione multipla nell'elenco della TV); il
  caricamento dal telefono accetta invece più file;
- un solo trasferimento/operazione di scrittura alla volta sulla TV;
- spazio libero verificato solo sui volumi POSIX;
- gli appunti non sopravvivono al riavvio dell'app TV;
- comportamento su hardware reale da verificare (vedi `TODO.md`, fase 15).

Esito build e test: test `:common` 35/35 superati (7 nuovi); `assembleDebug` TV e telefono
riusciti; `distApk` riuscito in 143 s → `build/outputs/apk/ManagerTV-app-tv-1.9.0.apk` e
`ManagerTV-app-mobile-1.9.0.apk`.

File toccati:

- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/Protocol.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/Messages.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/ProtocolCodec.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/files/FileNames.kt` (nuovo)
- `common/src/main/java/com/icarocomix/icaromanagertv/common/ui/EntryAdapter.kt`
- `common/src/test/java/com/icarocomix/icaromanagertv/common/FileOpsProtocolTest.kt` (nuovo)
- `app-tv/src/main/AndroidManifest.xml`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/TvApp.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/controller/BrowserController.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/server/RemoteServer.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/service/RemoteServerService.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/storage/FileOperations.kt` (nuovo)
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/storage/StorageRepository.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/storage/StorageAccess.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/ui/TvMainActivity.kt`
- `app-tv/src/main/res/layout/activity_tv.xml`
- `app-tv/src/main/res/values/strings.xml`
- `app-mobile/src/main/AndroidManifest.xml`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/client/RemoteClient.kt`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/transfer/TransferService.kt` (nuovo)
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/ui/MainActivity.kt`
- `app-mobile/src/main/res/layout/activity_main.xml`
- `app-mobile/src/main/res/menu/main.xml`
- `app-mobile/src/main/res/values/strings.xml`
- `app-mobile/src/main/res/drawable/ic_upload.xml`, `ic_download.xml`, `ic_paste.xml` (nuovi)
- `version.properties`, `README.md`, `TODO.md`
