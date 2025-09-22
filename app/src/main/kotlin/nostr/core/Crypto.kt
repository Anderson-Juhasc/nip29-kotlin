package com.nostr.core

import fr.acinq.secp256k1.Secp256k1
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Nostr cryptographic operations using Schnorr signatures (BIP-340)
 * with fr.acinq.secp256k1
 */
object Crypto {
  private val secp256k1 = Secp256k1.get()
  private val secureRandom = SecureRandom()
  private val n = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16) // secp256k1 order

  /**
   * Generate a new valid private key (32 bytes)
   */
  fun generatePrivateKey(): ByteArray {
    while (true) {
      val priv = ByteArray(32)
      secureRandom.nextBytes(priv)
      if (secp256k1.secKeyVerify(priv)) return priv
    }
  }

  /**
   * Get compressed public key (33 bytes, 0x02/0x03 + X)
   */
  fun getPublicKey(privateKey: ByteArray): ByteArray {
    require(privateKey.size == 32) { "Private key must be 32 bytes" }
    val pub = secp256k1.pubkeyCreate(privateKey)
    return secp256k1.pubKeyCompress(pub)
  }

  /**
   * Get x-only public key (32 bytes, no prefix) as required by Nostr/BIP-340
   */
  fun getPublicKeyXOnly(privateKey: ByteArray): ByteArray {
    val compressed = getPublicKey(privateKey)
    return compressed.copyOfRange(1, 33)
  }

  /**
   * Get public key as hex string (x-only)
   */
  fun getPublicKeyHex(privateKey: ByteArray): String {
    return getPublicKeyXOnly(privateKey).toHexString()
  }

  /**
   * Ensure even Y per BIP-340 (if pubkey y is odd, use n - d)
   */
  private fun ensureEvenYSecretKey(privateKey: ByteArray): ByteArray {
    val compressed = getPublicKey(privateKey)
    val prefix = compressed[0].toInt() and 0xFF
    return if (prefix == 0x03) {
      val d = BigInteger(1, privateKey)
      val dPrime = n.subtract(d).mod(n)
      dPrime.toFixedLengthByteArray(32)
    } else {
      privateKey
    }
  }

  /**
   * Sign a 32-byte message hash with Schnorr (BIP-340)
   *
   * Returns a 64-byte signature (r||s)
   */
  fun signMessage(
    privateKey: ByteArray,
    messageHash: ByteArray,
  ): ByteArray {
    require(messageHash.size == 32) { "Message hash must be 32 bytes" }
    require(privateKey.size == 32) { "Private key must be 32 bytes" }

    val secret = ensureEvenYSecretKey(privateKey)
    return secp256k1.signSchnorr(messageHash, secret, null)
  }

  /**
   * Verify Schnorr signature against 32-byte message hash and x-only public key
   */
  fun verifySignature(
    signature: ByteArray,
    messageHash: ByteArray,
    publicKey: ByteArray,
  ): Boolean {
    require(signature.size == 64) { "Signature must be 64 bytes" }
    require(messageHash.size == 32) { "Message hash must be 32 bytes" }

    // Accept compressed (0x02/0x03 + X) or x-only (32 bytes)
    val xOnly =
      when (publicKey.size) {
        32 -> publicKey
        33 -> publicKey.copyOfRange(1, 33) // remove prefix 0x02/0x03
        else -> throw IllegalArgumentException("Public key must be 32 (x-only) or 33 (compressed) bytes")
      }

    // IMPORTANT: fr.acinq verifySchnorr expects the 32-byte x-only public key (no prefix).
    return secp256k1.verifySchnorr(signature, messageHash, xOnly)
  }

  /**
   * Hash data with SHA-256
   */
  fun sha256(data: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(data)
  }

  /**
   * Hash string with SHA-256
   */
  fun sha256(data: String): ByteArray {
    return sha256(data.toByteArray())
  }
}

/**
 * Convert byte array to hex string
 */
fun ByteArray.toHexString(): String {
  return joinToString("") { "%02x".format(it) }
}

/**
 * Convert hex string to byte array
 */
fun String.hexToByteArray(): ByteArray {
  check(length % 2 == 0) { "Hex string must have even length" }
  return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * BigInteger -> fixed-length byte array
 */
fun BigInteger.toFixedLengthByteArray(length: Int): ByteArray {
  val arr = this.toByteArray()
  return when {
    arr.size == length -> arr
    arr.size > length -> arr.copyOfRange(arr.size - length, arr.size)
    else -> ByteArray(length - arr.size) + arr
  }
}
