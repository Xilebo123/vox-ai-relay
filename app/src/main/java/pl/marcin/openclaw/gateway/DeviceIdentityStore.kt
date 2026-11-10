package pl.marcin.openclaw.gateway

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import pl.marcin.openclaw.storage.SecureCredentialStore
import java.security.MessageDigest
import java.security.SecureRandom

data class DeviceIdentity(val deviceId: String, val publicKey: String, val privateKeyPkcs8: String)

class DeviceIdentityStore(context: Context) {
    private val secure = SecureCredentialStore(context)

    @Synchronized
    fun loadOrCreate(): DeviceIdentity {
        val public = secure.get(PUBLIC_KEY)
        val private = secure.get(PRIVATE_KEY)
        if (public != null && private != null) {
            val raw = decodeUrl(public)
            return DeviceIdentity(sha256Hex(raw), public, private)
        }
        val generator = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }
        val pair = generator.generateKeyPair()
        val pub = pair.public as Ed25519PublicKeyParameters
        val priv = pair.private as Ed25519PrivateKeyParameters
        return DeviceIdentity(
            deviceId = sha256Hex(pub.encoded),
            publicKey = encodeUrl(pub.encoded),
            privateKeyPkcs8 = encodeUrl(PrivateKeyInfoFactory.createPrivateKeyInfo(priv).encoded),
        ).also {
            secure.put(PUBLIC_KEY, it.publicKey)
            secure.put(PRIVATE_KEY, it.privateKeyPkcs8)
        }
    }

    fun sign(payload: String, identity: DeviceIdentity): String {
        val privateKey = PrivateKeyFactory.createKey(decodeUrl(identity.privateKeyPkcs8))
            as Ed25519PrivateKeyParameters
        return Ed25519Signer().run {
            init(true, privateKey)
            val bytes = payload.toByteArray(Charsets.UTF_8)
            update(bytes, 0, bytes.size)
            encodeUrl(generateSignature())
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun encodeUrl(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeUrl(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        private const val PUBLIC_KEY = "device.publicKey"
        private const val PRIVATE_KEY = "device.privateKey"
    }
}
