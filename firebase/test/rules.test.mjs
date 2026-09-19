// 운영에 배포된 실제 보안 규칙(firestore.rules, 스펙 v3)을 에뮬레이터에서 검증한다.
import { after, before, beforeEach, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import net from 'node:net';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, createUserWithEmailAndPassword, signOut } from 'firebase/auth';
import { collection, doc, getDoc, getDocFromServer, getDocs, setDoc, updateDoc, deleteDoc, query, where,
  orderBy, runTransaction, writeBatch, getFirestore, connectFirestoreEmulator, terminate } from 'firebase/firestore';
import { PROJECT_ID, requireEmulators } from '../scripts/emulator-guard.mjs';

const endpoints = requireEmulators();
const rules = readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8');
let env;

const empty = { table_no: 1, status: 'EMPTY', start_time: null, payment_confirmed: false, extended_minutes: 0, total_amount: 0 };
const priv = (phone, status, extra = {}) => ({ phone, party_size: 2, is_vip: false, status, called_at: null,
  created_at: 1000, public_id: `pub-${phone}`, ...extra });
const pub = (status, extra = {}) => ({ is_vip: false, status, created_at: 1000, ...extra });
const line = { table_id: 1, menu_id: '1', menu_name: '메뉴', menu_price: 5000, quantity: 2, added_by: '테스트', status: 'PENDING', created_at: 2000 };

// 손님 웹의 재등록: 같은 번호 문서에 새 WAITING 데이터로 덮어쓰기
const guestRegister = (phone, extra = {}) => ({ phone, party_size: 2, is_vip: false, status: 'WAITING', called_at: null,
  created_at: 5000, public_id: 'pub-new', ...extra });

before(async () => {
  env = await initializeTestEnvironment({ projectId: PROJECT_ID, firestore: { ...endpoints.firestore, rules } });
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore(); const b = writeBatch(db);
    b.set(doc(db, 'tables/1'), empty);
    b.set(doc(db, 'menu/1'), { name: '메뉴', price: 5000 });
    b.set(doc(db, 'orders/o1'), line);
    for (const [phone, status] of [['01000000001', 'WAITING'], ['01000000002', 'NO_SHOW'],
      ['01000000003', 'CANCELLED'], ['01000000004', 'SEATED']]) {
      b.set(doc(db, `waiting_private/${phone}`), priv(phone, status));
      b.set(doc(db, `waiting_public/pub-${phone}`), pub(status));
    }
    await b.commit();
  });
});
after(async () => { await env?.cleanup(); });

const guest = () => env.unauthenticatedContext().firestore();
const staff = () => env.authenticatedContext('staff-uid').firestore();

describe('비로그인(손님)', () => {
  test('waiting_private: get 허용, list 거부', async () => {
    const db = guest();
    await assertSucceeds(getDoc(doc(db, 'waiting_private/01000000001')));
    await assertFails(getDocs(collection(db, 'waiting_private')));
    await assertFails(getDocs(query(collection(db, 'waiting_private'), where('status', 'in', ['WAITING', 'NO_SHOW']),
      orderBy('is_vip', 'desc'), orderBy('created_at'))));
  });
  test('tables·orders 거부, menu 읽기 허용·쓰기 거부, waiting_public 읽기 허용', async () => {
    const db = guest();
    await assertFails(getDoc(doc(db, 'tables/1')));
    await assertFails(getDocs(collection(db, 'orders')));
    await assertFails(getDoc(doc(db, 'orders/o1')));
    await assertSucceeds(getDoc(doc(db, 'menu/1')));
    await assertFails(setDoc(doc(db, 'menu/9'), { name: '불가', price: 1 }));
    await assertSucceeds(getDocs(collection(db, 'waiting_public')));
  });
  test('신규 일반 등록 허용, is_vip:true 생성 거부 (private·public 모두)', async () => {
    const db = guest();
    await assertSucceeds(setDoc(doc(db, 'waiting_private/01000000009'), guestRegister('01000000009')));
    await assertSucceeds(setDoc(doc(collection(db, 'waiting_public')), pub('WAITING')));
    await assertFails(setDoc(doc(db, 'waiting_private/01000000008'), guestRegister('01000000008', { is_vip: true })));
    await assertFails(setDoc(doc(collection(db, 'waiting_public')), pub('WAITING', { is_vip: true })));
  });
  test('문서 ID 와 phone 불일치, party_size 비정수 생성 거부', async () => {
    const db = guest();
    await assertFails(setDoc(doc(db, 'waiting_private/01000000009'), guestRegister('01000000007')));
    await assertFails(setDoc(doc(db, 'waiting_private/01000000009'), guestRegister('01000000009', { party_size: 2.5 })));
  });
  test('CANCELLED·SEATED 번호 재등록(덮어쓰기) 허용', async () => {
    const db = guest();
    await assertSucceeds(setDoc(doc(db, 'waiting_private/01000000003'), guestRegister('01000000003')));
    await assertSucceeds(setDoc(doc(db, 'waiting_private/01000000004'), guestRegister('01000000004')));
  });
  test('WAITING·NO_SHOW 번호 재등록 거부', async () => {
    const db = guest();
    await assertFails(setDoc(doc(db, 'waiting_private/01000000001'), guestRegister('01000000001')));
    await assertFails(setDoc(doc(db, 'waiting_private/01000000002'), guestRegister('01000000002')));
  });
  test('재등록은 WAITING·is_vip false·called_at null 로만', async () => {
    const db = guest();
    await assertFails(setDoc(doc(db, 'waiting_private/01000000003'), guestRegister('01000000003', { is_vip: true })));
    await assertFails(setDoc(doc(db, 'waiting_private/01000000003'), guestRegister('01000000003', { called_at: 1 })));
    await assertFails(setDoc(doc(db, 'waiting_private/01000000003'), guestRegister('01000000003', { status: 'NO_SHOW' })));
  });
  test('상태 변경·삭제 거부 (private·public)', async () => {
    const db = guest();
    await assertFails(updateDoc(doc(db, 'waiting_private/01000000001'), { status: 'CANCELLED' }));
    await assertFails(updateDoc(doc(db, 'waiting_public/pub-01000000001'), { status: 'CANCELLED' }));
    await assertFails(deleteDoc(doc(db, 'waiting_private/01000000001')));
    await assertFails(deleteDoc(doc(db, 'waiting_public/pub-01000000001')));
  });
});

describe('로그인(스태프)', () => {
  test('waiting_private list(앱 목록 쿼리) 허용, tables·orders·menu 읽기 허용', async () => {
    const db = staff();
    const q = query(collection(db, 'waiting_private'), where('status', 'in', ['WAITING', 'NO_SHOW']),
      orderBy('is_vip', 'desc'), orderBy('created_at'));
    assert.equal((await assertSucceeds(getDocs(q))).size, 2);
    await assertSucceeds(getDoc(doc(db, 'tables/1')));
    await assertSucceeds(getDocs(collection(db, 'orders')));
    await assertSucceeds(getDoc(doc(db, 'menu/1')));
  });
  test('VIP 생성 허용 (private·public 짝, 기존 CANCELLED 번호 덮어쓰기 포함)', async () => {
    const db = staff();
    const pubRef = doc(collection(db, 'waiting_public'));
    await assertSucceeds(runTransaction(db, async (tx) => {
      await tx.get(doc(db, 'waiting_private/01000000003'));
      tx.set(pubRef, { is_vip: true, status: 'WAITING', created_at: 7000 });
      tx.set(doc(db, 'waiting_private/01000000003'), { phone: '01000000003', party_size: 3, is_vip: true, status: 'WAITING',
        called_at: null, created_at: 7000, public_id: pubRef.id });
    }));
  });
  test('모든 상태 변경 허용 (호출·무응답·복귀·취소·착석, private·public 함께)', async () => {
    const db = staff();
    const p = doc(db, 'waiting_private/01000000001'), q = doc(db, 'waiting_public/pub-01000000001');
    await assertSucceeds(updateDoc(p, { called_at: 3000 }));
    for (const status of ['NO_SHOW', 'WAITING', 'CANCELLED', 'WAITING', 'SEATED']) {
      await assertSucceeds(writeBatch(db).update(p, { status }).update(q, { status }).commit());
    }
    await assertSucceeds(deleteDoc(doc(db, 'waiting_public/pub-01000000002')));
  });
  test('착석: 테이블 + private + public 을 한 트랜잭션으로', async () => {
    const db = staff();
    await assertSucceeds(runTransaction(db, async (tx) => {
      const t = doc(db, 'tables/1'), p = doc(db, 'waiting_private/01000000001'), q = doc(db, 'waiting_public/pub-01000000001');
      await tx.get(t); await tx.get(p); await tx.get(q);
      tx.update(p, { status: 'SEATED' }); tx.update(q, { status: 'SEATED' });
      tx.update(t, { status: 'SEATED_PENDING_PAYMENT', start_time: 4000 });
    }));
  });
  test('스펙에 없는 경로(예전 waiting 등)는 로그인해도 거부', async () => {
    const db = staff();
    for (const path of ['waiting/w1', 'config/settings', 'vipWaitings/v1', 'counters/orders']) {
      await assertFails(setDoc(doc(db, path), {}));
      await assertFails(getDoc(doc(db, path)));
    }
  });
});

test('Auth 에뮬레이터: 로그인하면 tables 읽기 허용, 로그아웃하면 거부', async () => {
  const app = initializeApp({ projectId: PROJECT_ID, apiKey: 'fake-emulator-key' }, 'auth-flow');
  const auth = getAuth(app); connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
  const db = getFirestore(app); connectFirestoreEmulator(db, '127.0.0.1', 8080);
  try {
    await createUserWithEmailAndPassword(auth, `test-${Date.now()}@example.test`, 'emulator-only-1234');
    await assertSucceeds(getDocFromServer(doc(db, 'tables/1'))); await signOut(auth);
    await assertFails(getDocFromServer(doc(db, 'tables/1')));
  } finally { await terminate(db); await deleteApp(app); }
});

test('TCP 단절 시 트랜잭션 실패, 복구 뒤 읽기 가능', { timeout: 30000 }, async () => {
  const sockets = new Set(); let offline = false;
  const proxy = net.createServer((client) => {
    if (offline) { client.destroy(); return; }
    const upstream = net.connect({ host: '127.0.0.1', port: 8080 });
    sockets.add(client); sockets.add(upstream);
    client.pipe(upstream); upstream.pipe(client);
    client.on('error', () => {}); upstream.on('error', () => client.destroy());
    client.on('close', () => { sockets.delete(client); upstream.destroy(); });
    upstream.on('close', () => { sockets.delete(upstream); client.destroy(); });
  });
  await new Promise((resolve) => proxy.listen(18080, '127.0.0.1', resolve));
  const isolated = await initializeTestEnvironment({ projectId: PROJECT_ID, firestore: { host: '127.0.0.1', port: 18080 } });
  const db = isolated.authenticatedContext('tcp-test').firestore();
  try {
    await getDocFromServer(doc(db, 'tables/1'));
    offline = true; for (const socket of sockets) socket.destroy();
    await assert.rejects(runTransaction(db, async (tx) => {
      const ref = doc(db, 'tables/1'); await tx.get(ref); tx.update(ref, { extended_minutes: 10 });
    }, { maxAttempts: 1 }));
    offline = false;
    assert.equal((await getDocFromServer(doc(db, 'tables/1'))).data().extended_minutes, 0);
  } finally {
    await isolated.cleanup(); for (const socket of sockets) socket.destroy();
    await new Promise((resolve) => proxy.close(resolve));
  }
});
