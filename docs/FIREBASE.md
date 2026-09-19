# 축제 주점 시스템 — Firebase 데이터 설계 (v0.3)

> 노트북 서버(REST + WebSocket, `docs/API.md` v0.2) 방식을 **Firebase 로 교체**한 설계. 관리자 앱은 이 문서대로 구현되어 있다.
> **"🔶 친구 확인 필요"** 표시는 친구 서버 쪽과 아직 합의하지 않은 초안이다.
> 앱에서는 경로·필드 이름을 `android/.../data/FirestoreMapper.kt`(`Fs`, `FirestoreMapper`) 한 곳에 모아 두었다.
>
> - 앱과 친구의 서버(웨이팅 등록, 입금확인, QR 주문)가 **같은 Firebase 프로젝트**를 본다. 노트북 서버, REST, WebSocket 은 없다.
> - 핫스팟 폰의 모바일 데이터로 인터넷에 연결한다. **LTE 순단**에 강해야 한다.
> - 유지되는 규칙: 입금확인은 서버가 한다(앱은 `PAID` 주문만 씀). 테이블 배치(rows/cols)는 서버 쪽이 관리하고 앱은 보여주기만 한다.
>   CLAUDE.md 3장의 나머지 업무 규칙과 앱의 화면·알림 동작은 그대로다.

---

## 0. Firestore 를 쓰는 이유 (vs Realtime Database)

| | Firestore (채택) | Realtime Database |
|---|---|---|
| 여러 문서 원자적 쓰기 | 트랜잭션으로 **테이블 + 웨이팅**을 한 번에 | 다중 경로 업데이트는 되지만, 조건 확인까지 하려면 공통 부모 노드에 트랜잭션을 걸어야 함 |
| 보안 규칙 | 필드 단위 제한이 쉬움 (`diff().affectedKeys()`) | `.validate`를 필드마다 써야 함 |
| 오프라인 | 로컬 캐시 + 쓰기 큐 (기본 켜짐) | 같음 |
| 서버 시각 오프셋 | 직접 구함 (4장) | `.info/serverTimeOffset` 기본 제공 |
| 연결 상태 | 직접 판단 (5장) | `.info/connected` 기본 제공 |
| 쿼리 | `status in [...]`, `paymentStatus == PAID && createdAt >= 오늘` | 한 필드로만 정렬·필터 |

**RTDB 라면 달라지는 점**
- 경로 모양은 같다(`/settings`, `/tables/{no}` …). 시각은 `ServerValue.TIMESTAMP`(epoch ms)로 바뀐다.
- 앱이 `PAID`만 읽게 하려면 **서버가 PAID 주문을 `/paidOrders`에 복사**해 둬야 한다.
- 연결 상태와 시계 오프셋은 `.info` 경로로 해결된다.

---

## 1. 데이터 구조

모든 시각은 **Firestore `Timestamp`**이고, 쓸 때는 `serverTimestamp()`로 기록한다(4장).
앱이 쓰는 문서에는 담당자 이름(`updatedBy` / `createdBy` / `addedBy` / `cookedBy`)과 서버 시각(`updatedAt`)이 남는다.

```
config/settings        { rows, cols, rotationMinutes, imminentMinutes, noShowMinutes, updatedBy, updatedAt }
config/staff           { names: ["동현", "영준", "민지"] }
counters/waitings      { next }     // 일반 대기번호 — 친구 서버 전용 (앱은 접근 못 함)
counters/vip           { next }     // VIP 번호 — 앱 전용
counters/orders        { next }     // 주문번호 — QR 주문 웹과 앱(직원 주문)이 공유
menu/{menuId}          { id, name, price, category, soldOut }
tables/{no}            { no, status, seatedAt, extendedMinutes, partySize, phone, waitingId, waitingIsVip,
                         updatedBy, updatedAt }                                   // 문서 ID = "1" … "N"
waitings/{id}          { id, phone, partySize, isVip:false, status, createdAt, calledAt, tableNo, updatedBy, updatedAt }
                                                                                   // 일반 손님 (친구 서버가 생성)
vipWaitings/{id}       { id, phone, partySize, isVip:true, status, createdAt, calledAt, tableNo,
                         createdBy, updatedBy, updatedAt }                        // VIP (앱만 생성·수정)
orders/{id}            { id, tableNo, items:[{menuId,name,price,qty}], total, source,
                         paymentStatus, cookStatus, createdAt, addedBy,
                         paidAt, paidBy, cookedAt, cookedBy }                     // 문서 ID = 주문번호
clocks/{uid}           { at }       // 기기 시계 보정용, 기기마다 자기 문서만 (4장)
```

### 1-1. 현재 모델과의 대응표

| Models.kt | Firestore | 변환 | 비고 |
|---|---|---|---|
| `Settings.*` | `config/settings` 같은 이름 | Long → Int | 문서나 필드가 없으면 기본값 |
| `Snapshot.staff` | `config/staff.names` | 문자열만 | |
| `Snapshot.menu` | `menu/*` | | `id` 없으면 문서 ID |
| `TableInfo.*` | `tables/{no}` 같은 이름 | `seatedAt`: Timestamp → ms | `waitingIsVip` 추가(`waitingId`가 어느 컬렉션 번호인지) |
| `Waiting.*` | `waitings/*` + `vipWaitings/*` | 시각 → ms | **`isVip`는 어느 컬렉션에서 왔는지로 정함**(필드 값은 믿지 않음) |
| `Waiting.key` (신규) | — | `"w12"` / `"v3"` | 두 컬렉션 번호가 겹쳐도 화면에서 구분 |
| `Waiting.label` (신규) | — | `"12"` / `"V3"` | 화면의 대기번호 표시 |
| `Order.*` | `orders/{id}` 같은 이름 | 시각 → ms | `total`이 없으면 항목 합계 |
| `Snapshot.serverTime` | (삭제) | — | 시계 보정은 `clocks/{uid}`로 대체 (4장) |

앱은 모든 리스너 결과를 합쳐 지금과 같은 `Snapshot`(StateFlow)으로 내보낸다. 그래서 화면과 `Logic.kt`(정렬·필터·타이머)는 그대로다.

### 1-2. 앱이 구독하는 범위

| 구독 | 쿼리 | 비고 |
|---|---|---|
| `config/settings`, `config/staff` | 문서 | |
| `menu` | 전체 | |
| `tables` | 전체 | 앱은 `1 ≤ no ≤ rows×cols`만 표시. 🔶 문서가 없는 번호는 빈자리로 보여 준다(착석은 문서가 있어야 가능) |
| `waitings` | `status in [WAITING, CALLED, NO_SHOW]` | 일반 손님 |
| `vipWaitings` | `status in [WAITING, CALLED, NO_SHOW]` | VIP. 두 목록을 합쳐 **VIP 맨 위, 나머지는 등록순**(Logic.kt 그대로) |
| `orders` | `paymentStatus == "PAID"` **그리고** `createdAt >= 오늘 0시(한국 시간)` | 복합 색인 필요 (`firebase/firestore.indexes.json`) |

⚠️ 주문 구독의 "오늘"은 **앱을 켠 날의 0시** 기준이다. 자정을 넘겨 계속 켜 두면 그 전날 0시부터가 유지되어 문제없다. 하지만 **자정 이후에 앱을 새로 켜면 자정 전 주문이 테이블 상세에서 빠진다**. 행사가 자정을 넘기면 기준 시각을 새벽(예: 06시)으로 바꾸는 것을 검토한다(`FirestoreMapper.todayStartKst`).

### 1-3. 번호(대기번호·VIP 번호·주문번호)와 카운터

Firestore 에는 자동 증가 번호가 없다. 그래서 `counters/*` 문서의 `next`를 **트랜잭션 안에서 읽고 1 올린 뒤** 그 번호로 문서를 만든다.

- **앱과 서버가 서로 다른 컬렉션·카운터를 쓰므로 웨이팅 번호는 충돌하지 않는다.**
  - 일반 손님: `waitings` + `counters/waitings` → 친구 서버만 씀
  - VIP: `vipWaitings` + `counters/vip` → 앱만 씀. 화면에는 "V1, V2…"로 표시
  - 일반 3번과 VIP 3번이 동시에 있어도 컬렉션이 달라 섞이지 않는다(앱은 `key`로 구분).
- **주문번호(`counters/orders`)만 QR 주문 웹과 앱(1-5 직원 주문)이 공유한다.** 양쪽 모두 트랜잭션으로 +1 해야 번호가 겹치지 않는다(🔶 친구 확인 필요).
  - 보안 규칙이 "카운터를 +1 한 번호로만 생성"과 "기존 주문 덮어쓰기 금지"를 강제한다.

---

## 2. 누가 무엇을 쓰는가

| 데이터 | 관리자 앱 | 웨이팅 서버·웹 | 입금확인 서버 | QR 주문 웹 | 운영자(콘솔·스크립트) |
|---|---|---|---|---|---|
| `config/settings` rows/cols | ❌ | | | | ✅ 🔶 (배치 관리 주체) |
| `config/settings` 시간 3종 | ✅ (설정 화면) | | | | ✅ |
| `config/staff`, `menu/*` | 읽기만 | | | | ✅ |
| `tables/*` 문서 생성/삭제 | ❌ | | | | ✅ 🔶 (배치 변경 시) |
| `tables/*` 착석·연장·종료 | ✅ | | | | |
| `waitings/*` 생성 (일반) | **❌** | ✅ | | | |
| `waitings/*` 호출·무응답·복귀·취소·착석 | ✅ | 🔶 (손님 스스로 취소 허용 여부) | | | |
| `counters/waitings` | **❌ (읽기도 불가)** | ✅ | | | |
| `vipWaitings/*`, `counters/vip` | ✅ **앱 전용** | ❌ (읽지도 쓰지도 않음) | | | |
| `orders/*` 생성 (`source:"QR"`) | ❌ | | | ✅ | |
| `orders/*` 생성 (`source:"STAFF"`, `PENDING`) | ✅ (1-5 직원 주문) | | | | |
| `counters/orders` | ✅ (+1) | | | ✅ (+1) | |
| `orders.paymentStatus` / `paidAt` / `paidBy` | **❌ 절대 쓰지 않음** | | ✅ | | |
| `orders.cookStatus` / `cookedAt` / `cookedBy` | ✅ (조리완료) | | | | |
| 이용 종료 시 입금 전(`PENDING`) 주문 처리 | ❌ | | ✅ 🔶 | | |

- 앱은 이용 종료 때 **주문을 건드리지 않는다.** 입금 전 주문을 어떻게 할지는 서버가 정한다.
- 손님 조회 화면의 "내 앞 대기"는 서버가 계산한다. **서버는 VIP를 몰라도 된다.** `waitings`만 세면 v0.2의 "VIP 제외" 규칙과 결과가 같다.

---

## 3. 동시성 (트랜잭션)

**트랜잭션은 온라인일 때만 된다.** 오프라인이면 실패하고, 앱은
"인터넷 연결이 끊겨 착석을 저장하지 못했습니다. 연결되면 다시 눌러주세요" 같은 스낵바를 띄운다.
단순한 상태 변경은 **보안 규칙으로 허용된 전이만 통과**시키고, 오프라인 쓰기 큐(3-2)에 태운다.

### 3-1. 트랜잭션으로 처리 (온라인 필요)

| 작업 | 읽는 문서 | 조건 → 실패 시 안내 | 쓰는 문서 |
|---|---|---|---|
| **착석 (웨이팅 팀)** | 테이블, 웨이팅(VIP면 `vipWaitings`, 일반이면 `waitings`) | 테이블 `EMPTY` → "n번 테이블은 이미 이용 중입니다"<br>웨이팅 `WAITING/CALLED/NO_SHOW` → "이미 처리된 웨이팅입니다" | 테이블 `OCCUPIED`·`seatedAt`·`waitingId`·`waitingIsVip` 등 + 웨이팅 `SEATED`·`tableNo` |
| **착석 (현장 손님)** | 테이블 | 테이블 `EMPTY` | 테이블 |
| **이용 종료** | 테이블 | `OCCUPIED`이고 **화면에서 본 `seatedAt`과 같을 때만** → "새 손님이 앉았습니다. 화면을 확인하고…" | 테이블 초기화 |
| **VIP 등록** | `counters/vip` | 같은 번호가 대기 중이면 "이미 대기 중입니다"(화면 목록으로 확인, 원자적이지 않음) | 카운터 +1, `vipWaitings/{n}` |
| **직원 주문** | `counters/orders`, 테이블, 메뉴들 | 테이블 `OCCUPIED` → "착석 처리된 테이블만 주문할 수 있습니다"<br>품절 → "○○은(는) 품절입니다" | 카운터 +1, `orders/{n}` (`PENDING`) |

- **같은 테이블 동시 착석**: 두 스태프가 동시에 눌러도 Firestore 가 충돌을 감지해 뒤쪽 트랜잭션을 다시 실행한다. 그때 테이블이 `OCCUPIED`라서 **하나만 성공**한다.
- **같은 웨이팅 팀을 두 테이블에 동시 배정**: 웨이팅 문서 조건으로 막힌다.
- **종료 후 재착석과 겹치는 경우**: 늦게 갱신된 화면에서 [종료]를 눌러도 `seatedAt` 비교로 새 손님 테이블을 비우지 않는다.

### 3-2. 단순 업데이트 + 보안 규칙 (오프라인 쓰기 큐 사용)

| 작업 | 쓰는 필드 | 규칙이 허용하는 전이 |
|---|---|---|
| 호출 | `status=CALLED`, `calledAt` | `WAITING/CALLED → CALLED` |
| 무응답 | `status=NO_SHOW` | `WAITING/CALLED → NO_SHOW` |
| 대기 복귀 | `status=WAITING`, `calledAt=null` | `NO_SHOW → WAITING` (**createdAt 은 못 바꿈** = 원래 순서) |
| 웨이팅 취소 | `status=CANCELLED` | `WAITING/CALLED/NO_SHOW → CANCELLED` |
| 연장 | `extendedMinutes += 10/20/30` (`increment`) | 테이블이 `OCCUPIED`일 때만, 증가폭 10/20/30 |
| 조리완료 | `cookStatus=DONE`, `cookedAt`, `cookedBy` | `PAID`이고 `WAITING`일 때만 |
| 시간 설정 | 시간 3종 | 값 범위(30–240 / 5–60 / 1–10분) |

- **호출 기록 재시도 로직은 이 쓰기 큐로 대체했다.** 맨 위 팀을 탭하는 순간 LTE가 끊겨 있어도 화면에는 바로 "호출됨"이 되고, 연결되면 자동으로 전송된다.
- 서버에 도착했을 때 이미 다른 스태프가 그 팀을 착석시켰다면 규칙이 거절한다. 그러면 로컬 값이 되돌아가고 앱이 스낵바로 알린다.
- 큐에 쌓인 쓰기는 앱 프로세스가 살아 있는 동안 유지되고, 포그라운드 서비스가 프로세스를 붙잡아 둔다. 끊긴 동안에는 배너에 "전송 대기 n건"을 표시한다.

---

## 4. 시간 기준

- `seatedAt`, `calledAt`, `createdAt`, `paidAt`, `cookedAt`, `updatedAt`은 **모두 `serverTimestamp()`**로 쓴다. 보안 규칙이 `== request.time`으로 강제한다.
  - 읽을 때 `ServerTimestampBehavior.ESTIMATE`를 쓰므로, 방금 쓴(전송 전인) 착석도 바로 타이머가 돈다.
- **기기 시계 보정** (예전 `clockOffset` 대체):
  1. 연결될 때마다와 10분마다 `clocks/{내 uid}`에 `{ at: serverTimestamp() }`를 쓴다. 보낸 시각을 `t0`, 응답 시각을 `t1`로 기록한다.
  2. 서버에서 확정된 `at`을 읽어 `offset = at − (t0 + t1) / 2`로 계산한다. 오차는 대략 왕복 시간의 절반이다.
  3. `vm.now = 기기 시각 + offset`이라서 화면과 `Logic.kt`는 그대로다.

---

## 5. 연결 상태 표시와 오프라인 캐시

- **표시** (빨간 "연결 끊김" 배너) — 세 신호를 합친다.
  1. Android 네트워크 콜백: 검증된 인터넷이 없으면 **즉시** 끊김으로 본다.
  2. 리스너 메타데이터 `isFromCache`: 망은 있어도 Firebase 에 닿지 못하면(핫스팟은 켜져 있는데 LTE가 끊긴 경우) "연결 중"으로 본다.
  3. `hasPendingWrites`: 배너에 "전송 대기 n건"을 붙인다.
  - 1-O 화면에는 "Firebase 연결됨 ✓ / 연결 중… / 인터넷 연결 없음"을 보여 준다.
- **오프라인 캐시**: Firestore 로컬 디스크 캐시(기본값)를 쓴다.
  - 앱을 재시작해도 마지막 화면이 바로 뜨고, 재연결과 백오프는 SDK가 처리한다.
  - 30분 이내에 재연결하면 변경분만 다시 받는다(resume token).

### 5-1. 하루 읽기 횟수 계산 (무료 Spark 한도: 읽기 5만 / 쓰기 2만 / 일)

기준: **스태프 폰 5대, 테이블 30개, 웨이팅 150팀(+VIP 10), 주문 300건**, 영업 12시간.
리스너는 **바뀐 문서 1개당 폰마다 읽기 1회**가 든다.

| 항목 | 계산 | 읽기 |
|---|---|---|
| 테이블 변경 (착석 180 + 종료 180 + 연장 60) | 420 × 5대 | 2,100 |
| 웨이팅 변경 (등록·호출·착석/취소 등 팀당 약 3.5회) | (150×3.5 + 10×3) × 5대 | 2,775 |
| 주문 변경 (PAID 로 들어옴 + 조리완료) | 300 × 2 × 5대 | 3,000 |
| 트랜잭션 읽기 (착석 2건, 종료 1건, 직원 주문 3건 등) | 폰 1대만 | 약 700 |
| 시계 보정 (10분마다 + 재연결) | 약 80회 × 5대 | 400 |
| 앱 시작 / 30분 넘게 끊긴 뒤 재연결 (전체 다시 받기, 1회 최대 약 370건) | 하루 4회 × 5대 × 370 | 7,400 |
| **앱 합계** | | **약 16,400 / 50,000** |

→ **앱만 보면 무료 한도의 약 1/3이라 여유가 있다.** 쓰기도 하루 약 2,000건이라 한도(2만)에 한참 못 미친다.
다만 **친구 쪽 웹이 읽기를 크게 늘릴 수 있다**(7장 12번). 손님 150명이 조회 웹에서 `waitings` 전체를 실시간 구독하면
처음에만 150 × 150 ≈ 2만 건이 들고, 이후 변경마다 열려 있는 손님 수만큼 더 든다. 그러면 한도를 넘길 수 있다.

---

## 6. 인증과 보안 규칙

### 6-1. 인증: 스태프 공용 계정 (이메일/비밀번호)
- Firebase Authentication 에 **스태프 공용 계정 하나**를 둔다. (구버전 초안에는 고정 이메일이 있었으나, 현재 앱은 계정 이메일을 코드에 두지 않고 로그인 화면에서 입력받는다 — AGENTS.md 기준)
- 비밀번호는 운영자가 Firebase 콘솔에서 정한다. **코드와 git 에는 넣지 않는다.**
- 앱의 1-O 화면에서 **폰마다 처음 한 번만** 비밀번호를 입력하면 로그인이 유지된다. 그다음에 담당자 이름을 고른다(기존과 같음).
  설정 화면의 [로그아웃]을 누르면 다시 비밀번호를 입력해야 한다.
- 보안 규칙은 **"이 공용 계정(비밀번호 로그인)으로 로그인한 경우"만** 읽기와 쓰기를 허용한다. 로그인하지 않았거나, 익명 로그인이거나, 다른 계정이면 모두 거부한다.
- 비밀번호가 새면 콘솔에서 비밀번호를 바꾼다. 그러면 모든 폰에서 다시 입력해야 한다.

### 6-2. 규칙
- 규칙 전문: [`firebase/firestore.rules`](../firebase/firestore.rules). 에뮬레이터 테스트: `firebase/test/rules.test.mjs` (`cd firebase && npm run test:rules`).
- 요점:
  - 앱은 `orders`를 **`paymentStatus == PAID`인 것만 읽을 수 있다.** PENDING 주문 읽기는 거부된다.
  - 앱은 **`paymentStatus`를 어떤 경우에도 바꿀 수 없다.** 주문 생성은 `STAFF` + `PENDING`으로만, 착석한 테이블에만 할 수 있다.
  - 앱은 **`waitings`를 만들 수 없다.** `counters/waitings`는 읽을 수도 없다. `vipWaitings`는 앱 계정만 읽고 쓴다.
  - 웨이팅 상태는 허용된 전이만 된다. 이미 착석한 팀은 호출이나 무응답으로 되돌릴 수 없고, `createdAt`(순서)과 전화번호도 바꿀 수 없다.
  - 착석으로 `SEATED`가 되는 것은 **같은 트랜잭션에서 그 테이블이 이 웨이팅으로 `OCCUPIED`가 될 때만** 허용한다(`getAfter`).
  - 테이블은 착석, 연장(10/20/30분), 종료 전이만 된다. 테이블 문서 생성과 삭제(배치 변경)는 앱이 할 수 없다.
  - 설정은 시간 3종만, 범위 안에서만 바꿀 수 있다. 모든 시각은 서버 시각이어야 한다.
- 🔶 **친구의 서버들은 Admin SDK를 쓴다고 가정한다**(Admin SDK는 규칙을 우회한다). 웹이 브라우저에서 클라이언트 SDK로 직접 쓴다면 손님용 규칙을 따로 추가해야 한다.

---

## 7. 친구에게 확인할 것 (그대로 전달용)

1. **Firestore** 를 쓰는지. Realtime Database 라면 0장을 참고.
2. **컬렉션·필드 이름**을 1장대로 쓸지. 이미 정한 구조가 있으면 그 구조를 주면 앱을 맞춘다.
3. **일반 웨이팅은 `waitings` + `counters/waitings`**로 서버가 번호를 매겨 생성. **VIP는 앱이 `vipWaitings`에 따로 관리하므로 서버는 VIP를 몰라도 된다.**
   "내 앞 대기"는 `waitings`만 세면 VIP 제외 규칙과 같다(`WAITING`/`CALLED`이면서 나보다 `createdAt`이 이른 팀 수).
4. **주문번호 `counters/orders`는 QR 주문 웹과 앱이 공유**한다. 반드시 **트랜잭션으로 +1** 해서 번호가 겹치지 않게 한다.
5. **시각 필드는 Firestore `Timestamp` + `serverTimestamp()`**로 쓴다(epoch ms 숫자 아님).
6. **테이블 배치**: rows/cols를 바꿀 때 `tables/{no}` 문서를 누가 만들고 지우는지(각 문서에 `status:"EMPTY"` 등 초기 필드 필요). 줄일 때 이용 중인 테이블이 있으면 거절한다(v0.2 규칙).
7. **서버들이 Admin SDK를 쓰는지**, 손님용 웹이 브라우저에서 Firestore 에 직접 쓰는지(보안 규칙 범위가 달라짐).
8. **일반 웨이팅의 중복 전화번호 확인**(v0.2의 "이미 대기 중입니다")은 서버에서 한다.
9. **이용 종료 시 입금 전(`PENDING`) 주문 처리**를 누가 언제 하는지(예: 테이블이 `EMPTY`가 되면 입금확인 서버가 `CANCELLED` 처리). 앱은 주문을 건드리지 않는다.
10. **입금확인 서버가 `PAID`로 바꿀 때 `paidAt`(serverTimestamp)을 채워 줄 것.** 주방은 `paidAt` 순서로 정렬한다.
11. **`config/staff`와 `menu`**를 누가 어떻게 넣는지(콘솔 / 초기화 스크립트).
12. **손님 조회 웹의 읽기 비용**: `waitings` 전체를 실시간 구독하면 읽기가 손님 수만큼 늘어 무료 한도(5만/일)를 넘길 수 있다(5-1).
    **서버가 계산한 값만 주거나, 손님 본인 문서 1개만 구독**하기를 권한다.
13. **손님 전화번호 보관 기간** — 기본안: **행사 다음 날 전부 삭제**(`waitings`, `vipWaitings`의 `phone`, `tables`의 `phone`). 누가 지울지.
14. **요금제**: Spark(무료)로 충분할지. 리허설 때 콘솔에서 읽기 수를 함께 확인할지.

## 8. 결정된 사항 (사용자)
- 인증: **스태프 공용 계정(이메일/비밀번호)**, 폰마다 처음 한 번만 입력하고 설정 화면에서 로그아웃.
- VIP 는 앱 전용 컬렉션 `vipWaitings` + `counters/vip`. 화면 표시는 "V1, V2…".
- 앱의 주문 구독은 오늘(한국 시간 0시 이후) PAID 주문만.
- 오프라인 중 착석·종료·VIP 등록·직원 주문은 실패하고 스낵바로 알린다. 행사 당일에는 종이에 기록했다가 복구 후 입력한다(README 체크리스트).
- CLAUDE.md 7장의 기존 미결정 사항은 그대로(VIP 판별 기준, 전화번호 마스킹, 테이블별 정원). 부분 입금은 서버 담당.
