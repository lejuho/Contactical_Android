#ifndef __FR_H
#define __FR_H

#include <stdint.h>
#include <string>
#include <gmp.h>

#ifdef __APPLE__
#include <sys/types.h>
#endif

#define Fr_N64 4
#define Fr_SHORT 0x00000000
#define Fr_LONG 0x80000000

typedef uint64_t FrRawElement[Fr_N64];

// 🔥 [핵심 수정] __attribute__((__packed__)) 제거!
// ARM64에서 Alignment 오류를 막기 위해 컴파일러가 알아서 패딩을 넣게 해줍니다.
typedef struct {
    int32_t shortVal;
    uint32_t type;
    FrRawElement longVal; // 이제 8바이트 정렬이 보장됩니다.
} FrElement;

typedef FrElement *PFrElement;

extern FrElement Fr_q;
extern FrElement Fr_R3;

extern "C" void Fr_copy(PFrElement r, PFrElement a);
extern "C" void Fr_copyn(PFrElement r, PFrElement a, int n);
extern "C" void Fr_add(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_sub(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_neg(PFrElement r, PFrElement a);
extern "C" void Fr_mul(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_square(PFrElement r, PFrElement a);
extern "C" void Fr_band(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_bor(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_bxor(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_bnot(PFrElement r, PFrElement a);
extern "C" void Fr_shl(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_shr(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_eq(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_neq(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_lt(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_gt(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_leq(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_geq(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_land(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_lor(PFrElement r, PFrElement a, PFrElement b);
extern "C" void Fr_lnot(PFrElement r, PFrElement a);
extern "C" void Fr_toNormal(PFrElement r, PFrElement a);
extern "C" void Fr_toLongNormal(PFrElement r, PFrElement a);
extern "C" void Fr_toMontgomery(PFrElement r, PFrElement a);

extern "C" int Fr_isTrue(PFrElement pE);
extern "C" int Fr_toInt(PFrElement pE);

// 더미 함수들 (사용 안함)
extern "C" void Fr_rawCopy(FrRawElement pRawResult, const FrRawElement pRawA);
extern "C" void Fr_rawAdd(FrRawElement pRawResult, const FrRawElement pRawA, const FrRawElement pRawB);
extern "C" void Fr_rawSub(FrRawElement pRawResult, const FrRawElement pRawA, const FrRawElement pRawB);
extern "C" void Fr_rawNeg(FrRawElement pRawResult, const FrRawElement pRawA);
extern "C" void Fr_rawMMul(FrRawElement pRawResult, const FrRawElement pRawA, const FrRawElement pRawB);
extern "C" void Fr_rawMSquare(FrRawElement pRawResult, const FrRawElement pRawA);

extern "C" void Fr_fail();

void Fr_str2element(PFrElement pE, char const*s, uint base);
char *Fr_element2str(PFrElement pE);
void Fr_div(PFrElement r, PFrElement a, PFrElement b);
void Fr_idiv(PFrElement r, PFrElement a, PFrElement b);
void Fr_mod(PFrElement r, PFrElement a, PFrElement b);
void Fr_inv(PFrElement r, PFrElement a);
void Fr_pow(PFrElement r, PFrElement a, PFrElement b);

#endif // __FR_H