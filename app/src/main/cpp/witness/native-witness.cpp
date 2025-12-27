#include <jni.h>
#include <string>
#include <vector>
#include <iostream>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <android/log.h>
#include "../nlohmann/json.hpp"
#include "calcwit.hpp"
#include "circom.hpp"

using json = nlohmann::json;

#define TAG "NativeWitness"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// -------------------------------------------------------------------------
// Helper Functions
// -------------------------------------------------------------------------

Circom_Circuit* loadCircuit(std::string const &datFileName) {
    LOGD("📂 loadCircuit Start: %s", datFileName.c_str());
    Circom_Circuit *circuit = new Circom_Circuit;
    int fd;
    struct stat sb;

    fd = open(datFileName.c_str(), O_RDONLY);
    if (fd == -1) {
        LOGE("❌ Error: .dat file not found: %s", datFileName.c_str());
        return nullptr;
    }

    if (fstat(fd, &sb) == -1) {
        LOGE("❌ Error: fstat failed");
        close(fd);
        return nullptr;
    }
    LOGD("📂 File Size: %ld bytes", sb.st_size);

    u8* bdata = (u8*)mmap(NULL, sb.st_size, PROT_READ , MAP_PRIVATE, fd, 0);
    close(fd);

    if (bdata == MAP_FAILED) {
        LOGE("❌ Error: mmap failed");
        return nullptr;
    }
    LOGD("📂 mmap success. Address: %p", bdata);

    // 1. InputHashMap 로딩
    LOGD("📂 Loading InputHashMap... Size: %u", get_size_of_input_hashmap());
    circuit->InputHashMap = new HashSignalInfo[get_size_of_input_hashmap()];
    uint dsize = get_size_of_input_hashmap()*sizeof(HashSignalInfo);

    // 🔥 여기서 죽으면 파일 크기가 예상보다 작은 것
    if (dsize > sb.st_size) {
        LOGE("❌ CRITICAL: File is too small for InputHashMap! Need %u, File %ld", dsize, sb.st_size);
        return nullptr; // 강제 종료 방지
    }
    memcpy((void *)(circuit->InputHashMap), (void *)bdata, dsize);
    LOGD("✅ InputHashMap Loaded.");

    // 2. WitnessMap 로딩
    LOGD("📂 Loading WitnessMap... Size: %u", get_size_of_witness());
    circuit->witness2SignalList = new u64[get_size_of_witness()];
    uint inisize = dsize;
    dsize = get_size_of_witness()*sizeof(u64);

    if (inisize + dsize > sb.st_size) {
        LOGE("❌ CRITICAL: File is too small for WitnessMap! Offset %u, Need %u, File %ld", inisize, dsize, sb.st_size);
        return nullptr;
    }
    memcpy((void *)(circuit->witness2SignalList), (void *)(bdata+inisize), dsize);
    LOGD("✅ WitnessMap Loaded.");

    // 3. Constants 로딩
    circuit->circuitConstants = new FrElement[get_size_of_constants()];
    if (get_size_of_constants()>0) {
        inisize += dsize;
        dsize = get_size_of_constants()*sizeof(FrElement);
        if (inisize + dsize > sb.st_size) {
            LOGE("❌ CRITICAL: File too small for Constants!");
            return nullptr;
        }
        memcpy((void *)(circuit->circuitConstants), (void *)(bdata+inisize), dsize);
    }
    LOGD("✅ Constants Loaded.");

    // 4. IO Map (보통 없으므로 패스하거나 간단 처리)
    std::map<u32,IOFieldDefPair> templateInsId2IOSignalInfo1;
    if (get_size_of_io_map()>0) {
        // ... (생략, 보통 0임) ...
    }
    circuit->templateInsId2IOSignalInfo = move(templateInsId2IOSignalInfo1);

    LOGD("✅ loadCircuit Finish Successfully.");
    return circuit;
}

bool check_valid_number(std::string & s, uint base){
    bool is_valid = true;
    if (base == 16){
        for (uint i = 0; i < s.size(); i++){
            is_valid &= (('0' <= s[i] && s[i] <= '9') || ('a' <= s[i] && s[i] <= 'f') || ('A' <= s[i] && s[i] <= 'F'));
        }
    } else{
        for (uint i = 0; i < s.size(); i++){
            is_valid &= ('0' <= s[i] && s[i] < char(int('0') + base));
        }
    }
    return is_valid;
}

void json2FrElements (json val, std::vector<FrElement> & vval){
    if (!val.is_array()) {
        FrElement v;
        std::string s_aux, s;
        uint base;
        if (val.is_string()) {
            s_aux = val.get<std::string>();
            std::string possible_prefix = s_aux.substr(0, 2);
            if (possible_prefix == "0b" || possible_prefix == "0B"){ s = s_aux.substr(2); base = 2; }
            else if (possible_prefix == "0o" || possible_prefix == "0O"){ s = s_aux.substr(2); base = 8; }
            else if (possible_prefix == "0x" || possible_prefix == "0X"){ s = s_aux.substr(2); base = 16; }
            else{ s = s_aux; base = 10; }
            if (!check_valid_number(s, base)) throw std::runtime_error("Invalid number in JSON");
        } else if (val.is_number()) {
            double vd = val.get<double>();
            std::stringstream stream;
            stream << std::fixed << std::setprecision(0) << vd;
            s = stream.str();
            base = 10;
        } else {
            throw std::runtime_error("Invalid JSON type");
        }
        Fr_str2element (&v, s.c_str(), base);
        vval.push_back(v);
    } else {
        for (uint i = 0; i < val.size(); i++) json2FrElements (val[i], vval);
    }
}

void parseJsonInput(Circom_CalcWit *ctx, std::string jsonString) {
    json j = json::parse(jsonString);
    u64 nItems = j.size();
    if (nItems == 0){
        ctx->tryRunCircuit();
    }
    for (json::iterator it = j.begin(); it != j.end(); ++it) {
        u64 h = fnv1a(it.key());
        std::vector<FrElement> v;
        json2FrElements(it.value(),v);
        uint signalSize = ctx->getInputSignalSize(h);
        if (v.size() < signalSize) throw std::runtime_error("Not enough values for " + it.key());
        if (v.size() > signalSize) throw std::runtime_error("Too many values for " + it.key());
        for (uint i = 0; i<v.size(); i++){
            ctx->setInputSignal(h,i,v[i]);
        }
    }
}

void writeBinWitness(Circom_CalcWit *ctx, std::string wtnsFileName) {
    FILE *write_ptr = fopen(wtnsFileName.c_str(),"wb");
    if (!write_ptr) {
        LOGE("Error opening output file: %s", wtnsFileName.c_str());
        return;
    }
    fwrite("wtns", 4, 1, write_ptr);
    u32 version = 2; fwrite(&version, 4, 1, write_ptr);
    u32 nSections = 2; fwrite(&nSections, 4, 1, write_ptr);
    u32 idSection1 = 1; fwrite(&idSection1, 4, 1, write_ptr);
    u32 n8 = Fr_N64*8;
    u64 idSection1length = 8 + n8; fwrite(&idSection1length, 8, 1, write_ptr);
    fwrite(&n8, 4, 1, write_ptr);
    fwrite(Fr_q.longVal, Fr_N64*8, 1, write_ptr);
    uint Nwtns = get_size_of_witness();
    u32 nVars = (u32)Nwtns; fwrite(&nVars, 4, 1, write_ptr);
    u32 idSection2 = 2; fwrite(&idSection2, 4, 1, write_ptr);
    u64 idSection2length = (u64)n8*(u64)Nwtns; fwrite(&idSection2length, 8, 1, write_ptr);
    FrElement v;
    for (int i=0;i<Nwtns;i++) {
        ctx->getWitness(i, &v);
        Fr_toLongNormal(&v, &v);
        fwrite(v.longVal, Fr_N64*8, 1, write_ptr);
    }
    fclose(write_ptr);
}

// -------------------------------------------------------------------------
// JNI Implementation
// -------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_contacticalattestation_zk_NativeWitness_calcWitness(
        JNIEnv* env,
        jobject /* this */,
        jstring inputJsonStr,
        jstring datPathStr,
        jstring wtnsPathStr) {

    const char *input_json = env->GetStringUTFChars(inputJsonStr, 0);
    const char *dat_path = env->GetStringUTFChars(datPathStr, 0);
    const char *wtns_path = env->GetStringUTFChars(wtnsPathStr, 0);

    LOGD("🚀 Starting Witness Calculation (Heap Mode)...");

    Circom_Circuit *circuit = nullptr;
    Circom_CalcWit *ctx = nullptr;

    try {
        // 1. Load Circuit
        circuit = loadCircuit(dat_path);
        if (!circuit) {
            throw std::runtime_error("Failed to load circuit .dat file (Check logs above)");
        }

        // 2. Create CalcWit (Heap)
        LOGD("🚀 Creating Circom_CalcWit on Heap...");
        ctx = new Circom_CalcWit(circuit, 1);
        LOGD("✅ Circom_CalcWit Created.");

        // 3. Parse JSON
        LOGD("🚀 Parsing JSON Input...");
        parseJsonInput(ctx, std::string(input_json));

        if (ctx->getRemaingInputsToBeSet() != 0) {
            throw std::runtime_error("Not all inputs set!");
        }

        // 4. Write Witness
        LOGD("🚀 Writing Witness to file...");
        writeBinWitness(ctx, wtns_path);

        LOGD("✅ Witness Generation Successful!");

    } catch (const std::exception& e) {
        LOGE("❌ Witness Error: %s", e.what());
        if (ctx) delete ctx;
        if (circuit) delete circuit;
        env->ReleaseStringUTFChars(inputJsonStr, input_json);
        env->ReleaseStringUTFChars(datPathStr, dat_path);
        env->ReleaseStringUTFChars(wtnsPathStr, wtns_path);
        return false;
    }

    if (ctx) delete ctx;
    // circuit은 ctx 내에서 참조되므로 별도 해제는 loadCircuit 구조에 따라 결정
    // 여기선 OS 회수에 맡기거나 필요한 경우 delete circuit;

    env->ReleaseStringUTFChars(inputJsonStr, input_json);
    env->ReleaseStringUTFChars(datPathStr, dat_path);
    env->ReleaseStringUTFChars(wtnsPathStr, wtns_path);
    return true;
}