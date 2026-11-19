# Indietro a due livelli, pad del telefono affidabile, avvio a freddo dal telefono, ricerca ricorsiva di default (v1.7.0–1.7.1)

## Obiettivo

1. **Indietro durante la riproduzione (TV)**: con i controlli a schermo, il primo «Indietro» deve
   solo nasconderli; solo con i controlli già nascosti «Indietro» riduce il video a miniatura.
2. **Pad virtuale del telefono**: frecce, OK, Indietro e Home del pad non avevano effetto in
   diverse situazioni, mentre il telecomando fisico funzionava. Devono produrre lo stesso
   effetto del telecomando.
3. **Apertura dell'app TV dal telefono senza dipendere dall'app già avviata**: subito dopo
   l'installazione, e dopo un riavvio a freddo della TV (spina staccata), il pulsante del
   telefono non apriva l'app. Dopo un primo abbinamento i parametri della TV vanno salvati sul
   telefono e l'apertura deve funzionare appena la TV è accesa, con stati e messaggi chiari se
   l'abbinamento manca o la TV non è ancora raggiungibile.
4. **Ricerca nelle sottocartelle attiva di default**, disattivabile dall'utente.

## Analisi delle cause

### Indietro
Il callback di «Indietro» dell'activity TV riduceva sempre a miniatura il video a schermo
intero, senza considerare se i controlli Media3 fossero visibili.

### Pad del telefono
Lato telefono il collegamento dei pulsanti era corretto: ogni tasto inviava il messaggio `key`.
Sulla TV, fuori dall'elenco (player a schermo intero, pannelli, dialog, pannello laterale), il
tasto veniva **iniettato come evento di input di sistema** (`Instrumentation`). Un'app senza il
permesso INJECT_EVENTS può iniettare solo verso le proprie finestre, e quando il sistema
considera a fuoco un'altra finestra (o il produttore restringe l'iniezione) l'evento viene
**scartato senza eccezioni**: il ripiego previsto per gli errori non scattava mai. Il ripiego
stesso, che consegnava l'evento all'activity, non poteva comunque spostare il focus: la
navigazione con le frecce non la fanno le viste ma `ViewRootImpl`, che un evento consegnato a mano
non attraversa.

Il tasto Home del pad inviava il comando «radice dell'elenco»: con il video a schermo intero
cambiava l'elenco nascosto sotto il player, senza effetti visibili.

### Avvio a freddo
Un'app Android appena installata e mai aperta è nello stato «stopped»: non riceve broadcast,
nemmeno BOOT_COMPLETED, e nessun suo componente gira finché l'utente non la apre. Dopo un riavvio
della TV l'app parte solo se il ricevitore di boot (v1.6) viene eseguito, e alcuni firmware lo
ritardano o lo bloccano. In entrambi i casi il server dell'app non ascolta e il telefono non ha
nessuno con cui parlare: nessuna modifica all'app TV può risolverlo da sola.

## Decisioni

### Indietro a due livelli
Con il video a schermo intero, se i controlli sono a schermo (animazioni comprese) «Indietro» li
nasconde; altrimenti riduce a miniatura come prima. I pannelli impostazioni/sottotitoli sono
finestre separate e si chiudono da soli con «Indietro», senza arrivare al callback.

### Consegna diretta dei tasti del pad
L'iniezione di sistema è sostituita da una **consegna nel processo**: il tasto va alla radice
della finestra dell'app che ha il focus (activity, dialog o popup di Media3), trovata con
l'elenco pubblico delle finestre del processo (`WindowInspector`, Android 10+). Il lavoro di
`ViewRootImpl` che manca viene replicato:
- le frecce non consumate da nessuna vista spostano il focus con la ricerca direzionale standard
  (`focusSearch`), passando il rettangolo di partenza come fa il sistema; senza focus si
  ripristina quello predefinito;
- «Indietro» per activity e dialog dell'app passa dal loro `OnBackPressedDispatcher`, l'unica
  strada valida anche con il ritorno predittivo (attivo di default con targetSdk 36), che non
  passa più da `onKeyUp`; i popup di Media3 gestiscono «Indietro» nel proprio `dispatchKeyEvent`;
- in touch mode la vista con il focus esce dal touch mode come con un tasto vero.

Su Android 8–9, senza `WindowInspector`, si cercano activity e dialog noti; se nessuno ha il
focus (popup di Media3) si ricorre ancora all'iniezione di sistema. I dialog dell'activity sono
tracciati per ricevere «Indietro» dal dispatcher.

Alternativa scartata: chiamare `InputManager.injectInputEvent` per riflessione per conoscere
l'esito dell'iniezione (API nascosta, comportamento diverso tra versioni e produttori).

### Tasto Home del pad
Nuovo comando `ui` **HOME** (retrocompatibile: una TV precedente lo scarta). Sulla TV: video a
schermo intero ridotto a miniatura (non fermato), elenco riportato agli archivi con ricerca
azzerata, eventuale dialog chiuso e focus sull'elenco; con l'app non visibile, la porta in primo
piano come «Apri l'app sulla TV».

### Avvio a freddo: servizio di controllo remoto di Android TV
Le TV Android TV / Google TV certificate hanno un servizio di sistema, «Android TV Remote Service»
(protocollo v2, quello dell'app Google TV), attivo dall'accensione e indipendente dalle app
installate. Può aprire un'app tramite link anche quando questa è nello stato «stopped», perché
l'avvio esplicito di un'activity non è soggetto a quella restrizione. Il telefono lo usa così:

- **Abbinamento una tantum** (porta 6467, TLS con certificato client): la TV mostra un codice
  esadecimale di 6 caratteri; il telefono invia l'hash SHA-256 che lega codice e chiavi
  pubbliche di telefono e TV. Il primo byte dell'hash coincide con i primi due caratteri del
  codice: il telefono verifica il codice prima di inviarlo, così un errore di battitura non
  consuma il tentativo sulla TV.
- **Persistenza** sul telefono: indirizzo IP della TV, nome con cui annuncia il servizio in mDNS
  (`_androidtvremote2._tcp`), impronta SHA-256 del suo certificato. La chiave privata del
  telefono resta nell'AndroidKeyStore e non viene mai esportata. L'ultima TV collegata
  all'app (id, nome, IP, porta) era già salvata dal client.
- **Apertura** (porta 6466): presentazione del telefono, attivazione della sessione, eventuale
  risveglio dallo standby (tasto WAKEUP) e richiesta di apertura del deep link
  `icaromanagertv://open`, che la TV dichiara sulla sua activity principale. Se entro 15 s l'app
  non risponde al telefono si prova `market://launch?id=<pacchetto>` (apertura tramite il Play
  Store), poi si segnala il problema.
- **Sicurezza**: all'abbinamento il certificato autofirmato della TV è accettato perché il codice
  ne vincola la chiave pubblica; da allora il certificato è fissato e qualunque altro viene
  rifiutato (TV ripristinata, dispositivo diverso). Il deep link apre solo l'interfaccia, senza
  parametri.

Il protobuf necessario (pochi messaggi) è codificato a mano in `:common`, senza la libreria
protobuf né il generatore di codice; formato e segreto sono coperti da test unitari.

**Gestione degli stati** sul telefono, guidata da un'unica macchina a stati mostrata come dialog:
- app TV in esecuzione → comando diretto come in v1.6 (nessun abbinamento necessario);
- nessun abbinamento → spiegazione e proposta di abbinamento, con scelta della TV se in rete ce
  ne sono più d'una (trovate via mDNS; altrimenti l'ultima TV nota);
- al primo collegamento riuscito con l'app TV l'abbinamento viene proposto una volta sola;
- TV in avvio o non ancora in rete → nuovi tentativi ogni 3 s per un minuto, con il messaggio
  «la TV non risponde ancora (forse si sta avviando)» e il numero del tentativo; ogni tre
  tentativi la TV viene ricercata di nuovo in mDNS, perché dopo un riavvio può aver cambiato IP
  (il nuovo indirizzo viene salvato);
- esiti distinti per: TV non in rete, servizio della TV che rifiuta il collegamento (in avvio o
  assente sul modello), TV che non riconosce più il telefono o ha cambiato identità (con proposta
  di rifare l'abbinamento), codice sbagliato (stessa sessione), codice rifiutato o scaduto (la
  TV ne mostra uno nuovo);
- ogni operazione è annullabile; lo stato vive nel ViewModel e sopravvive alle rotazioni.

Alternative scartate:
- *Wake-on-LAN*: accende (se supportato) ma non apre un'app;
- *ADB in rete*: richiede le opzioni sviluppatore e un'autorizzazione non adatta all'uso
  domestico;
- *DIAL / Cast*: non avvia app arbitrarie non registrate presso Google;
- *solo ricevitore di boot*: resta (v1.6) ma non copre l'app mai avviata né i firmware che lo
  bloccano.

### Revisione (v1.7.1): apertura senza nessuna azione dell'utente
Requisito aggiunto: l'utente non deve fare nulla, nemmeno digitare un codice.

**Il codice di abbinamento non è generabile né leggibile dalle app.** Lo crea a caso, a ogni
abbinamento, il servizio di sistema di Google sulla TV, che lo mostra in una propria finestra di
sistema; il segreto da inviare dipende da quel codice e dalla chiave della TV. È la protezione
che impedisce a qualunque dispositivo in rete di prendere il controllo del televisore senza che
qualcuno lo guardi. L'unica via per leggerlo sarebbe un servizio di accessibilità sulla TV, che
va comunque attivato a mano dall'utente ed è fragile tra un firmware e l'altro: scartata.

**Il riconoscimento reciproco senza codice c'è già, tra telefono e app TV**: al primo collegamento
il telefono memorizza id, nome, IP e porta della TV. Basta quindi che l'app TV sia sempre in
ascolto, cosa che dipende da due vincoli di Android, entrambi risolti una sola volta
all'installazione via ADB (la procedura già prevista):
- un'app appena installata è «ferma» finché non viene aperta: lo script `installa-tv.sh` la
  avvia una volta;
- per portarsi in primo piano da background serve «Mostra sopra altre app»: lo script la
  concede insieme ai permessi di lettura e all'accesso a tutti i file, ed esclude l'app
  dall'ottimizzazione della batteria.

Modifiche:
- TV: il servizio è ora `START_STICKY` (ricreato se il sistema chiude il processo; se non ottiene
  il primo piano resta comunque in ascolto finché il processo vive) e il ricevitore di avvio
  risponde anche all'aggiornamento dell'app e alle «accensioni rapide» di alcuni produttori;
- telefono: «Apri l'app sulla TV» senza collegamento attende fino a un minuto l'ultima TV nota,
  sollecitando ricerca e collegamento ogni 3 s, poi le chiede di portarsi in primo piano. Se il
  telefono non ha mai visto la TV lo dice, indicando come rimediare;
- l'abbinamento con codice non viene più proposto in automatico: resta una voce di menu
  facoltativa per i firmware che bloccano l'avvio automatico delle app e, se presente, è usato
  come ripiego dopo 10 s di attesa.

### Ricerca ricorsiva di default
Il default dello stato condiviso passa a «attiva» (anche nella decodifica di messaggi senza il
campo), le caselle di TV e telefono partono selezionate e la TV memorizza la scelta dell'utente
nelle proprie preferenze: chi la disattiva la ritrova disattivata al riavvio.

## Piano

1. TV: «Indietro» a due livelli nel callback dell'activity.
2. TV: componente di consegna dei tasti con navigazione del focus e «Indietro» via dispatcher;
   tracciamento dei dialog dell'activity.
3. Protocollo: comando `ui` HOME; TV: esecuzione nel controller (miniatura, radice) e
   nell'activity (focus, dialog); telefono: tasto Home del pad.
4. TV: deep link sull'activity principale.
5. `:common`: codifica protobuf minima, messaggi di abbinamento e comandi, calcolo del segreto,
   test.
6. Telefono: identità nell'AndroidKeyStore e TLS con certificato fissato, sessioni di
   abbinamento e apertura, ricerca mDNS del servizio, orchestrazione con persistenza, dialog e
   voce di menu.
7. Ricerca ricorsiva di default e preferenza persistente.
8. Versione 1.7.0 (versionCode 9), README, build sequenziale.

## Scoperte e correzioni durante lo sviluppo

- L'esito di `Instrumentation.sendKeyDownUpSync` non è osservabile: l'API di sistema restituisce
  un booleano che `Instrumentation` ignora, e l'eccezione arriva solo per il permesso negato.
  Per questo il ripiego della v1.5 non scattava e il pad risultava muto: da qui la scelta della
  consegna nel processo invece di un ripiego migliore.
- Con targetSdk 36 il ritorno predittivo è attivo di default sui dispositivi Android 16: un
  «Indietro» consegnato a mano non raggiungerebbe `onBackPressed` né la chiusura dei dialog.
  Activity e dialog ricevono quindi «Indietro» direttamente dal loro dispatcher.
- Nell'abbinamento la TV conferma la configurazione con un messaggio annidato vuoto: la
  decodifica deve considerare presente un campo di lunghezza zero (coperto da test).
- Mentre l'utente legge e digita il codice il canale di abbinamento resta inattivo: il timeout
  di lettura viene tolto dopo la conferma della configurazione.
- Con TLS 1.3 il rifiuto del certificato del telefono (telefono non abbinato) arriva alla prima
  lettura e non durante l'handshake: un errore prima della presentazione della TV vale quindi
  come «telefono non riconosciuto».

## Riepilogo finale

**Consegnato**
- «Indietro» a due livelli sul video a schermo intero.
- Pad del telefono consegnato alla finestra con il focus, con navigazione del focus e «Indietro»
  equivalenti al telecomando; Home del pad come schermata principale dell'app sulla TV.
- Apertura dell'app TV dal telefono senza azioni dell'utente (v1.7.1): app TV sempre in ascolto
  (installazione con `installa-tv.sh`, riavvio all'accensione, dopo gli aggiornamenti e se il
  sistema la chiude), attesa della TV appena accesa e messaggi distinti per ogni causa di errore.
- Ripiego facoltativo tramite il servizio di controllo remoto di Android TV, con abbinamento
  persistente, ricerca della TV anche se cambia IP e risveglio dallo standby.
- Ricerca nelle sottocartelle attiva di default, con preferenza memorizzata dalla TV.

**Limiti noti**
- Installata senza lo script, l'app va aperta una volta sulla TV (Android la tiene «ferma» fino
  ad allora) e serve abilitare «Avvio dal telefono».
- Alcuni firmware bloccano l'avvio automatico delle app di terze parti: lì serve il ripiego
  facoltativo, che richiede l'abbinamento con codice e una TV con il servizio Android TV Remote.
- Con la TV completamente spenta (non in standby di rete) nessun comando via rete può
  raggiungerla: va accesa con il telecomando o dal pulsante.
- Su Android 8–9 i tasti del pad verso i popup di Media3 passano ancora dall'iniezione di
  sistema.
- Il tasto Home del pad non ha effetto con una TV ancora alla v1.6.
- Comportamento verificato con build e test unitari; resta da provare sull'hardware reale:
  «Indietro» a controlli visibili/nascosti, pad del telefono su controlli Media3, pannelli,
  dialog e pannello laterale, abbinamento e apertura a freddo (app mai avviata, TV riaccesa
  staccando la spina, IP cambiato), apertura tramite deep link sui diversi firmware.

**Build e test**
- Test unitari `:common`: tutti superati (nuova suite del protocollo Android TV Remote, test
  della ricerca ricorsiva di default e del comando HOME).
- `:app-tv:assembleDebug`, `:app-mobile:assembleDebug` e `distApk` (v1.7.0 e v1.7.1) riusciti,
  eseguiti in sequenza.

**File toccati**
- `common/src/main/java/com/icarocomix/icaromanagertv/common/atv/Proto.kt` (nuovo)
- `common/src/main/java/com/icarocomix/icaromanagertv/common/atv/AtvRemoteProtocol.kt` (nuovo)
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/Protocol.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/Messages.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/ProtocolCodec.kt`
- `common/src/test/java/com/icarocomix/icaromanagertv/common/AtvRemoteProtocolTest.kt` (nuovo)
- `common/src/test/java/com/icarocomix/icaromanagertv/common/ProtocolCodecTest.kt`
- `common/src/test/java/com/icarocomix/icaromanagertv/common/PlaybackProtocolTest.kt`
- `app-tv/src/main/AndroidManifest.xml`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/ui/RemoteKeyDelivery.kt` (nuovo)
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/ui/TvMainActivity.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/controller/BrowserController.kt`
- `app-tv/src/main/res/layout/activity_tv.xml`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/launch/AtvIdentity.kt` (nuovo)
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/launch/AtvRemoteSession.kt` (nuovo)
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/launch/AtvServiceFinder.kt` (nuovo)
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/launch/TvStarter.kt` (nuovo)
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/client/RemoteClient.kt`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/ui/MainActivity.kt`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/ui/MainViewModel.kt`
- `app-mobile/src/main/res/layout/activity_main.xml`
- `app-mobile/src/main/res/layout/view_pad.xml`
- `app-mobile/src/main/res/menu/main.xml`
- `app-mobile/src/main/res/values/strings.xml`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/service/RemoteServerService.kt` (v1.7.1)
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/service/BootReceiver.kt` (v1.7.1)
- `installa-tv.sh` (nuovo, v1.7.1)
- `version.properties`, `README.md`, `TODO.md`
