# 축제 주점 시스템 — Firebase 데이터 설계 (초안 v0.3)

> **상태: 초안.** 친구가 정한 Firebase 구조가 아직 없어서, 현재 앱 모델(`android/.../data/Models.kt`)과
> `docs/API.md` v0.2(노트북 서버 방식)를 기준으로 제안한다. **"🔶 친구 확인 필요"** 표시는 확정이 아니다.
>
> - 앱과 모든 서버·웹이 **같은 Firebase 프로젝트**를 본다. 노트북 서버, REST, WebSocket 은 없어진다.
> - 핫스팟 폰의 모바일 데이터로 인터넷에 연결한다. **LTE 순단**에 강해야 한다.
> - 유지되는 규칙: 입금확인은 서버가 한다(앱은 `PAID` 주문만 씀). 테이블 배치는 서버 쪽이 관리하고 앱은 보여주기만 한다.
>   CLAUDE.md 3장의 나머지 업무 규칙과 앱의 화면·알림 동작은 그대로다.

---

## 0. Firestore 를 제안하는 이유

| | Firestore (제안) | Realtime Database |
|---|---|---|
| 여러 문서 원자적 쓰기 | 트랜잭션으로 **테이블 + 웨이팅**을 한 번에 | 다중 경로 업데이트는 되지만, 조건 확인까지 하려면 공통 부모 노드에 트랜잭션을 걸어야 함 |
| 보안 규칙 | 필드 단위 제한이 쉬움 (`diff().affectedKeys()`) | `.validate`를 필드마다 써야 함 |
| 오프라인 | 로컬 캐시 + 쓰기 큐 (기본 켜짐) | 같음 (`setPersistenceEnabled`) |
| 서버 시각 오프셋 | 직접 구해야 함 (4장) | `.info/serverTimeOffset` 기본 제공 |
| 연결 상태 | 직접 판단해야 함 (5장) | `.info/connected` 기본 제공 |
| 쿼리 | `status in [...]`, `paymentStatus == PAID` 같은 필터 | 한 필드로만 정렬·필터 |

**RTDB 라면 달라지는 점**
- 경로는 `/settings`, `/staff`, `/menu/{id}`, `/tables/{no}`, `/waitings/{id}`, `/orders/{id}`처럼 같은 모양으로 둔다.
- 시각은 `ServerValue.TIMESTAMP`(epoch ms Long)이라 모델 변환이 더 간단하다.
- 앱이 `PAID`만 읽게 하려면 규칙만으로는 어렵다. 그래서 `/paidOrders/{id}`처럼 **서버가 PAID 주문을 복사해 두는 경로**가 필요하다.
- 연결 상태와 시계 오프셋은 `.info` 경로로 바로 해결된다.

---

## 1. 데이터 구조 (Firestore 초안)

모든 시각은 **Firestore `Timestamp`**이고, 쓸 때는 `serverTimestamp()`로 기록한다(4장).
감사 로그용으로 앱이 쓰는 문서에는 `updatedBy`(담당자 이름)와 `updatedAt`(서버 시각)을 함께 남긴다.

```
config/settings        { rows, cols, rotationMinutes, imminentMinutes, noShowMinutes, updatedBy, updatedAt }
config/staff           { names: ["동현", "영준", "민지"] }
counters/waitings      { next: 13 }          // 다음 대기번호
counters/orders        { next: 32 }          // 다음 주문번호
menu/{menuId}          { id, name, price, category, soldOut }                     // 문서 ID = "1", "2" …
tables/{no}            { no, status, seatedAt, extendedMinutes, partySize, phone, waitingId,
                         updatedBy, updatedAt }                                    // 문서 ID = "1" … "N"
waitings/{id}          { id, phone, partySize, isVip, status, createdAt, calledAt, tableNo,
                         createdBy, updatedBy, updatedAt }                         // 문서 ID = 대기번호
orders/{id}            { id, tableNo, items:[{menuId,name,price,qty}], total, source,
                         paymentStatus, cookStatus, createdAt, addedBy,
                         paidAt, paidBy, cookedAt, cookedBy }                      // 문서 ID = 주문번호
clocks/{uid}           { at }                // 기기 시계 보정용 (4장), 기기마다 자기 문서만
```

### 1-1. 현재 모델과의 대응표

| Models.kt | Firestore | 변환 | 비고 |
|---|---|---|---|
| `Settings.*` | `config/settings` 같은 이름 | 그대로 (Int) | rows/cols 는 서버 쪽이 씀 |
| `Snapshot.staff` | `config/staff.names` | `List<String>` | |
| `Snapshot.menu` | `menu/*` | 그대로 | `id` 필드로 정렬 |
| `TableInfo.no` | `tables/{no}.no` | Int | 문서 ID도 같은 숫자 |
| `TableInfo.status` | `status` | `"EMPTY"`/`"OCCUPIED"` | |
| `TableInfo.seatedAt: Long?` | `seatedAt: Timestamp?` | Timestamp → epoch ms | |
| `TableInfo.extendedMinutes/partySize/phone/waitingId` | 같은 이름 | 그대로 | |
| `Waiting.id: Int` | `waitings/{id}.id` | Int | 🔶 숫자 대기번호 유지 (1-3) |
| `Waiting.createdAt/calledAt: Long` | `Timestamp` | → epoch ms | |
| `Waiting.phone/partySize/isVip/status/tableNo` | 같은 이름 | 그대로 | |
| `Order.id: Int` | `orders/{id}.id` | Int | 🔶 숫자 주문번호 유지 (1-3) |
| `Order.createdAt/paidAt/cookedAt: Long` | `Timestamp` | → epoch ms | |
| `Order.items/total/source/paymentStatus/cookStatus/addedBy/paidBy/cookedBy` | 같은 이름 | 그대로 | |
| `Snapshot.serverTime` | (없음) | — | 시계 보정은 `clocks/{uid}`로 대체 (4장) |

→ **`Models.kt`와 UI는 그대로 두고**, Firestore 문서를 이 모델로 바꾸는 변환 함수(`FirestoreMapper`)만 추가한다.
앱은 모든 리스너 결과를 합쳐 지금과 같은 `Snapshot`(StateFlow)으로 내보낸다.

### 1-2. 앱이 구독하는 범위

| 구독 | 쿼리 | 이유 |
|---|---|---|
| `config/settings`, `config/staff` | 문서 | |
| `menu` | 전체 | |
| `tables` | 전체 | 앱은 `1 ≤ no ≤ rows×cols`만 표시. 🔶 문서가 없는 번호는 빈자리로 보여 줄지 친구 확인 |
| `waitings` | `status in [WAITING, CALLED, NO_SHOW]` | 착석·취소된 웨이팅은 앱에서 쓰지 않음 |
| `orders` | `paymentStatus == "PAID"` | **앱은 PAID만** (v0.2 규칙). 보안 규칙도 PAID만 읽게 제한 (6장) |

### 1-3. 숫자 번호(대기번호, 주문번호)

Firestore 에는 자동 증가 번호가 없다. 대기번호는 손님에게 보여 주고 주문번호는 주방에서 `#31`로 쓰므로 숫자를 유지한다.
- **`counters/waitings`, `counters/orders`**를 트랜잭션 안에서 `next`를 읽고 1 올린 뒤, 그 번호로 문서를 만든다.
- 🔶 웨이팅 등록 웹과 QR 주문 웹(서버)도 **같은 카운터**를 써야 한다. 문자열 ID로 바꾸는 방안도 있다(앱 UI 수정 필요).

---

## 2. 누가 무엇을 쓰는가

| 데이터 | 관리자 앱 | 웨이팅 서버·웹 | 입금확인 서버 | QR 주문 웹 | 운영자(콘솔·스크립트) |
|---|---|---|---|---|---|
| `config/settings` rows/cols | ❌ | | | | ✅ 🔶 (배치 관리 주체) |
| `config/settings` 시간 3종 | ✅ (설정 화면) | | | | ✅ |
| `config/staff`, `menu/*` | ❌ 읽기만 | | | | ✅ |
| `tables/*` 문서 생성/삭제 | ❌ | | | | ✅ 🔶 (배치 변경 시) |
| `tables/*` 착석·연장·종료 | ✅ | | | | |
| `waitings/*` 생성 (일반) | ❌ | ✅ | | | |
| `waitings/*` 생성 (VIP) | ✅ | | | | |
| `waitings/*` 호출·무응답·복귀·취소·착석 | ✅ | 🔶 (손님 스스로 취소 허용 여부) | | | |
| `orders/*` 생성 (`source:"QR"`) | ❌ | | | ✅ | |
| `orders/*` 생성 (`source:"STAFF"`, `PENDING`) | ✅ (1-5 직원 주문) | | | | |
| `orders.paymentStatus` / `paidAt` / `paidBy` | **❌ 절대 쓰지 않음** | | ✅ | | |
| `orders.cookStatus` / `cookedAt` / `cookedBy` | ✅ (조리완료) | | | | |
| 이용 종료 시 입금 전(`PENDING`) 주문 처리 | ❌ | | ✅ 🔶 | | |
| `counters/*` | ✅ (VIP, 직원 주문) | ✅ | | ✅ | |

- 앱은 이용 종료 때 **주문을 건드리지 않는다.** 입금 전 주문을 어떻게 할지는 서버가 정한다(v0.2 그대로).
- 손님 조회 화면의 "내 앞 대기"(`aheadCount`, VIP 제외) 계산은 🔶 웨이팅 서버·웹이 한다.

---

## 3. 동시성 (트랜잭션)

**트랜잭션은 온라인일 때만 된다.** 오프라인이면 바로 실패하고, 앱은 "인터넷 연결을 확인하세요"라고 스낵바로 알린다.
단순한 상태 변경은 **보안 규칙으로 허용되는 전이만 통과**시키고, 오프라인 쓰기 큐(3-2)에 태운다.

### 3-1. 트랜잭션으로 처리 (온라인 필요)

| 작업 | 읽는 문서 | 조건 → 실패 시 안내 | 쓰는 문서 |
|---|---|---|---|
| **착석 (웨이팅 팀)** | 테이블, 웨이팅 | 테이블 `EMPTY` → "n번 테이블은 이미 이용 중입니다"<br>웨이팅 `WAITING/CALLED/NO_SHOW` → "이미 처리된 웨이팅입니다" | 테이블 `OCCUPIED`·`seatedAt`·`extendedMinutes=0`·`partySize`·`phone`·`waitingId` + 웨이팅 `SEATED`·`tableNo` |
| **착석 (현장 손님)** | 테이블 | 테이블 `EMPTY` | 테이블 |
| **이용 종료** | 테이블 | `OCCUPIED`이고 **화면에서 본 `seatedAt`과 같을 때만** → "이미 정리된 테이블입니다" | 테이블 초기화 |
| **VIP 등록** | `counters/waitings` | — | 카운터 +1, `waitings/{n}` 생성 |
| **직원 주문** | `counters/orders`, 테이블, 메뉴들 | 테이블 `OCCUPIED` → "착석 처리된 테이블만 주문할 수 있습니다"<br>품절 → "○○은(는) 품절입니다" | 카운터 +1, `orders/{n}` 생성 (`PENDING`) |

- **같은 테이블 동시 착석**: 두 스태프가 동시에 눌러도 Firestore 가 충돌을 감지해 뒤쪽 트랜잭션을 다시 실행한다. 그때 테이블이 `OCCUPIED`라서 **하나만 성공**한다.
- **같은 웨이팅 팀을 두 테이블에 동시 배정**: 웨이팅 문서 조건으로 막힌다.
- **종료 후 재착석과 겹치는 경우**: A가 7번을 종료하고 B가 새 손님을 앉힌 뒤, 화면이 늦게 갱신된 C가 [종료]를 누르는 경우다. `seatedAt` 비교가 없으면 새 손님 테이블이 종료되므로 이 비교가 필요하다.
- **중복 웨이팅(같은 번호)**: 클라이언트 트랜잭션 안에서는 쿼리를 쓸 수 없다. 그래서 VIP 등록은 **등록 직전 조회로 확인**하는데, 원자적이지 않다(VIP 등록은 드물어 허용). 🔶 일반 등록의 중복 확인은 웨이팅 서버 담당이다. 완전히 막으려면 `activePhones/{phone}` 잠금 문서가 필요하다.

### 3-2. 단순 업데이트 + 보안 규칙 (오프라인 쓰기 큐 사용)

| 작업 | 쓰는 필드 | 규칙이 허용하는 전이 |
|---|---|---|
| 호출 | `status=CALLED`, `calledAt`, `updatedBy/At` | `WAITING/CALLED → CALLED` |
| 무응답 | `status=NO_SHOW` | `WAITING/CALLED → NO_SHOW` |
| 대기 복귀 | `status=WAITING`, `calledAt=null` | `NO_SHOW → WAITING` (**createdAt 유지** = 원래 순서) |
| 웨이팅 취소 | `status=CANCELLED` | `WAITING/CALLED/NO_SHOW → CANCELLED` |
| 연장 | `extendedMinutes += 10/20/30` (`increment`) | 테이블이 `OCCUPIED`일 때만, 증가폭 10/20/30 |
| 조리완료 | `cookStatus=DONE`, `cookedAt`, `cookedBy` | `PAID`이고 `WAITING`일 때만 |
| 시간 설정 | 시간 3종 | 값 범위 확인 |

- **호출 기록 재시도 로직은 이 쓰기 큐로 대체한다.** 맨 위 팀을 탭하는 순간 LTE가 끊겨 있어도 로컬에 먼저 반영되고(화면에 바로 "호출됨"), 연결되면 자동으로 전송된다.
- 서버에 도착했을 때 이미 다른 스태프가 그 팀을 착석시켰다면, 규칙이 쓰기를 거절한다. 그러면 로컬 값이 되돌아가고 앱이 스낵바로 알린다.
- 큐에 쌓인 쓰기는 앱 프로세스가 살아 있는 동안 유지된다. 포그라운드 서비스가 프로세스를 붙잡아 둔다.

---

## 4. 시간 기준

- `seatedAt`, `calledAt`, `createdAt`, `paidAt`, `cookedAt`, `updatedAt`은 **모두 `serverTimestamp()`**로 쓴다. 기기 시계는 쓰지 않는다.
  - 보안 규칙에서 `== request.time`으로 강제한다(6장).
  - 읽을 때 `ServerTimestampBehavior.ESTIMATE`를 쓴다. 그래서 자기가 방금 쓴(아직 전송 전인) 착석도 바로 타이머가 돈다.
- **기기 시계 보정 (지금의 `clockOffset` 대체)**: Firestore 에는 서버 시각 조회 API가 없다. 그래서 다음 방법을 쓴다.
  1. 연결될 때마다(그리고 10분마다) `clocks/{내 uid}`에 `{ at: serverTimestamp() }`를 쓴다. 보낸 시각을 `t0`로 기록한다.
  2. 그 문서가 서버에서 확정되어(`hasPendingWrites == false`) 돌아온 시각을 `t1`이라 한다.
     `offset = at − (t0 + t1) / 2`로 계산한다. 오차는 대략 왕복 시간의 절반이라 1초 단위 타이머에는 충분하다.
  3. `vm.now = System.currentTimeMillis() + offset`은 지금과 같다. 화면과 `Logic.kt`는 바뀌지 않는다.
  - RTDB 를 같이 쓰면 `.info/serverTimeOffset` 한 줄로 대체할 수 있다(대신 제품이 하나 늘어남).

---

## 5. 연결 상태 표시와 오프라인 캐시

- **표시 (지금의 빨간 "연결 끊김" 배너 대체)** — 세 신호를 합친다.
  1. Android `ConnectivityManager` 네트워크 콜백: 인터넷이 없으면 **즉시** 배너를 띄운다.
  2. 리스너 메타데이터 `isFromCache == true`: 망은 있어도 Firebase 에 닿지 못하면 배너를 띄운다.
  3. `hasPendingWrites`: 아직 전송되지 않은 쓰기가 있으면 "전송 대기 중" 표시를 붙인다.
  - 배너 문구 예: "인터넷 끊김 — 화면 정보가 최신이 아닐 수 있음 (전송 대기 2건)".
  - 1-O 화면에는 "Firebase 연결됨 ✓ / 연결 중…"을 보여 준다.
- **오프라인 캐시**: Firestore 로컬 캐시(기본값, 디스크)를 켜 둔다.
  - 앱을 재시작해도 마지막 화면이 바로 뜨고, 망이 돌아오면 자동으로 따라잡는다.
  - 재연결과 백오프는 SDK가 처리하므로 지금의 재연결 루프는 없어진다.
  - 30분 이내에 재연결하면 변경분만 다시 받는다(resume token).
- **읽기 비용** 🔶: 무료(Spark) 요금제는 **하루 읽기 5만 건**이다. 대략 스태프 폰 5대 × (처음 수백 건 + 변경마다 1건)이라 넉넉할 것으로 보이지만, 행사 전 리허설에서 사용량을 확인한다.

---

## 6. 인증과 보안 규칙

### 6-1. 인증
- **최소안 (요청하신 방식): 익명 로그인.** 앱이 켜지면 `signInAnonymously()`를 하고, 담당자 이름은 지금처럼 고른다.
- ⚠️ **위험**: `google-services.json`의 API 키는 APK 안에 들어 있어 누구나 꺼낼 수 있다. 익명 로그인만 쓰면 **APK를 가진 사람은 누구나 손님 전화번호를 읽을 수 있다.** 행사 뒤에 데이터를 지우더라도 아래 강화안 중 하나를 권한다(사용자 결정 필요).
  - **강화안 A — 기기 등록**: 1-O 화면에 "기기 코드 ABCD"(uid 앞자리)를 보여 준다. 운영자가 콘솔에서 `staffDevices/{uid}` 문서를 만들어 준 기기만 읽고 쓸 수 있다.
  - **강화안 B — 공용 비밀번호**: 스태프 공용 이메일/비밀번호 계정 하나를 두고, 폰마다 한 번만 입력하면 저장된다.
- App Check(Play Integrity)는 스토어 밖에서 설치한 APK에는 맞지 않아서 제외한다.

### 6-2. 규칙 초안 (Firestore)

```
rules_version = '2';
service cloud.firestore {
  match /databases/{db}/documents {
    function signedIn() { return request.auth != null; }
    // 강화안 A 를 쓰면: return signedIn() && exists(/databases/$(db)/documents/staffDevices/$(request.auth.uid));
    function staff() { return signedIn(); }
    function changed() { return request.resource.data.diff(resource.data).affectedKeys(); }
    function only(keys) { return changed().hasOnly(keys); }
    function now(v) { return v == request.time; }
    function tableDoc(no) { return /databases/$(db)/documents/tables/$(string(no)); }

    match /config/staff    { allow read: if signedIn(); }
    match /config/settings {
      allow read: if staff();
      allow update: if staff()
        && only(['rotationMinutes', 'imminentMinutes', 'noShowMinutes', 'updatedBy', 'updatedAt'])
        && request.resource.data.rotationMinutes is int && request.resource.data.rotationMinutes >= 30
        && request.resource.data.imminentMinutes is int && request.resource.data.noShowMinutes is int
        && now(request.resource.data.updatedAt);
    }
    match /menu/{id} { allow read: if staff(); }

    match /tables/{no} {
      allow read: if staff();
      allow update: if staff() && now(request.resource.data.updatedAt) && (
        // 착석: EMPTY → OCCUPIED, seatedAt 은 서버 시각
        (resource.data.status == 'EMPTY' && request.resource.data.status == 'OCCUPIED'
          && now(request.resource.data.seatedAt) && request.resource.data.extendedMinutes == 0
          && only(['status', 'seatedAt', 'extendedMinutes', 'partySize', 'phone', 'waitingId', 'updatedBy', 'updatedAt']))
        // 연장: 10/20/30 분만
        || (resource.data.status == 'OCCUPIED' && request.resource.data.status == 'OCCUPIED'
          && only(['extendedMinutes', 'updatedBy', 'updatedAt'])
          && (request.resource.data.extendedMinutes - resource.data.extendedMinutes) in [10, 20, 30])
        // 종료: OCCUPIED → EMPTY, 필드 초기화
        || (resource.data.status == 'OCCUPIED' && request.resource.data.status == 'EMPTY'
          && request.resource.data.seatedAt == null && request.resource.data.waitingId == null
          && request.resource.data.extendedMinutes == 0
          && only(['status', 'seatedAt', 'extendedMinutes', 'partySize', 'phone', 'waitingId', 'updatedBy', 'updatedAt']))
      );
    }

    match /waitings/{id} {
      allow read: if staff();
      // VIP 등록만 (일반 등록은 웨이팅 서버)
      allow create: if staff() && request.resource.data.isVip == true
        && request.resource.data.status == 'WAITING' && now(request.resource.data.createdAt)
        && request.resource.data.id == int(id);
      allow update: if staff() && only(['status', 'calledAt', 'tableNo', 'updatedBy', 'updatedAt'])
        && now(request.resource.data.updatedAt) && (
          (request.resource.data.status == 'CALLED' && resource.data.status in ['WAITING', 'CALLED']
            && now(request.resource.data.calledAt))
          || (request.resource.data.status == 'NO_SHOW' && resource.data.status in ['WAITING', 'CALLED'])
          || (request.resource.data.status == 'WAITING' && resource.data.status == 'NO_SHOW'
            && request.resource.data.calledAt == null)
          || (request.resource.data.status == 'CANCELLED' && resource.data.status in ['WAITING', 'CALLED', 'NO_SHOW'])
          // 착석: 같은 트랜잭션에서 그 테이블이 이 웨이팅으로 OCCUPIED 가 되는 경우만
          || (request.resource.data.status == 'SEATED' && resource.data.status in ['WAITING', 'CALLED', 'NO_SHOW']
            && getAfter(tableDoc(request.resource.data.tableNo)).data.waitingId == resource.data.id)
        );
    }

    match /orders/{id} {
      // 앱은 PAID 만 읽는다 (쿼리에도 paymentStatus == 'PAID' 조건이 있어야 통과)
      allow read: if staff() && resource.data.paymentStatus == 'PAID';
      // 직원 주문: PENDING 으로만 생성, 착석한 테이블만
      allow create: if staff() && request.resource.data.source == 'STAFF'
        && request.resource.data.paymentStatus == 'PENDING' && request.resource.data.cookStatus == 'WAITING'
        && now(request.resource.data.createdAt) && request.resource.data.id == int(id)
        && get(tableDoc(request.resource.data.tableNo)).data.status == 'OCCUPIED';
      // 조리완료만. paymentStatus 는 절대 못 바꿈
      allow update: if staff() && resource.data.paymentStatus == 'PAID' && resource.data.cookStatus == 'WAITING'
        && only(['cookStatus', 'cookedAt', 'cookedBy']) && request.resource.data.cookStatus == 'DONE'
        && now(request.resource.data.cookedAt);
    }

    match /counters/{name} {
      allow read: if staff();
      allow update: if staff() && name in ['waitings', 'orders'] && only(['next'])
        && request.resource.data.next == resource.data.next + 1;
    }

    match /clocks/{uid} { allow read, write: if request.auth != null && request.auth.uid == uid; }
  }
}
```

- 🔶 **서버들은 Admin SDK를 쓴다고 가정한다**(Admin SDK는 규칙을 우회한다). 웨이팅 웹이나 QR 주문 웹이 브라우저에서 클라이언트 SDK로 직접 쓴다면, 손님용 규칙(생성만 허용, 필드 제한, 조회 제한)을 따로 설계해야 한다.
- 직원 주문의 금액(`total`, `items.price`)은 규칙으로 메뉴 가격과 대조하기 어렵다. 입금확인 서버가 금액을 대조하므로 실질적인 위험은 작다.

---

## 7. 친구에게 확인할 것 (그대로 전달용)

1. **Firestore 로 갈지, Realtime Database 로 갈지.** 제안은 Firestore(0장 비교표).
2. **컬렉션·필드 이름**을 1장 초안대로 쓸지. 이미 정한 구조가 있으면 그 구조를 주면 앱을 맞춘다.
3. **대기번호와 주문번호를 숫자로 유지할지**(`counters/*` 트랜잭션, 1-3). 유지하면 웨이팅 웹과 QR 주문 웹도 같은 카운터를 써야 한다.
4. **시각 필드는 Firestore `Timestamp` + `serverTimestamp()`**로 써 줄 수 있는지(epoch ms 숫자 아님).
5. **테이블 배치 관리**: rows/cols를 바꿀 때 `tables/{no}` 문서를 누가 만들고 지우는지. 줄일 때 이용 중인 테이블이 있으면 거절하는 규칙(v0.2)도 그쪽에서 지켜야 한다. 문서가 없는 번호를 앱이 빈자리로 보여 줘도 되는지.
6. **서버들이 Admin SDK를 쓰는지**, 손님용 웹이 브라우저에서 Firestore 에 직접 쓰는지(보안 규칙 범위가 달라짐).
7. **일반 웨이팅의 중복 전화번호 확인**(v0.2의 409)과 **"내 앞 대기"(VIP 제외) 계산**을 어디서 하는지.
8. **이용 종료 시 입금 전(`PENDING`) 주문 처리**를 누가 언제 하는지(예: 테이블이 `EMPTY`가 되면 입금확인 서버가 `CANCELLED` 처리).
9. **입금확인 서버가 `PAID`로 바꿀 때** `paidAt`(serverTimestamp)을 채워 줄 것. 주방은 `paidAt` 순서로 정렬한다.
10. **`config/staff`와 `menu`**를 누가 어떻게 넣는지(콘솔 / 초기화 스크립트).
11. **손님 개인정보**: 전화번호가 클라우드에 저장된다. 행사 뒤 삭제 시점과 담당자.
12. **요금제와 사용량**: Spark(무료)로 충분할지, 리허설 때 읽기 수를 함께 확인할지.

## 8. 사용자(나)가 정할 것
- **인증 방식**: 익명만 쓸지, 강화안 A(기기 등록)나 B(공용 비밀번호)를 쓸지(6-1).
- CLAUDE.md 7장의 기존 미결정 사항은 그대로 유지(VIP 판별 기준, 전화번호 마스킹, 테이블별 정원).
  부분 입금은 서버 담당.
