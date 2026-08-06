# Android 16 migration verification evidence

Updated: 2026-08-06 (Asia/Seoul)

This record separates source/automated evidence from release and device proof.
The final automated values below came from one clean local ARM64 build after
the last source change. They are build evidence for the reviewed working tree,
not a production release or device attestation.

## Build identity

| Field | Value |
| --- | --- |
| Baseline commit | `c2b5e60fecc37bb8d95c52d082da06031f87187c` |
| Toolchain commit | `fc025c7` |
| API 36 compile commit | `a506f64` |
| API 36 target commit | `acd38eb` |
| Final implementation commit | `d9cd9f1` |
| CI run URL | Not run; local ARM64 parity gate used |
| Application ID | `com.codex.remote` |
| Version | `0.1.6` / `versionCode = 7` |
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
| Clean build | Passed in 1m 7s; 136 actionable tasks (134 executed, 2 up-to-date) |
| JVM unit tests | 212 tests in 24 suites; 0 failures, 0 errors, 0 skipped |
| Android instrumentation tests compiled | Passed; Android-test APK assembled |
| Android instrumentation tests executed | **Not run**; no device/emulator runner was used |
| Lint | 0 errors, 19 warnings |
| Debug APK | SHA-256 `e5d85474e5eaf8c104070815209e43a1c8314048704780021a111def81139c61`; 22,232,575 bytes |
| Android-test APK | SHA-256 `d694e12a9cb9adc63845c413f0ec5ee3937acc70c7d4efbc1bf0a44594dfa5e5`; 1,099,873 bytes |
| Unsigned minified release APK | SHA-256 `4c9fe8bac82ba26c468e6addf8ad3ff7b09b5878870127b64ea117448d267b25`; 3,602,532 bytes |
| R8 mapping | SHA-256 `d89257b851db25978a8582e88436122bfdbfdddb9598b5f72d422ae7c7934241`; 40,693,765 bytes |
| APK ZIP integrity | Passed for debug, Android-test, and unsigned release |
| Debug/test APK signatures | APK Signature Scheme v2 passed; debug certificate SHA-256 `051880cf4cb981b9b5e0326ecd54d424dd4948412bb6114701296fb1061cae0f` |
| Unsigned release remains unsigned | Confirmed; signature verification failed as required |
| 16 KiB native-library alignment | Exact local-header check passed: 8/8 native libraries in both app APKs; test APK has no native library |
| Package metadata (`compileSdk`/target/min/version) | `36` / `36` / `26` / `0.1.6` (`versionCode = 7`) |

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

## Signed release gate -- not performed

- [ ] Release APK signed with the production certificate.
- [ ] Signer certificate SHA-256 matches the installed/version `0.1.6` APK.
- [ ] APK v2+ signature verification passes.
- [ ] Production-signed APK ZIP integrity and 16 KiB alignment pass.
- [ ] Upgrade installation over `0.1.6` preserves saved connections.
- [ ] Minified signed build launches and completes a real SSH session.

The four `CODEX_REMOTE_*` signing variables were unavailable for this work.
The unsigned release is deliberately non-distributable and must not be used as
evidence for any item above.

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
snapshot. Targeted fixes and regression tests were added afterward. Until a
fresh scan is pinned to the final commit and the final artifacts are built,
neither that report nor this document is a post-fix release attestation.

## Promotion rule

Do not publish or install a production release until the signed-release gate
is complete and at least the Android 16 plus real SSH critical path has
executed on a device. Never use a debug or unsigned APK with production SSH
credentials.
