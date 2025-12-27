package com.example.contacticalattestation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.contacticalattestation.v1.MsgCreateClaim
import com.example.contacticalattestation.v1.MsgGrpcKt
import com.example.contacticalattestation.v1.MsgRegisterNode
import com.example.contacticalattestation.zk.NativeProver
import com.example.contacticalattestation.zk.NativeWitness
import com.example.contacticalattestation.zk.ZkInputGenerator
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.Signature
import kotlinx.coroutines.withTimeout

class MainActivity : AppCompatActivity() {

    private val TAG = "ZkLogin"
    private val RC_SIGN_IN = 9001
    private val KEY_ALIAS = "ContacticalKeyAlias"

    // 체인 주소 (이 지갑 주소가 NodeID가 됩니다)
    private val MY_WALLET_ADDRESS = "cosmos1wnjf06xyn68svgcferpm8lz42mgpwp3l37aj3y"

    private val channel by lazy {
        // 에뮬레이터 루프백 -> 로컬 프록시 (localhost:9095)
        ManagedChannelBuilder.forAddress("127.0.0.1", 9095).usePlaintext().build()
    }
    private val stub by lazy { MsgGrpcKt.MsgCoroutineStub(channel) }

    // Load native library
    companion object {
        init {
            System.loadLibrary("contactical-prover")
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        
        val button = Button(this).apply {
            text = "Google Sign-In -> Register -> Claim"
            setOnClickListener {
                it.isEnabled = false // 🔥 중복 실행 방지 (이게 Mutex 역할 대체)
                startGoogleSignIn() }
        }
        setContentView(button)
    }

    // 1. 구글 로그인 시작
    private fun startGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("1052539334492-463oh6ok57smp7q7uch055jh4bjj0mdv.apps.googleusercontent.com") // Web Client ID 확인 필수
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
                    lifecycleScope.launch {
                        // 1. 등록 프로세스 실행
                        val isRegistered = processZkRegistration(idToken)

                        // 2. 등록 성공 시, 3초 뒤 데이터 제출(Claim) 시도
                        if (isRegistered) {
                            Log.i(TAG, "⏳ Waiting 6s for block confirmation...")
                            delay(6000)
                            submitClaimWithTeeSignature()
                        }
                    }
                }
            } catch (e: ApiException) {
                Log.w(TAG, "SignIn failed code=${e.statusCode}")
            }
        }
    }

    // ----------------------------------------------------------------
    // 단계 1: 노드 등록 (ZK Proof + TEE Attestation)
    // ----------------------------------------------------------------
    private suspend fun processZkRegistration(idToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            // A. TEE 키 생성 (없으면)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyPairGenerator = java.security.KeyPairGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_EC,
                    "AndroidKeyStore"
                )
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN
                )
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge("contactical_challenge".toByteArray())
                    .build()

                keyPairGenerator.initialize(spec)
                keyPairGenerator.generateKeyPair()
                Log.d(TAG, "✅ New TEE Key Generated")
            }

            // B. 인증서 체인 추출
            val certs = keyStore.getCertificateChain(KEY_ALIAS)
            if (certs == null || certs.isEmpty()) {
                Log.e(TAG, "❌ Failed to get certificate chain.")
                return@withContext false
            }

            val certChainBase64 = certs.map { cert ->
                Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
            }

            // C. PubKey 추출 (Key_A) -> Base64 인코딩
            val devicePubKey = Base64.encodeToString(certs[0].publicKey.encoded, Base64.NO_WRAP)

            // [수정됨] D. ZK 입력 생성 및 Witness 계산 (Real-Time!)

            // 1. 필요한 파일 준비
            // zkey와 dat 파일은 불변이므로 Assets에서 복사
            val zkeyPath = copyAssetToCache(applicationContext, "circuit.zkey")
            val datPath = copyAssetToCache(applicationContext, "circuit.dat") // witness 계산용

            // 2. 결과물이 저장될 경로 지정 (매번 새로 씀)
            val wtnsPath = File(applicationContext.cacheDir, "witness.wtns").absolutePath

            Log.i(TAG, "🚀 Generating ZK Input from ID Token...")

            // 3. JWT -> ZK Input JSON 변환 (Kotlin Logic)
            // [중요] ZkInputGenerator가 올바르게 구현되어 있어야 합니다.
            // 👇 수정 후
            val generator = ZkInputGenerator()
            // TODO: Fetch real modulus from Google JWKS
            val dummyModulus = "C518..." // Placeholder - Replace with real 2048-bit modulus hex
            val (zkInputJsonStr, publicSignals) = generator.generateInput(idToken)

            Log.d(TAG, "🔍 Generated Input JSON: $zkInputJsonStr")
            Log.d(TAG, "🔍 Public Signals: $publicSignals")  // 🔍 새 로그 추가
            // generateInput 함수가 String(JSON)을 반환한다고 가정

            Log.d(TAG, "🔍 Generated Input JSON: $zkInputJsonStr")

            // 4. Witness 계산 (C++ Native)
            // (input.json + circuit.dat -> witness.wtns)
            val nativeWitness = NativeWitness()
            // [수정] 타임아웃 추가 (예: 20초)
            // 20초가 지나면 TimeoutCancellationException이 발생하여 앱이 멈추지 않고 다음으로 넘어갑니다.
            val witnessSuccess = try {
                withTimeout(20_000L) {
                    nativeWitness.calcWitness(zkInputJsonStr, datPath, wtnsPath)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "⏰ Witness Calculation Timed Out! (C++ Deadlock or Slow)")
                false
            }

            if (!witnessSuccess) {
                Log.e(TAG, "❌ Witness Calculation Failed")
                return@withContext false
            }
            Log.d(TAG, "✅ Witness Calculated Successfully at: $wtnsPath")

            // 5. Proof 생성 (Rapidsnark C++ Native)
            // (circuit.zkey + 방금 만든 witness.wtns -> proof)
            val nativeProver = NativeProver()
            val proofJson = nativeProver.generateProof(zkeyPath, wtnsPath)

            if (proofJson == "ERROR") {
                Log.e("ZkLogin", "❌ Proof Generation Failed inside C++")
                return@withContext false
            }

            Log.d("ZkLogin", "⚡ Real Proof from Rapidsnark: $proofJson")

            // 6. Proof JSON 파싱 및 필드 추출
            // 서버(Go-witness-verifier 등)가 기대하는 포맷은 pi_a, pi_b, pi_c 좌표들의 배열인 경우가 많습니다.
            // 현재 proofJson은 {"pi_a":["...","...","1"], "pi_b":[["...","..."],["...","..."],["1","0"]], ...} 형태입니다.
            // 만약 서버에서 이 JSON 전체를 string으로 받아서 파싱하는게 아니라,
            // 특정 바이너리 구조를 원한다면 여기서 변환 로직이 필요합니다.
            
            // 오류 메시지 "bn256: malformed point"는 좌표값이 잘못되었거나 형식이 맞지 않을 때 발생합니다.
            // ByteString.copyFrom(proofJson.toByteArray(Charsets.UTF_8)) 처럼 보내면
            // 서버는 텍스트 "{"를 좌표의 첫 바이트로 인식하여 에러가 납니다.

            // 일단은 JSON 문자열 그대로 보내는 것이 아니라, 
            // 서버가 기대하는 "JSON 문자열 그 자체"를 전송하도록 유지하되
            // 서버측 검증 코드가 이 JSON을 어떻게 처리하는지 확인이 필요합니다.
            val proofBytes = proofJson.toByteArray(Charsets.UTF_8)

            // E. 전송 (gRPC) - 기존과 동일
            val request = MsgRegisterNode.newBuilder()
                .setCreator(MY_WALLET_ADDRESS)
                .setZkProof(ByteString.copyFrom(proofBytes))
                .addAllPublicSignals(publicSignals)
                .setPubKey(devicePubKey)
                .addAllCertChain(certChainBase64)
                .build()

            Log.i(TAG, "📡 Sending RegisterNode...")
            val response = stub.registerNode(request)

            if (response.success) {
                Log.i(TAG, "✅ Registration Success!")
                return@withContext true
            } else {
                Log.e(TAG, "❌ Registration Returned False")
                return@withContext false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Register Error: ${e.message}", e)
            return@withContext false
        }
    }

    // ----------------------------------------------------------------
    // 단계 2: 데이터 제출 (TEE 서명 포함)
    // ----------------------------------------------------------------
    private suspend fun submitClaimWithTeeSignature() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "🚀 Starting Submit Claim Process...")

            // 1. 데이터(Payload) 생성
            // Proxy가 int64를 원하므로 1,000,000을 곱해서 전송
            val lat = 37.5665
            val lng = 126.9780


            val latInt = (lat * 1_000_000).toLong()
            val lngInt = (lng * 1_000_000).toLong()

            // [중요] 서명할 원본 메시지 (Proxy 검증 로직과 순서/형식이 일치해야 함)
            // 여기서는 단순 문자열 payload를 서명한다고 가정
            val timestamp = System.currentTimeMillis() / 1000 // UNIX timestamp (seconds)
            val payloadString = "lat:$latInt,lng:$lngInt,ts:$timestamp"

            // 2. TEE 키로 서명 (Sign)
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry

            if (entry == null) {
                Log.e(TAG, "❌ Key not found for signing")
                return@withContext
            }

            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(entry.privateKey)
            signature.update(payloadString.toByteArray(StandardCharsets.UTF_8))

            val signBytes = signature.sign()
            val signBase64 = Base64.encodeToString(signBytes, Base64.NO_WRAP)


            Log.d(TAG, "✍️ Payload: $payloadString")
            Log.d(TAG, "✍️ Signature: $signBase64")

            // 3. CreateClaim 요청 생성
            val request = MsgCreateClaim.newBuilder()
                .setCreator(MY_WALLET_ADDRESS)
                .setNodeId(MY_WALLET_ADDRESS) // NodeID = Creator Address
                .setPayload(payloadString)    // Proxy가 검증할 원본 데이터
                .setDataSignature(signBase64) // TEE 서명
                .setLatitude(latInt)
                .setLongitude(lngInt)
                .setTimestamp(timestamp)
                .build()

            // 4. 전송
            Log.i(TAG, "📡 Sending CreateClaim...")
            stub.createClaim(request)
            Log.i(TAG, "✅ Claim Submitted Successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "Claim Error: ${e.message}", e)
        }
    }


    fun copyAssetToCache(context: Context, fileName: String): String {
        val file = File(context.cacheDir, fileName)

        if (file.exists()) {
            Log.d(TAG, "✅ Asset already exists, skipping copy: $fileName")
            return file.absolutePath
        }

        try {
            context.assets.open(fileName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d(TAG, "✅ Asset copied: $fileName (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to copy asset", e)
        }

        return file.absolutePath
    }

    override fun onDestroy() {
        super.onDestroy()
        channel.shutdown()
    }
}