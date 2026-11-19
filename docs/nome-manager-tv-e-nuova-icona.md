# Nuovo nome «Manager TV» e nuova icona (v1.8.0)

## Obiettivo

- Rinominare l'applicazione da «IcaroManagerTV» a **Manager TV** (app TV) e **Manager TV Remote**
  (app smartphone), togliendo «Icaro» dal nome: IcaroComix compare solo nei crediti.
- Adottare come icona dell'applicazione l'immagine fornita (`icona.png`): telecomando blu con
  onde Wi-Fi su piastra bianca arrotondata, 252×256 px.

## Decisioni

### Cosa cambia nome e cosa no
Cambia tutto ciò che l'utente vede:
- nomi delle app nel launcher, nella barra del telefono, nell'intestazione della TV, nella
  notifica del servizio e nei messaggi;
- intestazione della TV senza la riga «di IcaroComix» (resta nei crediti, con logo aziendale);
- banner Android TV;
- cartella e file dei log esportati (`Download/ManagerTV`, `managertv-<origine>-log-<data>.zip`);
- APK di rilascio (`ManagerTV-app-<modulo>-<versione>.apk`), nome del progetto Gradle;
- prefisso del servizio annunciato in mDNS («Manager TV · <nome TV>»), visibile nei browser di rete;
- nome con cui il telefono si presenta alla TV nell'abbinamento facoltativo e soggetto del suo
  certificato.

Restano invariati, perché non visibili e legati alla continuità:
- identificativi dei pacchetti (`com.icarocomix.icaromanagertv.tv` / `.mobile`): cambiarli
  renderebbe le nuove versioni app diverse, senza aggiornamento in place, con perdita di
  permessi concessi, preferenze, posizioni di ripresa e abbinamenti;
- firma dei datagrammi UDP del protocollo e tipo di servizio mDNS: cambiarli impedirebbe a
  telefono e TV di versioni diverse di trovarsi;
- schema del deep link (`icaromanagertv://open`), alias della chiave nell'AndroidKeyStore (la
  chiave già generata resta valida), nomi interni di stili e lock di rete.

Il telefono riconosce sia il prefisso mDNS nuovo sia quello delle TV precedenti alla v1.8, quindi
la versione è compatibile all'indietro (MINOR).

### Icona
La sorgente è un'icona già composta, mentre Android (minSdk 26) usa icone adattive: fondo e primo
piano separati, ritagliati dalla maschera del launcher (cerchio, squircle…), più un livello
monocromatico per le icone a tema di Android 13+. Scelte:
- **fondo**: bianco, come la piastra della sorgente;
- **primo piano**: solo il disegno (telecomando e onde), estratto con una conversione "colore
  verso trasparenza" rispetto al bianco: ogni pixel diventa il colore che, composto sul bianco,
  ridà esattamente l'originale, così bagliori e ombre restano semitrasparenti e l'icona sul
  fondo bianco coincide con la sorgente. Il disegno è alto 60 dp su 108, dentro il cerchio di
  sicurezza che tutte le maschere mostrano;
- **monocromo**: la sagoma del disegno, con il corpo del telecomando pieno, l'ombra eliminata e
  i dettagli chiari (accensione, play) come trafori;
- **logo** nell'interfaccia (intestazione della TV, crediti): la sorgente con gli angoli esterni
  alla piastra resi trasparenti;
- **banner TV**: logo e scritta «Manager TV» sul gradiente blu del tema.

Le risorse sono PNG per densità generati da uno script (`art/genera_icone.py`) a partire dalla
sorgente copiata in `art/icona.png`: sostituendo la sorgente si rigenera tutto con un comando.
Alternativa scartata: ridisegnare l'icona come vettoriale, che avrebbe reinterpretato il disegno
fornito.

## Scoperte e correzioni durante lo sviluppo

- La sorgente non ha trasparenza: fuori dalla piastra c'è un fondo grigio-azzurro chiaro e la
  piastra ha bordo e ombra leggeri. La prima estrazione li scambiava per disegno (riquadro
  largo quanto l'immagine): ora il disegno si cerca solo all'interno della piastra bianca,
  rientrando dagli angoli arrotondati.
- Il primo piano risultava sbiadito per due motivi, individuati confrontando anteprime con la
  sorgente: il colore era calcolato dopo aver ridotto l'opacità, e l'incollaggio con maschera su
  un livello trasparente moltiplica l'opacità per sé stessa. Il colore ora usa l'opacità piena
  e la composizione usa l'alpha compositing.
- Nel monocromo l'ombra del telecomando, amplificata, produceva un alone: le opacità basse sono
  ora azzerate.
- La sorgente è piccola: per il livello a densità più alta il disegno viene ingrandito di circa
  1,4 volte. Una sorgente da 512 o 1024 px darebbe un'icona più nitida.

## Riepilogo finale

**Consegnato**: nuovo nome visibile in entrambe le app, IcaroComix solo nei crediti, nuova icona
adattiva con livello monocromatico, nuovo logo nell'interfaccia e nei crediti, nuovo banner TV,
script di rigenerazione, compatibilità della ricerca in rete con le TV precedenti.

**Limiti noti**
- Le vecchie risorse vettoriali dell'icona e del logo (`ic_launcher_foreground.xml`,
  `ic_launcher_background.xml`, `ic_launcher_monochrome.xml`, `logo_app.xml` e i colori
  `icon_bg_*`) non sono più usate ma sono state lasciate nel progetto.
- Un telefono precedente alla v1.8 trova una TV v1.8 solo tramite il beacon UDP, non via mDNS.
- I documenti di lavoro precedenti in `docs/` citano il vecchio nome come riferimento storico.

**Build e test**: test unitari `:common` (con il nuovo test sui nomi di servizio mDNS),
`:app-tv:assembleDebug`, `:app-mobile:assembleDebug` e `distApk` (v1.8.0), eseguiti in sequenza.

**File toccati**
- `art/icona.png` (nuovo), `art/genera_icone.py` (nuovo), `art/genera_banner.py`
- `common/src/main/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_art.png`,
  `ic_launcher_art_mono.png` (nuovi), `common/src/main/res/drawable-nodpi/logo_manager_tv.png` (nuovo)
- `common/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`
- `common/src/main/res/values/colors.xml`, `common/src/main/res/layout/dialog_credits.xml`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/Protocol.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/log/AppLogs.kt`
- `common/src/test/java/com/icarocomix/icaromanagertv/common/ProtocolCodecTest.kt`
- `app-tv/src/main/res/drawable-xhdpi/tv_banner.png`, `app-tv/src/main/res/layout/activity_tv.xml`,
  `app-tv/src/main/res/values/strings.xml`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/diagnostics/LogExporter.kt`
- `app-mobile/src/main/res/values/strings.xml`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/discovery/TvDiscovery.kt`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/launch/AtvIdentity.kt`,
  `AtvServiceFinder.kt`, `TvStarter.kt` (commenti)
- `build.gradle.kts`, `settings.gradle.kts`, `installa-tv.sh`, `version.properties`, `README.md`,
  `TODO.md`
