package com.example.contacticalattestation.zk

class NativeProver {
    companion object {
        init {
            System.loadLibrary("contactical-prover")
        }
    }

    // [수정됨] 이제 JSON이 아니라 파일 경로 2개를 받습니다.
    external fun generateProof(zkeyPath: String, wtnsPath: String): String
}