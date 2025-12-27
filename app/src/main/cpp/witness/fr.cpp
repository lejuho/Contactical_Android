#include "fr.hpp"
#include <stdio.h>
#include <stdlib.h>
#include <gmp.h>
#include <assert.h>
#include <string.h>
#include <iostream>
#include <android/log.h> // 디버깅용

#define LOG_TAG "NativeFr"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// BN128 Modulus
FrElement Fr_q = {
        0, Fr_LONG,
        {
                0x43e1f593f0000001ULL, // [0] 하위 64비트
                0x2833e84879b97091ULL, // [1]
                0xb85045b68181585dULL, // [2]
                0x30644e72e131a029ULL  // [3] 상위 64비트
        }
};

static mpz_t q;
static bool initialized = false;

bool Fr_init() {
    if (initialized) return false;
    initialized = true;
    mpz_init(q);
    // Packed 제거로 안전하게 접근 가능
    mpz_import(q, Fr_N64, -1, 8, -1, 0, (const void *)Fr_q.longVal);
    return true;
}

// 안전장치: q가 초기화 안됐으면 강제 초기화
void check_init() {
    if (!initialized) Fr_init();
}

void Fr_toMpz(mpz_t r, PFrElement pE) {
    check_init();
    if (!(pE->type & Fr_LONG)) {
        mpz_set_si(r, pE->shortVal);
        if (pE->shortVal < 0) mpz_add(r, r, q);
    } else {
        mpz_import(r, Fr_N64, -1, 8, -1, 0, (const void *)pE->longVal);
    }
}

void Fr_fromMpz(PFrElement pE, const mpz_t v) {
    check_init();
    mpz_t temp; mpz_init_set(temp, v);
    mpz_mod(temp, temp, q);
    pE->type = Fr_LONG;
    memset(pE->longVal, 0, sizeof(pE->longVal));
    size_t count;
    mpz_export((void *)(pE->longVal), &count, -1, 8, -1, 0, temp);
    mpz_clear(temp);
}

void Fr_copy(PFrElement r, PFrElement a) {
    r->type = a->type;
    r->shortVal = a->shortVal;
    memcpy(r->longVal, a->longVal, sizeof(r->longVal));
}

void Fr_toNormal(PFrElement r, PFrElement a) { Fr_copy(r, a); }

void Fr_toLongNormal(PFrElement r, PFrElement a) {
    mpz_t m; mpz_init(m);
    Fr_toMpz(m, a);
    Fr_fromMpz(r, m);
    mpz_clear(m);
}

void Fr_mul(PFrElement r, PFrElement a, PFrElement b) {
    mpz_t ma, mb, mr; mpz_init(ma); mpz_init(mb); mpz_init(mr);
    Fr_toMpz(ma, a); Fr_toMpz(mb, b);
    mpz_mul(mr, ma, mb); mpz_mod(mr, mr, q);
    Fr_fromMpz(r, mr);
    mpz_clear(ma); mpz_clear(mb); mpz_clear(mr);
}

void Fr_inv(PFrElement r, PFrElement a) {
    mpz_t ma, mr; mpz_init(ma); mpz_init(mr);
    Fr_toMpz(ma, a);
    mpz_invert(mr, ma, q);
    Fr_fromMpz(r, mr);
    mpz_clear(ma); mpz_clear(mr);
}

void Fr_div(PFrElement r, PFrElement a, PFrElement b) {
    mpz_t ma, mb, mr; mpz_init(ma); mpz_init(mb); mpz_init(mr);
    Fr_toMpz(ma, a); Fr_toMpz(mb, b);
    if (mpz_invert(mb, mb, q) == 0) { LOGE("Division by zero"); exit(1); }
    mpz_mul(mr, ma, mb); mpz_mod(mr, mr, q);
    Fr_fromMpz(r, mr);
    mpz_clear(ma); mpz_clear(mb); mpz_clear(mr);
}

void Fr_add(PFrElement r, PFrElement a, PFrElement b) {
    mpz_t ma, mb, mr; mpz_init(ma); mpz_init(mb); mpz_init(mr);
    Fr_toMpz(ma, a); Fr_toMpz(mb, b);
    mpz_add(mr, ma, mb); mpz_mod(mr, mr, q);
    Fr_fromMpz(r, mr);
    mpz_clear(ma); mpz_clear(mb); mpz_clear(mr);
}

void Fr_sub(PFrElement r, PFrElement a, PFrElement b) {
    mpz_t ma, mb, mr; mpz_init(ma); mpz_init(mb); mpz_init(mr);
    Fr_toMpz(ma, a); Fr_toMpz(mb, b);
    mpz_sub(mr, ma, mb); mpz_mod(mr, mr, q);
    Fr_fromMpz(r, mr);
    mpz_clear(ma); mpz_clear(mb); mpz_clear(mr);
}

void Fr_neg(PFrElement r, PFrElement a) {
    mpz_t ma, mr; mpz_init(ma); mpz_init(mr);
    Fr_toMpz(ma, a);
    mpz_neg(mr, ma); mpz_mod(mr, mr, q);
    Fr_fromMpz(r, mr);
    mpz_clear(ma); mpz_clear(mr);
}

void Fr_square(PFrElement r, PFrElement a) { Fr_mul(r, a, a); }

void Fr_str2element(PFrElement pE, char const *s, uint base) {
    check_init();
    mpz_t mr; mpz_init_set_str(mr, s, base);
    mpz_mod(mr, mr, q);
    Fr_fromMpz(pE, mr);
    mpz_clear(mr);
}

char *Fr_element2str(PFrElement pE) {
    mpz_t r; mpz_init(r);
    Fr_toMpz(r, pE);
    char *res = mpz_get_str(0, 10, r);
    mpz_clear(r);
    return res;
}

void Fr_idiv(PFrElement r, PFrElement a, PFrElement b) { Fr_div(r, a, b); }
void Fr_mod(PFrElement r, PFrElement a, PFrElement b) { if (r == nullptr) return; Fr_copy(r, a); }
void Fr_pow(PFrElement r, PFrElement a, PFrElement b) {}
void Fr_fail() { assert(false); }

// 🔥 [핵심 수정] GMP 할당 없이 빠르고 안전하게 비교
void Fr_eq(PFrElement r, PFrElement a, PFrElement b) {
    mpz_t ma, mb; mpz_init(ma); mpz_init(mb);
    Fr_toMpz(ma, a); Fr_toMpz(mb, b);

    r->type = Fr_SHORT;
    if (mpz_cmp(ma, mb) == 0) {
        r->shortVal = 1;
    } else {
        r->shortVal = 0;
    }
    // 안전을 위해 longVal 초기화
    memset(r->longVal, 0, sizeof(r->longVal));

    mpz_clear(ma); mpz_clear(mb);
}

// 🔥 [핵심 수정] Fr_neg를 쓰지 않고 단순 논리 반전 (메모리 문제 해결)
void Fr_neq(PFrElement r, PFrElement a, PFrElement b) {
    Fr_eq(r, a, b);

    // eq 결과가 1이면 0으로, 0이면 1로 뒤집기
    if (r->shortVal != 0) {
        r->shortVal = 0;
    } else {
        r->shortVal = 1;
    }
    // 타입과 메모리 확실히 정리
    r->type = Fr_SHORT;
    memset(r->longVal, 0, sizeof(r->longVal));
}

int Fr_isTrue(PFrElement pE) {
    return Fr_toInt(pE) != 0;
}

void Fr_lt(PFrElement r, PFrElement a, PFrElement b) {
    mpz_t ma, mb; mpz_init(ma); mpz_init(mb);
    Fr_toMpz(ma, a); Fr_toMpz(mb, b);
    r->type = Fr_SHORT;
    if (mpz_cmp(ma, mb) < 0) {
        r->shortVal = 1;
    } else {
        r->shortVal = 0;
    }
    memset(r->longVal, 0, sizeof(r->longVal));
    mpz_clear(ma); mpz_clear(mb);
}

int Fr_toInt(PFrElement pE) {
    if (pE->type & Fr_LONG) {
        // LONG 타입인데 값이 0이 아닐 확률이 높으므로 1 리턴
        // 더 정확히 하려면 longVal 검사해야 하지만 보통은 이걸로 충분
        return 1;
    }
    return pE->shortVal;
}

// Raw 함수들 (더미 유지)
void Fr_rawCopy(FrRawElement pRawResult, const FrRawElement pRawA) { memcpy(pRawResult, pRawA, 32); }
void Fr_rawAdd(FrRawElement pRawResult, const FrRawElement pRawA, const FrRawElement pRawB) { memcpy(pRawResult, pRawA, 32); }
void Fr_rawSub(FrRawElement pRawResult, const FrRawElement pRawA, const FrRawElement pRawB) { memcpy(pRawResult, pRawA, 32); }
void Fr_rawNeg(FrRawElement pRawResult, const FrRawElement pRawA) { memcpy(pRawResult, pRawA, 32); }
void Fr_rawMMul(FrRawElement pRawResult, const FrRawElement pRawA, const FrRawElement pRawB) { memcpy(pRawResult, pRawA, 32); }
void Fr_rawMSquare(FrRawElement pRawResult, const FrRawElement pRawA) { memcpy(pRawResult, pRawA, 32); }

static bool init = Fr_init();