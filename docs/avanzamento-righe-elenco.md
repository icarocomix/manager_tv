# Indicatore di avanzamento nelle righe dell'elenco (IcaroManagerTV 1.2.0)

## Obiettivo

Mostrare in ogni riga dell'elenco, su TV e telefono, quanta parte di un file audio/video è già
stata vista, usando le posizioni che il player integrato salva sulla TV (archivio SQLite introdotto
nella 1.1.0). La barra deve aggiornarsi anche mentre il file è in riproduzione nella miniatura,
senza ritrasmettere l'elenco.

## Decisioni

| Tema | Scelta | Alternative scartate |
|---|---|---|
| Trasporto | **Messaggio separato `progress`**: sequenza dell'elenco + mappa sparsa indice → millesimi | Campo aggiuntivo nelle voci di `listing`: ogni salvataggio (ogni 5 s in riproduzione) richiederebbe di ritrasmettere l'intero elenco o di cambiarne la sequenza, invalidando selezioni e comandi in volo del telefono |
| Formato | Coppie appiattite `[indice, millesimi, …]`, valori 1..999 | Array denso parallelo all'elenco: in cartelle con migliaia di voci quasi tutte senza avanzamento sprecherebbe banda a ogni aggiornamento |
| Unità | Millesimi, mai 0 né 1000 | Percentuale intera: troppo grossolana per file lunghi; 0 o 1000 renderebbero la barra invisibile o "finita" per file che invece hanno una posizione da riprendere |
| Lettura | Una query `IN (…)` per gruppi di 500 identificativi, sul ciclo dei comandi del file manager, accodata dopo la pubblicazione dell'elenco | Una query per file: troppi round-trip su cartelle grandi; lettura sincrona prima di pubblicare l'elenco: ritarderebbe la comparsa dell'elenco |
| Aggiornamenti in riproduzione | L'archivio notifica ogni salvataggio/cancellazione (identificativo + millesimi); il file manager aggiorna la sola voce interessata | Rilettura completa a ogni salvataggio: query inutili su elenchi grandi ogni 5 s |
| Rendering | Barra orizzontale sottile sotto i dettagli della riga (3 dp telefono, 4 dp TV), colore d'accento su binario tenue, aggiornata con payload RecyclerView | Percentuale testuale nei dettagli: più rumore visivo; rebind completo: sfarfallio e lavoro inutile |
| Versione | **1.2.0** (versionCode 3): MINOR, messaggio aggiuntivo ignorato dai client precedenti | MAJOR: nessuna rottura del protocollo |

## Architettura

- **Modello comune**: avanzamento dell'elenco identificato dalla stessa sequenza di `listing`;
  funzione di calcolo dei millesimi condivisa (durata ignota → nessuna barra). Il codec scarta
  indici negativi, valori fuori intervallo e coppie incomplete.
- **Archivio (TV)**: lettura in blocco per un insieme di file, con lo stesso controllo della
  dimensione usato per la ripresa (file sostituito → nessuna barra); flusso di notifiche con buffer
  limitato che, se chi ascolta resta indietro, scarta le notifiche più vecchie (il dato resta su
  disco e la rilettura successiva lo recupera).
- **File manager (TV)**: dopo ogni elenco pubblicato accoda una rilettura; durante una ricerca, con
  più elenchi parziali in fila, viene eseguita solo quella dell'elenco più recente. Mappa
  identificativo → indice dei soli file audio/video dell'elenco attuale per tradurre le notifiche
  dell'archivio. Tutto avviene sul ciclo dei comandi, senza lock. Le notifiche arrivate prima della
  rilettura dell'elenco corrente vengono ignorate: la rilettura le comprende già.
- **Server**: diffonde il nuovo messaggio e lo invia ai client appena collegati dopo elenco e stato.
- **Adattatore condiviso**: conserva l'ultimo avanzamento ricevuto e lo applica solo se la sequenza
  coincide con l'elenco mostrato; così l'ordine di arrivo rispetto all'elenco è indifferente. Ridisegna
  solo le righe il cui valore cambia.
- **Telefono**: nuovo flusso nel client, azzerato al collegamento con una TV diversa (la sequenza
  di un'altra TV potrebbe coincidere per caso).

## Scoperte e correzioni

- I file finiti o appena iniziati non hanno riga nell'archivio (regole di significatività della
  ripresa), quindi non mostrano barra: coerente con la domanda di ripresa, che per loro non compare.
- Un file la cui durata non era nota al momento del salvataggio non può avere barra: l'archivio
  conserva la posizione per la ripresa ma i millesimi risultano indefiniti.

## Riepilogo finale

**Consegnato**
- Barra della parte vista nelle righe di file audio/video, su TV e telefono, aggiornata in tempo
  reale durante la riproduzione e con descrizione accessibile ("Visto al N%").
- Messaggio di protocollo `progress` retrocompatibile; versione 1.2.0 (versionCode 3).

**Build e test**
- Test unitari `:common`: 17 su 17 (3 nuovi: andata/ritorno del codec, coppie malformate, calcolo
  dei millesimi).
- `assembleDebug` di app TV e app telefono senza errori né avvisi; `distApk` per le release.
- Non ancora verificato su TV e telefono reali.

**Limiti noti**
- I file conclusi non sono distinti da quelli mai aperti (nessun segno di "visto").
- La potatura periodica dell'archivio (oltre 5.000 voci) non notifica le righe rimosse: la barra
  sparisce alla successiva apertura della cartella.
- Telefono 1.2 con TV precedente: nessuna barra; telefono precedente con TV 1.2: invariato.

**File toccati**
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/Messages.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/ProtocolCodec.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/ui/EntryAdapter.kt`
- `common/src/main/res/layout/item_entry.xml`
- `common/src/main/res/drawable/bg_entry_progress.xml` (nuovo)
- `common/src/main/res/values/dimens.xml`
- `common/src/main/res/values/strings.xml`
- `common/src/test/java/com/icarocomix/icaromanagertv/common/ListingProgressTest.kt` (nuovo)
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/playback/ProgressStore.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/controller/BrowserController.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/TvApp.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/server/RemoteServer.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/ui/TvMainActivity.kt`
- `app-tv/src/main/res/values/dimens.xml`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/client/RemoteClient.kt`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/ui/MainActivity.kt`
- `version.properties`, `README.md`, `TODO.md`, `docs/player-integrato-playlist-resume.md`
