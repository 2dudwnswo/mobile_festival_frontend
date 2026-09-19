import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {initializeTestEnvironment} from '@firebase/rules-unit-testing';
import {collection,getDocs} from 'firebase/firestore';
import {requireEmulators,PROJECT_ID} from '../scripts/emulator-guard.mjs';
const endpoints=requireEmulators();
test('시드 초기화 두 번 실행해도 v2 데이터 개수와 가짜 번호 유지',async()=>{
  for(let attempt=0;attempt<2;attempt++) {
    await new Promise((resolve,reject)=>{
      const child=spawn(process.execPath,['scripts/seed.mjs','--reset'],{env:process.env,stdio:'inherit',windowsHide:true});
      child.on('error',reject);child.on('exit',code=>code===0?resolve():reject(Error(`seed failed ${code}`)));
    });
  }
  const env=await initializeTestEnvironment({projectId:PROJECT_ID,firestore:endpoints.firestore});
  try {
    const db=env.authenticatedContext('seed-check').firestore();
    for(const [name,count] of [['tables',30],['menu',4],['waiting',3],['orders',2]])
      assert.equal((await getDocs(collection(db,name))).size,count);
    const waits=await getDocs(collection(db,'waiting'));
    waits.forEach(d=>assert.match(d.data().phone,/^010000000\d{2}$/));
  } finally {await env.cleanup();}
});
