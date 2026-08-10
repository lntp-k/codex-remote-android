# Android 16 migration verification evidence

Updated: 2026-08-08 (Asia/Seoul)

This record separates source/automated evidence from release-candidate and
device proof. The final automated values below came from one clean local ARM64
build after the release metadata change. They establish a signed QA candidate,
not a device-tested or promoted production release.

## Build identity

| Field | Value |
| --- | --- |
| Baseline commit | `c2b5e60fecc37bb8d95c52d082da06031f87187c` |
| Toolchain commit | `fc025c7` |
| API 36 compile commit | `a506f64` |
| API 36 target commit | `acd38eb` |
| Final implementation commit | `d9cd9f1` |
| Verification/documentation commit before release metadata | `e794d9e` |
| CI run URL | Not run; local ARM64 parity gate used |
| Application ID | `com.codex.remote` |
| Version | `0.1.7` / `versionCode = 8` |
| AGP | `8.13.2` |
| Gradle | `8.13` |
| JDK/JVM target | `17` |
| `compileSdk` | `36` |
| `targetSdk` | `36` |
| `minSdk` | `26` |
| Remote Codex used for SSH smoke | Not tested in this migration run |

The Android toolchain, compile SDK, and target SDK changes were committed as
separate steps. Kotlin remains 2.0.21; the migration did not bundle a Compose,
SSHJ, coroutine, serialization, or broad dependency refresh.

## Supply-chain and CI controls

Workflow: `.github/workflows/android.yml`

- GitHub Actions are pinned to full commit SHAs.
- Job permissions are `contents: read`.
- Gradle Wrapper JAR SHA-256 is checked as
  `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
- Gradle 8.13 distribution SHA-256 is pinned as
  `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.
- CI installs Android 35/36 platforms and Build Tools 35.0.0 explicitly.
- Release-signing environment variables are set to empty in CI so an
  accidentally signed production artifact cannot be produced there.
- CI asserts that the release output is exactly the unsigned minified artifact
  and that no `app-release.apk` exists.

The CI build command now includes all of:

```text
clean
testDebugUnitTest
lintDebug
assembleDebug
assembleDebugAndroidTest
assembleRelease
```

After assembly it checks that debug, Android-test, unsigned release, and R8
mapping artifacts exist; validates all APK ZIP containers; verifies 16 KiB
alignment for every APK; verifies the debug and Android-test signatures;
requires signature verification of the unsigned release to fail; and prints
SHA-256 digests.

## Final automated gate

| Check | Status / evidence |
| --- | --- |
| Clean build | Passed in 1m 7s; 137 actionable tasks (135 executed, 2 up-to-date) |
| JVM unit tests | 212 tests in 24 suites; 0 failures, 0 errors, 0 skipped |
| Android instrumentation tests compiled | Passed; Android-test APK assembled |
| Android instrumentation tests executed | **Not run**; no device/emulator runner was used |
| Lint | 0 errors, 19 warnings |
| Debug APK | SHA-256 `95bcc199c12788e3d61b4dde391f97b908a5187b323e2baa59a1a82b652d00de`; 22,232,575 bytes |
| Android-test APK | SHA-256 `d694e12a9cb9adc63845c413f0ec5ee3937acc70c7d4efbc1bf0a44594dfa5e5`; 1,099,873 bytes |
| Signed minified release APK | SHA-256 `fb18519c95a8d51bf4e845858f0cfacfc3c5f9474907ebd482332e0a17d2d440`; 3,614,820 bytes |
| R8 mapping | SHA-256 `d89257b851db25978a8582e88436122bfdbfdddb9598b5f72d422ae7c7934241`; 40,693,765 bytes |
| APK ZIP integrity | Passed for debug, Android-test, and signed release |
| Debug/test APK signatures | APK Signature Scheme v2 passed; debug certificate SHA-256 `051880cf4cb981b9b5e0326ecd54d424dd4948412bb6114701296fb1061cae0f` |
| Signed release signature | APK Signature Scheme v2 passed; one RSA-4096 signer; certificate SHA-256 `253257e7a3f4a1175b5eed656470e764efad2072252442cee617f99bb2bd1d20` |
| Signer continuity | Exact certificate SHA-256 match to the retained signed `0.1.6` APK |
| 16 KiB native-library alignment | Exact local-header check passed: 8/8 native libraries in both app APKs; test APK has no native library |
| Native ELF load alignment | Every `PT_LOAD` segment in all 8 release native libraries has `p_align >= 0x4000` |
| Package metadata (`compileSdk`/target/min/version) | `36` / `36` / `26` / `0.1.7` (`versionCode = 8`) |

The 19 lint warnings are 10 `GradleDependency`, 6
`NewerVersionAvailable`, one `AndroidGradlePluginVersion`, one
`ObsoleteSdkInt`, and one `TrustAllX509TrustManager`. The TLS warning points to
the cached Bouncy Castle `bcpkix-jdk18on-1.78.1.jar`, not an application-source
trust manager. It is recorded rather than suppressed.

### DGX Spark ARM64 build environment

The disposable ARM64 environment uses an explicit compatible `aapt2` override:

- path inside that build environment: `/toolchain/aapt2`;
- reported version: `Android Asset Packaging Tool (aapt) 2.19-`;
- SHA-256:
  `7e5ae2e1f62fc24cab14072555ffd0a1a7e1ce27e82cc1008967b835b6d8df5b`.

This binary is build-environment state, not a repository artifact. CI runs on
x86-64 and uses the official Android SDK package. Any replacement must have its
version and full digest recorded before use.

## Signed release-candidate gate

- [x] Release APK signed with the authorized local release certificate.
- [x] Signer certificate SHA-256 matches the retained signed `0.1.6` APK.
- [x] APK v2+ signature verification passes.
- [x] Signed APK ZIP integrity and 16 KiB ZIP/ELF alignment pass.
- [ ] Upgrade installation over `0.1.6` preserves saved connections.
- [ ] Minified signed build launches and completes a real SSH session.

Authorized signing material from the existing external local signing store was
injected through the four `CODEX_REMOTE_*` variables. No password or private
key was copied into the repository or artifact destinations, and their values
are intentionally absent from this record.

Static upgrade prerequisites are satisfied: both APKs use
`com.codex.remote`, the version code increases from 7 to 8, and the signer is
identical. That does not prove that an actual Android upgrade succeeds or that
saved connections survive it.

### Signed `0.1.7` artifact copies

The following two copies are byte-identical to the validated Gradle output and
have SHA-256
`fb18519c95a8d51bf4e845858f0cfacfc3c5f9474907ebd482332e0a17d2d440`:

- `/home/jl/coding/codex-remote-android/codex-remote-android-v0.1.7.apk`
- `/home/jl/mnt/dropbox-personal/codex-remote-android-v0.1.7.apk`

The retained `0.1.6` APK was not modified. The Dropbox remote reported the
uploaded object at 3,614,820 bytes; a read-back SHA-256 matched the build
output.

## Signed `0.1.8` background-recovery candidate

Updated: 2026-08-10 (Asia/Seoul)

This candidate adds process-wide foreground connection ownership,
default-network handoff, Doze-aware retry suspension, exact desired-target
restoration, bounded reconnect, and fail-closed handling for ambiguous work.
It remains a QA candidate rather than a device-validated production release.

| Check | Status / evidence |
| --- | --- |
| Source commit | `a330b01` (`Keep SSH sessions across Android network handoffs`) |
| Version | `0.1.8` / `versionCode = 9` |
| JVM unit tests | 249 tests; 0 failures, 0 errors, 0 skipped |
| Lint | 0 errors, 22 non-blocking warnings |
| Debug and Android-test APK assembly | Passed |
| Signed minified release assembly | Passed, including release lint-vital and R8 |
| Signed release APK | 3,632,216 bytes; SHA-256 `7a4fe4c3ee1aa58c619974265ed2f0b4bef5a34986fb84d182ec8061db7bf118` |
| Signature | APK Signature Scheme v2 passed; one RSA-4096 signer |
| Signer continuity | Certificate SHA-256 `253257e7a3f4a1175b5eed656470e764efad2072252442cee617f99bb2bd1d20`, exact match to `0.1.7` and `0.1.6` |
| Package metadata | `com.codex.remote`; compile/target/min SDK `36` / `36` / `26` |
| 16 KiB ZIP alignment | All stored 64-bit native-library data offsets are divisible by 16,384; 32-bit offsets are divisible by 4,096 |
| Native ELF alignment | Every 64-bit native-library `PT_LOAD` segment has `p_align = 0x4000` |
| Independent source review | Two lifecycle/network reviews found no remaining release-blocking source defect |

The following copies were read back as byte-identical:

- `/home/jl/coding/codex-remote-android/codex-remote-android-v0.1.8.apk`
- `/home/jl/mnt/dropbox-personal/codex-remote-android-v0.1.8.apk`

The previous `0.1.7` candidate was retained unchanged. No usable ADB-connected
phone was present, so overwrite installation, saved-connection preservation,
Samsung background/forced-Doze behavior, actual SSH, approval interruption,
and Wi-Fi/cellular/Tailscale transitions remain device-validation gates.

## Device and runtime validation -- not performed

- [ ] Android 14 / API 34 physical device or emulator.
- [ ] Android 15 / API 35 physical device or emulator.
- [ ] Android 16 / API 36 physical device or emulator.
- [ ] Samsung device background/foreground cycle.
- [ ] Tablet or large-screen resizing and rotation.
- [ ] Edge-to-edge insets and keyboard behavior.
- [ ] Predictive Back across workspace, connections, drawer, and dialogs.
- [ ] Password and private-key SSH authentication.
- [ ] First-use host-key confirmation and changed-key rejection.
- [ ] Wi-Fi, cellular, Tailscale, and direct hostname paths as applicable.
- [ ] Reconnect after screen-off, Doze, process death, and network transition.
- [ ] Three simultaneous remote sessions remain independently observable.
- [ ] Background approval opens the exact owning task and cannot be allowed
      under a neighboring task's visual context.

Compiling `assembleDebugAndroidTest` proves the instrumentation sources and APK
can be assembled; it does **not** execute Compose/device tests. No real SSH host
or remote Codex app-server was contacted in this migration verification run.

## Security evidence boundary

The security review referenced by
`SECURITY_REVIEW_2026-08-06.md` scanned a pinned **pre-fix** working-tree
snapshot. Targeted fixes and regression tests were added afterward. The signed
artifact is now built, but until a fresh scan is pinned to the final commit and
the device/runtime gates execute, neither that report nor this document is a
post-fix production-release attestation.

## Promotion rule

Do not promote this QA candidate as a production release until the remaining
signed-release gate and at least the Android 16 plus real SSH critical path
have executed on a device. Never use a debug or unsigned APK with production
SSH credentials.
