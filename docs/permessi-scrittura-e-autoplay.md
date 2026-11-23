# Permessi di scrittura chiesti all'avvio e interruttore autoplay visibile (v1.9.1)

## Obiettivo

1. Ripristinare la visibilità del comando «Riproduzione automatica del successivo» nella
   schermata del player del telefono.
2. Eliminare gli errori di permesso durante le operazioni sui file (copia, sposta, rinomina,
   elimina, nuova cartella, ricezione dal telefono) e ottenere i permessi necessari già
   all'installazione, invece di scoprirne la mancanza al primo errore.

## Diagnosi

- **Autoplay**: il comando non era stato rimosso. L'interruttore si trovava in fondo alla
  schermata del player, dentro lo scorrimento; le righe aggiunte nelle versioni precedenti
  (sottotitoli/impostazioni, volume) e il pad direzionale fisso in basso lo avevano spinto fuori
  dalla parte visibile, tanto da sembrare sparito.
- **Permessi**: il messaggio mostrato dal telefono («Scrittura non consentita in … concedi
  Accesso a tutti i file») proviene dalla TV ed è il rifiuto di scrittura POSIX. Da Android 11
  i permessi multimediali (READ_MEDIA_*) permettono di leggere i dischi ma non di scriverci:
  serve «Accesso a tutti i file» (MANAGE_EXTERNAL_STORAGE). L'app lo proponeva una sola volta
  nella sua vita, al primo avvio; installazioni che lo avevano scartato quando l'app era di sola
  lettura (versioni precedenti alla 1.9) non lo vedevano più proporre, e restava solo il
  pulsante nel pannello laterale.
- Il pacchetto di log analizzato conteneva solo i log del telefono (TV non collegata al momento
  dell'esportazione); sul telefono risultano un caricamento interrotto («unexpected end of
  stream», cioè la TV che chiude la connessione dopo aver rifiutato la scrittura) e cadute del
  collegamento mentre l'app del telefono è in background (selettore di file aperto), con
  ricollegamento al ritorno in primo piano.

## Decisioni

- **Permessi «all'installazione»**: Android non consente di concedere permessi speciali
  (MANAGE_EXTERNAL_STORAGE, SYSTEM_ALERT_WINDOW) durante l'installazione da APK; l'unica via
  automatica è ADB, già coperta da `installa-tv.sh`. Scelta: procedura guidata al primo avvio di
  **ogni versione installata** (lettura → accesso a tutti i file → sovrapposizione), una schermata
  di sistema alla volta con spiegazione preliminare. Il completamento è memorizzato per
  versionCode, quindi ogni aggiornamento ripropone ciò che manca.
  Scartato: chiedere a ogni avvio finché il permesso manca (invadente, ciclo di richieste se
  l'utente rifiuta consapevolmente).
- **Richiesta al bisogno**: se un'operazione fallisce per un permesso mancante (anche partita dal
  telefono), il controller della TV lo segnala con uno stato persistente e la UI ripropone subito
  la procedura; se la UI non è visibile la richiesta compare alla successiva apertura.
- **Diagnosi precisa del rifiuto**: il messaggio distingue disco montato in sola lettura (tipico
  NTFS, nessun permesso lo risolve), accesso a tutti i file mancante, permesso di archiviazione
  mancante (Android 9/10), disco rimovibile su Android 9/10 (scrivibile solo tramite cartella SAF)
  e altre cause (con il messaggio del sistema). Anche l'eliminazione usa questa diagnosi invece
  del generico «impossibile eliminare».
- **Autoplay**: l'interruttore è stato spostato subito sotto la posizione nella playlist,
  sempre visibile anche con il pad aperto; resta uno SwitchCompat (stato sincronizzato con la TV).
- Nessuna modifica al protocollo: versione PATCH (1.9.1, versionCode 13).

## Limiti noti

- Sui dischi montati dal sistema in sola lettura nessuna app può scrivere: serve un disco
  exFAT/FAT32 (o una TV con driver NTFS in scrittura).
- Se la TV non offre la schermata «Accesso a tutti i file», resta il suggerimento con il comando
  ADB (o l'uso di `installa-tv.sh`).
- Le cadute del collegamento del telefono in background non sono state modificate in questo
  intervento.

## Esito

Test `:common` OK, `assembleDebug` TV e telefono OK, `distApk` OK (v1.9.1, versionCode 13).
Da verificare sull'hardware reale: procedura al primo avvio dopo l'aggiornamento, operazioni sui
file sul disco USB con accesso concesso, richiesta che ricompare dopo un rifiuto, interruttore
autoplay visibile con il pad aperto.

## File toccati

- `app-mobile/src/main/res/layout/screen_player.xml`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/storage/FileOperations.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/storage/StorageAccess.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/controller/BrowserController.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/server/RemoteServer.kt`
- `app-tv/src/main/java/com/icarocomix/icaromanagertv/tv/ui/TvMainActivity.kt`
- `app-tv/src/main/res/values/strings.xml`
- `version.properties`, `README.md`, `TODO.md`
