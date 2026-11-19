# Manager TV

File manager per Android TV con telecomando da smartphone.

- **Manager TV** (app TV): sfoglia i dischi USB collegati al televisore e riproduce video e
  musica con il player integrato (Media3 ExoPlayer): miniatura in basso a destra mentre si sfoglia,
  ripresa dal punto interrotto (con barra della parte già vista nelle righe dell'elenco, anche
  sul telefono), file precedente/successivo e riproduzione automatica della cartella. Foto, altri formati e codec non decodificabili passano al player installato (VLC,
  MX Player, Kodi…).
- **Manager TV Remote** (app smartphone): trova da sola la TV nella rete Wi-Fi, rispecchia in
  tempo reale cartelle e selezione, cerca con la tastiera del telefono, avvia i file sulla TV,
  offre un pad direzionale e una schermata Player (pausa, salti, inizio/fine, precedente/successivo,
  autoplay, miniatura/schermo intero sulla TV, volume della TV, sottotitoli e impostazioni del
  player della TV) con mini-barra per tornarci dal file manager; apre l'app sulla TV da remoto,
  anche con la TV appena riaccesa, senza codici né passaggi sulla TV.

Architettura, decisioni e limiti: [docs/icaromanagertv-architettura.md](docs/icaromanagertv-architettura.md);
player integrato, miniatura, ripresa e playlist: [docs/player-integrato-playlist-resume.md](docs/player-integrato-playlist-resume.md);
barra di avanzamento nelle righe: [docs/avanzamento-righe-elenco.md](docs/avanzamento-righe-elenco.md);
schermo sempre acceso durante la riproduzione ed esportazione dei log: [docs/schermo-acceso-ed-esportazione-log.md](docs/schermo-acceso-ed-esportazione-log.md);
volume della TV dal telefono: [docs/volume-tv-dal-telefono.md](docs/volume-tv-dal-telefono.md);
controlli del player e pad del telefono come telecomando: [docs/pad-telefono-come-telecomando.md](docs/pad-telefono-come-telecomando.md);
OK = pausa, Giù = timeline, apertura dell'app e pannelli del player dal telefono: [docs/ok-pausa-timeline-avvio-remoto.md](docs/ok-pausa-timeline-avvio-remoto.md);
Indietro a due livelli, pad del telefono, avvio a freddo dal telefono e ricerca ricorsiva di default: [docs/indietro-pad-avvio-a-freddo-ricerca.md](docs/indietro-pad-avvio-a-freddo-ricerca.md);
nuovo nome e nuova icona: [docs/nome-manager-tv-e-nuova-icona.md](docs/nome-manager-tv-e-nuova-icona.md).

## Uso del player

| Dove | Azione | Effetto |
|---|---|---|
| TV, schermo intero | OK con i controlli nascosti | Pausa (compaiono i controlli, focus su Play) / ripresa |
| TV, schermo intero | Giù con i controlli nascosti | Controlli con il focus sulla barra temporale; Giù ancora: sottotitoli, impostazioni |
| TV, schermo intero | Su/Sinistra/Destra con i controlli nascosti | Controlli con il focus su Play/Pausa |
| TV, schermo intero | Indietro con i controlli visibili | Nasconde i controlli (il video resta a schermo intero) |
| TV, schermo intero | Indietro con i controlli nascosti | Video in miniatura, file manager navigabile (il video continua) |
| TV, miniatura | OK sulla miniatura o «Torna al player» | Schermo intero |
| TV | «Chiudi il player» / tasto Stop | Ferma e salva la posizione |
| TV | Tasti multimediali del telecomando | Play/pausa, avanti/indietro, successivo/precedente in ogni vista |
| Telefono, Player | «File manager» o Indietro | Torna all'elenco senza toccare la TV |
| Telefono, file manager | Mini-barra in basso | Torna al Player (pulsante play/pausa diretto) |
| Telefono, Player | «Miniatura sulla TV» / «Schermo intero sulla TV» | Cambia la disposizione sulla TV |
| Telefono, Player | Muto, −, cursore, + (riga «Volume della TV») | Volume di sistema della TV (indicatore visibile sulla TV) |
| Telefono, Player | Tasti volume del telefono | Alzano/abbassano il volume della TV (scollegato: volume del telefono) |
| Telefono, Player | «Telecomando» in alto a destra | Mostra il pad: sulla TV a schermo intero muove il focus tra i controlli del player, OK li attiva, Indietro riduce a miniatura |
| Telefono, Player | «Sottotitoli» / «Impostazioni» | Apre sulla TV il selettore dei sottotitoli / il pannello impostazioni (audio, velocità) e mostra il pad per sceglierne le voci |
| Telefono, menu | «Apri l'app sulla TV» (icona schermo) | Porta Manager TV in primo piano sulla TV, attendendo fino a un minuto la TV appena accesa |
| Telefono, menu ⋮ | «Abbina la TV (facoltativo)…» | Solo per TV che bloccano l'avvio automatico: abbinamento con codice al servizio di sistema, usato come ripiego |
| Telefono, pad | Home (casetta) | Schermata principale dell'app sulla TV: app in primo piano, video in miniatura, archivi |
| Telefono, file manager | Pad nell'elenco | Su/Giù selezione, Sinistra risale (alla radice: va al pannello laterale), Destra/OK apre |
| Telefono, pad | Fuori dall'elenco (pannello, ricerca, dialog, ripresa) | Stesso effetto dei tasti direzionali del telecomando della TV |

La ricerca guarda anche nelle sottocartelle per impostazione predefinita; la casella «Anche nelle sottocartelle»
la disattiva e la TV ricorda la scelta.

Riaprendo un file già iniziato (oltre 15 s e prima degli ultimi 30 s / 95%) TV e telefono chiedono
se riprendere: risponde il primo, senza risposta la TV riprende da sola dopo 15 s. La playlist è
formata dai file dello stesso tipo (video o audio) visibili nell'elenco quando si avvia la
riproduzione, filtro di ricerca compreso.

Il volume è quello di sistema della TV: i cambi fatti con il telecomando della TV compaiono sul
telefono entro un secondo. Sui box HDMI con volume fisso (gestito dal televisore via HDMI-CEC) il
telefono mostra solo i tasti −/+/muto, senza cursore.

Durante la riproduzione (non in pausa) la TV tiene lo schermo acceso: niente salvaschermo né
standby per inattività, che prima mettevano in pausa il video dopo circa 20 minuti.

## Log di diagnostica

Entrambe le app scrivono i propri log su file (rotazione, massimo ~5 MB per app) e li esportano
come ZIP:

| Dove | Come | Risultato |
|---|---|---|
| TV | Pannello › «Esporta log» › Salva in Download | `Download/ManagerTV/managertv-tv-log-<data>.zip` |
| TV | Pannello › «Esporta log» › Salva nella cartella aperta | ZIP nella cartella del disco USB aperta (se scrivibile) |
| TV | Pannello › «Esporta log» › Condividi… | Invio a un'app della TV (email, Drive, Bluetooth…) |
| Telefono | Menu ⋮ › «Esporta log (TV + telefono)» | Un unico ZIP con `tv/` e `telefono/`, salvato dove si sceglie |

Il pacchetto contiene `info.txt` (dispositivo, memoria, stato di server e player), i file di log
ruotati, `crash.log` (eccezioni fatali) e un'istantanea finale di logcat; il player registra ogni
evento (tag `PlaybackController` e `PlayerEvents`). Il telefono scarica i log della TV da
`GET /api/logs?id=<id TV>` (solo rete locale, id ricevuto nel saluto WebSocket).

## Struttura

| Modulo | Contenuto |
|---|---|
| `common` | Protocollo JSON/WebSocket, beacon UDP, ricerca condivisa, adattatore dell'elenco, dialog Info / Crediti, log su file ed esportazione ZIP, tema, icone |
| `app-tv` | Player integrato Media3 con miniatura, posizioni salvate su SQLite, server NanoHTTPD/WebSocket, annuncio NSD + beacon UDP, servizio in primo piano, accesso ai dischi (POSIX + SAF), avvio dei player, UI Leanback |
| `app-mobile` | Ricerca della TV (NSD + UDP), client OkHttp con riconnessione automatica, UI telecomando |

## Compilazione

Requisiti: JDK 17+ (testato con 21), Android SDK con platform 36 (`local.properties` → `sdk.dir`).

```bash
./gradlew distApk                       # release di entrambe le app → build/outputs/apk/
./gradlew :common:testDebugUnitTest     # test unitari
./gradlew assembleDebug                 # APK di debug
```

`distApk` produce in `build/outputs/apk/`:

- `app-tv-release.apk`, `app-mobile-release.apk`
- `ManagerTV-app-tv-<versione>.apk`, `ManagerTV-app-mobile-<versione>.apk`

Le release sono firmate con la chiave di debug locale (app per installazione manuale).
`gradle.properties` è impostato per un PC con poche risorse: niente daemon né build parallele.

## Versione

Unica per le due app, in `version.properties` (SemVer):

- **MAJOR**: modifiche non retrocompatibili, in particolare un cambio di `Protocol.VERSION`
  (TV e telefono con MAJOR diverse non si parlano);
- **MINOR**: nuove funzionalità retrocompatibili;
- **PATCH**: correzioni e piccola manutenzione.

`versionCode` va incrementato di 1 a ogni rilascio.

## Installazione e primo avvio

```bash
./installa-tv.sh <tv>          # es. ./installa-tv.sh 192.168.1.50:5555 (ADB via rete o USB)
adb -s <telefono> install build/outputs/apk/app-mobile-release.apk
```

`installa-tv.sh` installa l'app TV, le concede i permessi (lettura, «Accesso a tutti i file»,
«Mostra sopra altre app»), la esclude dall'ottimizzazione della batteria e la avvia una volta:
sulla TV non serve nessun passaggio manuale. Il primo avvio è indispensabile perché Android
tiene «ferma» un'app appena installata finché non viene aperta.

Sul telefono basta aprire Manager TV Remote sulla stessa rete Wi-Fi: la TV viene trovata,
collegata e ricordata da sola.

Da allora il server della TV resta in ascolto: riparte da solo all'accensione (anche dopo aver
staccato la spina), dopo ogni aggiornamento e se il sistema lo chiude. «Apri l'app sulla TV» sul
telefono attende la TV fino a un minuto (il tempo di avvio dopo l'accensione) e la porta in primo
piano, senza codici né conferme. Non riparte se è stato spento con «Spegni server ed esci» nel
pannello (fino alla successiva apertura dell'app).

Facoltativo: se il firmware della TV blocca l'avvio automatico delle app, il menu ⋮ del telefono
offre «Abbina la TV»: abbinamento una tantum con il servizio di controllo remoto di Android TV
(la TV mostra un codice da digitare sul telefono, generato dal sistema della TV e non leggibile
dalle app), poi usato come ripiego per aprire l'app.

Installazione manuale senza script: `adb install` dell'APK, poi sulla TV aprire Manager TV,
concedere lettura e «Accesso ai file» e abilitare «Avvio dal telefono»; oppure:

```bash
adb shell appops set --uid com.icarocomix.icaromanagertv.tv MANAGE_EXTERNAL_STORAGE allow
adb shell appops set com.icarocomix.icaromanagertv.tv SYSTEM_ALERT_WINDOW allow
```

## Rete

| Porta | Uso |
|---|---|
| TCP 47810 (o libera) | HTTP/WebSocket della TV (`/ws`, `/api/info`, `/api/logs`) |
| UDP 47811 | Beacon della TV e sonde del telefono (broadcast) |
| mDNS | Servizio `_icaromgrtv._tcp` |
| TCP 6466 / 6467 (sistema della TV) | Servizio di controllo remoto di Android TV (`_androidtvremote2._tcp`): comandi / abbinamento, usati dal telefono per aprire l'app |

## Grafica

La sorgente dell'icona è `art/icona.png`. Dopo averla sostituita si rigenerano icona adattiva
(primo piano e monocromo per le icone a tema, `common/src/main/res/drawable-<densità>`), logo
(`drawable-nodpi/logo_manager_tv.png`) e banner TV:

```bash
python3 -I art/genera_icone.py
python3 -I art/genera_banner.py app-tv/src/main/res/drawable-xhdpi/tv_banner.png
```

Una sorgente più grande (512 o 1024 px) rende l'icona più nitida sugli schermi ad alta densità.

## Crediti

Applicazione creata da **IcaroComix** (schermata Info / Crediti delle due app).
