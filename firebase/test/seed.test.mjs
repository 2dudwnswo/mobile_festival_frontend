import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {initializeTestEnvironment} from '@firebase/rules-unit-testing';
import {collection,getDocs} from 'firebase/firestore';
import {requireEmulators,PROJECT_ID} from '../scripts/emulator-guard.mjs';
const endpoints=requireEmulators();
test('시드 초기화 두 번 실행해도 v3 데이터 개수·짝 연결·가짜 번호 유지',async()=>{
  for(let attempt=0;attempt<2;attempt++) {
    await new Promise((resolve,reject)=>{
      const child=spawn(process.execPath,['scripts/seed.mjs','--reset'],{env:process.env,stdio:'inherit',windowsHide:true});
      child.on('error',reject);child.on('exit',code=>code===0?resolve():reject(Error(`seed failed ${code}`)));
    });
  }
  const env=await initializeTestEnvironment({projectId:PROJECT_ID,firestore:endpoints.firestore});
  try {
    const db=env.authenticatedContext('seed-check').firestore();
    for(const [name,count] of [['tables',30],['menu',4],['waiting_private',4],['waiting_public',4],['orders',2]])
      assert.equal((await getDocs(collection(db,name))).size,count);
    const publics=new Map((await getDocs(collection(db,'waiting_public'))).docs.map(d=>[d.id,d.data()]));
    const privates=await getDocs(collection(db,'waiting_private'));
    privates.forEach(d=>{
      const p=d.data();
      assert.match(p.phone,/^010000000\d{2}$/); assert.equal(d.id,p.phone);
      const pub=publics.get(p.public_id); assert.ok(pub,'public 짝 문서 존재');
      assert.equal(pub.status,p.status); assert.equal(pub.is_vip,p.is_vip); assert.equal(pub.phone,undefined);
    });
  } finally {await env.cleanup();}
});
