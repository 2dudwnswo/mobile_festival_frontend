// Firestore 보안 규칙 테스트 (에뮬레이터에서 실행)
//   cd firebase && npm run test:rules
import { after, before, beforeEach, describe, test } from 'node:test';
import { readFileSync } from 'node:fs';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collection, doc, getDoc, getDocs, increment, query, runTransaction,
  serverTimestamp, setDoc, Timestamp, updateDoc, where,
} from 'firebase/firestore';

const STAFF_EMAIL = 'staff@festival-pub.local';
let env;

// 로그인 상태별 Firestore
const staffDb = () =>
  env.authenticatedContext('staff-phone-1', { email: STAFF_EMAIL, firebase: { sign_in_provider: 'password' } }).firestore();
const anonDb = () =>
  env.authenticatedContext('anon-1', { firebase: { sign_in_provider: 'anonymous' } }).firestore();
const otherAccountDb = () =>
  env.authenticatedContext('other-1', { email: 'someone@else.com', firebase: { sign_in_provider: 'password' } }).firestore();
const noAuthDb = () => env.unauthenticatedContext().firestore();

const ts = Timestamp.fromMillis(1_789_700_000_000);

async function seed() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'config/settings'), { rows: 5, cols: 6, rotationMinutes: 100, imminentMinutes: 15, noShowMinutes: 3 });
    await setDoc(doc(db, 'config/staff'), { names: ['동현', '영준'] });
    await setDoc(doc(db, 'menu/1'), { id: 1, name: '해물파전', price: 15000, category: '안주', soldOut: false });
    await setDoc(doc(db, 'counters/vip'), { next: 2 }); // vipWaitings/1 이 이미 있음
    await setDoc(doc(db, 'counters/orders'), { next: 10 });
    await setDoc(doc(db, 'counters/waitings'), { next: 5 });
    const empty = { status: 'EMPTY', seatedAt: null, extendedMinutes: 0, partySize: null, phone: null, waitingId: null, waitingIsVip: false };
    await setDoc(doc(db, 'tables/1'), { no: 1, ...empty });
    await setDoc(doc(db, 'tables/2'), { no: 2, ...empty });
    await setDoc(doc(db, 'tables/7'), { no: 7, ...empty, status: 'OCCUPIED', seatedAt: ts, partySize: 3 });
    const w = { phone: '01000000001', partySize: 2, isVip: false, status: 'WAITING', createdAt: ts, calledAt: null, tableNo: null };
    await setDoc(doc(db, 'waitings/1'), { id: 1, ...w });
    await setDoc(doc(db, 'waitings/2'), { id: 2, ...w, phone: '01000000002', status: 'NO_SHOW' });
    await setDoc(doc(db, 'waitings/3'), { id: 3, ...w, phone: '01000000003', status: 'SEATED', tableNo: 7 });
    await setDoc(doc(db, 'vipWaitings/1'), { id: 1, ...w, phone: '01000000009', isVip: true });
    const o = { tableNo: 7, items: [{ menuId: 1, name: '해물파전', price: 15000, qty: 1 }], total: 15000, source: 'QR',
      cookStatus: 'WAITING', createdAt: ts, addedBy: null, paidAt: null, paidBy: null, cookedAt: null, cookedBy: null };
    await setDoc(doc(db, 'orders/1'), { id: 1, ...o, paymentStatus: 'PENDING' });
    await setDoc(doc(db, 'orders/2'), { id: 2, ...o, paymentStatus: 'PAID', paidAt: ts, paidBy: '서버' });
  });
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-festival-pub',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
beforeEach(async () => {
  await env.clearFirestore();
  await seed();
});
after(async () => { await env?.cleanup(); });

const stamp = (by = '영준') => ({ updatedBy: by, updatedAt: serverTimestamp() });

// ======================================================================
describe('로그인하지 않았거나 공용 계정이 아니면 전부 거부', () => {
  for (const [name, mk] of [['로그인 안 함', noAuthDb], ['익명 로그인', anonDb], ['다른 계정', otherAccountDb]]) {
    test(`${name}: 읽기 전부 거부`, async () => {
      const db = mk();
      for (const path of ['config/settings', 'config/staff', 'menu/1', 'tables/1', 'waitings/1', 'vipWaitings/1', 'orders/2', 'counters/vip']) {
        await assertFails(getDoc(doc(db, path)));
      }
      await assertFails(getDocs(query(collection(db, 'orders'), where('paymentStatus', '==', 'PAID'))));
    });
    test(`${name}: 쓰기 전부 거부`, async () => {
      const db = mk();
      await assertFails(updateDoc(doc(db, 'tables/1'), { status: 'OCCUPIED', seatedAt: serverTimestamp(), extendedMinutes: 0, ...stamp() }));
      await assertFails(updateDoc(doc(db, 'waitings/1'), { status: 'CALLED', calledAt: serverTimestamp(), ...stamp() }));
      await assertFails(updateDoc(doc(db, 'orders/2'), { cookStatus: 'DONE', cookedAt: serverTimestamp(), cookedBy: 'x' }));
      await assertFails(updateDoc(doc(db, 'config/settings'), { rotationMinutes: 90, ...stamp() }));
    });
  }
});

// ======================================================================
describe('주문: 앱은 PAID 만 읽고, paymentStatus 는 절대 못 바꾼다', () => {
  test('PAID 주문 읽기 허용', async () => {
    await assertSucceeds(getDoc(doc(staffDb(), 'orders/2')));
  });
  test('PENDING 주문 읽기 거부', async () => {
    await assertFails(getDoc(doc(staffDb(), 'orders/1')));
  });
  test('조건 없는 주문 목록 쿼리 거부, PAID 조건 쿼리는 허용', async () => {
    const db = staffDb();
    await assertFails(getDocs(collection(db, 'orders')));
    await assertSucceeds(getDocs(query(collection(db, 'orders'), where('paymentStatus', '==', 'PAID'),
      where('createdAt', '>=', Timestamp.fromMillis(0)))));
  });
  test('paymentStatus 변경 거부 (PENDING → PAID, PAID → CANCELLED)', async () => {
    const db = staffDb();
    await assertFails(updateDoc(doc(db, 'orders/1'), { paymentStatus: 'PAID', paidAt: serverTimestamp(), paidBy: '영준' }));
    await assertFails(updateDoc(doc(db, 'orders/2'), { paymentStatus: 'CANCELLED' }));
    // 조리완료에 끼워 넣어도 거부
    await assertFails(updateDoc(doc(db, 'orders/2'), { cookStatus: 'DONE', cookedAt: serverTimestamp(), cookedBy: '주방1', paymentStatus: 'PENDING' }));
  });
  test('조리완료: PAID 는 허용, PENDING 은 거부, 되돌리기 거부', async () => {
    const db = staffDb();
    await assertFails(updateDoc(doc(db, 'orders/1'), { cookStatus: 'DONE', cookedAt: serverTimestamp(), cookedBy: '주방1' }));
    await assertSucceeds(updateDoc(doc(db, 'orders/2'), { cookStatus: 'DONE', cookedAt: serverTimestamp(), cookedBy: '주방1' }));
    await assertFails(updateDoc(doc(db, 'orders/2'), { cookStatus: 'WAITING', cookedAt: serverTimestamp(), cookedBy: '주방1' }));
  });
  test('직원 주문: 착석 테이블에 PENDING 으로만 생성 (카운터 +1 과 함께)', async () => {
    const db = staffDb();
    const order = (id, tableNo, extra = {}) => ({
      id, tableNo, items: [{ menuId: 1, name: '해물파전', price: 15000, qty: 1 }], total: 15000, source: 'STAFF',
      paymentStatus: 'PENDING', cookStatus: 'WAITING', createdAt: serverTimestamp(), addedBy: '영준',
      paidAt: null, paidBy: null, cookedAt: null, cookedBy: null, ...extra,
    });
    const create = (id, tableNo, extra) => runTransaction(db, async (tx) => {
      const c = await tx.get(doc(db, 'counters/orders'));
      tx.update(doc(db, 'counters/orders'), { next: c.data().next + 1 });
      tx.set(doc(db, `orders/${id}`), order(id, tableNo, extra));
    });
    await assertFails(create(10, 1));                              // 빈 테이블
    await assertFails(create(10, 7, { paymentStatus: 'PAID' }));   // PAID 로 생성
    await assertFails(create(10, 7, { source: 'QR' }));            // QR 주문 흉내
    await assertSucceeds(create(10, 7));
    // 카운터 없이 번호를 임의로 쓰면 거부
    await assertFails(setDoc(doc(db, 'orders/99'), order(99, 7)));
  });
});

// ======================================================================
describe('웨이팅: 일반은 서버만 생성, VIP 는 앱만', () => {
  test('앱은 일반 웨이팅(waitings)을 만들 수 없다', async () => {
    await assertFails(setDoc(doc(staffDb(), 'waitings/5'), {
      id: 5, phone: '01000000005', partySize: 2, isVip: false, status: 'WAITING', createdAt: serverTimestamp(), calledAt: null, tableNo: null,
    }));
  });
  test('앱은 counters/waitings 에 접근할 수 없다', async () => {
    const db = staffDb();
    await assertFails(getDoc(doc(db, 'counters/waitings')));
    await assertFails(updateDoc(doc(db, 'counters/waitings'), { next: increment(1) }));
  });
  test('VIP 등록: counters/vip 를 +1 하면서 vipWaitings 생성', async () => {
    const db = staffDb();
    const vip = (id) => ({ id, phone: '01000000008', partySize: 2, isVip: true, status: 'WAITING', createdAt: serverTimestamp(),
      calledAt: null, tableNo: null, createdBy: '영준', updatedBy: '영준', updatedAt: serverTimestamp() });
    await assertFails(setDoc(doc(db, 'vipWaitings/2'), vip(2)));  // 카운터 없이
    // 카운터가 어긋나 이미 있는 번호를 덮어쓰려 하면 거부
    await assertFails(runTransaction(db, async (tx) => {
      await tx.get(doc(db, 'counters/vip'));
      tx.update(doc(db, 'counters/vip'), { next: 2 });
      tx.set(doc(db, 'vipWaitings/1'), vip(1));
    }));
    await assertSucceeds(runTransaction(db, async (tx) => {
      const c = await tx.get(doc(db, 'counters/vip'));
      const n = c.data().next;
      tx.update(doc(db, 'counters/vip'), { next: n + 1 });
      tx.set(doc(db, `vipWaitings/${n}`), vip(n));
    }));
  });
  test('카운터는 1씩만 증가', async () => {
    const db = staffDb();
    await assertFails(updateDoc(doc(db, 'counters/vip'), { next: 5 }));
    await assertSucceeds(updateDoc(doc(db, 'counters/vip'), { next: 3 }));
  });

  for (const col of ['waitings', 'vipWaitings']) {
    test(`${col}: 허용된 상태 전이만`, async () => {
      const db = staffDb();
      const w = doc(db, `${col}/1`);
      await assertSucceeds(updateDoc(w, { status: 'CALLED', calledAt: serverTimestamp(), ...stamp() }));
      await assertSucceeds(updateDoc(w, { status: 'CALLED', calledAt: serverTimestamp(), ...stamp() })); // 재호출
      await assertFails(updateDoc(w, { status: 'WAITING', calledAt: null, ...stamp() }));                // CALLED → WAITING 불가
      await assertSucceeds(updateDoc(w, { status: 'NO_SHOW', ...stamp() }));
      await assertFails(updateDoc(w, { status: 'CALLED', calledAt: serverTimestamp(), ...stamp() }));    // 무응답 → 호출 불가
      await assertSucceeds(updateDoc(w, { status: 'WAITING', calledAt: null, ...stamp() }));             // 대기 복귀
      await assertFails(updateDoc(w, { createdAt: serverTimestamp(), ...stamp() }));                     // 순서 조작 불가
      await assertFails(updateDoc(w, { phone: '01099999999', ...stamp() }));                             // 전화번호 변경 불가
      await assertSucceeds(updateDoc(w, { status: 'CANCELLED', ...stamp() }));
      await assertFails(updateDoc(w, { status: 'WAITING', calledAt: null, ...stamp() }));                // 취소 후 복귀 불가
    });
  }
  test('이미 착석한 웨이팅은 호출·무응답 불가 (오프라인 큐로 늦게 도착한 쓰기 방어)', async () => {
    const db = staffDb();
    await assertFails(updateDoc(doc(db, 'waitings/3'), { status: 'CALLED', calledAt: serverTimestamp(), ...stamp() }));
    await assertFails(updateDoc(doc(db, 'waitings/3'), { status: 'NO_SHOW', ...stamp() }));
  });
});

// ======================================================================
describe('테이블: 착석·연장·종료', () => {
  const seatTx = (db, tableNo, col, id, isVip) => runTransaction(db, async (tx) => {
    const t = await tx.get(doc(db, `tables/${tableNo}`));
    if (t.data().status !== 'EMPTY') throw new Error('이미 이용 중');
    tx.update(doc(db, `${col}/${id}`), { status: 'SEATED', tableNo, ...stamp() });
    tx.update(doc(db, `tables/${tableNo}`), { status: 'OCCUPIED', seatedAt: serverTimestamp(), extendedMinutes: 0,
      partySize: 2, phone: '01000000001', waitingId: id, waitingIsVip: isVip, ...stamp() });
  });

  test('웨이팅 팀 착석 (일반·VIP): 테이블과 웨이팅을 한 트랜잭션으로', async () => {
    const db = staffDb();
    await assertSucceeds(seatTx(db, 1, 'waitings', 1, false));
    await assertSucceeds(seatTx(db, 2, 'vipWaitings', 1, true));
  });
  test('웨이팅만 SEATED 로 바꾸기(테이블 없이) 거부, 다른 컬렉션 표시로 착석 거부', async () => {
    const db = staffDb();
    await assertFails(updateDoc(doc(db, 'waitings/1'), { status: 'SEATED', tableNo: 1, ...stamp() }));
    await assertFails(seatTx(db, 1, 'waitings', 1, true)); // 일반 웨이팅인데 VIP 로 표시
  });
  test('이미 이용 중인 테이블에 착석 거부', async () => {
    const db = staffDb();
    await assertFails(updateDoc(doc(db, 'tables/7'), { status: 'OCCUPIED', seatedAt: serverTimestamp(), extendedMinutes: 0, ...stamp() }));
  });
  test('착석 시각은 서버 시각만 (기기 시각 거부)', async () => {
    await assertFails(updateDoc(doc(staffDb(), 'tables/1'), { status: 'OCCUPIED', seatedAt: Timestamp.now(), extendedMinutes: 0, ...stamp() }));
  });
  test('연장은 이용 중일 때 10/20/30분만', async () => {
    const db = staffDb();
    await assertSucceeds(updateDoc(doc(db, 'tables/7'), { extendedMinutes: increment(10), ...stamp() }));
    await assertSucceeds(updateDoc(doc(db, 'tables/7'), { extendedMinutes: increment(30), ...stamp() }));
    await assertFails(updateDoc(doc(db, 'tables/7'), { extendedMinutes: increment(15), ...stamp() }));
    await assertFails(updateDoc(doc(db, 'tables/1'), { extendedMinutes: increment(10), ...stamp() })); // 빈 테이블
  });
  test('이용 종료: 필드 초기화만 허용', async () => {
    const db = staffDb();
    const reset = { status: 'EMPTY', seatedAt: null, extendedMinutes: 0, partySize: null, phone: null, waitingId: null, waitingIsVip: false };
    await assertFails(updateDoc(doc(db, 'tables/7'), { ...reset, seatedAt: ts, ...stamp() }));
    await assertSucceeds(updateDoc(doc(db, 'tables/7'), { ...reset, ...stamp() }));
  });
  test('테이블 문서 생성·삭제(배치 변경)는 앱이 못 함', async () => {
    const db = staffDb();
    await assertFails(setDoc(doc(db, 'tables/31'), { no: 31, status: 'EMPTY' }));
  });
});

// ======================================================================
describe('설정: 시간 3종만', () => {
  test('rows/cols 변경 거부, 시간 값 변경 허용, 범위 밖 거부', async () => {
    const db = staffDb();
    await assertFails(updateDoc(doc(db, 'config/settings'), { rows: 6, cols: 3, ...stamp() }));
    await assertSucceeds(updateDoc(doc(db, 'config/settings'), { rotationMinutes: 90, ...stamp() }));
    await assertFails(updateDoc(doc(db, 'config/settings'), { rotationMinutes: 5, ...stamp() }));
  });
  test('메뉴·스태프 목록은 읽기만', async () => {
    const db = staffDb();
    await assertSucceeds(getDoc(doc(db, 'config/staff')));
    await assertFails(setDoc(doc(db, 'config/staff'), { names: ['해커'] }));
    await assertFails(updateDoc(doc(db, 'menu/1'), { price: 1 }));
  });
});

// ======================================================================
describe('시계 보정 문서', () => {
  test('자기 문서만', async () => {
    const db = staffDb();
    await assertSucceeds(setDoc(doc(db, 'clocks/staff-phone-1'), { at: serverTimestamp() }));
    await assertFails(setDoc(doc(db, 'clocks/other-phone'), { at: serverTimestamp() }));
  });
});

