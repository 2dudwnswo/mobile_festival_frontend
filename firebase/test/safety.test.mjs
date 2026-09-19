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
