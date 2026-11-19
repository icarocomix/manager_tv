#!/usr/bin/env bash
# Installa Manager TV sulla TV via ADB e la prepara così che sulla TV non serva nessun
# passaggio manuale:
#   1. installa (o aggiorna) l'APK;
#   2. concede i permessi: lettura dei contenuti, «Accesso a tutti i file» e «Mostra sopra altre
#      app» (serve all'app per portarsi in primo piano quando la apre il telefono);
#   3. esclude l'app dall'ottimizzazione della batteria, così il server resta in ascolto;
#   4. la avvia una volta: un'app appena installata è «ferma» finché non viene aperta, e fino ad
#      allora non riparte all'accensione né risponde al telefono.
# Da qui in poi il server riparte da solo all'accensione della TV e dopo ogni aggiornamento.
#
# Uso: ./installa-tv.sh [seriale-adb o ip:porta] [percorso dell'APK]
#   es.  ./installa-tv.sh 192.168.1.50:5555
set -euo pipefail

PACKAGE="com.icarocomix.icaromanagertv.tv"
ACTIVITY="$PACKAGE/.ui.TvMainActivity"
TARGET="${1:-}"
APK="${2:-$(dirname "$0")/build/outputs/apk/app-tv-release.apk}"

if [[ ! -f "$APK" ]]; then
    echo "APK non trovato: $APK (compila con ./gradlew distApk o indica il percorso)" >&2
    exit 1
fi

ADB=(adb)
if [[ -n "$TARGET" ]]; then
    # Un indirizzo ip:porta va prima collegato (ADB via rete, attivabile dalle opzioni sviluppatore).
    if [[ "$TARGET" == *:* ]]; then
        adb connect "$TARGET" >/dev/null
    fi
    ADB=(adb -s "$TARGET")
fi

"${ADB[@]}" get-state >/dev/null 2>&1 || {
    echo "TV non raggiungibile via ADB${TARGET:+ ($TARGET)}: attiva il debug USB/di rete sulla TV e autorizza questo computer." >&2
    exit 1
}

echo "» Installazione di $(basename "$APK")…"
"${ADB[@]}" install -r "$APK"

SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "» Permessi (Android API $SDK)…"
if (( SDK >= 33 )); then
    PERMISSIONS=(android.permission.READ_MEDIA_VIDEO android.permission.READ_MEDIA_AUDIO android.permission.READ_MEDIA_IMAGES)
else
    PERMISSIONS=(android.permission.READ_EXTERNAL_STORAGE)
    (( SDK < 29 )) && PERMISSIONS+=(android.permission.WRITE_EXTERNAL_STORAGE)
fi
for permission in "${PERMISSIONS[@]}"; do
    # Un permesso non previsto da questa versione di Android non è un errore bloccante.
    "${ADB[@]}" shell pm grant "$PACKAGE" "$permission" 2>/dev/null || echo "  (permesso $permission non concesso)"
done
if (( SDK >= 30 )); then
    "${ADB[@]}" shell appops set --uid "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow || echo "  (accesso a tutti i file non concesso)"
fi
"${ADB[@]}" shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow || echo "  (sovrapposizione non concessa)"

echo "» Esclusione dall'ottimizzazione della batteria…"
"${ADB[@]}" shell dumpsys deviceidle whitelist "+$PACKAGE" >/dev/null 2>&1 || echo "  (non disponibile su questa TV)"

echo "» Primo avvio…"
"${ADB[@]}" shell am start -n "$ACTIVITY" >/dev/null

echo "Fatto: Manager TV è attiva. Apri Manager TV Remote sul telefono (stessa rete Wi-Fi)."
