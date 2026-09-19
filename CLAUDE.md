# CLAUDE.md — 축제 주점 관리자 앱

이 저장소에서 작업하는 Claude Code를 위한 프로젝트 컨텍스트다. 작업 전에 끝까지 읽는다.

## 1. 제품 한 줄 요약

야외 축제 주점에서 스태프가 쓰는 **스태프 전용 Android 앱**이다. 테이블 회전(타이머), 웨이팅 호출, 주방 조리 현황을 관리하고, 입금확인은 서버가 한다.
데이터는 **Firebase(Firestore)**로 주고받으며, 핫스팟 폰의 모바일 데이터로 인터넷에 연결한다. **행사장 LTE가 끊길 수 있으므로 순단에 강해야 한다.**
행사는 하루짜리이고, 앱이 멈추지 않고 실수가 나지 않는 것이 가장 중요하다.

## 2. 시스템 구성과 담당

| 구성요소 | 담당 | 위치 |
|---|---|---|
| 관리자 앱 (Android, Kotlin) | **나 (이 저장소의 주 작업 대상)** | `android/` |
| Firebase 보안 규칙·색인·에뮬레이터 설정, 규칙 테스트 | 나 | `firebase/` |
| 서버들(웨이팅 등록, 입금확인, QR 주문) + 웹 3종 | 친구 | 별도 저장소 (같은 Firebase 프로젝트에 씀) |
| Firebase 데이터 설계 (계약서) | 공동 | `docs/FIREBASE.md` |
| 구버전(LAN 방식) 명세·mock 서버 | — | `docs/API.md`, `mock-server/` (참고용, 더 이상 쓰지 않음) |

- **`docs/FIREBASE.md`가 앱과 친구 서버 사이의 계약서다.** 앱은 이 설계에만 의존한다.
- 설계를 바꿔야 하면 코드보다 먼저 `docs/FIREBASE.md`를 고치고, 변경 내용을 사용자에게 알린다. 친구에게 전달해야 하기 때문이다.
- 친구가 정해야 하는 항목(🔶)은 임의로 확정하지 않는다. 앱에서는 경로·필드 이름을 `data/FirestoreMapper.kt`(`Fs`, `FirestoreMapper`)에 모아 둔다.
- 보안 규칙(`firebase/firestore.rules`)을 바꾸면 규칙 테스트(`firebase/test/`)도 같이 고친다.

## 3. 확정된 업무 규칙 (임의로 바꾸지 말 것)

- 기본 회전 시간은 **100분**이고, 타이머는 **착석 순간**에 시작한다. 결제와 무관하다.
- 종료 **15분 전**에 임박(주황), 시간이 지나면 초과(빨강·깜빡임).
  - 초과되면 진동과 소리를 내고, 정리될 때까지 1분마다 반복한다.
- 임박·초과는 **앱이 계산**한다. 서버 데이터에는 `EMPTY`/`OCCUPIED`, `seatedAt`, `extendedMinutes`만 있다.
- 테이블 연장은 +10/20/30분 단위다.
- **테이블 배치(가로×세로, 개수)는 서버 쪽이 관리한다.**
  - 앱은 `config/settings`의 `rows/cols`와 `tables`를 받아 **보여주기만** 한다.
  - 앱에 배치를 편집하는 UI를 만들지 않고, `rows`/`cols`를 쓰지 않는다.
  - 테이블 번호는 1..N으로 고정한다(QR에 인쇄되기 때문).
- 웨이팅은 **VIP가 최상단**, 나머지는 등록순이다.
  - VIP는 스태프가 앱에서 등록하고, 앱 전용 컬렉션 `vipWaitings`(번호는 `counters/vip`, 화면 표시는 "V1")에 저장한다.
  - 일반 손님은 친구 서버가 `waitings`에 만든다. 앱은 상태 변경(호출·무응답·복귀·취소·착석)만 한다.
  - 손님 조회 화면의 "내 앞 대기"는 VIP를 뺀 숫자다. 계산은 서버가 한다(`waitings`만 세면 된다).
- **웨이팅 맨 위 팀을 탭하면 바로 전화**한다.
  - `CALL_PHONE` 권한이 있으면 `ACTION_CALL`, 없으면 `ACTION_DIAL`을 쓴다.
  - 탭과 동시에 호출 시각을 기록한다. 인터넷이 끊겨 있어도 오프라인 쓰기 큐에 쌓였다가 연결되면 전송된다.
- 무응답: 호출 후 **3분**이 지나면 [무응답] 버튼이 나타나고, 스태프가 **수동**으로 처리한다.
  - 무응답 팀은 목록 아래쪽에 흐리게(반투명) 모아 둔다.
  - 무응답 팀은 대기 복귀(원래 순서)와 착석 배정이 가능하다.
- 주문은 손님이 **테이블 QR 웹**으로 넣는다. 앱의 주문 입력(1-5)은 예비 수단이다.
- **주문마다 선결제**다. 주문은 `PENDING`으로 생성되고, **서버가 입금을 판단해 `PAID`로 바꾼다.**
  - **앱은 입금을 확인하지 않는다.** 입금확인·주문취소 버튼이 없고, `paymentStatus`를 절대 쓰지 않는다(보안 규칙으로도 막음).
  - **앱이 쓰는 주문은 `PAID`만**이고, 구독 범위는 오늘(한국 시간 0시 이후)이다.
    테이블 상세의 주문 내역·합계, 주방(1-6) 모두 입금확인된 주문 기준이다.
  - 직원 주문(1-5, `source:"STAFF"`)도 QR 주문과 똑같이 서버의 입금 판단을 거친다.
  - 테이블 이용 종료 때 입금 전 주문을 어떻게 할지는 서버가 결정한다. 앱은 주문을 건드리지 않는다.
  - 주방 탭을 보고 있을 때 새 주문(서버가 `PAID`로 바꾼 주문)이 들어오면 짧은 알림음을 한 번 낸다.
- 빈 테이블에는 주문할 수 없다(QR 웹은 서버가, 직원 주문은 앱 트랜잭션과 보안 규칙이 거절).
- 스태프 공용 계정(이메일/비밀번호)으로 폰마다 처음 한 번 로그인한다. 앱을 실행할 때마다 담당자 이름을 고른다(1-O).
  모든 쓰기에 담당자 이름을 남긴다(감사 로그용).
- UX 원칙:
  - 모든 액션은 **최대 2탭**.
  - 되돌릴 수 없는 액션(이용 종료, 조리완료, 웨이팅 취소)에만 확인창을 띄운다. 계정 로그아웃도 비밀번호가 있어야 되돌릴 수 있어 확인창을 띄운다.
  - 입력은 숫자패드, 스테퍼, 버튼 위주로 한다.
  - UI 문구는 전부 한국어.
- 결제 연동, 통계, 메뉴 편집 UI, 다국어는 **범위 밖**이다.

## 4. 기술 스택과 구조

- Kotlin 2.0.20, Jetpack Compose (BOM 2024.09.00, Material3), AGP 8.5.2, Gradle 8.9
- minSdk 26, targetSdk 34, **Android 전용**
- 라이브러리: Firebase(BoM 33.7.0: Firestore, Auth), kotlinx-coroutines(+play-services), AndroidViewModel + StateFlow
- 네비게이션 라이브러리는 쓰지 않는다. 하단 탭 4개와 상태 변수로 전환한다.
- `android/app/google-services.json`은 git 에 올리지 않는다(README 참고).

```
android/app/src/main/java/com/festivalpub/admin/
├─ MainActivity.kt       App(): 로그인/담당자 화면 ↔ 메인 스캐폴드(하단 탭 4개, 설정 진입)
├─ AppViewModel.kt       스냅샷·연결 상태 노출, 1초 ticker(now), 알림 판단, 모든 쓰기 (담당자 이름 첨부)
├─ Alerts.kt             진동/소리 (알람 용도), 주방 새 주문 알림음
├─ KeepAliveService.kt   포그라운드 서비스: "실행 중" 상시 알림만. 백그라운드에서 앱이 얼지 않게 함 (로직 없음)
├─ Notifications.kt      알림 채널, "N번 테이블 시간 초과" 알림(누르면 테이블 탭)
├─ Phone.kt              dialPhone()
├─ data/Models.kt        앱 모델 (Settings, TableInfo, Waiting(key/label), Order, Snapshot)
├─ data/Logic.kt         테이블 상태 계산, 웨이팅 정렬, 주문 필터, 격자 크기, 포맷 함수 (순수 함수)
├─ data/FirestoreMapper.kt  Firestore 경로 상수(Fs) + 문서 ↔ 모델 변환 (순수 함수)
├─ data/FirebaseRepository.kt  리스너 → Snapshot, 연결 상태, 시계 보정, 트랜잭션·쓰기, 로그인
└─ ui/
   ├─ Common.kt          색상(PubColors), 테마, Stepper, ConfirmDialog
   ├─ ConnectScreen.kt   1-O 공용 계정 로그인(처음 한 번) + Firebase 연결 상태 + 담당자 선택
   ├─ TablesScreen.kt    1-1 대시보드(한 화면 격자), 1-2 테이블 상세 시트(주문 내역은 PAID만), OrderCard
   ├─ WaitingScreen.kt   1-3 목록, 1-4 상세 시트, VIP 등록
   ├─ OrderInputScreen.kt 1-5 주문 입력
   ├─ KitchenScreen.kt   1-6 주방
   └─ SettingsScreen.kt  1-S 배치 보기(읽기 전용)/시간/담당자/로그아웃
firebase/
├─ firestore.rules       보안 규칙 (스태프 공용 계정만, 허용된 필드·전이만)
├─ firestore.indexes.json 복합 색인 (orders: paymentStatus + createdAt)
├─ firebase.json, .firebaserc  에뮬레이터 설정 (demo-festival-pub)
└─ test/rules.test.mjs   규칙 테스트 (npm run test:rules)
```

### 동기화 방식 (중요)

- `FirebaseRepository`가 컬렉션 리스너 결과를 **전체 `Snapshot` 하나**로 합쳐 StateFlow 로 내보낸다.
- 앱은 쓰기만 하고 **쓰기 결과로 로컬 상태를 직접 고치지 않는다.** 화면은 언제나 스냅샷에서 그린다. 이 원칙을 깨지 않는다.
  - 오프라인에서 쓴 단순 변경도 Firestore 로컬 캐시를 거쳐 리스너로 다시 들어오므로, 이 원칙 안에서 바로 반영된다.
- 여러 문서를 조건부로 바꾸는 작업(착석·종료·VIP 등록·직원 주문)은 **트랜잭션**이다. 오프라인이면 실패하고 스낵바로 알린다.
- 단순 상태 변경(호출·무응답·복귀·취소·연장·조리완료·설정)은 update 로 쓴다. 오프라인이면 쓰기 큐에 쌓이고, 허용 여부는 보안 규칙이 판단한다.
- 재연결과 백오프는 Firestore SDK가 한다. 연결 상태는 네트워크 콜백과 리스너의 `isFromCache`를 합쳐 판단하고, 전송 대기 건수는 `hasPendingWrites`로 센다.
- 담당자를 선택하면 `KeepAliveService`(포그라운드 서비스, specialUse)가 시작된다. 은행 앱·통화 중에도 ViewModel 의
  타이머와 알림, Firestore 리스너가 계속 돈다. 로직을 서비스로 옮기지 않는다. 담당자 변경·로그아웃·앱 종료·최근 앱에서 스와이프하면 멈춘다.
- 모든 시각은 `serverTimestamp()`로 쓴다. 기기마다 시계가 다를 수 있으므로 모든 시간 계산은 `vm.now`를 쓴다.
  이 값은 `clocks/{uid}` 문서로 구한 서버 시각 오프셋으로 보정한 현재 시각이다.

## 5. 개발·검증 방법

```bash
# 앱 빌드 (android/app/google-services.json 필요 — README 참고)
cd android && ./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                                   # Logic.kt · FirestoreMapper 단위 테스트
./gradlew lint

# 보안 규칙 테스트 (Firestore/Auth 에뮬레이터를 띄워 실행, Java 21+ 필요)
cd firebase && npm install && npm run test:rules
```

- 터미널 빌드는 Android Studio 의 JDK 21(`C:\Program Files\Android\Android Studio\jbr`)로 한다. 시스템 기본 JDK 25는 Gradle 8.9에서 안 된다.
- 메모리가 부족한 PC다. 빌드, Firebase 에뮬레이터, 안드로이드 에뮬레이터를 동시에 여러 개 띄우지 않는다.
- 증분 컴파일 캐시가 꼬여 `Unresolved reference`가 엉뚱하게 나면 `./gradlew clean` 후 다시 빌드한다.

## 6. 현재 상태와 주의점

- 데이터 계층을 노트북 서버(REST + WebSocket)에서 Firebase 로 바꿨다. 친구 서버 쪽 구조는 아직 확정 전(🔶, `docs/FIREBASE.md` 7장)이다.
- 테스트: `Logic.kt` 39개 + `FirestoreMapper` 21개(JUnit), 보안 규칙 29개(에뮬레이터).
- 주문 구독 기준은 "앱을 켠 날 0시(KST)"다. 자정 이후에 앱을 새로 켜면 자정 전 주문이 빠진다(`docs/FIREBASE.md` 1-2).

## 7. 미결정 사항 (사용자에게 물어볼 것, 임의로 정하지 말 것)

- VIP 판별 기준. 지금은 스태프 누구나 등록할 수 있다.
- 관리자 화면의 전화번호 마스킹 여부. 지금은 전체 번호를 표시한다.
- 테이블별 정원 차이. 지금은 없다고 가정한다.
- 입금액이 주문 합계와 다를 때(부분 입금 등) 처리 기준. → 입금 판단은 서버 담당이므로 서버(친구) 쪽에서 정할 사항.

## 8. 작업 규칙

- 한국어로 소통한다. 커밋 메시지도 한국어로 쓴다.
- 업무 규칙(3장)이나 데이터 설계(`docs/FIREBASE.md`)를 바꿔야 하면 먼저 사용자에게 확인한다.
- 기능 하나를 끝내면 Firebase 에뮬레이터에 붙여 시나리오로 확인한 뒤 다음으로 넘어간다.
- 테스트용 웨이팅 전화번호는 **010-0000-0001 같은 명백한 가짜 번호만** 쓴다. 실제 폰에서 진짜 전화가 걸린다.
- 과한 추상화(DI 프레임워크, 멀티모듈 등)는 넣지 않는다. 하루짜리 행사용 앱이다. 단순함과 안정성이 우선이다.
