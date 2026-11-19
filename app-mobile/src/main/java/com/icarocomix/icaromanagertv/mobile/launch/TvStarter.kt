package com.icarocomix.icaromanagertv.mobile.launch

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.icarocomix.icaromanagertv.common.atv.AtvRemoteProtocol
import com.icarocomix.icaromanagertv.common.net.LocalNetwork
import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.Protocol
import com.icarocomix.icaromanagertv.common.protocol.UiCommand
import com.icarocomix.icaromanagertv.mobile.R
import com.icarocomix.icaromanagertv.mobile.client.Connection
import com.icarocomix.icaromanagertv.mobile.client.RemoteClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Stato del percorso "apri l'app sulla TV", mostrato dall'activity come dialog o messaggio. */
sealed class StartState {
    data object Idle : StartState()

    /** Operazione in corso (annullabile). */
    data class Working(val text: String) : StartState()

    /** Serve l'accoppiamento con la TV: [reason] spiega perché, [targets] le TV tra cui scegliere. */
    data class OfferPairing(val reason: String, val targets: List<PairTarget>) : StartState()

    /** La TV mostra il codice: l'utente lo digita. [error] dopo un codice sbagliato. */
    data class AwaitingCode(val tvName: String, val error: String? = null) : StartState()

    /** Esito finale da mostrare una volta (poi [TvStarter.acknowledge]). */
    data class Finished(val text: String, val success: Boolean) : StartState()
}

/** TV con cui accoppiarsi: [serviceName] è il nome annunciato via mDNS, se noto. */
data class PairTarget(val name: String, val host: String, val serviceName: String?)

/**
 * Apre Manager TV sulla TV dal telefono senza azioni dell'utente:
 *
 * 1. app TV in ascolto (telefono collegato al suo server): comando `ui` diretto;
 * 2. TV appena accesa o server non ancora raggiungibile: l'app TV si riavvia da sola
 *    (accensione, aggiornamenti, chiusura da parte del sistema), quindi attendo fino a un minuto
 *    l'ultima TV nota, sollecitando ricerca e collegamento, e poi la porto in primo piano;
 * 3. ripiego facoltativo, solo se l'utente ha fatto di sua iniziativa l'abbinamento (menu): il
 *    servizio di controllo remoto di sistema della TV (Android TV Remote v2), che apre l'app con
 *    il suo deep link anche quando il firmware ne blocca l'avvio automatico. Il codice di
 *    abbinamento lo genera e lo mostra il servizio di sistema e non è leggibile da altre app:
 *    per questo non è mai nel percorso normale né proposto in automatico.
 */
class TvStarter(
    private val app: Application,
    private val client: RemoteClient,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<StartState>(StartState.Idle)
    val state: StateFlow<StartState> = _state.asStateFlow()

    private val store = PairingStore(app)
    private val identity by lazy { AtvIdentity() }
    private val launcher by lazy { AtvLauncher(identity) }
    private val finder = AtvServiceFinder(app)

    private var job: Job? = null
    @Volatile private var pairing: AtvPairing? = null
    private var pairingTarget: PairTarget? = null

    val isPaired: Boolean get() = store.pairing() != null

    /** Voce «Apri l'app sulla TV». */
    fun openOnTv() {
        if (job?.isActive == true) return
        if (client.send(ClientMessage.Ui(UiCommand.BRING_TO_FRONT))) {
            _state.value = StartState.Finished(app.getString(R.string.open_on_tv_sent), success = true)
            return
        }
        job = scope.launch { openWhenReachable() }
    }

    /**
     * Percorso senza azioni dell'utente: l'app TV, una volta installata e avviata, resta sempre
     * in ascolto (riavvio all'accensione, dopo gli aggiornamenti e se il sistema la chiude), e il
     * telefono conosce già la TV dal primo collegamento. Se ora non risponde è quasi sempre una
     * TV appena accesa che sta ancora avviando rete e servizi: la attendo, sollecitando ricerca e
     * collegamento, e appena c'è le chiedo di portarsi in primo piano. Solo se l'utente ha fatto
     * di sua iniziativa l'abbinamento con il servizio di sistema lo uso come ripiego (firmware
     * che bloccano l'avvio automatico delle app).
     */
    private suspend fun openWhenReachable() {
        val known = client.lastKnownTv()
        val saved = store.pairing()
        if (known == null && saved == null) {
            _state.value = StartState.Finished(app.getString(R.string.launch_never_seen), success = false)
            return
        }
        val tvName = known?.name ?: saved?.tvName.orEmpty()
        _state.value = StartState.Working(app.getString(R.string.launch_waiting_app, tvName))
        val window = if (saved != null) WAIT_BEFORE_SYSTEM_MS else WAIT_FOR_APP_MS
        val deadline = SystemClock.elapsedRealtime() + window
        while (SystemClock.elapsedRealtime() < deadline) {
            client.reconnectNow()
            // Collegamento caduto proprio tra l'attesa e l'invio: continuo ad attendere.
            if (awaitConnected(RECONNECT_POKE_MS) && client.send(ClientMessage.Ui(UiCommand.BRING_TO_FRONT))) {
                _state.value = StartState.Finished(app.getString(R.string.open_on_tv_sent), success = true)
                return
            }
        }
        if (saved != null) {
            Log.i(TAG, "L'app TV non risponde: ripiego sul servizio di sistema della TV")
            launchViaSystem(saved)
        } else {
            _state.value = StartState.Finished(app.getString(R.string.launch_app_silent, tvName), success = false)
        }
    }

    /** Voce di menu per (ri)fare l'accoppiamento in qualunque momento. */
    fun pairNow() {
        if (job?.isActive == true) return
        job = scope.launch { offerPairing(app.getString(R.string.pair_explain)) }
    }

    fun startPairing(target: PairTarget) {
        job?.cancel()
        job = scope.launch { beginPairing(target, error = null) }
    }

    fun submitCode(code: String) {
        val target = pairingTarget ?: return
        val normalized = AtvRemoteProtocol.normalizeCode(code)
        if (normalized == null) {
            _state.value = StartState.AwaitingCode(target.name, app.getString(R.string.pair_code_format))
            return
        }
        job?.cancel()
        job = scope.launch { finishPairing(target, normalized) }
    }

    /** Annulla qualunque operazione in corso e chiude il canale di accoppiamento. */
    fun cancel() {
        job?.cancel()
        job = null
        closePairing()
        _state.value = StartState.Idle
    }

    /** L'esito è stato mostrato. */
    fun acknowledge() {
        if (_state.value is StartState.Finished || _state.value is StartState.OfferPairing) _state.value = StartState.Idle
    }

    // ------------------------------------------------------------------ accoppiamento

    private suspend fun offerPairing(reason: String) {
        _state.value = StartState.Working(app.getString(R.string.pair_searching))
        val targets = withContext(Dispatchers.IO) { collectTargets() }
        _state.value = if (targets.isEmpty()) {
            StartState.Finished(app.getString(R.string.pair_no_tv_found), success = false)
        } else {
            StartState.OfferPairing(reason, targets)
        }
    }

    /**
     * TV candidate: quella collegata ora all'app (indirizzo certo), quelle che annunciano il
     * servizio di controllo remoto e, se nessuna risponde, l'ultima TV nota. Unisco per
     * indirizzo, tenendo il nome mDNS che serve a ritrovarla se l'IP cambia.
     */
    private fun collectTargets(): List<PairTarget> {
        val targets = LinkedHashMap<String, PairTarget>()
        (client.connection.value as? Connection.Connected)?.let { targets[it.tv.host] = PairTarget(it.serverName, it.tv.host, null) }
        finder.find().forEach { found ->
            val known = targets[found.host]
            targets[found.host] = PairTarget(known?.name ?: found.name, found.host, found.name)
        }
        if (targets.isEmpty()) client.lastKnownTv()?.let { targets[it.host] = PairTarget(it.name, it.host, null) }
        return targets.values.toList()
    }

    private suspend fun beginPairing(target: PairTarget, error: String?) {
        closePairing()
        _state.value = StartState.Working(app.getString(R.string.pair_connecting, target.name))
        val session = AtvPairing(identity, target.host)
        pairing = session
        pairingTarget = target
        try {
            withContext(Dispatchers.IO) { session.begin(clientName()) }
            _state.value = StartState.AwaitingCode(target.name, error)
        } catch (e: CancellationException) {
            session.close()
            throw e
        } catch (e: AtvException) {
            session.close()
            Log.w(TAG, "Accoppiamento con ${target.host} non avviato: ${e.message}", e)
            _state.value = StartState.Finished(describe(e, pairing = true), success = false)
        } catch (e: Exception) {
            session.close()
            Log.e(TAG, "Accoppiamento con ${target.host} non avviato", e)
            _state.value = StartState.Finished(app.getString(R.string.pair_failed, e.message ?: e.javaClass.simpleName), success = false)
        }
    }

    private suspend fun finishPairing(target: PairTarget, code: String) {
        val session = pairing ?: return beginPairing(target, app.getString(R.string.pair_code_expired))
        _state.value = StartState.Working(app.getString(R.string.pair_checking))
        try {
            val fingerprint = withContext(Dispatchers.IO) { session.finish(code) }
            closePairing()
            // Nome mDNS della TV: serve a ritrovarla se dopo un riavvio cambia indirizzo IP.
            val serviceName = target.serviceName
                ?: withContext(Dispatchers.IO) { finder.find().firstOrNull { it.host == target.host }?.name }
            val saved = Pairing(target.host, serviceName, fingerprint, target.name)
            store.save(saved)
            Log.i(TAG, "Accoppiamento con ${target.host} (${serviceName ?: "nome mDNS ignoto"}) completato")
            if (client.connection.value is Connection.Connected) {
                _state.value = StartState.Finished(app.getString(R.string.pair_done), success = true)
            } else {
                launchViaSystem(saved)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AtvException) {
            when (e.reason) {
                // Errore di battitura: la TV aspetta ancora, la sessione resta buona.
                AtvException.Reason.BAD_CODE -> _state.value = StartState.AwaitingCode(target.name, app.getString(R.string.pair_code_wrong))
                // La TV ha scartato il tentativo o chiuso la sessione (codice scaduto): ne apro
                // una nuova, la TV mostra un nuovo codice.
                else -> {
                    Log.w(TAG, "Codice rifiutato o sessione chiusa: ${e.message}")
                    beginPairing(target, app.getString(R.string.pair_code_rejected))
                }
            }
        } catch (e: Exception) {
            // Errori del Keystore o simili: li mostro invece di lasciare il dialog appeso.
            closePairing()
            Log.e(TAG, "Accoppiamento non concluso", e)
            _state.value = StartState.Finished(app.getString(R.string.pair_failed, e.message ?: e.javaClass.simpleName), success = false)
        }
    }

    private fun closePairing() {
        pairing?.close()
        pairing = null
    }

    private fun clientName(): String = app.getString(R.string.pair_client_name, LocalNetwork.deviceName(app))

    // ------------------------------------------------------------------ apertura via sistema

    private suspend fun launchViaSystem(saved: Pairing) {
        _state.value = StartState.Working(app.getString(R.string.launch_searching, saved.tvName))
        val deadline = SystemClock.elapsedRealtime() + LAUNCH_RETRY_WINDOW_MS
        var hosts = withContext(Dispatchers.IO) { candidateHosts(saved) }
        var attempt = 0
        var lastError: AtvException? = null
        var sentTo: String? = null
        while (sentTo == null && SystemClock.elapsedRealtime() < deadline) {
            for (host in hosts) {
                try {
                    withContext(Dispatchers.IO) { launcher.openLink(host, saved.fingerprint, Protocol.TV_APP_LINK, deviceInfo()) }
                    sentTo = host
                    break
                } catch (e: AtvException) {
                    lastError = e
                    Log.w(TAG, "Apertura tramite $host non riuscita (${e.reason}): ${e.message}")
                    if (!e.retryable) return handleFatal(e)
                }
            }
            if (sentTo != null) break
            attempt++
            _state.value = StartState.Working(app.getString(R.string.launch_waiting_tv, attempt))
            delay(RETRY_DELAY_MS)
            // La TV può essere tornata in rete con un altro indirizzo: ogni tanto la ricerco.
            if (attempt % 3 == 0) hosts = withContext(Dispatchers.IO) { candidateHosts(saved) }
        }
        val host = sentTo ?: run {
            _state.value = StartState.Finished(
                if (lastError?.reason == AtvException.Reason.REFUSED) {
                    app.getString(R.string.launch_refused, saved.tvName)
                } else {
                    app.getString(R.string.launch_unreachable, saved.tvName)
                },
                success = false,
            )
            return
        }
        if (host != saved.host) store.save(saved.copy(host = host))

        // Il link è stato accettato: aspetto che l'app TV parta e che il telefono la trovi.
        _state.value = StartState.Working(app.getString(R.string.launch_opening))
        client.reconnectNow()
        if (awaitConnected(APP_START_WAIT_MS)) return finishOpened()
        // Ripiego: alcune TV aprono i deep link solo tramite il Play Store.
        Log.w(TAG, "L'app TV non si è collegata dopo il deep link: provo l'apertura tramite il Play Store")
        runCatching { withContext(Dispatchers.IO) { launcher.openLink(host, saved.fingerprint, Protocol.TV_STORE_LINK, deviceInfo()) } }
            .onFailure { Log.w(TAG, "Ripiego tramite il Play Store non inviato: ${it.message}") }
        client.reconnectNow()
        if (awaitConnected(APP_START_WAIT_MS)) return finishOpened()
        _state.value = StartState.Finished(app.getString(R.string.launch_no_answer), success = false)
    }

    private fun finishOpened() {
        _state.value = StartState.Finished(app.getString(R.string.launch_done), success = true)
    }

    private suspend fun awaitConnected(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { client.connection.first { it is Connection.Connected } } != null

    /**
     * Indirizzi da provare: quello attuale della TV se annuncia il servizio con il nome salvato
     * (l'IP può cambiare a ogni riavvio), poi l'ultimo indirizzo noto.
     */
    private fun candidateHosts(saved: Pairing): List<String> {
        val hosts = LinkedHashSet<String>()
        if (saved.serviceName != null) {
            finder.find().firstOrNull { it.name == saved.serviceName }?.let { hosts += it.host }
        }
        hosts += saved.host
        return hosts.toList()
    }

    private suspend fun handleFatal(e: AtvException) {
        when (e.reason) {
            AtvException.Reason.NOT_RECOGNISED -> offerPairing(app.getString(R.string.pair_needed_forgotten))
            AtvException.Reason.CERT_CHANGED -> offerPairing(app.getString(R.string.pair_needed_identity))
            else -> _state.value = StartState.Finished(describe(e, pairing = false), success = false)
        }
    }

    private fun describe(e: AtvException, pairing: Boolean): String = when (e.reason) {
        AtvException.Reason.REFUSED -> app.getString(R.string.atv_service_missing)
        AtvException.Reason.UNREACHABLE -> app.getString(R.string.atv_unreachable)
        AtvException.Reason.NOT_RECOGNISED -> app.getString(R.string.pair_needed_forgotten)
        AtvException.Reason.CERT_CHANGED -> app.getString(R.string.pair_needed_identity)
        else -> app.getString(if (pairing) R.string.pair_failed else R.string.launch_failed, e.message.orEmpty())
    }

    private fun deviceInfo(): AtvLauncher.DeviceInfo {
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "1.0"
        return AtvLauncher.DeviceInfo(Build.MODEL ?: "Android", Build.MANUFACTURER ?: "Android", version)
    }

    private companion object {
        const val TAG = "TvStarter"

        /** La TV appena accesa impiega fino a un minuto ad avviare i servizi di rete. */
        const val LAUNCH_RETRY_WINDOW_MS = 60_000L
        const val RETRY_DELAY_MS = 3_000L
        const val APP_START_WAIT_MS = 15_000L

        /** Tempo concesso alla TV appena accesa per avviare rete e app senza abbinamento. */
        const val WAIT_FOR_APP_MS = 60_000L

        /** Con l'abbinamento di sistema disponibile passo prima al ripiego. */
        const val WAIT_BEFORE_SYSTEM_MS = 10_000L

        /** Ogni quanto sollecito ricerca e collegamento mentre attendo. */
        const val RECONNECT_POKE_MS = 3_000L
    }
}

/** Accoppiamento salvato: indirizzo, nome mDNS e impronta del certificato della TV. */
data class Pairing(val host: String, val serviceName: String?, val fingerprint: String, val tvName: String)

/** Persistenza dell'accoppiamento (nessun segreto: la chiave privata resta nell'AndroidKeyStore). */
private class PairingStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pairing(): Pairing? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val fingerprint = prefs.getString(KEY_FINGERPRINT, null) ?: return null
        return Pairing(host, prefs.getString(KEY_SERVICE, null), fingerprint, prefs.getString(KEY_NAME, null) ?: host)
    }

    fun save(pairing: Pairing) {
        prefs.edit()
            .putString(KEY_HOST, pairing.host)
            .putString(KEY_SERVICE, pairing.serviceName)
            .putString(KEY_FINGERPRINT, pairing.fingerprint)
            .putString(KEY_NAME, pairing.tvName)
            .apply()
    }

    private companion object {
        const val PREFS = "atv_pairing"
        const val KEY_HOST = "host"
        const val KEY_SERVICE = "service"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_NAME = "name"
    }
}
