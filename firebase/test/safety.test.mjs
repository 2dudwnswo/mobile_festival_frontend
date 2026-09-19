import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { requireEmulators } from '../scripts/emulator-guard.mjs';
import { fixtures } from '../scripts/fixtures.mjs';
const valid = { FIRESTORE_EMULATOR_HOST: '127.0.0.1:8080', FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:9099' };
test('환경변수 없으면 시드 거부', () => {
  assert.throws(() => requireEmulators({}));
  const env = {...process.env}; delete env.FIRESTORE_EMULATOR_HOST; delete env.FIREBASE_AUTH_EMULATOR_HOST;
  const r = spawnSync(process.execPath, ['scripts/seed.mjs'], { env, encoding:'utf8' });
  assert.notEqual(r.status, 0); assert.match(r.stderr, /실행 거부/);
});
test('운영·외부·URL 형식·다른 포트 거부', () => {
  for (const host of ['firestore.googleapis.com:443', '10.0.0.5:8080', 'http://127.0.0.1:8080', '127.0.0.1:443'])
    assert.throws(() => requireEmulators({...valid, FIRESTORE_EMULATOR_HOST: host}));
});
test('두 에뮬레이터와 프로젝트 일치 필수', () => {
  assert.deepEqual(requireEmulators(valid).firestore, {host:'127.0.0.1', port:8080});
  assert.throws(() => requireEmulators({...valid, FIREBASE_AUTH_EMULATOR_HOST:undefined}));
  assert.throws(() => requireEmulators({...valid, GCLOUD_PROJECT:'another-project'}));
});
test('기본 deploy 대상과 프로젝트 별칭 없음', () => {
  const config = JSON.parse(readFileSync('firebase.json','utf8').replace(/^\uFEFF/,''));
  assert.deepEqual(Object.keys(config), ['emulators']);
  assert.deepEqual(JSON.parse(readFileSync('.firebaserc','utf8').replace(/^\uFEFF/,'')),{projects:{}});
  assert.notEqual(spawnSync(process.execPath,['scripts/deny-deploy.cjs']).status,0);
  assert.notEqual(spawnSync(process.execPath,['scripts/emulators.mjs','deploy']).status,0);
});
test('외부 TCP와 HTTP를 실제 연결 전에 차단', () => {
  const r = spawnSync(process.execPath, ['--require','./scripts/local-network-only.cjs','-e',
    `const assert=require('node:assert'); assert.throws(()=>require('node:net').connect({host:'firestore.googleapis.com',port:443}),/차단/); assert.throws(()=>fetch('https://firestore.googleapis.com'),/차단/);`]);
  assert.equal(r.status,0);
});
test('시드 번호와 컬렉션은 v2 허용 범위', () => {
  const f = fixtures(1000000); assert.equal(f.tables.length,30); assert.equal(f.menus.length,4);
  for (const [, w] of f.waitings) assert.match(w.phone,/^010000000\d{2}$/);
  assert.equal(f.orders[0][1].created_at, f.orders[1][1].created_at);
  assert.equal(f.orders.reduce((n,[,o])=>n+o.menu_price*o.quantity,0),f.tables[0][1].total_amount);
});
test('에뮬레이터 프로젝트 ID는 demo- 로 시작 (도구·실행 스크립트·앱 debug 모두 같은 ID)', async () => {
  const { PROJECT_ID } = await import('../scripts/emulator-guard.mjs');
  assert.match(PROJECT_ID, /^demo-/);
  // 실행 스크립트는 가드의 ID만 쓰고, 다른 --project 값을 직접 적지 않는다
  const runner = readFileSync('scripts/emulators.mjs', 'utf8');
  assert.match(runner, /'--project', PROJECT_ID/);
  assert.doesNotMatch(runner, /mobokfestivalpub/);
  // 앱: 에뮬레이터용 FirebaseApp 은 같은 demo ID, 운영 ID 는 별도 상수로만 존재
  const app = readFileSync('../android/app/src/main/java/com/festivalpub/admin/data/FirebaseConnection.kt', 'utf8');
  assert.equal(app.match(/EMULATOR_PROJECT_ID = "([^"]+)"/)?.[1], PROJECT_ID);
  assert.match(app, /setProjectId\(EMULATOR_PROJECT_ID/);
  // 에뮬레이터 설정 파일과 별칭에는 운영 ID가 없다
  for (const f of ['firebase.emulator.json', 'firebase.json', '.firebaserc']) assert.doesNotMatch(readFileSync(f, 'utf8'), /mobokfestivalpub/);
  // 가드는 다른 프로젝트 ID 환경변수를 거부한다
  assert.throws(() => requireEmulators({ ...valid, GCLOUD_PROJECT: 'mobokfestivalpub' }));
});
