# blackhole_dimensity7050.hpp — FIXED verdict, 2026-11-03

Branch full-7050. Header fixed in place (see git log) and wired only
to on-tap native self-tests. It is NOT wired into XMRig workers,
dataset, or share checks, and must not be.

## Fixes applied (all 6, plus allocator signalling)
(a) Light pinning: cpu0..cpu5 only, never big cores. Heavy pinning:
worker 0->cpu6, 1->cpu7 only; a 3rd heavy thread returns -1 instead
of colliding. pin_and_verify() pins then reads the mask back.
(b) sched_setaffinity return is returned to the caller (0 / -1 with
errno). A failed pin is visible in the self-test string, never silent.
(c) THP readout: /proc/self/smaps_rollup only, scope labelled
"self-smaps_rollup". No /proc/meminfo fallback. -1 = unreadable,
-2 = line not present.
(d) Nbits: full 24-bit mantissa (0x00FFFFFF). Sign bit (0x00800000)
=> invalid (false). mant==0 => invalid (false), never 1.0.
(e) Word-7: 64-bit addition. 0xFFFFFFFF + 1 = 0x100000000, correctly
> any 32-bit target. Documented in the header: this can only save a
final comparison, never hash work, and only if word7 is the
most-significant word for that algorithm's target comparison —
not established here, so it must not be wired into a share check.
(f) Prevhash: VERIFIED against MoneroOcean XMRig v6.25.0-mo1
src/crypto/ghostrider/ghostrider.cpp — GhostRider selects indices
with select_indices() over seed data[4..36) (first 32 bytes of
PrevBlockHash), every nibble modulo N, deduplicated. There is NO
trailing-byte nibble rule, cn-pico is NOT in GhostRider's CN list
(dark, dark-lite, fast, lite, turtle, turtle-lite), and MinotaurX
is absent from this XMRig tree. The old predicate
is_l2_fast_branch_prevhash is DELETED and replaced by
is_unverified_prevhash_heuristic(), which must never select an
algorithm, scratchpad, or branch.
Allocator: no silent malloc fallback (nullptr on posix_memalign
failure), madvise_rc reported, thp_requested = (madvise_rc == 0)
which is a request success, NOT proof huge pages were granted.
NEON kernel: still labelled SYNTHETIC in the header (sA1 keyed by
sA0, sB1 by sB0, arbitrary PMULL lane, compiler-assigned registers),
never a RandomX / Verus / SHA-256 round.

## Tests actually run (this VM, 2026-11-03)
Host (x86_64, 4 vCPUs — so cpu5/6/7 do not exist here):
- heavy0 rc=-1, heavy1 rc=-1: VISIBLE failures, because cpu6/cpu7
  are absent on this host. That is the fix working, not a phone
  result. On the 8-core Dimensity 7050 these must be re-checked
  on-device; they are NOT claimed to pass there yet.
- heavy2 rc=-1 (must be -1, no collision): PASS.
- light0 rc=0 cpu=0: PASS. light5 rc=-1: visible failure on this
  4-CPU host (cpu5 absent), not a phone result. light6 rc=-1
  (must be -1, never big): PASS.
- Alloc 256KB: pointer 2MB-aligned=1, madvise_rc=0,
  thp_requested=1 (request only), anon_kb_self=0 on this host.
  0 AnonHugePages does not prove anything about the phone.
- Nbits: 0x1d00ffff ok=1 diff=1.000000. 0x00000000 ok=0.
  0x1d80ffff ok=0 (sign bit). 0x1b0404cb ok=1 diff=16307.420939.
- Word-7: overflow (0xFFFFFFFF+1, target 0) = 0 (fixed).
  Normal (10+5, target 20) = 1.
- Heuristic: byte0=1, byte2=0, labelled unverified only.
ARM cross-compile (NDK aarch64 clang, -march=armv8.2-a+crypto+aes
+sha2+dotprod+fp16 -mtune=cortex-a78): blackhole_bench.cpp,
anvil_native.cpp, vulkan_miner.cpp all compile, exit 0.
git diff --check: OK.
Local Gradle: gradle-8.7/bin/gradle compileDebugKotlin --no-daemon
=> FAILURE "Could not receive a message from the daemon." Known VM
sandbox IPC limitation, compilation never reached. Kotlin
correctness is NOT proven by a local build; CI is the only build
route. No local APK was built, no on-device benchmark was run.

## Wiring (honest scope)
- CMakeLists.txt: blackhole_bench.cpp added to anvil_native.
  The header is included by that file.
- NativeBench.kt: nativeBlackholeSelfTest() + blackholeSelfTest(),
  safe fallback if the native library is absent.
- Settings: "Run blackhole self-tests (once, fixed header)" button,
  once on tap, on a background thread, result toasted + logged.
  It pins the TEST thread only.
- NOT wired: XMRig worker pinning (XMRig already pins via its
  corrected [1, cpu] config arrays, commit 486214a), XMRig dataset
  allocation, word-7 share checks, prevhash algorithm selection.
  Any of those would need a separate, on-device-verified change.

## Remaining on-device checklist (user, after install)
See FULL-7050-STATUS.md plus: tap the blackhole self-test button
and record the exact string. On the phone, expected: heavy0/1 rc=0
with cpu=6/7, light0/5 rc=0 with cpu=0/5, heavy2/light6 rc=-1,
nbits genesis diff=1.0, word7 overflow=0. If heavy pins fail on
the phone, the fixed pinning has failed and no isolation conclusion
may be drawn. anon_kb_self is the app process only, not the
XMRig dataset.
