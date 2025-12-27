cmake_minimum_required(VERSION 3.22.1)
project("contacticalattestation")

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -Ofast")

# --------------------------------------------------------
# 1. GMP 라이브러리 설정 (Witness 계산용 필수)
# --------------------------------------------------------
add_library(gmp STATIC IMPORTED)
set_target_properties(gmp PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/gmp/libgmp.a) # 경로 확인!
include_directories(${CMAKE_SOURCE_DIR}/gmp) # gmp.h 위치

# --------------------------------------------------------
# 2. Rapidsnark 라이브러리 설정 (증명용, 이미 빌드된 .so)
# --------------------------------------------------------
add_library(rapidsnark-lib SHARED IMPORTED)
set_target_properties(rapidsnark-lib PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/librapidsnark.so)

# --------------------------------------------------------
# 3. Target A: Native Prover (기존 기능)
#    - native-lib.cpp를 빌드해서 Rapidsnark와 연결
# --------------------------------------------------------
add_library(contactical-prover SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(contactical-prover
        rapidsnark-lib
        ${log-lib})

# --------------------------------------------------------
# 4. Target B: Witness Calculator (새 기능)
#    - witness 폴더의 cpp 파일들을 빌드해서 GMP와 연결
# --------------------------------------------------------
add_library(witness-calc SHARED
        witness/native-witness.cpp
        witness/calcwit.cpp
        witness/fr.cpp
        witness/jwt_verifier.cpp # <-- 본인 회로 cpp 파일명으로 수정 필요!
)

# [중요] cpp 파일 목록에 회로 이름이 포함된 cpp 파일(예: circuit.cpp 또는 jwt_verifier.cpp)이 반드시 있어야 합니다.
# user님이 파일 목록에서 'jwt_verifier.cpp'라고 하셨으므로 위 주석을 풀고 사용하세요.

target_link_libraries(witness-calc
        gmp         # 수학 연산을 위해 GMP 필수
        ${log-lib})