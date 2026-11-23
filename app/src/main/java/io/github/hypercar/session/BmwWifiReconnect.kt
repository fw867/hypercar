package io.github.hypercar.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cached access to the local-only hotspot. This never represents AirPlay activation. */
internal object BmwWifiReconnect {
    private const val PROFILE = "bmw_id85"
    private const val KEY_ALIAS = "bmw-local-wifi"
    private const val NONCE_BYTES = 12

    class Credentials(val ssid: String, val passphrase: String)

    fun canRestore(profile: String, manual: Boolean, target: String?, bonded: String?, paired: Boolean): Boolean =
        profile == PROFILE && manual && paired && target != null &&
            PairingEvidence.receiverKey(target) != null && target.equals(bonded, ignoreCase = true)

    fun canRemember(profile: String, passphrase: String): Boolean = profile == PROFILE && passphrase.isNotEmpty()

    @Synchronized
    fun save(context: Context, receiver: String, credentials: Credentials) {
        val peer = PairingEvidence.receiverKey(receiver) ?: return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(key(create = true)))
        cipher.updateAAD(peer.encodeToByteArray())
        val payload = ByteArrayOutputStream().apply {
            DataOutputStream(this).use {
                it.writeUTF(credentials.ssid)
                it.writeUTF(credentials.passphrase)
            }
        }.toByteArray()
        val ciphertext = cipher.doFinal(payload)
        check(cipher.iv.size == NONCE_BYTES)
        val file = file(context, peer)
        check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val output = file.startWrite()
        try {
            output.write(cipher.iv)
            output.write(ciphertext)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun load(context: Context, receiver: String): Credentials? {
        val peer = PairingEvidence.receiverKey(receiver) ?: return null
        val file = file(context, peer)
        if (!file.baseFile.isFile) return null
        val stored = file.readFully()
        val key = key(create = false) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, stored, 0, NONCE_BYTES))
        cipher.updateAAD(peer.encodeToByteArray())
        val decoded = cipher.doFinal(stored, NONCE_BYTES, stored.size - NONCE_BYTES)
        return DataInputStream(ByteArrayInputStream(decoded)).use { Credentials(it.readUTF(), it.readUTF()) }
    }

    @Synchronized
    fun forget(context: Context, receiver: String) {
        PairingEvidence.receiverKey(receiver)?.let { file(context, it).delete() }
    }

    @Synchronized
    fun forgetAll(context: Context) {
        directory(context).listFiles().orEmpty()
            .filter { it.name.matches(Regex("bt_[0-9a-f]{12}(?:\\.new|\\.bak)?")) }
            .forEach { it.delete() }
    }

    private fun directory(context: Context) = File(context.noBackupFilesDir, "bmw-local-wifi")

    private fun file(context: Context, peer: String) = AtomicFile(File(directory(context), peer))

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
}
