package com.example.contacticalattestation.zk

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest

class ZkInputGenerator {

    companion object {
        private const val TAG = "ZkInputGenerator"
        private const val LIMB_SIZE = 64
        private const val NUM_LIMBS = 32
    }

    /**
     * ID Token을 받아서 Circom RSA 회로용 Input JSON을 생성합니다.
     */
    fun generateInput(idToken: String): Pair<String, List<String>> {
        try {
            // 1. JWT 파싱 (Header.Payload.Signature)
            val parts = idToken.split(".")
            if (parts.size != 3) throw IllegalArgumentException("Invalid JWT format")

            val headerPayload = "${parts[0]}.${parts[1]}"
            val signatureStr = parts[2]

            // 2. Message 처리 (SHA-256 해시 -> BigInteger)
            // 회로가 "Hash된 값"을 입력으로 받으므로, 여기서 해싱을 수행합니다.
            val md = MessageDigest.getInstance("SHA-256")
            val messageHashBytes = md.digest(headerPayload.toByteArray())
            // BigInteger는 부호 비트 때문에 1을 추가하여 양수로 해석하게 함
            val messageHashBI = BigInteger(1, messageHashBytes)

            // 3. Signature 처리 (Base64Url Decode -> BigInteger)
            val signatureBytes = Base64.decode(signatureStr, Base64.URL_SAFE)
            val signatureBI = BigInteger(1, signatureBytes)

            // 4. Modulus (공개키 N) 처리
            // ID Token의 kid를 확인하고, 그에 맞는 n 값을 가져옵니다.
            val modulusBI = getGoogleModulus(idToken)

            // 5. Limb 변환 (64비트 * 32개)
            val signatureLimbs = toLimbs(signatureBI)
            val modulusLimbs = toLimbs(modulusBI)
            val messageLimbs = toLimbs(messageHashBI)

            // 6. JSON 생성
            val json = JSONObject()
            json.put("signature", JSONArray(signatureLimbs))
            json.put("modulus", JSONArray(modulusLimbs))
            json.put("message", JSONArray(messageLimbs))
            json.put("message_len", NUM_LIMBS.toString()) // 길이는 Limb 개수로 고정

            // 7. Public Signals
            // (필요하다면 여기에 Message Hash나 Modulus Hash 등을 추가하여 서버가 검증하게 합니다)
            // 현재는 1로 설정 (서버 로그의 "nPublic=1"과 일치해야 함)
            val publicSignals = listOf("1")

            Log.d(TAG, "✅ ZK Input Generated Successfully")
            return Pair(json.toString(), publicSignals)

        } catch (e: Exception) {
            Log.e(TAG, "Input Generation Failed", e)
            throw e
        }
    }

    /**
     * ID Token의 kid에 맞는 Google Modulus를 반환합니다.
     */
    private fun getGoogleModulus(idToken: String): BigInteger {
        // 1. 토큰 헤더에서 kid 파싱
        val parts = idToken.split(".")
        val headerJson = String(Base64.decode(parts[0], Base64.URL_SAFE))
        val header = JSONObject(headerJson)
        val kid = header.getString("kid")

        Log.d(TAG, "🔑 ID Token KID: $kid")

        // 2. KID에 해당하는 Modulus (n) 문자열 매핑
        val targetN = when(kid) {
            // 사용자님의 ID Token KID와 일치하는 N 값
            "6a906ec119d7ba46a6a43ef1ea842e34a8ee08b4" -> "24IIHpxk2q24EcrPd-u4JnRVtBXc49cjViy8LhNrkZQNwfwJ3MfW7fNTz_9_fpRb23DulFbHpGVoOoiNKtlS6hd74-SFCMbV305pUxLBSfmZWe5iMy8tjccgRDRG4Fxp_94gMO9Wm3IvdENTXwkHBHyKW4-8l5eOPC1FqhVnwjjj-LK5IwcQTy6b2MnfOqb5u--UzQI1_Qpm7u7JCcr81K-GCzGjQ9w7tYCavMmIiZ6AU5hXcSn1rUBUAURIoMP6ThUpTxQ4-7QbCKpP51cG2RXqIJiMzsFc0RdOSrJNLJxxS_0BmxADYsfxNydAeLaJ8WJs2I47pLOhwx7H-FdV_Q"

            // (필요 시 다른 kid 추가 가능)
            "496d008e8c7be1cae4209e0d5c21b050a61e960f" -> "nLFX9zZNqpLMgVGQ3WPbnwMTRo6AexegmSDsujoOQFkldYXdjFibe18IFky68nVtow-9AOMVkKYFoPA19_DP035iALm-jF2jmbsZiO5LFlxl91CH4y5jOZ85t2OJ77E8yGeY9xWFNAiizZpESk9ZAQ_siGsJbnyGD5M0bxgZNTp1wjzk6Nj_e00zOFYX0_lNXr8iizYnflXbMx-VRfcl_YP6jZ5Spvm3EAvWGQTziB6RcsAYvc7g6BIhVS92xlNtg1sfxWzlhVspjkGsCoELQaraBGWhqhB7rvgRvJOMoy_QzVmnyKTmh6RPNSp2ZYAe1c5LbZdJUWG-zgy1aUhqVw"

            else -> throw IllegalArgumentException("Unknown KID: $kid. Please update ZkInputGenerator with the latest Google JWKS keys.")
        }

        // 3. Base64Url 디코딩 -> BigInteger 변환
        val nBytes = Base64.decode(targetN, Base64.URL_SAFE)
        return BigInteger(1, nBytes)
    }

    /**
     * BigInteger를 64비트 단위의 String 배열(32개)로 쪼갭니다. (Little-Endian)
     */
    private fun toLimbs(value: BigInteger): List<String> {
        val limbs = mutableListOf<String>()
        var current = value

        // 2^64 - 1 (64비트 마스크)
        val mask = BigInteger.ONE.shiftLeft(LIMB_SIZE).subtract(BigInteger.ONE)

        for (i in 0 until NUM_LIMBS) {
            // 하위 64비트 추출
            val limb = current.and(mask)
            limbs.add(limb.toString())

            // 64비트 오른쪽으로 이동
            current = current.shiftRight(LIMB_SIZE)
        }

        return limbs
    }
}