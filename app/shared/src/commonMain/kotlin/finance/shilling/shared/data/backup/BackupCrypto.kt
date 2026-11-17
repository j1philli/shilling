package finance.shilling.shared.data.backup

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64

/** AES-GCM uses provider-generated random IVs and authenticates the complete object context. */
class BackupCrypto {
    private val aes get() = CryptographyProvider.Default.get(AES.GCM)
    suspend fun newKey(): String = Base64.encode(aes.keyGenerator().generateKey().encodeToByteArray(AES.Key.Format.RAW))
    suspend fun validateKey(key: String) { decode(key) }
    private suspend fun decode(key: String): AES.GCM.Key {
        val bytes = Base64.decode(key.trim())
        require(bytes.size == 32) { "A recovery key must contain 32 bytes." }
        return aes.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, bytes)
    }
    suspend fun encrypt(key: String, context: String, bytes: ByteArray): String =
        Base64.encode(decode(key).cipher().encrypt(bytes, context.encodeToByteArray()))
    suspend fun decrypt(key: String, context: String, ciphertext: String): ByteArray =
        decode(key).cipher().decrypt(Base64.decode(ciphertext), context.encodeToByteArray())
    suspend fun digest(bytes: ByteArray): String = Base64.UrlSafe.encode(CryptographyProvider.Default.get(SHA256).hasher().hash(bytes))
    suspend fun fingerprint(key: String): String = Base64.UrlSafe.encode(
        CryptographyProvider.Default.get(SHA256).hasher().hash(Base64.decode(key))
    )
}

/** Platform implementations must durably store secrets before returning. Never use plain Settings. */
interface BackupKeyVault {
    suspend fun read(namespace: String): String?
    suspend fun write(namespace: String, secret: String)
    suspend fun delete(namespace: String)
}
