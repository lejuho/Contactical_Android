#include <iomanip>
#include <sstream>
#include <assert.h>
#include <iostream>
#include <pthread.h>
#include <android/log.h> // 🔥 로그 헤더 추가
#include "calcwit.hpp"

// 🔥 디버깅 매크로 정의
#define TAG "NativeCalcWit"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern void run(Circom_CalcWit* ctx);

std::string int_to_hex( u64 i )
{
    std::stringstream stream;
    stream << "0x"
           << std::setfill ('0') << std::setw(16)
           << std::hex << i;
    return stream.str();
}

u64 fnv1a(std::string s) {
    u64 hash = 0xCBF29CE484222325LL;
    for(char& c : s) {
        hash ^= u64(c);
        hash *= 0x100000001B3LL;
    }
    return hash;
}

Circom_CalcWit::Circom_CalcWit (Circom_Circuit *aCircuit, uint maxTh) {
    LOGD("🚩 Constructor Start. Addr: %p", this); // 생성자 시작 로그

    circuit = aCircuit;
    inputSignalAssignedCounter = get_main_input_signal_no();
    inputSignalAssigned = new bool[inputSignalAssignedCounter];
    for (int i = 0; i< inputSignalAssignedCounter; i++) {
        inputSignalAssigned[i] = false;
    }
    signalValues = new FrElement[get_total_signal_no()];
    Fr_str2element(&signalValues[0], "1", 10);
    componentMemory = new Circom_Component[get_number_of_components()];
    circuitConstants = circuit ->circuitConstants;
    templateInsId2IOSignalInfo = circuit -> templateInsId2IOSignalInfo;
    busInsId2FieldInfo = circuit -> busInsId2FieldInfo;
    listOfTemplateMessages = NULL;

    // 뮤텍스 초기화 및 로그
    maxThread = maxTh;
    numThread = 0;
    threads = new pthread_t[maxThread];

    LOGD("🚩 Init Mutexes...");
    int res1 = pthread_mutex_init(&mutex, NULL);
    int res2 = pthread_mutex_init(&processing, NULL);

    if (res1 != 0 || res2 != 0) {
        LOGE("❌ Mutex Init Failed! res1=%d, res2=%d", res1, res2);
    } else {
        LOGD("✅ Mutex Init Success. mutex_addr=%p, processing_addr=%p", &mutex, &processing);
    }

    pthread_cond_init(&consumeSignal, NULL);
    pthread_cond_init(&produceSignal, NULL);
    pthread_cond_init(&processingReady, NULL);

    LOGD("🚩 Constructor End");
}

Circom_CalcWit::~Circom_CalcWit() {
    LOGD("💀 Destructor Called. Addr: %p", this); // 소멸자 로그

    delete [] inputSignalAssigned;
    delete [] signalValues;
    delete [] componentMemory;
    delete [] threads;

    LOGD("💀 Destroying Mutexes...");
    pthread_mutex_destroy(&mutex);
    pthread_cond_destroy(&consumeSignal);
    pthread_cond_destroy(&produceSignal);
    pthread_mutex_destroy(&processing);
    pthread_cond_destroy(&processingReady);
    LOGD("💀 Destructor End");
}

uint Circom_CalcWit::getInputSignalHashPosition(u64 h) {
    uint n = get_size_of_input_hashmap();
    uint pos = (uint)(h % (u64)n);

    if (circuit->InputHashMap[pos].hash != h) {
        uint inipos = pos;
        pos = (pos + 1) % n;

        while (pos != inipos) {
            if (circuit->InputHashMap[pos].hash == h) {
                return pos;
            }
            if (circuit->InputHashMap[pos].signalid == 0) {
                LOGE("Signal not found");
                assert(false);
            }
            pos = (pos + 1) % n;
        }
        LOGE("Signals not found");
        assert(false);
    }
    return pos;
}

void Circom_CalcWit::tryRunCircuit(){
    // 로그 추가
    LOGD("⚡ tryRunCircuit. Remaining: %d", inputSignalAssignedCounter);

    if (inputSignalAssignedCounter == 0) {
        LOGD("⚡ Locking 'processing' mutex (%p)...", &processing);
        pthread_mutex_lock(&processing);
        LOGD("⚡ Locked 'processing'. numThread: %d", numThread);

        if (numThread < maxThread) {
            numThread++;
            pthread_mutex_unlock(&processing);

            LOGD("⚡ Calling extern run(this)...");
            // 🔥 여기가 가장 의심되는 지점 (circuit.cpp로 넘어가는 순간)
            run(this);
            LOGD("⚡ Returned from run(this).");

        } else {
            pthread_mutex_unlock(&processing);
            LOGD("⚡ Threads full, unlocked 'processing'.");
        }
    }
}

void Circom_CalcWit::setInputSignal(u64 h, uint i,  FrElement & val){
    // 로그 추가 (너무 많이 찍힐 수 있으니 주의, 처음 몇 개만 찍거나 에러 직전 확인용)
    // LOGD("📥 setInputSignal i=%d", i);

    pthread_mutex_lock(&mutex);

    if (inputSignalAssignedCounter == 0) {
        pthread_mutex_unlock(&mutex);
        return;
    }

    uint pos = getInputSignalHashPosition(h);
    if (i >= circuit->InputHashMap[pos].signalsize) {
        pthread_mutex_unlock(&mutex);
        LOGE("Input signal array access exceeds the size");
        assert(false);
    }

    uint si = circuit->InputHashMap[pos].signalid + i;
    if (inputSignalAssigned[si - get_main_input_signal_start()]) {
        pthread_mutex_unlock(&mutex);
        LOGE("Signal assigned twice: %d", si);
        assert(false);
    }

    signalValues[si] = val;
    inputSignalAssigned[si - get_main_input_signal_start()] = true;
    inputSignalAssignedCounter--;

    pthread_cond_signal(&produceSignal);
    pthread_mutex_unlock(&mutex);

    tryRunCircuit();
}

void Circom_CalcWit::join() {
    LOGD("⏳ Join called. Locking 'processing'...");
    pthread_mutex_lock(&processing);
    while (numThread > 0) {
        LOGD("⏳ Waiting for threads...");
        pthread_cond_wait(&processingReady, &processing);
    }
    pthread_mutex_unlock(&processing);
    LOGD("✅ Join finished.");
}

u64 Circom_CalcWit::getInputSignalSize(u64 h) {
    uint pos = getInputSignalHashPosition(h);
    return circuit->InputHashMap[pos].signalsize;
}

std::string Circom_CalcWit::getTrace(u64 id_cmp){
    if (id_cmp == 0) return componentMemory[id_cmp].componentName;
    else{
        u64 id_father = componentMemory[id_cmp].idFather;
        std::string my_name = componentMemory[id_cmp].componentName;
        return Circom_CalcWit::getTrace(id_father) + "." + my_name;
    }
}

std::string Circom_CalcWit::generate_position_array(uint* dimensions, uint size_dimensions, uint index){
    std::string positions = "";
    for (uint i = 0 ; i < size_dimensions; i++){
        uint last_pos = index % dimensions[size_dimensions -1 - i];
        index = index / dimensions[size_dimensions -1 - i];
        std::string new_pos = "[" + std::to_string(last_pos) + "]";
        positions =  new_pos + positions;
    }
    return positions;
}