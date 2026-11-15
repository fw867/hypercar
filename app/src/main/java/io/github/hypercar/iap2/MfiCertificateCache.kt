package io.github.hypercar.iap2

import android.content.Context
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.Locale

/** Public certificates only, scoped to the actual BT peer. No authentication result is cached. */
internal object MfiCertificateCache {
    const val MAX_ENTRIES = 16
    const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    private const val FILE = "mfi_certificate_cache"
    private const val MAX_CERT_BYTES = 32768
    private val memory = linkedMapOf<String,String>()

    /** Strict TLV framing and exactly one nonempty bounded parameter zero. */
    fun serial(body: ByteArray): ByteArray? = parameter(body,32)?.let(::normalize)
    private fun parameter(body: ByteArray, limit: Int): ByteArray? {
        if (body.size > limit + 4) return null
        val fields = Iap2Csm.parseParams(body)
        return fields.singleOrNull()?.takeIf { it.id==0 && it.value.size in 1..limit && body.size==it.value.size+4 }?.value
    }
    private fun normalize(value: ByteArray): ByteArray? = value.takeIf { it.size in 1..32 }
        ?.dropWhile { it==0.toByte() }?.toByteArray()?.takeIf { it.isNotEmpty() }
    private fun key(peer: String?): String? {
        val value=peer?.trim()?.replace(":","")?.replace("-","")?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[0-9a-f]{12}")) && it !in setOf("000000000000","020000000000") } ?: return null
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun valid(certificate: ByteArray, serial: ByteArray, now: Long): Boolean = runCatching {
        val bytes=parameter(certificate,MAX_CERT_BYTES) ?: return false
        val input=bytes.inputStream()
        val certificates=CertificateFactory.getInstance("X.509").generateCertificates(input)
        input.available()==0 && certificates.filterIsInstance<X509Certificate>().any { cert ->
            cert.serialNumber.signum()>0 && normalize(cert.serialNumber.toByteArray())?.contentEquals(serial)==true &&
                runCatching { cert.checkValidity(Date(now)) }.isSuccess
        }
    }.getOrDefault(false)
    private fun prefs(context: Context?) = context?.getSharedPreferences(FILE,Context.MODE_PRIVATE)
    private fun entries(context: Context?): Map<String,String> = prefs(context)?.all?.mapNotNull { (k,v) -> (v as? String)?.let { k to it } }?.toMap() ?: memory.toMap()
    private fun remove(context: Context?, key: String) {
        if (context==null) memory.remove(key) else prefs(context)!!.edit().remove(key).apply()
    }
    @Synchronized fun load(context: Context?, peer: String?, serial: ByteArray, now: Long = System.currentTimeMillis()): ByteArray? {
        val key=key(peer) ?: return null
        val expected=normalize(serial) ?: return null
        val encoded=entries(context)[key] ?: return null
        if (encoded.length > (MAX_CERT_BYTES+4)*4/3+256) { remove(context,key);return null }
        val certificate=runCatching {
            val parts=encoded.split('|',limit=3)
            val time=parts[0].toLong()
            if (time>now || now-time>MAX_AGE_MILLIS) return@runCatching null
            if (!Base64.getDecoder().decode(parts[1]).contentEquals(expected)) return@runCatching null
            Base64.getDecoder().decode(parts[2]).takeIf { valid(it,expected,now) }
        }.getOrNull()
        if (certificate==null) remove(context,key)
        return certificate
    }
    /** Caller records only after this connection produced a challenge response. Trust is still unverified. */
    @Synchronized fun remember(context: Context?, peer: String?, serial: ByteArray, certificate: ByteArray, now: Long = System.currentTimeMillis()): Boolean {
        val key=key(peer) ?: return false
        val normalized=normalize(serial) ?: return false
        if (!valid(certificate,normalized,now)) return false
        val encoded="$now|${Base64.getEncoder().encodeToString(normalized)}|${Base64.getEncoder().encodeToString(certificate)}"
        val all=entries(context).toMutableMap().also { it[key]=encoded }
        val evicted=all.entries.sortedBy { it.value.substringBefore('|').toLongOrNull() ?: Long.MIN_VALUE }
            .take((all.size-MAX_ENTRIES).coerceAtLeast(0)).map { it.key }
        if (context==null) { memory[key]=encoded;evicted.forEach(memory::remove) }
        else prefs(context)!!.edit().putString(key,encoded).also { edit -> evicted.forEach(edit::remove) }.apply()
        return true
    }
    @Synchronized fun forget(context: Context?, peer: String?) { key(peer)?.let { remove(context,it) } }
    @Synchronized fun clearAll(context: Context?) { memory.clear();prefs(context)?.edit()?.clear()?.apply() }
}
