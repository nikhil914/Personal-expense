package app.ledgerly.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SQLCipher passphrase management.
 *
 * A random 32-byte passphrase is generated once and stored only in wrapped (encrypted) form in a
 * private SharedPreferences file. The wrapping key is an AES-256-GCM key that lives in the Android
 * Keystore (hardware-backed where available) and never leaves it. The prefs file is excluded from
 * backup (see res/xml/backup_rules.xml and data_extraction_rules.xml): restoring the encrypted DB on
 * another device is impossible by design; use the encrypted export for migration.
 */
class DbKeyManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreatePassphrase(): ByteArray {
        val stored = prefs.getString(KEY_WRAPPED, null)
        if (stored != null) return unwrap(stored)
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_WRAPPED, wrap(passphrase)).commit()
        return passphrase
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + ct, Base64.NO_WRAP)
    }

    private fun unwrap(encoded: String): ByteArray {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLen = all[0].toInt()
        val iv = all.copyOfRange(1, 1 + ivLen)
        val ct = all.copyOfRange(1 + ivLen, all.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ledgerly_db_kek"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS = "ledgerly_keys"
        private const val KEY_WRAPPED = "db_passphrase_wrapped"
    }
}
