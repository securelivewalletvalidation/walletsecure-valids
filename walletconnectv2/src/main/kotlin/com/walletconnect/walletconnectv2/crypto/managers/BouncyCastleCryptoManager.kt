package com.walletconnect.walletconnectv2.crypto.managers


import org.bouncycastle.math.ec.rfc7748.X25519
import com.walletconnect.walletconnectv2.common.Topic
import com.walletconnect.walletconnectv2.crypto.CryptoManager
import com.walletconnect.walletconnectv2.crypto.data.PrivateKey
import com.walletconnect.walletconnectv2.crypto.data.PublicKey
import com.walletconnect.walletconnectv2.crypto.data.SharedKey
import com.walletconnect.walletconnectv2.storage.KeyChain
import com.walletconnect.walletconnectv2.storage.KeyStore
import com.walletconnect.walletconnectv2.util.bytesToHex
import com.walletconnect.walletconnectv2.util.hexToBytes
import java.security.MessageDigest
import java.security.SecureRandom
import com.walletconnect.walletconnectv2.crypto.data.Key as WCKey

class BouncyCastleCryptoManager(private val keyChain: KeyStore = KeyChain()) : CryptoManager {

    override fun generateKeyPair(): PublicKey {
        val publicKey = ByteArray(KEY_SIZE)
        val privateKey = ByteArray(KEY_SIZE)
        X25519.generatePrivateKey(SecureRandom(ByteArray(KEY_SIZE)), privateKey)
        X25519.generatePublicKey(privateKey, 0, publicKey, 0)
        setKeyPair(PublicKey(publicKey.bytesToHex().lowercase()), PrivateKey(privateKey.bytesToHex().lowercase()))
        return PublicKey(publicKey.bytesToHex().lowercase())
    }

    internal fun getSharedKey(selfPrivate: PrivateKey, peerPublic: PublicKey): String {
        val sharedKeyBytes = ByteArray(KEY_SIZE)
        X25519.scalarMult(selfPrivate.keyAsHex.hexToBytes(), 0, peerPublic.keyAsHex.hexToBytes(), 0, sharedKeyBytes, 0)
        return sharedKeyBytes.bytesToHex()
    }

    override fun generateTopicAndSharedKey(self: PublicKey, peer: PublicKey): Pair<SharedKey, Topic> {
        val (publicKey, privateKey) = getKeyPair(self)
        val sharedKeyBytes = ByteArray(KEY_SIZE)
        X25519.scalarMult(privateKey.keyAsHex.hexToBytes(), 0, peer.keyAsHex.hexToBytes(), 0, sharedKeyBytes, 0)
        val sharedKey = SharedKey(sharedKeyBytes.bytesToHex())
        val topic = generateTopic(sharedKey.keyAsHex)
        setEncryptionKeys(sharedKey, publicKey, Topic(topic.value.lowercase()))
        return Pair(sharedKey, topic)
    }

    override fun setEncryptionKeys(sharedKey: SharedKey, publicKey: PublicKey, topic: Topic) {
        keyChain.setKey(topic.value, sharedKey, publicKey)
    }

    override fun removeKeys(tag: String) {
        val (_, publicKey) = keyChain.getKeys(tag)
        with(keyChain) {
            deleteKeys(publicKey.lowercase())
            deleteKeys(tag)
        }
    }

    override fun getKeyAgreement(topic: Topic): Pair<SharedKey, PublicKey> {
        val (sharedKey, peerPublic) = keyChain.getKeys(topic.value)
        return Pair(SharedKey(sharedKey), PublicKey(peerPublic))
    }

    internal fun setKeyPair(publicKey: PublicKey, privateKey: PrivateKey) {
        keyChain.setKey(publicKey.keyAsHex, publicKey, privateKey)
    }

    internal fun getKeyPair(wcKey: WCKey): Pair<PublicKey, PrivateKey> {
        val (publicKeyHex, privateKeyHex) = keyChain.getKeys(wcKey.keyAsHex)
        return Pair(PublicKey(publicKeyHex), PrivateKey(privateKeyHex))
    }

    private fun generateTopic(sharedKey: String): Topic {
        val messageDigest: MessageDigest = MessageDigest.getInstance(SHA_256)
        val hashedBytes: ByteArray = messageDigest.digest(sharedKey.hexToBytes())
        return Topic(hashedBytes.bytesToHex())
    }

    companion object {
        private const val KEY_SIZE: Int = 32
        private const val SHA_256: String = "SHA-256"
    }
}