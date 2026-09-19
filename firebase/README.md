# Firebase 로컬 검증 (2단계)

운영 DB 읽기·쓰기는 금지한다. 실제 비밀번호를 여기에 입력하지 않는다.

## 안전장치

- Debug 기본: Auth 127.0.0.1:9099, Firestore 127.0.0.1:8080. 에뮬레이터 프로젝트 ID는 **`demo-festival-pub`**(앱 debug·시드·테스트·실행 스크립트 공통, `scripts/emulator-guard.mjs`의 `PROJECT_ID`). 운영 ID `mobokfestivalpub`는 release와 운영 debug에서만 쓴다.
- 테스트 FirebaseApp에는 가짜 API 키를 사용하고 운영과 다른 이름(local-emulator)으로 세션을 분리한다. google-services.json의 운영 키를 테스트에 사용하지 않는다.
- FirebaseInitProvider 자동 초기화를 제거했다. Application에서 useEmulator를 설정한 뒤 Repository가 SDK를 사용한다. 초기화 실패 시 앱 접근을 차단한다.
- 연결 실패 시 테스트 DB 오류를 표시하며 운영으로 전환하는 코드가 없다.
- Debug에 보라색 `테스트 DB(에뮬레이터)` 띠. 운영 Debug는 빨간 `⚠️ 실제 DB`. Release는 띠 없음.
- 운영 Debug는 `-PfirebaseDebugProduction=true`를 명시해야 한다. 이 속성을 gradle.properties에 저장하지 않는다. 지금은 빌드 설정 검증만 하며 운영 앱 실행은 3단계 승인 전 금지한다.
- Release는 항상 운영 연결이다. 3단계 전 설치·실행하지 않는다.
- 기본 firebase.json에는 배포 대상이 없고 .firebaserc에는 기본 프로젝트가 없다. 별도 firebase.emulator.json의 predeploy와 npm run deploy는 의도적으로 실패한다.
- 지원 진입점은 아래 npm 명령만이다. emulators.mjs는 start/test 외 인자를 거부하고 외부 TCP/HTTP를 차단한다. 원시 firebase CLI에 임의 config/project를 지정하는 행위까지 OS 차원에서 막는 장치는 아니다.
- 자동 다운로드도 차단된다. 필요한 CLI 패키지와 Firestore 에뮬레이터 JAR은 사전 설치돼 있어야 한다.

`demo-`로 시작하는 프로젝트 ID는 Firebase가 "실제 프로젝트 없음"으로 취급한다. 에뮬레이터로 지정하지 않은 제품을 호출해도 운영 리소스에 닿지 않고 실패하며, 이 ID로는 배포 대상도 존재하지 않는다. 그 위에 Node 도구의 외부 네트워크 차단(loopback만 허용)을 이중으로 둔다. 안전장치 테스트가 모든 에뮬레이터 설정의 ID가 `demo-`로 시작하는지 검사한다.

공식 문서: [Firestore 연결](https://firebase.google.com/docs/emulator-suite/connect_firestore), [Auth 연결](https://firebase.google.com/docs/emulator-suite/connect_auth).

## 실행 (PowerShell, firebase 폴더)

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
npm run test:safety
npm run test:rules
```

테스트 명령은 Auth/Firestore를 시작하고 순차 테스트 후 종료한다. Java 힙은 512MB로 제한했다. Android 에뮬레이터를 동시에 띄우지 않는다. 빌드도 Firebase 종료 후 순차 실행한다.

## USB 실기기

1. 폰에서 USB 디버깅을 허용하고 PC 연결을 승인한다.
2. 별도 터미널에서 `npm run emulators`로 로컬 서비스를 유지한다.
3. 다른 터미널에서 `./scripts/connect-usb.ps1` 실행 (`adb`가 PATH에 없으면 `-Adb`에 SDK platform-tools/adb.exe 경로 지정).
4. 아래 시드를 실행하고 **기본 debug APK만** 설치한다. 띠가 `테스트 DB(에뮬레이터)`인지 확인한다.
5. 가짜 테스트 계정 `staff@example.test` / `emulator-only-1234`로 로그인한다. 운영 비밀번호를 쓰지 않는다.

```powershell
$env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8080'
$env:FIREBASE_AUTH_EMULATOR_HOST = '127.0.0.1:9099'
npm run seed -- --reset
```

`--reset`은 로컬 Firestore 데이터를 비운 뒤 다시 만든다. Auth에는 테스트 계정만 추가하며 운영과 무관하다. 호스트 변수가 없거나 외부 주소/다른 포트이면 초기화 전에 거부한다. 모든 전화번호는 010-0000-00xx 범위의 고정 가짜 번호다. 실제 전화 버튼은 누르지 않는다.

## 검증의 범위와 한계

- Node 테스트: v2 규칙 권한표와 SDK의 트랜잭션/배치/쿼리/오프라인 동작. Android Repository를 직접 실행하는 테스트는 아니다.
- Android JVM 테스트: 실제 Mapper/Logic의 시간·금액·정렬·묶음·배지·종료 보호 계산.
- Firestore 에뮬레이터는 운영 복합 색인 존재를 보장하지 않는다. 여기에는 스펙에 이미 있다는 두 색인만 기록한다.
- 규칙은 스펙 3장의 권한표를 흉내 낸다. 실제 배포된 규칙을 내려받지 않았다. 로그인 스태프에게 넓은 쓰기 권한이 있어 상태 전이·추가 필드를 규칙에서 강제하지 않는다.
- WriteBatch는 주문 줄과 합계의 원자성은 보장하지만, 전송 직전 읽기와 배치 사이의 동시 이용종료까지 잠그지는 않는다. 이 제약을 임의로 새 필드·규칙으로 해결하지 않는다.
- 입금확인·연장·호출 등 단순 update는 트랜잭션과 달리 서버의 최신 상태에 대한 조건부 쓰기가 아니다.

## 폰에서 확인할 시나리오

| 구분 | 실기기 확인 |
|---|---|
| 안전 | 로그인·메인·설정 상단 테스트 띠, 서버 중지/USB reverse 제거 시 오류와 운영 전환 없음 |
| a | 동시에 착석 요청한 두 클라이언트 중 하나만 성공, 실패 스낵바 |
| b | 종료 확인창을 열고 다른 클라이언트로 종료·재착석한 뒤 이전 확인창 확정 시 거절 |
| c | 메뉴 여러 줄 전송과 합계, 전송 중 재전송 방지 |
| d | 시드의 1번 테이블 5분 미확인 배지, 금액 확인창, 확인 후 타이머 그대로 |
| e | NO_SHOW 아래 흐림, 복귀 순서와 호출 경과 표시, 3분 이후 버튼 |
| f | VIP 우선 표시, 자동 ID 비노출 |
| g | 같은 테이블/같은 시각 묶음, 조리완료 확인창 및 전체 줄 제거, 새 주문 알림음 |
| h | 로그아웃 후 로그인 화면, 로컬 규칙 권한 거절 시 로그인 화면 이동 |
| i | 에뮬레이터 중지 또는 reverse 해제 시 빨간 오류·캐시 유지, 트랜잭션 실패 스낵바, 복구 후 큐 전송 |

LTE 끊김과 USB 로컬 에뮬레이터 단절은 다르다. USB 테스트는 reverse/에뮬레이터를 끊어 검증하며 LTE/운영 연결은 3단계에서 별도로 확인한다.
