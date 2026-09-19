# 축제 주점 관리 앱 — 안드로이드(Kotlin) 개발 스펙 (v2, 최종)

이 문서는 축제 주점(야외 부스) 운영을 위한 관리자용 안드로이드 앱을 만들기 위한 스펙입니다. 데이터베이스는 이미 구축되어 있고(Firebase Firestore), 이 앱은 그 Firestore를 직접 읽고 쓰면 됩니다. 별도 백엔드 서버나 REST API는 없습니다 — Firebase Android SDK로 Firestore에 바로 접근하는 구조입니다.

이전에 전달한 스펙(v1)과 다른 점: **테이블 이용시간 100분 확정, 타이머는 착석 시점에 시작, `waiting.status`에 `NO_SHOW` 추가, 스태프 로그인(Firebase Auth) 도입.** 모두 아래 본문에 반영되어 있습니다.

## 0\. 앱 개요

주점에는 테이블 30개가 있고, 손님은 별도의 웹페이지(태블릿)로 웨이팅 등록을 합니다. 이 앱은 그 웨이팅/테이블/주문을 관리하는 스태프용 앱입니다.

화면 구성 (기획 문서 화면 ID 기준):

| 화면 ID | 이름 | 설명 |
| :---- | :---- | :---- |
| 1-O | 담당자 선택 | (Firebase Auth 로그인 화면으로 대체됨 — 아래 1번 참고) |
| 1-1 | 테이블 현황 | 테이블 30개를 한눈에 보는 대시보드 (빈 테이블/착석/이용중 상태, 남은 시간) |
| 1-2 | 테이블 상세 | 착석 처리, 입금확인, 시간 연장(+10/20/30분), 이용종료 |
| 1-3 | 웨이팅 목록 | 대기 중인 손님 목록 (VIP 우선, 그다음 등록 시간순) |
| 1-4 | 웨이팅 상세 | 전화 호출 기록, 무응답 처리, 웨이팅 취소 |
| 1-5 | 주문 입력 | 테이블 번호 \+ 메뉴 \+ 수량 입력 |
| 1-6 | 주문 현황(주방용) | 아직 조리 안 된 주문을 실시간으로 보여주는 화면 |

테이블 타이머는 서버가 카운트다운을 관리하는 게 아니라, `start_time`(타임스탬프)만 저장해두고 **클라이언트가 매초 `현재시각 - start_time`으로 직접 계산**하는 방식입니다.

- **이용 가능 시간: 100분.** 상수로 분리해서 나중에 값만 바꾸면 되게 해두세요.  
- **타이머 시작 시점: 착석 시점.** 손님이 자리에 앉는 순간(착석 처리) `start_time`을 기록합니다. 입금확인은 결제 여부만 표시하는 용도로 별도 관리되고, 타이머 시작에는 영향을 주지 않습니다. 결제가 늦어져도 자리 회전 시간은 지켜야 한다는 목적입니다.

결제(입금 확인) 흐름 자체는 별도의 웹페이지 \+ 서버로 처리할 예정입니다. 이 앱/이 문서의 스키마 범위 밖이니, `orders`나 `tables`에 결제 관련 필드를 추가하실 필요 없습니다.

## 1\. Firebase 프로젝트 연동

- Firebase 프로젝트 ID: `mobokfestivalpub`  
- Android 앱 등록 완료 (패키지 `com.festivalpub.admin`), `google-services.json`은 별도로 전달됩니다.  
  - 안드로이드 스튜디오 프로젝트의 `app/` 폴더에 넣고, 프로젝트 수준 `build.gradle`에 `com.google.gms:google-services` 플러그인 추가, 앱 수준 `build.gradle`에 플러그인 적용 \+ `implementation("com.google.firebase:firebase-firestore-ktx")` \+ `implementation("com.google.firebase:firebase-auth-ktx")` 추가  
  - `Manifest`에 인터넷 권한 필요 (`<uses-permission android:name="android.permission.INTERNET" />`)

### 로그인 (Firebase Authentication)

스태프 공용 계정으로 로그인해야 앱을 쓸 수 있습니다. 이메일/비밀번호는 별도로 전달됩니다.

FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)  
로그인에 성공한 뒤에야 아래 Firestore 컬렉션들을 읽고 쓸 수 있습니다 (보안 규칙으로 강제되어 있음). 로그인 실패/세션 만료 시 다시 로그인 화면으로 보내는 처리만 있으면 됩니다.

## 2\. Firestore 데이터 구조

### `tables/{id}` — 문서ID는 "1" \~ "30" (문자열)

| 필드 | 타입 | 설명 |
| :---- | :---- | :---- |
| table\_no | Long | 정렬용 숫자 (문서ID가 문자열이라 "10"이 "2"보다 앞에 오는 문제를 피하려고 별도 필드로 둠) |
| status | String | `EMPTY` | `SEATED_PENDING_PAYMENT` | `IN_USE` |
| start\_time | Long? | **착석 시각** (epoch millis). 타이머 계산 기준. 빈 테이블이면 null |
| payment\_confirmed | Boolean |  |
| extended\_minutes | Long | 연장된 시간 누적 (분) |
| total\_amount | Long | 주문 합계 금액 |

**동작:**

- 테이블 현황(1-1): `tables` 컬렉션 전체를 실시간 리스너로 구독, `table_no` 기준 정렬해서 30개 표시  
- 착석 처리(1-2): `status: "SEATED_PENDING_PAYMENT", start_time: 현재시각(millis)` 업데이트 (이 순간 타이머 시작) \+ 배정한 waiting 문서의 `status`를 `"SEATED"`로 업데이트. **두 쓰기는 트랜잭션으로 묶어서 처리** (두 스태프가 같은 테이블에 동시에 앉히는 것 방지)  
- 입금확인(1-2): `payment_confirmed: true, status: "IN_USE"` 업데이트. **`start_time`은 건드리지 않음**  
- 시간 연장(1-2): `extended_minutes`를 `FieldValue.increment(minutes)`로 업데이트 (10/20/30분 버튼)  
- 이용종료(1-2): `status: "EMPTY", start_time: null, payment_confirmed: false, extended_minutes: 0, total_amount: 0`으로 초기화  
- 남은시간 계산 (클라이언트): `총이용가능분 = 100 + extended_minutes`, `남은시간 = 총이용가능분*60*1000 - (현재시각 - start_time)`. 매초 갱신.

### `waiting/{autoId}` — 문서ID는 Firestore 자동 생성

| 필드 | 타입 | 설명 |
| :---- | :---- | :---- |
| phone | String |  |
| party\_size | Long |  |
| is\_vip | Boolean | 손님용 등록 페이지에서는 항상 false로만 생성 가능 (보안규칙으로 막혀있음). VIP는 이 앱에서만 등록 |
| status | String | `WAITING` | `SEATED` | `CANCELLED` | `NO_SHOW` |
| called\_at | Long? | 전화 호출한 시각. null이면 아직 호출 안 한 것 |
| created\_at | Long | 등록 시각 (epoch millis), 정렬 기준. `NO_SHOW`로 바뀌어도 이 값은 유지 (다시 `WAITING`으로 되돌리면 원래 순번 그대로 복귀) |

**동작:**

- 웨이팅 목록(1-3): `where(status in ["WAITING", "NO_SHOW"])`, `orderBy(is_vip, DESCENDING)`, `orderBy(created_at, ASCENDING)` 실시간 리스너 — VIP 먼저, 그다음 먼저 등록한 순. `NO_SHOW`는 화면에서 흐리게 표시하고 목록 아래쪽으로 정렬하는 건 앱에서 자유롭게 처리  
- 전화 호출(1-4): `called_at: 현재시각` 업데이트  
- 무응답 처리(1-4): `status: "NO_SHOW"` 업데이트 (제안하신 대로 호출 후 3분 기준으로 처리하면 됩니다)  
- 무응답 복귀(1-4): `status: "WAITING"` 업데이트  
- 취소(1-4): `status: "CANCELLED"` 업데이트  
- VIP 손님 등록: 이 앱에서 새 `waiting` 문서를 직접 생성하면서 `is_vip: true` 설정 (일반 손님은 웹페이지에서 등록하고 항상 `is_vip: false`)

손님용 웹페이지(등록/조회)도 이미 `NO_SHOW`를 인식하도록 반영되어 있습니다 (중복 등록 체크, 조회 화면 안내문구).

### `menu/{id}` — 문서ID는 "1"\~"4" (필요시 추가 가능)

| 필드 | 타입 |
| :---- | :---- |
| name | String |
| price | Long |

주문 입력 화면에서 이 컬렉션을 읽어서 메뉴 목록을 보여주면 됩니다. (실시간 리스너 없어도 무방, 자주 안 바뀜)

### `orders/{autoId}` — 문서ID는 Firestore 자동 생성

| 필드 | 타입 | 설명 |
| :---- | :---- | :---- |
| table\_id | Long |  |
| menu\_id | String |  |
| menu\_name | String | 주문 시점의 메뉴 이름 (스냅샷) |
| menu\_price | Long | 주문 시점의 가격 (스냅샷) |
| quantity | Long |  |
| added\_by | String | 주문 입력한 스태프 이름/식별자 |
| status | String | `PENDING` | `DONE` |
| created\_at | Long |  |

**동작:**

- 주문 입력(1-5): `orders`에 새 문서 추가 **\+ 동시에** 해당 `tables/{table_id}` 문서의 `total_amount`를 `FieldValue.increment(menu_price * quantity)`로 갱신. **`WriteBatch`로 묶어서 처리**  
- 주방 화면(1-6): `where(status == "PENDING")`, `orderBy(created_at, ASCENDING)` 실시간 리스너  
- 조리완료(1-6): 해당 주문 문서 `status: "DONE"` 업데이트

## 3\. 보안 규칙 (참고용 — 이미 배포되어 있음)

| 컬렉션 | 손님(비로그인) | 스태프(로그인) |
| :---- | :---- | :---- |
| tables | 접근 불가 | 읽기/쓰기 가능 |
| menu | 읽기만 가능 | 읽기/쓰기 가능 |
| orders | 접근 불가 | 읽기/쓰기 가능 |
| waiting | 생성(is\_vip=false만)·읽기 가능 | 읽기/생성/수정/삭제 모두 가능 |

앱에서 로그인 안 하고 Firestore 호출하면 `tables`/`orders`는 권한 오류(PERMISSION\_DENIED)가 납니다. 앱 시작 시 로그인 상태를 먼저 확인하고 진행하세요.

## 4\. 주의할 점

- 모든 목록형 화면(1-1, 1-3, 1-6)은 \*\*실시간 리스너(`addSnapshotListener`)\*\*로 구현하세요. 폴링 방식이 아닙니다.  
- `waiting`과 `orders`에 필요한 복합 색인(composite index)은 이미 등록되어 있습니다. 위 쿼리 조합대로 쓰면 문제 없지만, 혹시 "이 쿼리는 색인이 필요합니다" 에러가 뜨면 에러 메시지의 링크를 눌러서 색인을 만들면 됩니다 (1\~2분 소요, 알려주시면 같이 확인할게요).  
- `tables/{id}`의 id는 문자열입니다 (`"1"`, `"2"`, ..., `"30"`). 정수로 변환해서 쓰지 마세요.  
- `tables` 문서 1\~30번은 이미 만들어져 있습니다. 앱에서 문서를 새로 만들 필요 없습니다.

## 5\. 아직 미정인 것 (참고, 확정되면 반영)

- VIP 등록 기준  
- 전화 무응답 판단 시간 (3분으로 진행 중, 확정 원하면 알려주세요)  
- 테이블별 정원 차이  
- 주문 수정/취소 흐름  
- 테이블 배치(rows/cols): 별도 `config/layout` 문서로 내려줄지, 앱에서 6열 고정으로 표시할지 — 급하지 않으면 6열 고정으로 진행해도 무방