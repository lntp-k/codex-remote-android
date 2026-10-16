# 실기기 검증·0.1.11 수정 및 세션 마무리 — 2026-10-16

## 현재 상태와 게시 범위

2026-10-16 최종 확인: 수정 커밋 `2f38d7cde48fb13b292f85ada78f780a85249fea`은 독립 gpt-6.1-sol APPROVE, native 완료 대조 및 정식 승인 기록을 거쳐 fork/main에 fast-forward push됐다. exact-SHA internal-ci repository-baseline passed, static-baseline exit0. 원격 SHA를 직접 대조했다. 이 문서의 완료 기록은 그 뒤의 문서 전용 변경이다.

폰의 Tailscale 기존 계정을 연결하고 `Spark` 프로필(SSH22/userjl/폰 전용 키)을 저장했다. 호스트키를 실제 서버 fingerprint와 대조해 pin했다. USB reverse 목록이 빈 상태의 모바일망에서 공유 서버 실제 PHONE_READY_20261016 응답이 폰에 표시됐다. Ask를 선택했으며, 로컬 개인키 사본은 제거했다. 사용자 요청으로 상시 프로필·제한된 서버 권한·배포 entrypoint·Tailscale 연결은 유지한다.

사용자가 실기기 검증, 두 결함 수정, 연속5분 백그라운드 검증, 세션 마무리 및 폰 상시 접속 설정을 요청했다. Samsung user0의0.1.11(versionCode12) 설치를 실제 package 조회로 재확인했다. 업그레이드 시나리오는 사용자 지시로 제외한다.

게시 대상은 fork `lntp-k/codex-remote-android` main이며, upstream origin은 읽기 전용이다. 검토 기준 base는 `1b64744704f83163721ecde87ee7387a3f3a0abc`. 이번 세션 변경만 새 커밋으로 만든다. 기존 로컬 커밋은 모두 fork/main에 이미 포함돼 추가로 게시할 이전 세션 커밋은 없다. 후보 SHA와 승인·게시 결과는 exact-SHA gate 기록 및 최종 보고에서 확인한다. 수정 커밋은 이미 게시됐으며 이 완료 문서 변경은 별도 검토 후 게시한다.

## 경로 분류와 원본

| 경로 | 분류·처리 |
|---|---|
| `/home/jl/coding/remote-codex-android` | A. 시작 작업 사본, working branch1908c079와 기존 dirty 기록·APK·sidecar 보존. reset/pull/clean 금지 |
| `/tmp/codex-android-device-fixes` | A. 같은 저장소의 등록된 분리 worktree, 이번 코드·테스트·본 문서만 게시 후보 |
| `/tmp/codex-android-pr4-review` | A. 같은 저장소의 기존0.1.10 버전 변경을 보존, 이번 게시에 포함하지 않음 |
| `~/.codex`의 기존 검증 산출물·대화 파일 | C. 넓은 제어 저장소 내부이므로 wrap-up에서 새 수동 쓰기·커밋·인덱싱 없음. 기존 산출물은 보존 |
| `~/.claude` | C. 자동 스냅샷 게시·수동 문서 쓰기 없음. 정식 도구가 소유하는 승인 기록만 기존 gate 절차로 처리 |
| `~/.ssh/authorized_keys` 및 배포 SSH entrypoint | unclassified system write. 사용자가 요청한 폰 전용 상시 권한만 설정, 별도 HANDOFF/snapshot 없음 |
| `/tmp/codex-android-*`, `/tmp/android-wrap-20261016`, Mac QA cache | scratch. 시험 개인키는 폰 저장 후 제거, 필요한 근거·소스는 문서/커밋/기존 산출물로 보존 |

GIT_DIR/GIT_WORK_TREE unset, linked worktree .git 포인터 정상. Mode B 없음, 사건 식별정보 없음, memory 변경 없음. collector exit2: memories에 원격 target 없음 및 전체 lock 조회 불완전. 해당 memory는 읽기 전용이라 게시 범위 아님. 직접 lock 조회에서 Android 잠금 없음, 다른 프로젝트 잠금은 손대지 않음. fd 변경시각은 참고만 사용했다.

## 검증과 재사용

| 단위 | 입력·의존 대상 | 상태·근거 | 재사용/재실행 이유 |
|---|---|---|---|
| 수식 | MacroInfo 반사 호출·minified DEX | NewCommandMacro 이름/생성자/executeMacro 유지, 기존 DEX assertion red→수정APK green, 실제 폰 x² 렌더링 | 일반 수식만으로 사용자 명령 정상 여부를 추정하지 않음 |
| 새 작업 | 실제 refreshThreads/RPC parser | 오래된 목록에서 selected new→null 회귀 red→수정 green; 실제 신규 작업 응답까지 선택 유지, Stop→Send | mock 결과 대신 실제 공유 서버 재현 및 수정 효과 확인 |
| 외부 보관·삭제 | pinned0.159.2 schema, ThreadRemoved | archived/deleted ID 보존, 선택·실행·권한 정리. closed는 unload여서 삭제로 해석하지 않음. 외부 클라이언트 보관을 실제 폰에서 확인 | 독립 영역 검토에서 발견한 부작용 수정·재검증 |
| 통합 | 고정 Android 이미지·최종 app 입력 | 294 tests, failures/errors/skips0; lint errors0/warnings22; release build1m45s | source manifest 일치 확인. 커밋 후보에서도 Gradle 입력 검증 실행, 정식 CI는 push gate에서 별도 |
| 연속 백그라운드 | USB충전, HOME 후 sleep | 300.2568초/31표본, 동일PID3188·SSH service·Dozing, 관측오류0·재연결0.60초 후 실제 응답 전송, 복귀 후 재연결/재선택 없이 표시 | 이전15분 시험의USB공백을 성공으로 재사용하지 않음 |
| 상시 SSH 설정 | 폰 Tailscale·전용 키·고정 CLI | 사용자가 추가 요청. 완료: reviewed entrypoint 배포해시=소스해시, VPN·저장 프로필·실제 응답·직접 SSH 유지 확인. 키는 폰 암호화 저장소에만 남김 | 임시 USB 프로필을 상시 설정으로 잘못 보고하지 않음 |

현재APK 프로세스 logcat: NoSuchMethodException0, FATAL EXCEPTION0, ANR in0. 링 버퍼 범위이며 수시간/deep Doze는 미검증이다. 기본 CLI0.162.0과 실행 공유 daemon0.159.2는 불일치하므로 폰 전용 entrypoint는 기존 일치 CLI0.159.2를 고정한다. 기본CLI/daemon을 교체하거나 재시작하지 않는다.

## 소스 정본·산출물·재생성

정본은 이 커밋의 Kotlin, proguard, Gradle 및 tools source이다. 원래 작업 사본과 이전0.1.10 빌드 worktree는 보존 원본이다. 이전 세션 문서의 당시 미검증 주장은 날짜가 있는 이력이며 위 실제 검증 결과가 최신이다.

APK SHA256: `b0f52ad1c42a57dafb2e43a3e063d658d5243ed359aafa102267f0aedd68106d`.
Signer SHA256: `253257e7a3f4a1175b5eed656470e764efad2072252442cee617f99bb2bd1d20`.
파일: 시작 작업 사본의 `codex-remote-android-v0.1.11.apk`; `.build.json`/`.sha256`은 파생 근거이며 Git에 올리지 않는다. 정확한 app source manifest는 그 build.json에 있다. 아래 명령의 서명 환경 변수는 기존 비밀 저장소에서 프로세스 환경으로만 공급하며 값을 문서/로그에 쓰지 않는다.

```sh
docker run --rm --name android-device-fixes-build \
  -v /tmp/codex-android-device-fixes:/workspace \
  -v /home/jl/.local/share/codex-remote-android/signing:/qa-signing:ro -w /workspace \
  --env CODEX_REMOTE_KEYSTORE_PATH --env CODEX_REMOTE_KEYSTORE_PASSWORD \
  --env CODEX_REMOTE_KEY_ALIAS --env CODEX_REMOTE_KEY_PASSWORD \
  sha256:d3768cd434906067cbaa84e333c691e5256571f6dce1db04e5ef7f7cba901f35 \
  ./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
python3 -m unittest discover -s tools -p test_codex_ssh_entrypoint.py
```

컨테이너용 keystore path는 `/qa-signing/release.p12`, alias는 `codex-remote`. 호스트 비밀 파일은 기존 설정을 읽되 출력하지 않는다. 사용자0만 검증하고 Secure Folder150은 제외한다.

## 상시 폰 접속 운영

폰 Tailscale을 같은 tailnet에 연결한 뒤 Spark SSH host/port22/userjl를 사용한다. 전용 키는 Android Keystore로 암호화해 저장하고, 서버는 그 폰의 tailnet IP에서만 허용한다. OpenSSH `restrict`와 reviewed forced-command entrypoint는 POSIX probe, CLI version, daemon version, shared proxy만 허용한다. 임의 shell/SFTP/isolated stdio는 거부한다. 개인키는 로컬·저장소에 남기지 않는다.

기존 공유 daemon과 일치하는 CLI 바이너리가 없어지거나 daemon 버전이 바뀌면 전용 key command의 CLI 경로를 검증해 갱신해야 한다. 실패를 다른 CLI/격리 서버로 조용히 우회하지 않는다. Tailscale을 끄면 모바일망에서 연결되지 않으며 USB 연결은 필요하지 않다. 기본 권한은 Ask, Full access는 사용자가 필요할 때 전환한다.

## 결정·후속·기한

- 2026-10-16: 일반 thread/list 부재는 선택된 세션 삭제 근거로 쓰지 않고 명시적 archived/deleted를 처리한다. 늦은 목록 응답은 세대 번호로 제외한다.
- 2026-10-16: 일치하는 기존 CLI를 폰 전용 제한 entrypoint에 고정해 현재 Desktop 공유 서버를 보존한다. 서비스 재시작/글로벌 경로 변경을 선택하지 않았다.
- 기한·계산: 제공된 기한 없음(N/A). 사용자 결정: 추가 deep Doze/수시간 시험은 별도 범위. 상시 접속 요청은 승인받은 현재 범위다.
- 수정 커밋의 검증·Sol 승인·gate 기록·main push·원격 대조 및 폰 상시 연결 완료. 이 문서 전용 완료 변경의 검토·게시 결과는 최종 보고와 gate 기록으로 확인한다. 추가 사용자 결정은 없다.
- 다른 사본: dirty 시작 작업 사본/0.1.10 worktree는 갱신하지 않는다. 인덱스는 main 작업 사본이 게시 SHA와 다르므로 pending. 기존 host daemon은 Android 저장소의 실행 소비자가 아니어서 재시작 없음.
- 모든 QA/Gradle 작업 결과 회수 완료. 정식 검토 worker는 native 완료 기록까지 대조한다. 정식 검토 전 영역 검토는 merge 승인 대체가 아니다.
- 교훈: shared server를 쓰는 Android UI의 목록 snapshot과 명시적 lifecycle event를 구분해야 한다. 테스트 inspector에 노출되지 않는 popup은 실제 화면으로 확인한다.

## tool/hook anomalies (handled separately)

- 2026-10-16 Codex, shell static gate: `명령 선두에 글롭 문자([, ], *, ?)가 있어 실행될 명령을 정적으로 판정할 수 없습니다. 고정된 파일명으로 다시 쓰십시오.` 3회. 실제 대체: 고정 스크립트/PlistBuddy 및 사용자 시험 작업 열기. [ESTIMATE] 인용부 기호 오인. 열림, 별도 조사. gate 설정 변경 없음.
- 2026-10-16 Codex, Kotlin daemon: `The daemon has terminated unexpectedly on startup attempt #1 with error code: 0` / `Kotlin compile daemon is ready`. 같은 Gradle 실행 자동 복구 후294개 통과. [ESTIMATE] 초기 handshake. 열림, 별도 조사.
- 2026-10-16 Codex, collector: exit2 및 `review locks: review-log locks exited 1`, `no origin target branch found locally: publication range not examined`. 실제 대체: intended fork/main 직접 fetch/범위 열거, review-log locks 직접 조회. memory는 제외. [ESTIMATE] 기본 origin과 fork 대상 차이 및 집계기 반환 처리. 열림, 별도 조사.

## 최종 근거와 인계

- CI report: `/home/jl/.local/state/internal-ci/push-gate-runs/9dd07170a20b425aa5e3efd1fda710fe/report.json`, revision2f38d7cd, base1b647447, outcome passed, static-baseline127ms/exit0. GitHub Actions는 판정 근거에서 제외했다.
- 수정 Sol 검토127.65초; begin0.12초, verdict기록0.18초, 보호push2.44초. exact command/start/end/elapsed/result는 시작 작업 사본의 APK `.wrap-up.json` 파생 기록에 보존한다.
- 폰용 entrypoint 정본은 `tools/codex_ssh_entrypoint.py`, 배포본은 `~/.local/share/codex-remote-android/ssh-entrypoint.py`. 동일SHA256 대조 완료. `authorized_keys`에는 폰 tailnet IP와 fixed command로 제한한 전용 public key 항목1개를 유지한다. 개인키·암호는 Git에 없다.
- 로컬 앱 소스 변경이 없는 문서 전용 완료 단계에서는 커밋된 후보를 Gradle runner로 대조한 294개 테스트·lint 결과를 재사용한다. shell helper4개 unittest는 별도 재실행한다. 강제exact-SHA CI와 문서의 정식 Sol 검토는 생략하지 않는다.
- 지식 인덱스 목록 Last verified2026-10-10. codebase-memory/graphify는 대상 main 작업 사본이 dirty1908c079여서 게시 SHA와 달라 pending. Graphiti/GBrain은 no wrap-up action. broad hazard paths는 인덱싱하지 않는다. 목록·allowlist·watcher는 바꾸지 않았다.
- 수정과 테스트/QA worker 결과 전부 회수. 정식 Sol 검토1개(gpt-6.1-sol/high), 기존 구현/재현 worker2개(부모 모델 상속), 토큰 도구 미제공. 승인 기록은 채널/작업 ID와 직접 자식 native 완료를 검증했다. 완료 문서 검토는 별도 직접 자식으로 수행한다.
- 폰은 사용 가능한 상태로 설정했다. 실제 업무 내용·다른 Desktop 초안은 전송·변경하지 않았다. 이후 연결에 USB가 필요 없고 Tailscale은 켜져 있어야 한다. shared daemon 버전이 바뀌면 일치하는 CLI 고정 경로를 점검한다.
- scope상 미검증: deep Doze/수시간/이전 버전 업그레이드. APK 설치·사용에는 추가 설정 작업이 남아 있지 않다. dirty 원본 사본은 사용자 작업 보존을 위해 그대로 뒀다.
- 현재 단계에서 채팅 삭제 안전은 첨부자료 및 C경로 검증 근거의 별도 장기 보관 여부를 확인할 수 없어 권고하지 않는다. 코드·운영 결정은 이 HANDOFF로 인계한다.
- 2026-10-16 Codex UI inspector: `ValueError: Expected one node Open task list, got 0` / 직전 dump가 알림창 또는 다른 foreground로 전환돼 조작을 중단했다. 실제 대체: 허용 앱 패키지만 텍스트를 저장·출력하도록 scratch inspector를 제한하고, 대상 앱 복귀 후 필요한 작업만 수행했다. [ESTIMATE] 사용자 화면 전환/알림창. 별도 UI 도구 개선 대상이며 개인 알림 내용은 이 문서/저장소에 복사하지 않았다.
