#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <iostream>
#include <android/log.h>
#include <sys/time.h>

// Rapidsnark C API 헤더 포함 (groth16.hpp 대신 사용)
#include "prover.h"

#define TAG "NativeProver"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 파일 내용을 바이너리 버퍼로 읽는 헬퍼 함수
std::vector<char> readFileToBuffer(const std::string& filePath) {
    std::ifstream file(filePath, std::ios::binary | std::ios::ate);
    if (!file) {
        return {};
    }
    std::streamsize size = file.tellg();
    file.seekg(0, std::ios::beg);

    std::vector<char> buffer(size);
    if (file.read(buffer.data(), size)) {
        return buffer;
    }
    return {};
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_contacticalattestation_zk_NativeProver_generateProof(
        JNIEnv* env,
        jobject /* this */,
        jstring zkeyPath,
        jstring wtnsPath) {

    const char *zkey_path = env->GetStringUTFChars(zkeyPath, 0);
    const char *wtns_path = env->GetStringUTFChars(wtnsPath, 0);

    LOGD("🚀 Starting Proof Generation (C API)...");
    LOGD("📂 ZKey Path: %s", zkey_path);
    LOGD("📂 Witness Path: %s", wtns_path);

    struct timeval t1, t2;
    gettimeofday(&t1, NULL);

    std::string resultJson = "";

    // 1. Witness 파일 읽기 (C API는 Witness를 버퍼로 받음)
    std::vector<char> wtnsBuffer = readFileToBuffer(wtns_path);
    if (wtnsBuffer.empty()) {
        LOGE("❌ Failed to read witness file: %s", wtns_path);
        env->ReleaseStringUTFChars(zkeyPath, zkey_path);
        env->ReleaseStringUTFChars(wtnsPath, wtns_path);
        return env->NewStringUTF("ERROR_READ_WTNS");
    }

    // 2. 출력 버퍼 준비
    // 보통 Proof JSON은 수 KB 정도이지만 넉넉하게 잡음
    unsigned long long proofSize = 1024 * 1024; // 1MB
    unsigned long long publicSize = 1024 * 1024; // 1MB
    std::vector<char> proofBuffer(proofSize);
    std::vector<char> publicBuffer(publicSize);
    
    // 에러 메시지 버퍼
    char errorMsg[256];

    // 3. Rapidsnark Prover 실행 (C API)
    // int groth16_prover_zkey_file(...)
    int status = groth16_prover_zkey_file(
            zkey_path,
            wtnsBuffer.data(),
            wtnsBuffer.size(),
            proofBuffer.data(),
            &proofSize,
            publicBuffer.data(),
            &publicSize,
            errorMsg,
            sizeof(errorMsg)
    );

    if (status == PROVER_OK) {
        // 성공 시 JSON 문자열 구성
        // proofBuffer와 publicBuffer에 null-terminated string이 들어있음
        std::string proofStr(proofBuffer.data());
        // 필요하다면 public signals도 함께 리턴하거나 로그로 출력
        // std::string publicStr(publicBuffer.data());
        
        resultJson = proofStr;
        LOGD("✅ Proof Generated Successfully!");
    } else {
        LOGE("❌ Proof Generation Failed (Code %d): %s", status, errorMsg);
        resultJson = "ERROR_PROVE";
    }

    gettimeofday(&t2, NULL);
    double elapsedTime = (t2.tv_sec - t1.tv_sec) * 1000.0 + (t2.tv_usec - t1.tv_usec) / 1000.0;
    LOGD("⏱️ Time taken: %.2f ms", elapsedTime);

    env->ReleaseStringUTFChars(zkeyPath, zkey_path);
    env->ReleaseStringUTFChars(wtnsPath, wtns_path);

    return env->NewStringUTF(resultJson.c_str());
}