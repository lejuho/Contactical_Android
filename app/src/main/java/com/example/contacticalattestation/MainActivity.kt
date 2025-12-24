package com.example.contacticalattestation

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.contacticalattestation.v1.MsgGrpcKt
import com.example.contacticalattestation.v1.MsgRegisterNode
import com.example.contacticalattestation.zk.ZkInputGenerator
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private val TAG = "ZkLogin"
    private val RC_SIGN_IN = 9001

    // 체인 주소
    private val MY_WALLET_ADDRESS = "cosmos1yzzdt6epr46evz8uwn4etklqq2kqgvymr0n477"

    private val channel by lazy {
        // 에뮬레이터 루프백 주소 (로컬 프록시 연결용)
        ManagedChannelBuilder.forAddress("10.0.2.2", 9095).usePlaintext().build()
    }
    private val stub by lazy { MsgGrpcKt.MsgCoroutineStub(channel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val button = Button(this).apply {
            text = "Google Sign-In & ZK Register"
            setOnClickListener { startGoogleSignIn() }
        }
        setContentView(button)
    }

    // 1. 구글 로그인 시작
    private fun startGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("1052539334492-463oh6ok57smp7q7uch055jh4bjj0mdv.apps.googleusercontent.com") // 구글 클라우드 콘솔 Client ID
            .requestEmail()
            .build()
        val client = GoogleSignIn.getClient(this, gso)
        startActivityForResult(client.signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken
                Log.d(TAG, "ID Token: $idToken")

                if (idToken != null) {
                    lifecycleScope.launch { processZkRegistration(idToken) }
                }
            } catch (e: ApiException) {
                Log.w(TAG, "SignIn failed code=${e.statusCode}")
            }
        }
    }

    // processZkRegistration 함수 전체 수정

    private suspend fun processZkRegistration(idToken: String) = withContext(Dispatchers.IO) {
        try {
            // ----------------------------------------------------------------
            // 1. TEE Key Pair 생성 및 인증서 추출 (이 부분이 누락되었을 수 있음)
            // ----------------------------------------------------------------
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            // 키가 없으면 새로 생성
            if (!keyStore.containsAlias("ContacticalKeyAlias")) {
                val keyPairGenerator = java.security.KeyPairGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_EC,
                    "AndroidKeyStore"
                )
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    "ContacticalKeyAlias",
                    android.security.keystore.KeyProperties.PURPOSE_SIGN
                )
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge("dummy_challenge".toByteArray()) // 챌린지 설정
                    .build()

                keyPairGenerator.initialize(spec)
                keyPairGenerator.generateKeyPair()
                Log.d(TAG, "✅ New TEE Key Generated")
            }

            // 인증서 체인 가져오기
            val certs = keyStore.getCertificateChain("ContacticalKeyAlias")
            if (certs == null || certs.isEmpty()) {
                Log.e(TAG, "❌ Failed to get certificate chain. Is this a real device?")
                return@withContext
            }

            // 인증서를 Base64 문자열 리스트로 변환
            val certChainBase64 = certs.map { cert ->
                android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP)
            }
            Log.d(TAG, "📜 Cert Chain Size: ${certChainBase64.size}")

            // PubKey 추출 (Key_A)
            val devicePubKey = certs[0].publicKey.toString() // 또는 encoded 된 값을 사용해도 됨

            // ----------------------------------------------------------------
            // 2. ZK 로직 (기존 코드)
            // ----------------------------------------------------------------
            val generator = ZkInputGenerator()
            val zkInputJson = generator.generateInput(idToken, devicePubKey)

            val proofBytes = loadAssetProof()
            val publicSignals = listOf("1", "1")

            // ----------------------------------------------------------------
            // 3. 전송 (ZK Proof + TEE Certs)
            // ----------------------------------------------------------------
            val request = MsgRegisterNode.newBuilder()
                .setCreator(MY_WALLET_ADDRESS)
                .setZkProof(ByteString.copyFrom(proofBytes))
                .addAllPublicSignals(publicSignals)
                .setPubKey(devicePubKey)
                // [중요] 여기를 꼭 추가해야 합니다!
                .addAllCertChain(certChainBase64)
                .build()

            Log.i(TAG, "📡 Sending RegisterNode to Proxy...")
            val response = stub.registerNode(request)

            if (response.success) {
                Log.i(TAG, "✅ Success! Node Registered via ZK + TEE.")
            } else {
                Log.e(TAG, "❌ Failed.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun loadAssetProof(): ByteArray {
        // assets 폴더에 proof.json을 넣어두세요.
        return try {
            assets.open("proof.json").use { it.readBytes() }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        channel.shutdown()
    }
}