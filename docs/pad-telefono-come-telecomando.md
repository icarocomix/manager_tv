# Controlli del player che si nascondono e pad del telefono come telecomando della TV (v1.5.0)

## Obiettivo

1. Sulla TV, aprendo un video a schermo intero, i controlli del player (con il focus sul tasto
   Pausa) restavano visibili sopra l'immagine finché non si premeva un tasto direzionale; dopo
   la pressione sparivano regolarmente al termine del timeout. Devono invece nascondersi da soli
   pochi secondi dopo l'avvio della riproduzione.
2. Il pad direzionale dell'app telefono deve permettere di usare l'interfaccia della TV come il
   suo telecomando: in particolare muoversi tra i controlli del player a schermo intero, cosa
   impossibile in precedenza (funzionavano solo i comandi dedicati, come il volume).

## Analisi

### Controlli sempre visibili

Il componente Media3 `PlayerView` decide se mostrare i controlli "a tempo indeterminato" nel
momento in cui li mostra: se in quell'istante il player non è collegato, è in preparazione o non
ha ancora `playWhenReady`, il timeout viene azzerato. La TV mostrava i controlli all'apertura dello
schermo intero, cioè proprio in quella fase. Media3 ricalcola il timeout su alcuni eventi del
player solo se i controlli sono già del tutto visibili (non durante l'animazione d'ingresso), per
cui il caso restava scoperto finché un tasto non richiamava la logica di visualizzazione, con il
timeout corretto. L'analisi è stata fatta sul bytecode di `media3-ui` 1.10.0
(`PlayerView.maybeShowController`, `shouldShowControllerIndefinitely`,
`PlayerControlViewLayoutManager.resetHideCallbacks`).

### Pad del telefono

I tasti del pad arrivavano alla TV come comandi `Key` e venivano tradotti sempre in operazioni
sull'elenco (sposta selezione, risali, apri), anche con il player a schermo intero sopra
l'elenco: i controlli del player, il pannello laterale e le finestre di dialogo non li ricevevano
mai.

## Decisioni

- **Timeout dei controlli**: all'avvio effettivo della riproduzione (`onIsPlayingChanged(true)`),
  se i controlli sono visibili e in uso, la TV li rimostra: Media3 rivaluta la condizione, ora
  falsa, e riavvia il conto alla rovescia normale (4 s). Il controllo si ripete dopo 600 ms per
  coprire l'avvio durante l'animazione d'ingresso. Il listener segue sempre e solo il player
  collegato alla vista. Scartata la rinuncia a `showController()` all'apertura: l'utente non
  vedrebbe i comandi né avrebbe il focus pronto sul tasto Pausa.
- **Tasti del pad come eventi D-pad reali**: con l'interfaccia della TV in primo piano, i tasti
  del pad vengono iniettati come eventi di input di sistema nella finestra che ha il focus.
  Seguono così lo stesso percorso del telecomando fisico (focus delle viste, controlli Media3,
  callback di "Indietro", dialog dell'app). Un'app può iniettare eventi solo nelle proprie
  finestre senza permessi speciali, ed è esattamente il caso d'uso; l'iniezione sincrona avviene
  su un thread seriale dedicato (vietata sul main thread, ordine dei tasti preservato). Se
  l'iniezione fallisce e la finestra dell'activity ha comunque il focus, il tasto viene
  consegnato direttamente all'activity; altrimenti (finestra di un'altra app, es. permessi di
  sistema) viene scartato. Scartata la sola consegna diretta all'activity: non raggiungerebbe
  le finestre di dialogo, che hanno una finestra propria.
- **Semantica dell'elenco conservata**: con il focus sull'elenco (o senza focus), senza player a
  schermo intero, senza domanda di ripresa e senza dialog, il pad mantiene il comportamento
  storico (Su/Giù selezione, Sinistra risale di cartella, Destra/OK apre, Indietro annulla la
  ricerca o risale). Unica eccezione: Sinistra alla radice senza ricerca, dove non c'è nulla da
  risalire, passa come D-pad e porta al pannello laterale. Indietro nell'elenco non chiude mai
  l'app della TV dal telefono.
- **Senza interfaccia visibile** (solo servizio attivo) i tasti restano comandi di navigazione
  dell'elenco, come prima.
- **Protocollo invariato**: il cambiamento è tutto lato TV (instradamento) e nell'interfaccia
  del telefono → versione MINOR.
- **Telefono**: il pad è estratto in un layout riusabile e compare anche nella schermata del
  player (pulsante «Telecomando» in alto a destra, pad in basso fuori dallo scorrimento), così
  i tasti direzionali sono disponibili proprio quando servono per i controlli del player.

## Architettura

- Il controller del file manager espone un destinatario opzionale dei tasti del telefono,
  impostato dall'activity della TV in `onStart` e rimosso in `onStop` (solo se ancora suo).
- Il server WebSocket inoltra i messaggi del telefono a un ingresso dedicato del controller, che
  consegna i tasti `Key` al destinatario quando presente, altrimenti li accoda come prima.
- L'activity della TV riporta il tasto sul main thread, decide tra navigazione dell'elenco e
  D-pad reale, e nel secondo caso inietta l'evento corrispondente (Su/Giù/Sinistra/Destra →
  D-pad, OK → centro del D-pad, Indietro → Back).

## Scoperte durante lo sviluppo

- Il timeout dei controlli non dipende dal focus sul tasto Pausa (come sembrava osservandolo),
  ma dall'istante in cui i controlli vengono mostrati rispetto allo stato del player.

## Riepilogo

Consegnato: nascondimento automatico dei controlli dopo l'avvio della riproduzione; pad del
telefono usabile come telecomando della TV in tutta l'interfaccia (player a schermo intero,
pannello, ricerca, dialog, domanda di ripresa) con semantica dell'elenco invariata; pad
disponibile anche dalla schermata del player del telefono. Versione 1.5.0 (versionCode 7).

Limiti noti: con una finestra di un'altra app in primo piano sulla TV (es. richiesta di permessi
di sistema) i tasti del pad vengono ignorati; sui firmware che negassero anche l'iniezione nelle
finestre dell'app stessa, i tasti arrivano solo all'activity e non ai dialog. Da verificare
sull'hardware reale.

File toccati:
- app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/ui/TvMainActivity.kt
- app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/controller/BrowserController.kt
- app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/server/RemoteServer.kt
- app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/ui/MainActivity.kt
- app-mobile/src/main/res/layout/activity_main.xml
- app-mobile/src/main/res/layout/screen_player.xml
- app-mobile/src/main/res/layout/view_pad.xml (nuovo)
- version.properties, README.md, TODO.md
