package com.icarocomix.icaromanagertv.common.atv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Codifica protobuf minima per il protocollo di controllo remoto di Android TV: bastano interi
 * (varint), stringhe/byte e messaggi annidati, quindi evito la dipendenza dalla libreria
 * protobuf e dal generatore di codice per una manciata di messaggi.
 */
class ProtoWriter {

    private val out = ByteArrayOutputStream()

    fun int(field: Int, value: Long): ProtoWriter = apply {
        tag(field, WIRE_VARINT)
        writeVarint(out, value)
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, WIRE_LENGTH)
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    /** Messaggio annidato; un blocco vuoto produce comunque il campo (presenza = significato). */
    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = writeVarint(out, ((field shl 3) or wireType).toLong())
}

/**
 * Messaggio protobuf decodificato in modo generico: per ogni numero di campo i valori incontrati,
 * [Long] per i varint e [ByteArray] per i campi a lunghezza (stringhe, byte, messaggi). I campi a
 * 32/64 bit fissi, assenti nei messaggi usati, vengono saltati.
 */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int): Boolean = fields.containsKey(field)

    fun long(field: Int, default: Long = 0): Long = fields[field]?.lastOrNull() as? Long ?: default

    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray

    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)

    fun message(field: Int): ProtoMessage? = bytes(field)?.let(::parse)

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("Varint troncato")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift >= 64) throw IOException("Varint troppo lungo")
                }
            }
            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val value: Any? = when ((key and 7).toInt()) {
                    WIRE_VARINT -> varint()
                    WIRE_LENGTH -> {
                        val length = varint()
                        if (length < 0 || length > data.size - pos) throw IOException("Campo $field oltre la fine del messaggio")
                        data.copyOfRange(pos, pos + length.toInt()).also { pos += length.toInt() }
                    }
                    WIRE_FIXED64 -> { pos += 8; null }
                    WIRE_FIXED32 -> { pos += 4; null }
                    else -> throw IOException("Tipo di campo protobuf non gestito nel campo $field")
                }
                if (pos > data.size) throw IOException("Campo $field oltre la fine del messaggio")
                if (value != null) fields.getOrPut(field) { ArrayList(1) } += value
            }
            return ProtoMessage(fields)
        }
    }
}

/** Messaggi sul canale TLS: lunghezza in varint seguita dal messaggio protobuf. */
object ProtoFraming {

    /** Nessun messaggio legittimo del protocollo si avvicina a questa dimensione. */
    private const val MAX_FRAME = 64 * 1024

    fun frame(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 3)
        writeVarint(out, payload.size.toLong())
        out.write(payload)
        return out.toByteArray()
    }

    /** Legge un messaggio intero; [EOFException] se il canale si chiude. */
    fun read(input: InputStream): ByteArray {
        var length = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Canale chiuso dalla TV")
            length = length or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IOException("Lunghezza del messaggio non valida")
        }
        if (length > MAX_FRAME) throw IOException("Messaggio troppo grande ($length byte)")
        val payload = ByteArray(length.toInt())
        var read = 0
        while (read < payload.size) {
            val n = input.read(payload, read, payload.size - read)
            if (n < 0) throw EOFException("Canale chiuso a metà messaggio")
            read += n
        }
        return payload
    }
}

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH = 2
private const val WIRE_FIXED32 = 5

private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
    var v = value
    while (true) {
        if (v and 0x7FL.inv() == 0L) {
            out.write(v.toInt())
            return
        }
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
}
