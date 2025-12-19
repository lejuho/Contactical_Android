package com.example.contacticalattestation

import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
// [변경] 바뀐 Proto 서비스와 메시지 임포트
import com.example.contacticalattestation.v1.MsgGrpcKt
import com.example.contacticalattestation.v1.MsgRegisterNode
import com.example.contacticalattestation.v1.MsgCreateClaim
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private val TAG = "KeyAttestation"
    private val KEY_ALIAS = "ContacticalKeyAlias"

    // [중요] 실제 Ignite 체인에서 생성된 Alice 지갑 주소
    // 블록체인에서는 이 주소가 곧 Node ID 역할을 합니다.
    private val MY_WALLET_ADDRESS = "cosmos1y3d6pupvh0vnhvd9dhujsk5rvpw8hmj3r3jng9"

    // gRPC 채널
    private val channel by lazy {
        ManagedChannelBuilder
            .forAddress("10.0.2.2", 9095) // AVD(10.0.2.2) -> PC(localhost:9090)
            .usePlaintext()
            .build()
    }

    // [변경] Stub 이름이 MsgCoroutineStub으로 변경됨
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
            // 챌린지 생성
            val challenge = ByteArray(32)
            Random.nextBytes(challenge)
            val challengeBase64 = Base64.encodeToString(challenge, Base64.NO_WRAP)

            // TEE 키 생성
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

            // 인증서 체인 추출
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val certs = keyStore.getCertificateChain(KEY_ALIAS)

            if (certs == null || certs.isEmpty()) {
                Log.e(TAG, "❌ Certificate chain is empty")
                return@withContext
            }

            // Base64 인코딩
            val certChainBase64 = certs.map { cert ->
                Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
            }

            Log.i(TAG, "📜 Certificate Chain (${certs.size} certs)")

            // [변경] MsgRegisterNode 사용
            val request = MsgRegisterNode.newBuilder()
                .setCreator(MY_WALLET_ADDRESS) // [필수] 올바른 Bech32 주소
                .addAllCertChain(certChainBase64)
                .setChallenge(challengeBase64)
                .setPubKey("임시_공개키_값") // 나중에 실제 키 로직으로 교체 가능
                .build()

            Log.i(TAG, "📡 Calling RegisterNode RPC...")

            // [변경] stub.registerNode 호출
            val response = stub.registerNode(request)

            // [변경] response.nodeId 필드는 없음. 지갑 주소로 식별.
            if (response.success) {
                Log.i(TAG, "✅ Node Registered! ID: $MY_WALLET_ADDRESS")

                // 2단계: 데이터 서명 및 제출 (ID 대신 지갑주소 전달)
                submitDataWithSignature(MY_WALLET_ADDRESS)
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

            // TEE로 서명 생성
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

            // 인증서 가져오기
            val certs = keyStore.getCertificateChain(KEY_ALIAS)
            val certBase64 = Base64.encodeToString(certs[0].encoded, Base64.NO_WRAP)

            Log.i(TAG, "✍️ Data Signed: $payload")

            // [변경] MsgCreateClaim 사용 (필드명 주의)
            val request = MsgCreateClaim.newBuilder()
                .setCreator(creatorAddress)
                .setPayload(payload)              // 우리가 추가한 필드
                .setDataSignature(signatureBase64) // proto: data_signature
                .setCert(certBase64)              // 우리가 추가한 필드
                .setTimestamp(System.currentTimeMillis() / 1000) // 현재 시간
                // 아래 필드들은 Proto 정의상 필수이므로 더미 값이라도 넣어야 함
                .setSensorHash("dummy_sensor_hash")
                .setGnssHash("dummy_gnss_hash")
                .setAnchorSignature("dummy_anchor_sig")
                .setNodeId(creatorAddress)        // 우리가 추가한 필드 (선택)
                .build()

            Log.i(TAG, "📡 Calling CreateClaim RPC...")

            // [변경] stub.createClaim 호출
            val response = stub.createClaim(request)

            // Cosmos Msg 응답은 보통 빈 객체({})면 성공입니다.
            // gRPC 에러(Exception)가 안 났다면 성공으로 간주합니다.
            Log.i(TAG, "✅ Data Submitted Successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during data submission: ${e.message}", e)
        }
    }
}