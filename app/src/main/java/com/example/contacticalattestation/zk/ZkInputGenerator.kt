package com.example.contacticalattestation.zk

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets

class ZkInputGenerator {

    // JWT를 입력받아 ZK Prover용 JSON 문자열 반환
    fun generateInput(idToken: String, devicePubKey: String): String {
        val parts = idToken.split(".")
        if (parts.size != 3) throw IllegalArgumentException("Invalid JWT format")

        val header = parts[0]
        val payload = parts[1]
        val signature = parts[2]

        // 1. Signature를 BigInteger로 변환 및 쪼개기 (Limb Split)
        // 서명은 Base64Url 인코딩 되어 있음
        val sigBytes = Base64.decode(signature, Base64.URL_SAFE)
        val sigBigInt = BigInteger(1, sigBytes)

        // RSA 2048bit 서명을 121bit 단위 17개 조각으로 나눔 (Circom 회로 스펙에 따라 다름, 보통 64 or 121)
        // 여기서는 zk-email 표준인 121bit 예시
        val signatureLimbs = splitToLimbs(sigBigInt, 121, 17)

        // 2. 메시지(Header + . + Payload)를 ASCII Int 배열로 변환 및 패딩
        val messageStr = "$header.$payload"
        val messageBytes = messageStr.toByteArray(StandardCharsets.US_ASCII)

        // SHA-256 블록 사이즈(64 bytes)에 맞게 패딩 (회로 스펙 확인 필요)
        // 여기서는 단순하게 바이트 배열로 변환 (최대 길이 제한 주의)
        val messageInts = messageBytes.map { it.toInt() }
        val messageLen = messageBytes.size

        // 3. PubKey Hash (Poseidon Hash 필요, 여기서는 Mock 처리)
        // 실제로는 devicePubKey를 해싱해서 BigInt 문자열로 변환해야 함
        val pubKeyHash = "123456789" // TODO: Implement Poseidon Hash in Kotlin

        // 4. JSON 생성
        val json = JSONObject()
        json.put("jwt_signature", signatureLimbs)
        json.put("jwt_message", messageInts) // 패딩 로직 추가 필요할 수 있음
        json.put("jwt_message_len", messageLen)
        json.put("pubkey_hash", pubKeyHash)

        // RSA Public Key (Modulus) 정보도 필요함 (구글 키 서버에서 가져오거나 하드코딩)
        // json.put("rsa_modulus", ...)

        return json.toString()
    }

    private fun splitToLimbs(value: BigInteger, bits: Int, count: Int): List<String> {
        val limbs = mutableListOf<String>()
        var current = value
        val mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)

        for (i in 0 until count) {
            limbs.add(current.and(mask).toString())
            current = current.shiftRight(bits)
        }
        return limbs
    }
}