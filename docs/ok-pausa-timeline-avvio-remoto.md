# OK = pausa, Giù = timeline, apertura dell'app e pannelli del player dal telefono (v1.6.0)

## Obiettivo

1. **Tasto OK durante la riproduzione (TV)**: premendo OK sul video a schermo intero l'app
   fermava la riproduzione e chiudeva il player. Deve invece alternare pausa e riproduzione come
   sulle principali piattaforme di streaming: in riproduzione mette in pausa e mostra i
   controlli, in pausa riprende.
2. **Freccia Giù (TV)**: durante la riproduzione deve mostrare i controlli con il focus sulla
   barra temporale, da cui raggiungere sottotitoli, tracce audio e impostazioni.
3. **Avvio remoto**: il telefono deve poter avviare l'app sulla TV e portarla in primo piano.
4. **Impostazioni e Sottotitoli sul telefono**: due comandi nella schermata Player del telefono
   che agiscono in tempo reale sul player della TV, aprendo i pannelli corrispondenti.

## Analisi

### Perché OK chiudeva il player

Il player integrato è sovrapposto al file manager, che resta disposto sotto il video. Con i
controlli Media3 visibili il focus è su uno dei loro pulsanti; quando i controlli si nascondono
la vista con il focus diventa invisibile e Android riassegna il focus alla prima vista
selezionabile della finestra in ordine di albero, cioè il pulsante «Chiudi il player» del
pannello laterale (visibile proprio quando un file è aperto). Il tasto OK successivo premeva quel
pulsante: comando STOP, salvataggio della posizione e chiusura del player. Per lo stesso motivo
le frecce, a controlli nascosti, spostavano il focus nel file manager invisibile invece di
richiamare i controlli del player.

La gestione dei tasti di `PlayerView` (che a controlli nascosti mostra i controlli su qualunque
tasto direzionale) non entrava mai in gioco, perché il focus non era più dentro il player.

### Pannelli di Media3

I controlli Media3 1.10 contengono già il pulsante impostazioni (`exo_settings`: tracce audio,
velocità) e quello dei sottotitoli (`exo_subtitle`, abilitato solo con tracce di testo
supportate). Entrambi aprono un popup ancorato al pulsante, con il focus: una finestra propria
dell'app, raggiungibile sia dal telecomando sia dai tasti del pad del telefono iniettati come
eventi di input.

### Avvio da background

Il server della TV vive in un servizio in primo piano che sopravvive alla chiusura
dell'activity, quindi il telefono può parlare con la TV anche senza interfaccia aperta. Da
Android 10 un'app in background non può però avviare activity (il sistema scarta l'avvio senza
errori); l'eccezione prevista è il permesso «Mostra sopra altre app», già usato dalla TV per
avviare il player su comando del telefono. Da Android 15, per le app che puntano a quella
versione, l'eccezione vale solo se l'app ha una finestra sovrapposta effettivamente visibile.
Il servizio, inoltre, non ripartiva dopo il riavvio della TV: senza aprire l'app a mano il
telefono non trovava nulla.

## Decisioni

- **Focus del file manager bloccato a schermo intero**: con il video a schermo intero il
  contenitore del file manager blocca il focus dei discendenti; in miniatura e a player chiuso
  torna normale. Scartata la sola correzione del tasto OK: le frecce avrebbero continuato a
  navigare un'interfaccia invisibile.
- **Tasti intercettati a livello di activity a controlli nascosti**: con i controlli nascosti
  nessuna vista del player ha il focus, quindi la decisione avviene in `dispatchKeyEvent`
  dell'activity, prima della consegna alle viste:
  - OK / Invio: alterna play/pausa tramite lo stesso comando del telefono (stato condiviso); in
    pausa mostra i controlli con il focus su Play/Pausa (Media3 li tiene poi visibili a tempo
    indeterminato, essendo in pausa); alla ripresa il video resta libero. Le ripetizioni del
    tasto tenuto premuto sono ignorate.
  - Giù: mostra i controlli con il focus sulla barra temporale; un ulteriore Giù raggiunge la
    fila con sottotitoli e impostazioni (navigazione standard dei controlli Media3).
  - Su / Sinistra / Destra: mostra i controlli con il focus su Play/Pausa (comportamento
    predefinito di Media3, ora garantito).
  - Il rilascio di un tasto gestito viene assorbito: un rilascio isolato di OK sul pulsante
    appena focalizzato varrebbe come clic.
  - Con i controlli a schermo (anche durante le animazioni) l'activity non interviene: il tasto
    va al controllo con il focus.
  - Scartata la scelta di saltare ±10 s con Sinistra/Destra a controlli nascosti: non richiesta,
    e la barra temporale focalizzata offre già lo scorrimento.
- **Nuovo messaggio `ui` del protocollo** (telefono → TV) con tre comandi: porta in primo piano,
  impostazioni del player, sottotitoli del player. È retrocompatibile (un server precedente lo
  scarta come sconosciuto), quindi `Protocol.VERSION` resta invariato → versione MINOR.
  Scartato l'uso di nuove azioni di trasporto (`PlayerAction`): non riguardano il file in
  riproduzione ma l'interfaccia, e devono poter aprire l'app.
- **Pannelli aperti sulla TV, non replicati sul telefono**: il comando simula il clic sul
  pulsante Media3 corrispondente; lo stato (traccia audio, sottotitolo scelto, velocità) resta
  uno solo, sulla TV. Il telefono, dopo l'invio, mostra il pad per scegliere le voci del
  pannello. Scartato un selettore nativo sul telefono con l'elenco delle tracce: richiederebbe
  di trasmettere e mantenere sincronizzate le tracce e le scelte, con doppia interfaccia.
- **Disponibilità dei sottotitoli nello stato del player**: nuovo campo `subtitles` di
  `playback` (tracce di testo supportate dal player), ricalcolato a ogni cambio di tracce; il
  pulsante del telefono è disattivato quando il file non ne ha. Un server precedente non invia
  il campo: il pulsante resta disattivato.
- **Casi limite dei pannelli sulla TV**: senza player attivo, con la domanda di ripresa aperta
  o senza sottotitoli la TV risponde con un messaggio (toast su TV e telefono); con il video in
  miniatura passa prima a schermo intero e apre il pannello a layout applicato; se il pulsante
  non è disponibile tra i controlli lo segnala invece di fallire in silenzio.
- **Apertura dell'app da remoto**:
  - con l'interfaccia visibile il comando non fa nulla;
  - altrimenti la TV avvia l'activity principale (singleTask: un'eventuale istanza esistente
    torna in primo piano). Se il permesso di sovrapposizione è concesso, prima dell'avvio viene
    aggiunta per poco più di un secondo una finestra overlay di 1×1 pixel trasparente e non
    interattiva, che soddisfa la condizione di Android 15;
  - dopo 3 s la TV verifica di essere davvero in primo piano; in caso contrario invia un
    messaggio che indica la causa (permesso mancante o blocco del sistema);
  - un comando di pannello arrivato con l'app chiusa la apre e viene applicato all'avvio
    dell'interfaccia, solo se ricevuto da meno di 15 s (chi apre l'app a mano più tardi non deve
    trovarsi un pannello aperto da solo).
  - Scartati protocolli di terze parti (DIAL, protocollo del telecomando Android TV con
    accoppiamento, ADB in rete): complessi, non disponibili su tutte le TV o con implicazioni di
    sicurezza; il canale WebSocket esistente è già autenticato dalla scoperta in rete locale.
- **Server riavviato all'accensione della TV**: ricevitore di `BOOT_COMPLETED` che avvia il
  servizio in primo piano solo se l'utente l'aveva lasciato acceso; il pulsante «Spegni server
  ed esci» lo disattiva fino alla successiva apertura dell'app. L'avvio di un servizio
  `specialUse` dal boot è ammesso anche da Android 15.

## Architettura

- `common`: enum `UiCommand` (front / settings / subtitles), messaggio `ClientMessage.Ui`
  (`{"t":"ui","cmd":…}`), campo `subs` nello stato `playback`.
- TV, activity: intercettazione dei tasti a schermo intero, blocco del focus del file manager,
  destinatario dei comandi `ui` registrato in `onStart` e rimosso in `onStop` (come quello dei
  tasti del pad), pannello in attesa del passaggio a schermo intero.
- TV, controller del file manager: instrada `ui` all'interfaccia visibile oppure avvia
  l'interfaccia tramite il componente di avvio e tiene in attesa il comando di pannello.
- TV, componente di avvio dell'interfaccia: avvio dell'activity da background con finestra
  overlay temporanea e verifica dell'esito.
- TV, servizio: preferenza «server acceso» scritta da apertura dell'app e spegnimento
  esplicito; ricevitore del boot.
- TV, player: disponibilità dei sottotitoli pubblicata nello stato.
- Telefono: pulsanti «Sottotitoli» e «Impostazioni» nella schermata Player (sotto Inizio/Stop/
  Fine), voce «Apri l'app sulla TV» nella barra in alto.

## Scoperte durante lo sviluppo

- La causa della chiusura del player non era nella gestione del tasto OK (nessun codice
  associava OK allo STOP), ma nella riassegnazione automatica del focus da parte di Android a
  una vista coperta dal player: l'analisi dell'ordine delle viste del layout della TV l'ha
  ricondotta al pulsante «Chiudi il player».
- Media3, mostrando i controlli, porta il focus su Play/Pausa in modo sincrono; per focalizzare
  la barra temporale la richiesta va posticipata al passaggio successivo del main thread.
- Il pulsante sottotitoli di Media3 resta visibile ma disabilitato senza tracce di testo: la
  verifica prima del clic usa lo stato di abilitazione.

## Riepilogo

Consegnato:
- TV: OK alterna pausa/riproduzione a controlli nascosti (pausa con controlli visibili), Giù
  porta il focus sulla barra temporale, le altre frecce mostrano i controlli; il focus non
  finisce più nel file manager sotto il video.
- Telefono → TV: apertura dell'app da remoto, pulsanti Sottotitoli e Impostazioni che aprono i
  pannelli del player della TV, pulsante Sottotitoli attivo solo se il file ne ha.
- Server riavviato al boot se lasciato acceso.
- Versione 1.6.0 (versionCode 8).

Limiti noti:
- L'apertura da remoto richiede che il servizio della TV sia attivo (app aperta almeno una
  volta dopo l'installazione, server non spento) e, con un'altra app in primo piano, il
  permesso «Mostra sopra altre app». Alcuni firmware possono comunque bloccare l'avvio: in quel
  caso telefono e TV ricevono un messaggio.
- I pannelli di Media3 sono popup: la scelta delle voci avviene con il pad (telefono) o il
  telecomando, non con un elenco sul telefono.
- Con una TV ancora alla v1.5 il pulsante Impostazioni del telefono non ha effetto e
  Sottotitoli resta disattivato.
- Da verificare sull'hardware reale: comportamento di OK/Giù sui telecomandi che inviano
  `ENTER` invece del centro del D-pad, apertura da remoto con launcher o app di terze parti in
  primo piano (Android 10–14 e 15+), avvio al boot sui firmware dei produttori.

Esito build/test: test `:common` 19/19, `assembleDebug` TV e telefono OK, `distApk` OK
(v1.6.0).

File toccati:
- common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/Protocol.kt
- common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/Messages.kt
- common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/ProtocolCodec.kt
- common/src/test/java/com/icarocomix/icaromanagertv/common/PlaybackProtocolTest.kt
- common/src/main/res/drawable/ic_subtitles.xml (nuovo)
- common/src/main/res/drawable/ic_settings.xml (nuovo)
- common/src/main/res/drawable/ic_open_on_tv.xml (nuovo)
- app-tv/src/main/AndroidManifest.xml
- app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/TvApp.kt
- app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/controller/BrowserController.kt
- app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/playback/PlaybackController.kt
- app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/service/RemoteServerService.kt
- app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/service/BootReceiver.kt (nuovo)
- app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/ui/TvMainActivity.kt
- app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/ui/UiLauncher.kt (nuovo)
- app-tv/src/main/res/values/strings.xml
- app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/ui/MainActivity.kt
- app-mobile/src/main/res/layout/screen_player.xml
- app-mobile/src/main/res/menu/main.xml
- app-mobile/src/main/res/values/strings.xml
- version.properties, README.md, TODO.md
