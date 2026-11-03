// FILE: app/src/main/cpp/blackhole_dimensity7050.hpp
// Fixed version, 2026-11-03 (branch full-7050). Mining-only utilities.
// This header is NOT a miner, NOT a pool client, and NOT a RandomX /
// Verus / SHA-256 implementation. Its NEON round is a synthetic kernel
// (see below), its word-7 check is only a final comparison, and its
// prevhash predicate is an UNVERIFIED heuristic, never a protocol rule.
// Nothing in this header is wired into XMRig's workers, dataset, or
// share checks. It is exercised only by on-tap native self-tests.
#pragma once
#include <sched.h>
#include <sys/mman.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <math.h>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#ifndef MADV_HUGEPAGE
#define MADV_HUGEPAGE 14
#endif
#ifndef MADV_WILLNEED
#define MADV_WILLNEED 3
#endif

namespace blackhole {

// ---------------------------------------------------------------------------
// 1. 2MB Transparent HugePage allocator (fixed)
//    - 2MB-aligned via posix_memalign, madvise(MADV_HUGEPAGE|MADV_WILLNEED).
//    - madvise return is reported via madvise_rc_out (0 = call succeeded;
//      success is NOT proof huge pages were granted/collapsed).
//    - NO silent malloc fallback: if posix_memalign fails, returns nullptr
//      and sets thp_applied_out=false, so the caller cannot mistake an
//      unaligned plain allocation for a THP allocation.
//    - Does NOT memset the whole buffer: for a 2,080MB dataset that would
//      touch every page at alloc time. Callers zero only what they use.
// ---------------------------------------------------------------------------
struct ThpAllocResult {
    void* ptr = nullptr;
    size_t aligned_size = 0;
    int madvise_rc = -999;  // -999 = madvise not attempted (size < 256KB path is not used here)
    bool thp_requested = false;
};

inline ThpAllocResult alloc_thp_2mb_ex(size_t size, int* madvise_rc_out, bool* thp_applied_out) {
    ThpAllocResult r;
    const size_t align_2mb = 2ULL * 1024ULL * 1024ULL;
    size_t aligned_sz = (size + align_2mb - 1) & ~(align_2mb - 1);
    void* ptr = nullptr;
    if (posix_memalign(&ptr, align_2mb, aligned_sz) != 0 || ptr == nullptr) {
        if (madvise_rc_out) *madvise_rc_out = -999;
        if (thp_applied_out) *thp_applied_out = false;
        return r;
    }
    int rc = madvise(ptr, aligned_sz, MADV_HUGEPAGE | MADV_WILLNEED);
    r.ptr = ptr;
    r.aligned_size = aligned_sz;
    r.madvise_rc = rc;
    r.thp_requested = (rc == 0);
    if (madvise_rc_out) *madvise_rc_out = rc;
    if (thp_applied_out) *thp_applied_out = (rc == 0);
    return r;
}

// Backwards-compatible name, now with fixed semantics: nullptr on
// allocation failure, no malloc fallback, no silent THP claim.
// Prefer alloc_thp_2mb_ex when the madvise result matters.
inline void* alloc_thp_2mb(size_t size) {
    int rc = -999;
    bool applied = false;
    ThpAllocResult r = alloc_thp_2mb_ex(size, &rc, &applied);
    (void)rc; (void)applied;
    return r.ptr;
}

// ---------------------------------------------------------------------------
// 2. AnonHugePages readout, self-process ONLY (fixed)
//    Reads /proc/self/smaps_rollup only. No fallback to /proc/meminfo:
//    meminfo AnonHugePages is system-wide and would silently change the
//    scope of the number. Returns: >=0 = kB, -1 = file unreadable,
//    -2 = AnonHugePages line not present. Callers must label the scope
//    as "self-smaps_rollup" in any log line, and must not present this
//    as XMRig-dataset THP (that is a different process).
// ---------------------------------------------------------------------------
inline long read_anon_hugepages_kb() {
    FILE* f = fopen("/proc/self/smaps_rollup", "r");
    if (!f) return -1;
    char line[256];
    long kb = 0;
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "AnonHugePages: %ld kB", &kb) == 1) { found = true; break; }
    }
    fclose(f);
    return found ? kb : -2;
}

// ---------------------------------------------------------------------------
// 3. Dimensity 7050 cache-isolated pinning (fixed)
//    Big cores: cpu6, cpu7 only. Heavy worker_id must be 0 or 1;
//    requesting a 3rd heavy thread returns -1 instead of silently
//    colliding on cpu6/cpu7.
//    Little cores: cpu0..cpu5 only, NEVER big cores, when isolation
//    is the goal. Light worker_id must be 0..5; anything else is -1.
//    Returns sched_setaffinity's return (0 ok, -1 failed, errno set).
//    A failed pin is therefore visible to the caller, never silent.
//    CPU numbers are still hardcoded for this phone model; on-device
//    code must confirm them against sysfs before trusting them
//    (APK2 already discovers cores by cpuinfo_max_freq for XMRig).
// ---------------------------------------------------------------------------
inline int pin_dimensity7050_thread(int worker_id, bool is_l3_heavy_algo, int* out_cpu) {
    int cpu = -1;
    if (is_l3_heavy_algo) {
        if (worker_id < 0 || worker_id >= 2) return -1;
        cpu = 6 + worker_id;
    } else {
        if (worker_id < 0 || worker_id >= 6) return -1;
        cpu = worker_id;  // cpu0..cpu5 only
    }
    cpu_set_t mask;
    CPU_ZERO(&mask);
    CPU_SET(cpu, &mask);
    int rc = sched_setaffinity(0, sizeof(cpu_set_t), &mask);
    if (rc == 0 && out_cpu) *out_cpu = cpu;
    return rc;
}

// Pin, then read the mask back and verify it contains exactly the
// requested CPU. Returns 0 only if pin + readback both agree.
inline int pin_and_verify_dimensity7050_thread(int worker_id, bool is_l3_heavy_algo, int* out_cpu) {
    int cpu = -1;
    int rc = pin_dimensity7050_thread(worker_id, is_l3_heavy_algo, &cpu);
    if (rc != 0) return rc;
    cpu_set_t mask;
    CPU_ZERO(&mask);
    if (sched_getaffinity(0, sizeof(cpu_set_t), &mask) != 0) return -1;
    if (!CPU_ISSET(cpu, &mask)) return -1;
    if (out_cpu) *out_cpu = cpu;
    return 0;
}

#if defined(__aarch64__)
// ---------------------------------------------------------------------------
// 4. SYNTHETIC 2-stream AES + PMULL kernel — NOT a hash round.
//    sA1 uses sA0 as its round key and sB1 uses sB0, so the second
//    block of each stream is NOT an independent nonce stream. The
//    PMULL lane choice is arbitrary. This demonstrates interleaved
//    vaeseq/vaesmcq and vmull_p64 instructions only. It must never be
//    described as a RandomX, VerusHash, or SHA-256 round, and C++
//    variables do not guarantee v0..v15 / v16..v31 register allocation
//    (the compiler assigns registers).
// ---------------------------------------------------------------------------
inline void dual_stream_aes_pmull_round(
    uint8x16_t& sA0, uint8x16_t& sA1,
    uint8x16_t& sB0, uint8x16_t& sB1,
    const uint8x16_t rkey0, const uint8x16_t rkey1
) {
    sA0 = vaesmcq_u8(vaeseq_u8(sA0, rkey0));
    sB0 = vaesmcq_u8(vaeseq_u8(sB0, rkey0));
    // Synthetic chaining only: sA1 keyed by sA0, sB1 keyed by sB0.
    sA1 = vaesmcq_u8(vaeseq_u8(sA1, sA0));
    sB1 = vaesmcq_u8(vaeseq_u8(sB1, sB0));
    poly128_t mulA = vmull_p64(vgetq_lane_p64(vreinterpretq_p64_u8(sA0), 0),
                               vgetq_lane_p64(vreinterpretq_p64_u8(rkey1), 1));
    poly128_t mulB = vmull_p64(vgetq_lane_p64(vreinterpretq_p64_u8(sB0), 0),
                               vgetq_lane_p64(vreinterpretq_p64_u8(rkey1), 1));
    sA0 = veorq_u8(sA0, vreinterpretq_u8_p128(mulA));
    sB0 = veorq_u8(sB0, vreinterpretq_u8_p128(mulB));
}
#endif

// ---------------------------------------------------------------------------
// 5. Compact (nbits) difficulty (fixed)
//    Full 24-bit mantissa (mask 0x00FFFFFF). If the sign bit
//    (0x00800000) is set, the compact target is negative and the
//    difficulty is invalid: returns false. mant==0 is invalid too:
//    returns false, never 1.0. On success writes *out_diff.
//    Test: 0x1d00ffff -> 1.0.
// ---------------------------------------------------------------------------
inline bool decode_compact_nbits_diff(uint32_t nbits, double* out_diff) {
    uint32_t exp = (nbits >> 24) & 0xFF;
    uint32_t mant = nbits & 0x00FFFFFFu;
    if (mant == 0) return false;
    if (mant & 0x00800000u) return false;  // sign bit: negative target
    double target;
    if (exp >= 3) {
        target = (double)mant * pow(256.0, (double)(exp - 3));
    } else {
        target = (double)mant / pow(256.0, (double)(3 - exp));
    }
    double genesis = (double)0x00FFFF * pow(256.0, (double)(0x1D - 3));
    if (target <= 0.0) return false;
    if (out_diff) *out_diff = genesis / target;
    return true;
}

// ---------------------------------------------------------------------------
// 6. Prevhash predicate — RENAMED, UNVERIFIED HEURISTIC ONLY.
//    Verified against MoneroOcean XMRig v6.25.0-mo1
//    src/crypto/ghostrider/ghostrider.cpp: GhostRider selects its
//    core/CN indices with select_indices() over the seed in data[4..36)
//    (the first 32 bytes of PrevBlockHash), taking every nibble modulo
//    N and deduplicating. There is NO trailing-byte nibble rule, and
//    cn-pico is NOT in GhostRider's CN list (dark, dark-lite, fast,
//    lite, turtle, turtle-lite). MinotaurX is not present in this
//    XMRig tree at all. The old name is_l2_fast_branch_prevhash has
//    been DELETED. This heuristic must never be used to select an
//    algorithm, a scratchpad size, or a mining branch.
// ---------------------------------------------------------------------------
inline bool is_unverified_prevhash_heuristic(uint8_t last_prevhash_byte) {
    uint8_t nibble = last_prevhash_byte & 0x0F;
    return (nibble == 0 || nibble == 1 || nibble == 4 || nibble == 8 || nibble == 12);
}

// ---------------------------------------------------------------------------
// 7. Word-7 comparison (fixed wrap, scope documented)
//    Fixed with 64-bit addition: 0xFFFFFFFF + 1 = 0x100000000, which
//    correctly compares > any 32-bit target. This check can ONLY save
//    a final comparison, NEVER hash work: the full digest must already
//    be computed before word7 exists. It is only valid if word7 is
//    the most-significant word for that algorithm's target comparison;
//    that has NOT been established for any algorithm here, so this
//    function must not be wired into any share check.
// ---------------------------------------------------------------------------
inline bool check_word7_early_abort(uint32_t state_word7, uint32_t h_init7, uint32_t target_u32) {
    uint64_t sum = (uint64_t)state_word7 + (uint64_t)h_init7;
    return sum <= (uint64_t)target_u32;
}

} // namespace blackhole
