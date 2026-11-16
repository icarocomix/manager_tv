# Controllo del volume della TV dal telefono (v1.4.0)

## Obiettivo

Quando sulla TV è attivo il player integrato, l'app del telefono diventa un telecomando
(schermata Player). Obiettivo: aggiungere a quel telecomando i controlli del volume — alzare,
abbassare, togliere/rimettere l'audio e, dove possibile, impostare direttamente il livello — con
un riscontro sul telefono dello stato reale della TV.

## Decisioni

- **Volume di sistema della TV (`AudioManager`, flusso `STREAM_MUSIC`), non il volume interno di
  ExoPlayer.** È lo stesso volume dei tasti del telecomando della TV, resta coerente con
  l'indicatore di sistema e vale anche per i file aperti con un player esterno.
  - Scartato: `Player.setVolume` di ExoPlayer. Attenua solo il segnale dell'app (0..1) sopra il
    volume di sistema: a volume di sistema basso non permette di alzare, e il livello mostrato
    non corrisponderebbe a quello che la TV indica.
- **Indicatore del volume sulla TV** (`FLAG_SHOW_UI`) a ogni comando dal telefono: chi guarda lo
  schermo vede l'effetto come con il telecomando fisico.
- **Comandi relativi come nuove `PlayerAction`** (`volup`, `voldown`, `mute`) e **livello
  assoluto con un nuovo messaggio `volume`** (campo `level`). I comandi relativi funzionano in
  ogni caso; il livello assoluto serve al cursore.
- **Volume fisso (box HDMI).** Su molti box Android TV il sistema blocca il livello locale e
  inoltra i comandi relativi al televisore/amplificatore via HDMI-CEC. In quel caso il livello non
  è leggibile né impostabile: la TV pubblica livello -1 e il telefono nasconde il cursore,
  lasciando −/+/muto, che passano comunque da `adjustStreamVolume` e quindi dall'inoltro CEC.
- **Stato del volume nel messaggio `playback`** (campi `vol`, `volMax`, `mute`), letto a ogni
  pubblicazione dello stato del player (tick di un secondo e dopo ogni comando). Così il telefono
  vede anche le variazioni fatte con il telecomando della TV senza un canale dedicato.
  - Scartato: ascoltare il broadcast di sistema `VOLUME_CHANGED_ACTION`. Non è un'API pubblica
    garantita; la lettura costa una chiamata binder ed è già cadenzata dal tick del player.
  - Il volume viene pubblicato anche a player chiuso: costa poco e tiene i campi sempre validi.
- **Cursore che toglie il muto.** Portare il cursore su un livello udibile toglie anche il muto,
  come avviene con i tasti fisici; altrimenti il cursore si muoverebbe senza effetto percepibile.
- **Tasti volume fisici del telefono inoltrati alla TV** solo con la schermata Player aperta e la
  connessione attiva; altrimenti (o se l'invio fallisce) l'evento resta al sistema e regola il
  volume del telefono. L'autoripetizione del tasto tenuto premuto è diradata (circa un passo ogni
  180 ms), perché ogni evento corrisponde a un passo sulla TV.
- **Cursore con invio diradato.** Durante il trascinamento il livello parte al massimo ogni
  150 ms, più un invio finale garantito al rilascio; per poco più di un secondo dopo il rilascio
  il telefono ignora il livello pubblicato dalla TV, che arriva in ritardo e farebbe saltare
  indietro il cursore.
- **Compatibilità.** Messaggi e campi nuovi sono aggiuntivi: un server v1.3 scarta i comandi di
  volume come sconosciuti, un telefono v1.3 ignora i campi nuovi; un telefono v1.4 collegato a
  una TV v1.3 riceve livello assente e mostra solo i tasti. `Protocol.VERSION` invariato,
  incremento MINOR → 1.4.0 (versionCode 5).

## Architettura

- `:common`: `PlayerAction` con i tre comandi di volume (e la proprietà `isVolume`),
  `ClientMessage.Volume`, campi `volume`/`volumeMax`/`muted` e proprietà `volumeAdjustable` in
  `PlaybackState`, codifica/decodifica con valori predefiniti per i messaggi delle versioni
  precedenti, icone vettoriali del volume e stringhe.
- `:app-tv`: classe `VolumeControl` (lettura dello stato, su/giù/muto, livello assoluto limitato
  al massimo del flusso, eccezioni dei firmware gestite e registrate). `PlaybackController` la usa
  per i comandi (anche senza nulla in riproduzione) e include lo stato in ogni pubblicazione;
  `BrowserController` instrada `volume` al player come gli altri comandi.
- `:app-mobile`: nella schermata Player una riga «Volume della TV» con percentuale o «Muto», tasto
  muto (evidenziato nel colore d'accento quando l'audio è tolto), −, cursore, +; inoltro dei tasti
  volume fisici in `MainActivity`.

## Scoperte e correzioni

- `AudioManager.isVolumeFixed` è la distinzione rilevante fra TV con altoparlanti propri (volume
  regolabile) e box HDMI (volume fisso): da qui la scelta di un livello -1 al posto di un livello
  fittizio, che avrebbe mostrato un cursore inefficace.
- Alcuni firmware lanciano eccezioni di sicurezza sulla lettura o modifica dei flussi audio: ogni
  accesso ad `AudioManager` è protetto e, in caso di errore, il volume risulta semplicemente non
  disponibile invece di interrompere il player.

## Riepilogo

Consegnato: controllo del volume della TV dalla schermata Player del telefono (tasti, cursore,
muto, tasti fisici del telefono), stato del volume sincronizzato TV → telefono, compatibilità con
le versioni 1.3.

Limiti noti / da verificare sull'hardware reale: comportamento dell'inoltro HDMI-CEC sui box con
volume fisso (dipende dal firmware e dalle impostazioni CEC del televisore); presenza
dell'indicatore di sistema del volume su tutte le TV; granularità dei passi (dipende dal massimo
del flusso, tipicamente 15–100).

File toccati:

- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/Protocol.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/Messages.kt`
- `common/src/main/java/com/icarocomix/icaromanagertv/common/protocol/ProtocolCodec.kt`
- `common/src/main/res/drawable/ic_volume_up.xml`, `ic_volume_down.xml`, `ic_volume_off.xml`
- `common/src/main/res/values/strings.xml`
- `common/src/test/java/com/icarocomix/icaromanagertv/common/PlaybackProtocolTest.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/playback/VolumeControl.kt` (nuovo)
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/playback/PlaybackController.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/controller/BrowserController.kt`
- `app-mobile/src/main/res/layout/screen_player.xml`
- `app-mobile/src/main/res/values/styles.xml`
- `app-mobile/src/main/java/com/icarocomix/icaromanagertv/mobile/ui/MainActivity.kt`
- `version.properties`, `README.md`, `TODO.md`
