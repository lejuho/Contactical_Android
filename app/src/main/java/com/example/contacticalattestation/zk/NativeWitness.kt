package com.example.contacticalattestation.zk

class NativeWitness {
    companion object {
        init {
            System.loadLibrary("witness-calc")
        }
    }

    /**
     * @param inputJsonStr: ZK 입력값 (JSON String)
     * @param datPath: assets에서 복사한 circuit.dat 파일 경로
     * @param wtnsPath: 결과물이 저장될 .wtns 파일 경로
     */
    external fun calcWitness(inputJsonStr: String, datPath: String, wtnsPath: String): Boolean
}