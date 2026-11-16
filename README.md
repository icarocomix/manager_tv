# IcaroManagerTV

File manager per Android TV con telecomando da smartphone, di **IcaroComix**.

- **IcaroManagerTV** (app TV): sfoglia i dischi USB collegati al televisore e riproduce video e
  musica con il player integrato (Media3 ExoPlayer): miniatura in basso a destra mentre si sfoglia,
  ripresa dal punto interrotto (con barra della parte già vista nelle righe dell'elenco, anche
  sul telefono), file precedente/successivo e riproduzione automatica della cartella. Foto, altri formati e codec non decodificabili passano al player installato (VLC,
  MX Player, Kodi…).
- **IcaroManagerTV Remote** (app smartphone): trova da sola la TV nella rete Wi-Fi, rispecchia in
  tempo reale cartelle e selezione, cerca con la tastiera del telefono, avvia i file sulla TV,
  offre un pad direzionale e una schermata Player (pausa, salti, inizio/fine, precedente/successivo,
  autoplay, miniatura/schermo intero sulla TV, volume della TV) con mini-barra per tornarci dal
  file manager.

Architettura, decisioni e limiti: [docs/icaromanagertv-architettura.md](docs/icaromanagertv-architettura.md);
player integrato, miniatura, ripresa e playlist: [docs/player-integrato-playlist-resume.md](docs/player-integrato-playlist-resume.md);
barra di avanzamento nelle righe: [docs/avanzamento-righe-elenco.md](docs/avanzamento-righe-elenco.md);
schermo sempre acceso durante la riproduzione ed esportazione dei log: [docs/schermo-acceso-ed-esportazione-log.md](docs/schermo-acceso-ed-esportazione-log.md);
volume della TV dal telefono: [docs/volume-tv-dal-telefono.md](docs/volume-tv-dal-telefono.md).

## Uso del player

| Dove | Azione | Effetto |
|---|---|---|
| TV, schermo intero | Indietro | Video in miniatura, file manager navigabile (il video continua) |
| TV, miniatura | OK sulla miniatura o «Torna al player» | Schermo intero |
| TV | «Chiudi il player» / tasto Stop | Ferma e salva la posizione |
| TV | Tasti multimediali del telecomando | Play/pausa, avanti/indietro, successivo/precedente in ogni vista |
| Telefono, Player | «File manager» o Indietro | Torna all'elenco senza toccare la TV |
| Telefono, file manager | Mini-barra in basso | Torna al Player (pulsante play/pausa diretto) |
| Telefono, Player | «Miniatura sulla TV» / «Schermo intero sulla TV» | Cambia la disposizione sulla TV |
| Telefono, Player | Muto, −, cursore, + (riga «Volume della TV») | Volume di sistema della TV (indicatore visibile sulla TV) |
| Telefono, Player | Tasti volume del telefono | Alzano/abbassano il volume della TV (scollegato: volume del telefono) |

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
| TV | Pannello › «Esporta log» › Salva in Download | `Download/IcaroManagerTV/icaromanagertv-tv-log-<data>.zip` |
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
- `IcaroManagerTV-app-tv-<versione>.apk`, `IcaroManagerTV-app-mobile-<versione>.apk`

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
adb -s <tv> install build/outputs/apk/app-tv-release.apk
adb -s <telefono> install build/outputs/apk/app-mobile-release.apk
```

1. Sulla TV apri IcaroManagerTV e concedi il permesso di lettura; per vedere anche i file non
   multimediali concedi «Accesso a tutti i file» (pulsante «Accesso ai file»).
2. Sul telefono apri IcaroManagerTV Remote sulla stessa rete Wi-Fi: la TV viene trovata e
   collegata da sola.
3. Per avviare file dal telefono mentre sulla TV è aperto il player, abilita «Avvio dal telefono»
   (permesso «Mostra sopra altre app»).

Se la TV non offre le schermate dei permessi:

```bash
adb shell appops set --uid com.icarocomix.icaromanagertv.tv MANAGE_EXTERNAL_STORAGE allow
adb shell appops set --uid com.icarocomix.icaromanagertv.tv SYSTEM_ALERT_WINDOW allow
```

## Rete

| Porta | Uso |
|---|---|
| TCP 47810 (o libera) | HTTP/WebSocket della TV (`/ws`, `/api/info`, `/api/logs`) |
| UDP 47811 | Beacon della TV e sonde del telefono (broadcast) |
| mDNS | Servizio `_icaromgrtv._tcp` |

## Grafica

Icona adattiva e logo sono vettoriali (`common/src/main/res/drawable`). Il banner TV si rigenera con:

```bash
python3 -I art/genera_banner.py app-tv/src/main/res/drawable-xhdpi/tv_banner.png
```
