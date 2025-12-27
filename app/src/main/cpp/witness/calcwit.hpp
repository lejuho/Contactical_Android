#ifndef CALCWIT_HPP
#define CALCWIT_HPP

#include <vector>
#include <string>
#include <mutex>
#include <condition_variable>
#include <pthread.h>
#include "circom.hpp"
#include "fr.hpp" // FrElement 정의 필요

// native-witness.cpp에서 사용하므로 외부 공개
u64 fnv1a(std::string s);

class Circom_CalcWit {
public:
    Circom_Circuit *circuit;
    uint maxThread;
    uint numThread;

    // 🔥 [핵심] pthread 직접 사용 (SIGABRT 방지)
    pthread_t* threads;
    pthread_mutex_t mutex;
    pthread_cond_t consumeSignal;
    pthread_cond_t produceSignal;
    pthread_mutex_t processing;
    pthread_cond_t processingReady;

    bool* inputSignalAssigned;
    uint inputSignalAssignedCounter;
    FrElement* signalValues;
    Circom_Component* componentMemory;
    FrElement* circuitConstants;
    std::map<u32,IOFieldDefPair> templateInsId2IOSignalInfo;

    // 🔥 [복구] circuit.cpp와의 호환성을 위해 타입 원복 (vector -> pointer)
    IOFieldDefPair* busInsId2FieldInfo;

    // 🔥 [복구] circuit.cpp에서 참조하는 멤버
    std::string* listOfTemplateMessages;

    Circom_CalcWit(Circom_Circuit *aCircuit, uint maxTh = 1);
    ~Circom_CalcWit();

    void setInputSignal(u64 h, uint i, FrElement & val);
    void tryRunCircuit();
    void join();

    uint getInputSignalHashPosition(u64 h);
    u64 getInputSignalSize(u64 h);
    std::string getTrace(u64 id_cmp);
    std::string generate_position_array(uint* dimensions, uint size_dimensions, uint index);

    // 🔥 [복구] native-witness.cpp 및 JNI에서 사용하는 인라인 함수들
    inline uint getRemaingInputsToBeSet() {
        return inputSignalAssignedCounter;
    }

    inline void getWitness(uint idx, PFrElement val) {
        Fr_copy(val, &signalValues[circuit->witness2SignalList[idx]]);
    }
};

// 🔥 [복구] circuit.cpp (jwt_verifier.cpp)에서 사용하는 타입 정의
typedef void (*Circom_TemplateFunction)(uint __cIdx, Circom_CalcWit* __ctx);

#endif