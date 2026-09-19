# 축제 주점 시스템 — 서버 API / WebSocket 명세 (v0.1)

> 관리자 앱(Android)과 웹 3종(웨이팅 등록 / 웨이팅 조회 / 테이블 QR 주문)이 공유하는 서버 명세.
> 서버는 폰 핫스팟에 연결된 **노트북**에서 실행한다. 인터넷 없음.

- Base URL: `http://<노트북 IP>:8080`
- 모든 요청/응답은 JSON (`Content-Type: application/json`)
- 시간 값은 전부 **epoch milliseconds (Long)**, 서버 시계 기준
- 에러 응답: HTTP 4xx + `{ "error": "사람이 읽을 수 있는 한국어 메시지" }`
  - 앱은 `error` 문자열을 그대로 토스트로 띄운다.

---

## 1. 핵심 규칙 (확정 사항)

| 규칙 | 내용 |
|---|---|
| 회전 시간 | 기본 100분. **착석 순간** `seatedAt` 기록 → 타이머 시작 |
| 임박/초과 | 종료 15분 전 임박, 시간 경과 시 초과. **서버는 계산하지 않음** — 앱이 `seatedAt + (rotationMinutes + extendedMinutes)` 로 계산 |
| 결제 | **주문마다 선결제.** 주문은 `PENDING`으로 생성 → 스태프가 입금확인 → `PAID` |
| 주방 | `PAID` 된 주문만 주방 화면에 나온다 |
| 주문 가능 조건 | 테이블이 `OCCUPIED`일 때만. 빈 테이블 QR 주문은 **409 거절** |
| VIP | 스태프가 앱에서 등록. 목록 최상단. 일반 손님의 "내 앞 대기"에서는 **VIP 제외** |
| 무응답 | 호출 후 3분 경과 시 앱에 [무응답] 버튼 노출, **스태프가 수동 처리**. 서버 타이머 없음 |
| 중복 웨이팅 | 같은 전화번호가 `WAITING`/`CALLED`/`NO_SHOW` 상태로 있으면 **409** |
| 테이블 번호 | 1..N 고정 (QR에 인쇄됨). 배치(rows×cols) 변경은 모양만 바꾸고, 줄이면 뒷번호가 사라짐 |
| 테이블 배치 관리 | **웹서버가 관리한다.** 관리자 앱은 스냅샷의 `settings.rows/cols`와 `tables`를 받아 **보여주기만** 하고 배치를 수정하지 않는다 |

---

## 2. 데이터 모델

```jsonc
// Settings
{
  "rows": 5,               // 세로 줄 수
  "cols": 6,               // 한 줄당 테이블 수
  "rotationMinutes": 100,
  "imminentMinutes": 15,
  "noShowMinutes": 3
}
// 테이블 개수 = rows * cols

// Table
{
  "no": 7,                     // 1..rows*cols
  "status": "EMPTY",           // "EMPTY" | "OCCUPIED"
  "seatedAt": null,            // Long | null  (OCCUPIED일 때 착석 시각)
  "extendedMinutes": 0,        // 연장 누적(분)
  "partySize": null,           // Int | null
  "phone": null,               // 착석 팀 전화번호 (워크인이면 null)
  "waitingId": null            // 연결된 웨이팅 id
}

// Waiting
{
  "id": 12,                    // 대기번호로도 사용 (등록 완료 화면에 노출)
  "phone": "01012345678",      // 숫자만
  "partySize": 3,
  "isVip": false,
  "status": "WAITING",         // "WAITING" | "CALLED" | "NO_SHOW" | "SEATED" | "CANCELLED"
  "createdAt": 1726700000000,
  "calledAt": null,            // 마지막 호출 시각
  "tableNo": null              // SEATED 시 배정된 테이블
}

// MenuItem  (행사 전 서버에 고정 데이터로 입력)
{ "id": 1, "name": "해물파전", "price": 15000, "category": "안주", "soldOut": false }

// Order
{
  "id": 31,
  "tableNo": 7,
  "items": [ { "menuId": 1, "name": "해물파전", "price": 15000, "qty": 2 } ], // name/price는 주문 시점 스냅샷
  "total": 30000,
  "source": "QR",              // "QR" | "STAFF"
  "paymentStatus": "PENDING",  // "PENDING" | "PAID" | "CANCELLED"
  "cookStatus": "WAITING",     // "WAITING" | "DONE"
  "createdAt": 1726700000000,
  "addedBy": null,             // STAFF 주문이면 스태프 이름
  "paidAt": null, "paidBy": null,
  "cookedAt": null, "cookedBy": null
}

// Snapshot  (전체 상태)
{
  "serverTime": 1726700000000,  // 앱이 기기 시계 오차 보정에 사용
  "settings": { ... },
  "tables": [ Table ... ],      // no 오름차순
  "waitings": [ Waiting ... ],  // SEATED/CANCELLED 포함 전부 (앱이 필터링)
  "orders": [ Order ... ],
  "menu": [ MenuItem ... ],
  "staff": [ "동현", "영준", "민지" ]   // 1-O 담당자 선택 목록 (서버 설정 파일)
}
```

---

## 3. WebSocket — 실시간 동기화

- URL: `ws://<노트북 IP>:8080/ws`
- 서버 → 클라이언트 메시지는 한 종류:

```json
{ "type": "snapshot", "data": { /* Snapshot */ } }
```

- **보내는 시점**
  1. 클라이언트가 연결된 직후 1회
  2. 상태가 바뀌는 모든 요청(POST/PUT)이 성공한 직후, **연결된 모든 클라이언트에게** 브로드캐스트
- 왜 전체 스냅샷인가: 테이블 30 + 웨이팅 수십 + 주문 수백 건이어도 수십 KB라 LAN에선 충분히 빠르고,
  "이벤트 누락 → 화면 어긋남" 버그가 원천적으로 없다. 재연결 시 재동기화도 자동으로 해결된다.
- 클라이언트 → 서버 메시지는 없음. (연결 유지는 WebSocket ping으로 충분)
- 웹(조회/주문)도 같은 소켓을 써도 되고, 폴링(3~5초)으로 GET 해도 된다.

---

## 4. REST API

### 4-1. 공통

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/state` | Snapshot 반환 (WS와 동일한 data) |
| GET | `/api/menu` | 메뉴 목록 (주문 웹용) |

### 4-2. 설정

| Method | Path | Body | 사용처 | 비고 |
|---|---|---|---|---|
| PUT | `/api/settings` | `Settings` (부분 가능) | 웹서버 측 (배치) / 관리자 앱 (시간 값) | 보낸 필드만 갱신 |

- **테이블 배치(`rows`, `cols`)는 웹서버 쪽에서 설정한다.** 방식은 웹 관리 화면이든 서버 설정 파일이든 친구가 정한다.
  - 바뀌면 WS 스냅샷으로 앱에 자동 반영된다.
  - rows*cols가 줄어드는데 사라질 번호에 `OCCUPIED` 테이블이 있으면 **409** `"n번 테이블이 이용 중이라 줄일 수 없습니다"`.
- 관리자 앱은 `rotationMinutes`, `imminentMinutes`, `noShowMinutes`만 보낸다.

### 4-3. 테이블 (관리자 앱)

모든 body에 `"staff": "영준"` 포함 (감사 로그용).

| Method | Path | Body | 동작 |
|---|---|---|---|
| POST | `/api/tables/{no}/seat` | `{ staff, waitingId?, partySize? }` | EMPTY → OCCUPIED, `seatedAt=now`, `extendedMinutes=0`. `waitingId`가 있으면 그 웨이팅을 `SEATED`, `tableNo` 기록, phone/partySize 복사. 이미 OCCUPIED면 409 |
| POST | `/api/tables/{no}/extend` | `{ staff, minutes }` | `extendedMinutes += minutes` (10/20/30) |
| POST | `/api/tables/{no}/release` | `{ staff }` | OCCUPIED → EMPTY, 필드 초기화. 해당 테이블의 `PENDING` 주문은 `CANCELLED` 처리 |

### 4-4. 웨이팅

| Method | Path | Body | 사용처 | 동작 |
|---|---|---|---|---|
| POST | `/api/waitings` | `{ phone, partySize, isVip?, staff? }` | 등록 웹 / 앱(VIP) | 생성, status=WAITING. 중복이면 **409** `"이미 대기 중입니다"`. 응답: 생성된 `Waiting` |
| GET | `/api/waitings/lookup?phone=010...` | – | 조회 웹 | 아래 응답. 없으면 **404** |
| POST | `/api/waitings/{id}/call` | `{ staff }` | 앱 | status=CALLED, `calledAt=now` (재호출 시 갱신) |
| POST | `/api/waitings/{id}/no-show` | `{ staff }` | 앱 | status=NO_SHOW |
| POST | `/api/waitings/{id}/restore` | `{ staff }` | 앱 | NO_SHOW → WAITING (**createdAt 유지** = 원래 순서로 복귀) |
| POST | `/api/waitings/{id}/cancel` | `{ staff }` | 앱 | status=CANCELLED |

`GET /api/waitings/lookup` 응답:

```json
{
  "id": 12,
  "status": "WAITING",
  "partySize": 3,
  "aheadCount": 4
}
```

- `aheadCount` = **VIP가 아닌** 팀 중 status가 `WAITING` 또는 `CALLED`이고 `createdAt`이 나보다 이른 팀 수
- VIP는 카운트하지 않는다 → VIP가 끼어들어도 숫자가 늘지 않고, 잠시 줄지 않을 뿐
- 본인이 `NO_SHOW`면 status 그대로 내려주고 웹에서 "호출에 응답하지 않아 대기가 보류되었습니다. 부스로 와주세요" 안내 권장

### 4-5. 주문

| Method | Path | Body | 사용처 | 동작 |
|---|---|---|---|---|
| POST | `/api/orders` | `{ tableNo, items:[{menuId, qty}], source, staff? }` | QR 웹(`source:"QR"`) / 앱(`"STAFF"`) | 테이블이 EMPTY면 **409** `"착석 처리된 테이블만 주문할 수 있습니다"`. 품절/없는 메뉴 400. PENDING으로 생성, 응답: `Order` |
| GET | `/api/orders?tableNo=7` | – | QR 웹 | 해당 테이블의 현재 착석(seatedAt 이후) 주문 목록 → 손님이 입금 상태 확인 |
| POST | `/api/orders/{id}/confirm-payment` | `{ staff }` | 앱 | PENDING → PAID, paidAt/paidBy. 이때부터 주방에 노출 |
| POST | `/api/orders/{id}/cancel` | `{ staff }` | 앱 | PENDING → CANCELLED (입금 전 오주문 정리용) |
| POST | `/api/orders/{id}/cooked` | `{ staff }` | 앱(주방) | cookStatus=DONE. 되돌리기 없음 |

---

## 5. QR 코드 (웹 담당)

- 테이블 QR: `http://<노트북 IP>:8080/order?table=7`
- 조회 QR: `http://<노트북 IP>:8080/status`
- 등록 태블릿: `http://<노트북 IP>:8080/register`
- 노트북 IP가 바뀌면 QR을 다시 뽑아야 하므로 **핫스팟에서 노트북 IP 고정**(DHCP 예약 또는 수동 IP) 권장.

---

## 6. 서버 구현 참고

- `mock-server/server.js`가 이 명세대로 동작하는 **테스트용 구현**이다(메모리 저장, 재시작 시 초기화). 실제 서버 구현 시 참고용.
- 실제 서버는 서버 재시작 대비로 상태를 파일(JSON/SQLite)에 저장해 두길 권장 → 재시작 후 클라이언트는 WS 재연결 시 스냅샷으로 자동 복구.
- CORS: 웹을 같은 서버에서 서빙하면 불필요. 다른 포트에서 개발한다면 허용 필요.
