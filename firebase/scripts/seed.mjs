import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, doc, writeBatch } from 'firebase/firestore';
import { PROJECT_ID, requireEmulators } from './emulator-guard.mjs';
import { fixtures } from './fixtures.mjs';
const endpoints = requireEmulators(); // 네트워크/초기화보다 먼저 검사. 기본 운영 엔드포인트 없음.
if (process.argv.slice(2).some(arg => arg !== '--reset')) throw new Error('--reset 외 인자 금지');
const env = await initializeTestEnvironment({ projectId: PROJECT_ID, firestore: endpoints.firestore });
try {
  if (process.argv.includes('--reset')) await env.clearFirestore();
  await env.withSecurityRulesDisabled(async ctx => {
    const db = ctx.firestore(); const data = fixtures(); const batch = writeBatch(db);
    for (const [path, value] of [...data.tables, ...data.menus]) batch.set(doc(db, path), value);
    // v3: waiting_private/{전화번호} 와 waiting_public/{id} 를 짝으로 (private.public_id 로 연결)
    for (const w of data.waitings) { batch.set(doc(db, w.privatePath), w.private); batch.set(doc(db, w.publicPath), w.public); }
    for (const [, value] of data.orders) batch.set(doc(collection(db, 'orders')), value);
    await batch.commit();
  });
  const response = await fetch(`http://${endpoints.auth.host}:${endpoints.auth.port}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-emulator-key`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'staff@example.test', password: 'emulator-only-1234', returnSecureToken: true }),
  });
  if (!response.ok) {
    const result = await response.json();
    if (result.error?.message !== 'EMAIL_EXISTS') throw new Error('로컬 테스트 계정 생성 실패');
  }
  console.log('로컬 시드 완료: 테이블 30, 메뉴 4, 웨이팅 private/public 짝 4쌍(일반·VIP·NO_SHOW·CANCELLED), 주문 2줄. 운영 접속 없음.');
} finally { await env.cleanup(); }
