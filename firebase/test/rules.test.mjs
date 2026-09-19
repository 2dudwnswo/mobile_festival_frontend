import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import net from 'node:net';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, createUserWithEmailAndPassword, signOut } from 'firebase/auth';
import { collection, doc, getDoc, getDocFromCache, getDocFromServer, getDocs, setDoc, updateDoc,
  deleteDoc, query, where, orderBy, runTransaction, writeBatch, increment, disableNetwork,
  enableNetwork, getFirestore, connectFirestoreEmulator, terminate } from 'firebase/firestore';
import { PROJECT_ID, requireEmulators } from '../scripts/emulator-guard.mjs';
const endpoints = requireEmulators();
let env;
const rules = readFileSync(new URL('../firestore.rules', import.meta.url),'utf8');
const empty = { table_no:1,status:'EMPTY',start_time:null,payment_confirmed:false,extended_minutes:0,total_amount:0 };
const waiting = { phone:'01000000001',party_size:2,is_vip:false,status:'WAITING',created_at:1000,called_at:null };
const line = {table_id:1,menu_id:'1',menu_name:'메뉴',menu_price:5000,quantity:2,added_by:'테스트',status:'PENDING',created_at:2000};
const dbFor = uid => env.authenticatedContext(uid).firestore();
before(async()=> { env=await initializeTestEnvironment({projectId:PROJECT_ID,firestore:{...endpoints.firestore,rules}}); });
beforeEach(async()=>{
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async ctx=> {
    const db=ctx.firestore(); const b=writeBatch(db);
    b.set(doc(db,'tables/1'),empty); b.set(doc(db,'tables/2'),{...empty,table_no:2});
    b.set(doc(db,'waiting/w1'),waiting); b.set(doc(db,'waiting/w2'),{...waiting,phone:'01000000002',created_at:2000});
    b.set(doc(db,'menu/1'),{name:'메뉴',price:5000}); await b.commit();
  });
});
after(async()=>{await env?.cleanup();});
// Android Repository와 같은 v2 SDK 트랜잭션·배치 계약 검증. Android 앱 자체 실행 테스트는 별도다.
async function seat(db, tableId, waitingId, now) {
  return runTransaction(db,async tx=> {
    const t=doc(db,'tables',tableId), w=doc(db,'waiting',waitingId);
    const [ts,ws]=await Promise.all([tx.get(t),tx.get(w)]);
    if(ts.data()?.status!=='EMPTY') throw Error('occupied');
    if(!['WAITING','NO_SHOW'].includes(ws.data()?.status)) throw Error('waiting used');
    tx.update(t,{status:'SEATED_PENDING_PAYMENT',start_time:now}); tx.update(w,{status:'SEATED'});
  });
}
async function release(db,expected) {
  return runTransaction(db,async tx=> {
    const ref=doc(db,'tables/1'), t=(await tx.get(ref)).data();
    if(!['SEATED_PENDING_PAYMENT','IN_USE'].includes(t.status)||expected==null||t.start_time!==expected) throw Error('stale');
    tx.update(ref,{status:'EMPTY',start_time:null,payment_confirmed:false,extended_minutes:0,total_amount:0});
  });
}
test('권한표: 비로그인 tables/orders 불가, menu/waiting 읽기 허용',async()=>{
  const db=env.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(db,'tables/1'))); await assertFails(getDocs(collection(db,'orders')));
  await assertSucceeds(getDoc(doc(db,'menu/1'))); await assertSucceeds(getDoc(doc(db,'waiting/w1')));
  await assertFails(setDoc(doc(db,'menu/new'),{name:'불가',price:1}));
});
test('권한표: 손님 일반 웨이팅만 생성, 수정과 삭제 불가',async()=>{
  const db=env.unauthenticatedContext().firestore();
  await assertSucceeds(setDoc(doc(collection(db,'waiting')),waiting));
  await assertFails(setDoc(doc(collection(db,'waiting')),{...waiting,is_vip:true}));
  await assertFails(updateDoc(doc(db,'waiting/w1'),{status:'CANCELLED'}));
  await assertFails(deleteDoc(doc(db,'waiting/w1')));
});
test('권한표: 스태프 v2 컬렉션 쓰기, 스펙 밖 경로 거부',async()=>{
  const db=dbFor('staff');
  for(const path of ['tables/3','menu/2','orders/o1','waiting/vip']) await assertSucceeds(setDoc(doc(db,path),path.startsWith('orders')?line:{}));
  for(const path of ['config/settings','vipWaitings/v1','counters/orders','clocks/user']) await assertFails(setDoc(doc(db,path),{}));
});
test('a 동시 착석: 같은 테이블에는 한 팀만 성공',async()=>{
  const a=dbFor('a'),b=dbFor('b');
  const results=await Promise.allSettled([seat(a,'1','w1',3000),seat(b,'1','w2',4000)]);
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
  const waits=await getDocs(collection(a,'waiting'));
  assert.equal(waits.docs.filter(d=>d.data().status==='SEATED').length,1);
});
test('a 같은 웨이팅을 두 테이블에 배정해도 하나만 성공',async()=>{
  const a=dbFor('a'),b=dbFor('b');
  const results=await Promise.allSettled([seat(a,'1','w1',3000),seat(b,'2','w1',4000)]);
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
});
test('b 오래된 화면 종료 거부, 현재 팀 종료만 초기화',async()=>{
  const db=dbFor('staff'); await seat(db,'1','w1',3000); await release(db,3000); await seat(db,'1','w2',5000);
  await assert.rejects(release(db,3000),/stale/); await assert.rejects(release(db,null),/stale/);
  assert.equal((await getDoc(doc(db,'tables/1'))).data().start_time,5000);
  await release(db,5000); assert.deepEqual((await getDoc(doc(db,'tables/1'))).data(),empty);
});
test('c 여러 주문 줄과 합계 한 배치, 동시 증가도 유실 없음',async()=>{
  const db=dbFor('staff'); await seat(db,'1','w1',1000);
  const send=async suffix=> {const b=writeBatch(db); for(let i=0;i<2;i++) b.set(doc(db,`orders/${suffix}${i}`),line);
    b.update(doc(db,'tables/1'),{total_amount:increment(20000)}); await b.commit();};
  await Promise.all([send('a'),send('b')]);
  assert.equal((await getDocs(collection(db,'orders'))).size,4);
  assert.equal((await getDoc(doc(db,'tables/1'))).data().total_amount,40000);
});
test('c 배치 한 쓰기 거부 시 주문과 합계 모두 반영되지 않음',async()=>{
  const db=dbFor('staff'); const b=writeBatch(db); b.set(doc(db,'orders/new'),line);
  b.update(doc(db,'tables/1'),{total_amount:increment(10000)}); b.set(doc(db,'forbidden/test'),{});
  await assertFails(b.commit()); assert.equal((await getDocs(collection(db,'orders'))).size,0);
  assert.equal((await getDoc(doc(db,'tables/1'))).data().total_amount,0);
});
test('d 입금확인은 start_time과 total_amount를 보존',async()=>{
  const db=dbFor('staff'); await seat(db,'1','w1',1000); await updateDoc(doc(db,'tables/1'),{total_amount:25000});
  await updateDoc(doc(db,'tables/1'),{payment_confirmed:true,status:'IN_USE'});
  const t=(await getDoc(doc(db,'tables/1'))).data(); assert.equal(t.start_time,1000);assert.equal(t.total_amount,25000);assert.equal(t.payment_confirmed,true);
});
test('e 호출 무응답 복귀 취소, 등록 시각 유지',async()=>{
  const db=dbFor('staff'),ref=doc(db,'waiting/w1'); await updateDoc(ref,{called_at:2000});
  assert.equal((await getDoc(ref)).data().status,'WAITING'); await updateDoc(ref,{status:'NO_SHOW'}); await updateDoc(ref,{status:'WAITING'});
  assert.equal((await getDoc(ref)).data().created_at,1000); assert.equal((await getDoc(ref)).data().called_at,2000);
  await updateDoc(ref,{status:'CANCELLED'}); assert.equal((await getDoc(ref)).data().status,'CANCELLED');
});
test('f VIP 자동 ID와 통합 조회 정렬',async()=>{
  const db=dbFor('staff'),vip=doc(collection(db,'waiting')); await setDoc(vip,{...waiting,is_vip:true,created_at:5000});
  const q=query(collection(db,'waiting'),where('status','in',['WAITING','NO_SHOW']),orderBy('is_vip','desc'),orderBy('created_at','asc'));
  const docs=(await getDocs(q)).docs; assert.equal(docs[0].id,vip.id); assert.equal(docs.length,3);
});
test('g 주방은 입금 전 주문도 구독, 묶음 완료는 한 배치',async()=>{
  const db=dbFor('staff'); await seat(db,'1','w1',1000); const b=writeBatch(db);
  b.set(doc(db,'orders/a'),line); b.set(doc(db,'orders/b'),{...line,menu_id:'2'});await b.commit();
  const q=query(collection(db,'orders'),where('status','==','PENDING'),orderBy('created_at'));
  const docs=(await getDocs(q)).docs; assert.equal(docs.length,2); assert.equal((await getDoc(doc(db,'tables/1'))).data().payment_confirmed,false);
  const done=writeBatch(db); docs.forEach(d=>done.update(d.ref,{status:'DONE'}));await done.commit();
  assert.equal((await getDocs(q)).size,0);assert.equal((await getDocs(collection(db,'orders'))).size,2);
});
test('h Auth 에뮬레이터 로그인 허용, 로그아웃 후 서버 읽기 거부',async()=>{
  const app=initializeApp({projectId:PROJECT_ID,apiKey:'fake-emulator-key'},'auth-flow');
  const auth=getAuth(app); connectAuthEmulator(auth,'http://127.0.0.1:9099',{disableWarnings:true});
  const db=getFirestore(app);connectFirestoreEmulator(db,'127.0.0.1',8080);
  try {
    await createUserWithEmailAndPassword(auth,`test-${Date.now()}@example.test`,'emulator-only-1234');
    await assertSucceeds(getDocFromServer(doc(db,'tables/1'))); await signOut(auth);
    await assertFails(getDocFromServer(doc(db,'tables/1')));
  } finally {await terminate(db); await deleteApp(app);}
});
test('i 단순 쓰기는 오프라인 캐시에 남고 복구 뒤 전송',async()=>{
  const db=dbFor('offline'),ref=doc(db,'waiting/w1');await getDoc(ref);await disableNetwork(db);
  const pending=updateDoc(ref,{called_at:9000});
  try {
    // disableNetwork는 쓰기 큐 검증에만 사용한다. 아래 별도 테스트에서 TCP 경로를 실제로 차단한다.
    const cache=await getDocFromCache(ref);assert.equal(cache.data().called_at,9000);assert.equal(cache.metadata.hasPendingWrites,true);
  } finally {await enableNetwork(db); await pending;}
  assert.equal((await getDocFromServer(ref)).data().called_at,9000);
});

test('i TCP 단절 시 트랜잭션 실패, 복구 뒤 읽기 가능', {timeout:30000}, async()=>{
  const sockets = new Set(); let offline = false;
  const proxy = net.createServer(client => {
    if(offline) { client.destroy(); return; }
    const upstream=net.connect({host:'127.0.0.1',port:8080});
    sockets.add(client); sockets.add(upstream);
    client.pipe(upstream); upstream.pipe(client);
    client.on('error',()=>{}); upstream.on('error',()=>client.destroy());
    client.on('close',()=>{sockets.delete(client);upstream.destroy();});
    upstream.on('close',()=>{sockets.delete(upstream);client.destroy();});
  });
  await new Promise(resolve=>proxy.listen(18080,'127.0.0.1',resolve));
  const isolated=await initializeTestEnvironment({projectId:PROJECT_ID,firestore:{host:'127.0.0.1',port:18080}});
  const db=isolated.authenticatedContext('tcp-test').firestore();
  try {
    await getDocFromServer(doc(db,'tables/1'));
    offline=true; for(const socket of sockets) socket.destroy();
    await assert.rejects(runTransaction(db,async tx=>{
      const ref=doc(db,'tables/1'); await tx.get(ref);tx.update(ref,{extended_minutes:10});
    },{maxAttempts:1}));
    offline=false;
    assert.equal((await getDocFromServer(doc(db,'tables/1'))).data().extended_minutes,0);
  } finally {
    await isolated.cleanup(); for(const socket of sockets) socket.destroy();
    await new Promise(resolve=>proxy.close(resolve));
  }
});
