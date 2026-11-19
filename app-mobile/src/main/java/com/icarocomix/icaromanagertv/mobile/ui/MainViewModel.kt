package com.icarocomix.icaromanagertv.mobile.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.mobile.R
import com.icarocomix.icaromanagertv.mobile.client.RemoteClient
import com.icarocomix.icaromanagertv.mobile.client.TvLogsUnsupportedException
import com.icarocomix.icaromanagertv.mobile.diagnostics.CombinedLogExporter
import com.icarocomix.icaromanagertv.mobile.launch.TvStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Possiede il [RemoteClient]: sopravvive alle rotazioni dello schermo senza far cadere il
 * collegamento. In background il collegamento resta aperto per un breve periodo di grazia
 * (cambio rapido di app), poi si chiude per non consumare batteria e rete.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val client = RemoteClient(application, viewModelScope)

    /** Apertura dell'app sulla TV, anche quando è chiusa (vedi [TvStarter]). */
    val starter = TvStarter(application, client, viewModelScope)
    private var stopJob: Job? = null
    private val logExporter = CombinedLogExporter(application, client)

    private val _exportingLogs = MutableStateFlow(false)

    /** Esportazione dei log in corso: la voce di menu resta disattivata fino alla fine. */
    val exportingLogs: StateFlow<Boolean> = _exportingLogs.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Esiti da mostrare all'utente; sopravvivono alla ricreazione dell'activity. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun onForeground() {
        stopJob?.cancel()
        stopJob = null
        client.start()
    }

    fun onBackground() {
        stopJob?.cancel()
        stopJob = viewModelScope.launch {
            delay(BACKGROUND_GRACE_MS)
            client.stop()
        }
    }

    fun send(message: ClientMessage): Boolean = client.send(message)

    /** Salva in [destination] il pacchetto di log TV + telefono, fuori dal main thread. */
    fun exportLogs(destination: Uri) {
        if (_exportingLogs.value) return
        _exportingLogs.value = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            val message = try {
                val result = withContext(Dispatchers.IO) { logExporter.exportTo(destination) }
                when (result.tvError) {
                    null -> app.getString(R.string.logs_saved_both)
                    else -> app.getString(R.string.logs_saved_phone_only, result.tvError)
                }
            } catch (e: TvLogsUnsupportedException) {
                app.getString(R.string.logs_saved_phone_only, e.message)
            } catch (e: Exception) {
                Log.e(TAG, "Esportazione dei log non riuscita", e)
                app.getString(R.string.logs_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                _exportingLogs.value = false
            }
            _messages.emit(message)
        }
    }

    override fun onCleared() {
        starter.cancel()
        client.stop()
        super.onCleared()
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val BACKGROUND_GRACE_MS = 30_000L
    }
}
