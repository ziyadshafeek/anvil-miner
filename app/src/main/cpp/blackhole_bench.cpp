// Blackhole self-tests, on-tap only. Never run during mining, never
// wired into XMRig workers, dataset, or share checks. This file only
// exercises the fixed header functions and reports their return codes
// and read-back values, so a phone run can be checked line by line.
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <sched.h>
#include "blackhole_dimensity7050.hpp"

#define LOG_TAG "blackhole_bench"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::string mask_str() {
    cpu_set_t m;
    CPU_ZERO(&m);
    if (sched_getaffinity(0, sizeof(m), &m) != 0) return "getaffinity-failed";
    std::string s;
    for (int c = 0; c < 64; c++) {
        if (CPU_ISSET(c, &m)) {
            if (!s.empty()) s += ",";
            s += std::to_string(c);
        }
    }
    return s.empty() ? "(none)" : s;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_j3r0nim0_anvil_NativeBench_nativeBlackholeSelfTest(JNIEnv* env, jclass) {
    char buf[2048];
    int cpu = -1;
    int heavy0 = blackhole::pin_and_verify_dimensity7050_thread(0, true, &cpu);
    int heavy0_cpu = cpu; cpu = -1;
    int heavy1 = blackhole::pin_and_verify_dimensity7050_thread(1, true, &cpu);
    int heavy1_cpu = cpu; cpu = -1;
    int heavy2 = blackhole::pin_dimensity7050_thread(2, true, &cpu);  // must fail: only 2 big cores
    int light0 = blackhole::pin_and_verify_dimensity7050_thread(0, false, &cpu);
    int light0_cpu = cpu; cpu = -1;
    int light5 = blackhole::pin_and_verify_dimensity7050_thread(5, false, &cpu);
    int light5_cpu = cpu; cpu = -1;
    int light6 = blackhole::pin_dimensity7050_thread(6, false, &cpu);  // must fail: light is 0..5 only
    std::string mask_after = mask_str();

    int madvise_rc = -999;
    bool thp_applied = false;
    blackhole::ThpAllocResult a = blackhole::alloc_thp_2mb_ex(256 * 1024, &madvise_rc, &thp_applied);
    bool alloc_aligned = a.ptr != nullptr && (((uintptr_t)a.ptr % 2097152) == 0);
    long anon_kb = blackhole::read_anon_hugepages_kb();  // scope=self-smaps_rollup, -1/-2 = error
    if (a.ptr) free(a.ptr);

    double d1 = 0, d2 = 0;
    bool nbits_genesis = blackhole::decode_compact_nbits_diff(0x1d00ffffu, &d1);
    bool nbits_zero = blackhole::decode_compact_nbits_diff(0u, &d2);
    bool nbits_sign = blackhole::decode_compact_nbits_diff(0x1d80ffffu, &d2);
    double d3 = 0;
    bool nbits_other = blackhole::decode_compact_nbits_diff(0x1b0404cbu, &d3);

    bool word7_overflow = blackhole::check_word7_early_abort(0xFFFFFFFFu, 1u, 0u);  // fixed: must be 0
    bool word7_normal = blackhole::check_word7_early_abort(10u, 5u, 20u);            // must be 1

    snprintf(buf, sizeof(buf),
        "blackhole: heavy0 rc=%d cpu=%d heavy1 rc=%d cpu=%d heavy2 rc=%d(must be -1) "
        "light0 rc=%d cpu=%d light5 rc=%d cpu=%d light6 rc=%d(must be -1) mask_after=%s "
        "alloc_aligned=%d madvise_rc=%d thp_requested=%d anon_kb_scope=self-smaps_rollup=%ld "
        "nbits_genesis_ok=%d diff=%f nbits_zero_ok=%d(must be 0) nbits_sign_ok=%d(must be 0) nbits_other_ok=%d diff=%f "
        "word7_overflow=%d(must be 0) word7_normal=%d(must be 1) "
        "heuristic_unverified_only byte0=%d byte2=%d",
        heavy0, heavy0_cpu, heavy1, heavy1_cpu, heavy2,
        light0, light0_cpu, light5, light5_cpu, light6, mask_after.c_str(),
        alloc_aligned ? 1 : 0, madvise_rc, thp_applied ? 1 : 0, anon_kb,
        nbits_genesis ? 1 : 0, d1, nbits_zero ? 1 : 0, nbits_sign ? 1 : 0, nbits_other ? 1 : 0, d3,
        word7_overflow ? 1 : 0, word7_normal ? 1 : 0,
        blackhole::is_unverified_prevhash_heuristic(0) ? 1 : 0,
        blackhole::is_unverified_prevhash_heuristic(2) ? 1 : 0);
    LOGI("%s", buf);
    return env->NewStringUTF(buf);
}
