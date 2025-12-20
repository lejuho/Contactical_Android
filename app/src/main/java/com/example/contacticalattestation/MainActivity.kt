package com.example.contacticalattestation

import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.contacticalattestation.v1.MsgCreateClaim
import com.example.contacticalattestation.v1.MsgGrpcKt
import com.example.contacticalattestation.v1.MsgRegisterNode
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private val TAG = "KeyAttestation"
    private val KEY_ALIAS = "ContacticalKeyAlias"

    // 체인에서 사용 중인 Alice 주소
    private val MY_WALLET_ADDRESS = "cosmos1nvmp58qukxmndy27z3tvjrx9yvek2p84r3clyg"

    private val channel by lazy {
        ManagedChannelBuilder
            .forAddress("10.0.2.2", 9095)
            .usePlaintext()
            .build()
    }

    private val stub by lazy {
        MsgGrpcKt.MsgCoroutineStub(channel)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val button = Button(this).apply {
            text = "Generate TEE Key & Register Node"
            setOnClickListener {
                lifecycleScope.launch {
                    registerNodeWithAttestation()
                }
            }
        }
        setContentView(button)
    }

    override fun onDestroy() {
        super.onDestroy()
        channel.shutdown()
    }

    // 1단계: 키 생성 + 노드 등록
    private suspend fun registerNodeWithAttestation() = withContext(Dispatchers.IO) {
        try {
            // 1) 챌린지 생성
            val challenge = ByteArray(32)
            Random.nextBytes(challenge)
            val challengeBase64 = Base64.encodeToString(challenge, Base64.NO_WRAP)

            Log.i(TAG, "📌 Challenge bytes len=${challenge.size}")
            Log.d(TAG, "📌 Challenge bytes: ${challenge.joinToString()}")
            Log.i(TAG, "📌 Challenge Base64 len=${challengeBase64.length}")
            Log.d(TAG, "📌 Challenge Base64: $challengeBase64")

            // 2) TEE 키 생성
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            )

            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .build()

            keyPairGenerator.initialize(spec)
            keyPairGenerator.generateKeyPair()

            Log.d(TAG, "✅ TEE Key Pair Generated")

            // 3) 인증서 체인 추출
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val certs = keyStore.getCertificateChain(KEY_ALIAS)

            if (certs == null || certs.isEmpty()) {
                Log.e(TAG, "❌ Certificate chain is empty")
                return@withContext
            }

            Log.i(TAG, "📜 Certificate Chain (${certs.size} certs)")

            // Base64 인코딩 + 로그
            val certChainBase64 = certs.mapIndexed { index, cert ->
                val encoded = cert.encoded
                val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
                Log.i(TAG, "🔑 Cert[$index] DER len=${encoded.size}")
                Log.i(TAG, "🔑 Cert[$index] Base64 len=${b64.length}")
                Log.d(TAG, "🔑 Cert[$index] Base64: $b64")
                b64
            }

            // 4) MsgRegisterNode 생성
            val request = MsgRegisterNode.newBuilder()
                .setCreator(MY_WALLET_ADDRESS)
                .addAllCertChain(certChainBase64)
                .setChallenge(challengeBase64)
                .setPubKey("임시_공개키_값")
                .build()

            Log.d(TAG, "📦 MsgRegisterNode.cert_chain[0] len=${request.certChainList[0].length}")
            Log.d(TAG, "📦 MsgRegisterNode.challenge len=${request.challenge.length}")

            Log.i(TAG, "📡 Calling RegisterNode RPC...")
            val response = stub.registerNode(request)

            if (response.success) {
                Log.i(TAG, "✅ Node Registered! ID: $MY_WALLET_ADDRESS")

                // 5초 정도 대기 후 Claim 제출
                lifecycleScope.launch {
                    Log.i(TAG, "⏳ Waiting 5 seconds for block confirmation...")
                    delay(5000)

                    Log.i(TAG, "🚀 Submitting data now...")
                    submitDataWithSignature(MY_WALLET_ADDRESS)
                }
            } else {
                Log.e(TAG, "❌ Registration Failed (Success=false)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during registration: ${e.message}", e)
            e.printStackTrace()
        }
    }

    // 2단계: 데이터 서명 + 제출
    private suspend fun submitDataWithSignature(creatorAddress: String) = withContext(Dispatchers.IO) {
        try {
            val payload = "Hello Contactical"

            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (entry == null) {
                Log.e(TAG, "❌ Private key not found")
                return@withContext
            }

            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(entry.privateKey)
            signature.update(payload.toByteArray(Charsets.UTF_8))
            val signatureBytes = signature.sign()
            val signatureBase64 = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)

            Log.i(TAG, "✍️ Signature bytes len=${signatureBytes.size}")
            Log.d(TAG, "✍️ Signature bytes: ${signatureBytes.joinToString()}")
            Log.i(TAG, "✍️ Signature Base64 len=${signatureBase64.length}")
            Log.d(TAG, "✍️ Signature Base64: $signatureBase64")

            val certs = keyStore.getCertificateChain(KEY_ALIAS)
            val certBase64 = Base64.encodeToString(certs[0].encoded, Base64.NO_WRAP)
            Log.i(TAG, "🔐 Claim Cert Base64 len=${certBase64.length}")
            Log.d(TAG, "🔐 Claim Cert Base64: $certBase64")

            val request = MsgCreateClaim.newBuilder()
                .setCreator(creatorAddress)
                .setPayload(payload)
                .setDataSignature(signatureBase64)
                .setCert(certBase64)
                .setTimestamp(System.currentTimeMillis() / 1000)
                .setSensorHash("dummy_sensor_hash")
                .setGnssHash("dummy_gnss_hash")
                .setAnchorSignature("dummy_anchor_sig")
                .setNodeId(creatorAddress)
                .build()

            Log.d(TAG, "📦 MsgCreateClaim.data_signature len=${request.dataSignature.length}")
            Log.d(TAG, "📦 MsgCreateClaim.cert len=${request.cert.length}")

            Log.i(TAG, "📡 Calling CreateClaim RPC...")
            val response = stub.createClaim(request)
            Log.i(TAG, "✅ Data Submitted Successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during data submission: ${e.message}", e)
        }
    }
}
