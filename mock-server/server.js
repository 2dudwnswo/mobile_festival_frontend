// 축제 주점 테스트용 mock 서버 (docs/API.md 구현)
// 실행: npm install && npm start   →  http://<노트북IP>:8080
// 메모리에만 저장하므로 재시작하면 초기화됩니다.

const http = require('http');
const os = require('os');
const { URL } = require('url');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;

// ---------- 초기 데이터 ----------
const state = {
  settings: { rows: 5, cols: 6, rotationMinutes: 100, imminentMinutes: 15, noShowMinutes: 3 },
  tables: [],
  waitings: [],
  orders: [],
  menu: [
    { id: 1, name: '해물파전', price: 15000, category: '안주', soldOut: false },
    { id: 2, name: '김치전', price: 12000, category: '안주', soldOut: false },
    { id: 3, name: '닭꼬치', price: 5000, category: '안주', soldOut: false },
    { id: 4, name: '떡볶이', price: 10000, category: '안주', soldOut: false },
    { id: 5, name: '어묵탕', price: 12000, category: '안주', soldOut: false },
    { id: 6, name: '감자튀김', price: 8000, category: '안주', soldOut: false },
    { id: 7, name: '소주', price: 5000, category: '주류', soldOut: false },
    { id: 8, name: '맥주', price: 5000, category: '주류', soldOut: false },
    { id: 9, name: '막걸리', price: 6000, category: '주류', soldOut: false },
    { id: 10, name: '콜라', price: 2000, category: '음료', soldOut: false },
    { id: 11, name: '사이다', price: 2000, category: '음료', soldOut: false },
  ],
  staff: ['동현', '영준', '민지', '주방1'],
};
let nextWaitingId = 1;
let nextOrderId = 1;

function emptyTable(no) {
  return { no, status: 'EMPTY', seatedAt: null, extendedMinutes: 0, partySize: null, phone: null, waitingId: null };
}
function resizeTables() {
  const n = state.settings.rows * state.settings.cols;
  const byNo = new Map(state.tables.map(t => [t.no, t]));
  state.tables = [];
  for (let i = 1; i <= n; i++) state.tables.push(byNo.get(i) || emptyTable(i));
}
resizeTables();

const snapshot = () => ({ serverTime: Date.now(), ...state });

// ---------- WebSocket ----------
const server = http.createServer(handle);
const wss = new WebSocketServer({ server, path: '/ws' });
wss.on('connection', ws => ws.send(JSON.stringify({ type: 'snapshot', data: snapshot() })));
function broadcast() {
  const msg = JSON.stringify({ type: 'snapshot', data: snapshot() });
  wss.clients.forEach(c => { if (c.readyState === 1) c.send(msg); });
}

// ---------- HTTP ----------
class ApiError extends Error { constructor(code, msg) { super(msg); this.code = code; } }
const fail = (code, msg) => { throw new ApiError(code, msg); };

function send(res, code, body) {
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,OPTIONS',
  });
  res.end(JSON.stringify(body));
}
function readBody(req) {
  return new Promise(resolve => {
    let s = '';
    req.on('data', c => (s += c));
    req.on('end', () => { try { resolve(s ? JSON.parse(s) : {}); } catch { resolve({}); } });
  });
}
const findTable = no => state.tables.find(t => t.no === Number(no)) || fail(404, `${no}번 테이블이 없습니다`);
const findWaiting = id => state.waitings.find(w => w.id === Number(id)) || fail(404, '웨이팅을 찾을 수 없습니다');
const findOrder = id => state.orders.find(o => o.id === Number(id)) || fail(404, '주문을 찾을 수 없습니다');
const ACTIVE_WAITING = ['WAITING', 'CALLED', 'NO_SHOW'];

async function handle(req, res) {
  if (req.method === 'OPTIONS') return send(res, 204, {});
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  const m = req.method;
  try {
    if (m === 'GET' && p === '/dev') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); return res.end(DEV_PAGE); }
    if (m === 'GET' && p === '/api/state') return send(res, 200, snapshot());
    if (m === 'GET' && p === '/api/menu') return send(res, 200, state.menu);

    if (m === 'GET' && p === '/api/waitings/lookup') {
      const phone = (url.searchParams.get('phone') || '').replace(/\D/g, '');
      const mine = state.waitings.filter(w => w.phone === phone && ACTIVE_WAITING.includes(w.status)).pop();
      if (!mine) return send(res, 404, { error: '등록된 웨이팅이 없습니다' });
      const aheadCount = state.waitings.filter(w => !w.isVip && ['WAITING', 'CALLED'].includes(w.status) && w.createdAt < mine.createdAt).length;
      return send(res, 200, { id: mine.id, status: mine.status, partySize: mine.partySize, aheadCount: mine.isVip ? 0 : aheadCount });
    }
    if (m === 'GET' && p === '/api/orders') {
      const t = findTable(url.searchParams.get('tableNo'));
      const list = t.status === 'OCCUPIED' ? state.orders.filter(o => o.tableNo === t.no && o.createdAt >= t.seatedAt) : [];
      return send(res, 200, list);
    }

    const body = await readBody(req);
    const now = Date.now();
    let result = { ok: true };
    let mt;

    if (m === 'PUT' && p === '/api/settings') {
      const s = { ...state.settings };
      for (const k of Object.keys(s)) if (body[k] != null) s[k] = Math.max(1, parseInt(body[k], 10) || s[k]);
      const n = s.rows * s.cols;
      const busy = state.tables.find(t => t.no > n && t.status === 'OCCUPIED');
      if (busy) fail(409, `${busy.no}번 테이블이 이용 중이라 줄일 수 없습니다`);
      state.settings = s;
      resizeTables();
      result = state.settings;
    } else if ((mt = p.match(/^\/api\/tables\/(\d+)\/(seat|extend|release)$/)) && m === 'POST') {
      const t = findTable(mt[1]);
      if (mt[2] === 'seat') {
        if (t.status === 'OCCUPIED') fail(409, `${t.no}번 테이블은 이미 이용 중입니다`);
        let w = null;
        if (body.waitingId != null) {
          w = findWaiting(body.waitingId);
          if (!ACTIVE_WAITING.includes(w.status)) fail(409, '이미 처리된 웨이팅입니다');
          w.status = 'SEATED'; w.tableNo = t.no;
        }
        Object.assign(t, { status: 'OCCUPIED', seatedAt: now, extendedMinutes: 0,
          partySize: w ? w.partySize : (body.partySize || null), phone: w ? w.phone : null, waitingId: w ? w.id : null });
      } else if (mt[2] === 'extend') {
        if (t.status !== 'OCCUPIED') fail(409, '이용 중인 테이블만 연장할 수 있습니다');
        t.extendedMinutes += parseInt(body.minutes, 10) || 0;
      } else {
        state.orders.forEach(o => { if (o.tableNo === t.no && o.paymentStatus === 'PENDING') o.paymentStatus = 'CANCELLED'; });
        Object.assign(t, emptyTable(t.no));
      }
      result = t;
    } else if (m === 'POST' && p === '/api/waitings') {
      const phone = String(body.phone || '').replace(/\D/g, '');
      const partySize = parseInt(body.partySize, 10);
      if (phone.length < 10 || !(partySize > 0)) fail(400, '전화번호와 인원수를 확인해 주세요');
      if (state.waitings.some(w => w.phone === phone && ACTIVE_WAITING.includes(w.status))) fail(409, '이미 대기 중입니다');
      const w = { id: nextWaitingId++, phone, partySize, isVip: !!body.isVip, status: 'WAITING', createdAt: now, calledAt: null, tableNo: null };
      state.waitings.push(w);
      result = w;
    } else if ((mt = p.match(/^\/api\/waitings\/(\d+)\/(call|no-show|restore|cancel)$/)) && m === 'POST') {
      const w = findWaiting(mt[1]);
      const a = mt[2];
      if (a === 'call') { if (!['WAITING', 'CALLED'].includes(w.status)) fail(409, '호출할 수 없는 상태입니다'); w.status = 'CALLED'; w.calledAt = now; }
      if (a === 'no-show') { if (w.status !== 'CALLED' && w.status !== 'WAITING') fail(409, '무응답 처리할 수 없는 상태입니다'); w.status = 'NO_SHOW'; }
      if (a === 'restore') { if (w.status !== 'NO_SHOW') fail(409, '무응답 상태가 아닙니다'); w.status = 'WAITING'; w.calledAt = null; }
      if (a === 'cancel') { if (!ACTIVE_WAITING.includes(w.status)) fail(409, '이미 처리된 웨이팅입니다'); w.status = 'CANCELLED'; }
      result = w;
    } else if (m === 'POST' && p === '/api/orders') {
      const t = findTable(body.tableNo);
      if (t.status !== 'OCCUPIED') fail(409, '착석 처리된 테이블만 주문할 수 있습니다');
      const items = (body.items || []).filter(i => i.qty > 0).map(i => {
        const mi = state.menu.find(x => x.id === Number(i.menuId)) || fail(400, '없는 메뉴입니다');
        if (mi.soldOut) fail(400, `${mi.name}은(는) 품절입니다`);
        return { menuId: mi.id, name: mi.name, price: mi.price, qty: parseInt(i.qty, 10) };
      });
      if (!items.length) fail(400, '메뉴를 선택해 주세요');
      const o = { id: nextOrderId++, tableNo: t.no, items, total: items.reduce((s, i) => s + i.price * i.qty, 0),
        source: body.source === 'STAFF' ? 'STAFF' : 'QR', paymentStatus: 'PENDING', cookStatus: 'WAITING', createdAt: now,
        addedBy: body.staff || null, paidAt: null, paidBy: null, cookedAt: null, cookedBy: null };
      state.orders.push(o);
      result = o;
    } else if ((mt = p.match(/^\/api\/orders\/(\d+)\/(confirm-payment|cancel|cooked)$/)) && m === 'POST') {
      const o = findOrder(mt[1]);
      if (mt[2] === 'confirm-payment') { if (o.paymentStatus !== 'PENDING') fail(409, '입금 대기 중인 주문이 아닙니다'); o.paymentStatus = 'PAID'; o.paidAt = now; o.paidBy = body.staff || null; }
      if (mt[2] === 'cancel') { if (o.paymentStatus !== 'PENDING') fail(409, '입금 전 주문만 취소할 수 있습니다'); o.paymentStatus = 'CANCELLED'; }
      if (mt[2] === 'cooked') { if (o.paymentStatus !== 'PAID') fail(409, '입금 확인된 주문이 아닙니다'); o.cookStatus = 'DONE'; o.cookedAt = now; o.cookedBy = body.staff || null; }
      result = o;
    } else {
      return send(res, 404, { error: '없는 경로입니다' });
    }
    console.log(new Date().toLocaleTimeString(), m, p, body.staff ? `(${body.staff})` : '');
    send(res, 200, result);
    broadcast();
  } catch (e) {
    if (e instanceof ApiError) return send(res, e.code, { error: e.message });
    console.error(e);
    send(res, 500, { error: '서버 오류' });
  }
}

// 손님 역할을 흉내내는 테스트 페이지 (웨이팅 등록 / QR 주문)
const DEV_PAGE = `<!doctype html><meta name=viewport content="width=device-width"><title>테스트</title>
<style>body{font-family:sans-serif;max-width:480px;margin:16px auto;padding:0 12px}input,button{font-size:16px;padding:8px;margin:4px 0}pre{background:#eee;padding:8px;white-space:pre-wrap}</style>
<h2>손님 흉내 (테스트용)</h2>
<h3>웨이팅 등록</h3><input id=ph placeholder="01012345678"> <input id=ps type=number value=2 style="width:60px"> <button onclick="reg()">등록</button>
<button onclick="rnd()">랜덤 5팀</button>
<h3>대기 조회</h3><input id=lp placeholder="전화번호"> <button onclick="look()">조회</button>
<h3>QR 주문</h3>테이블 <input id=tn type=number value=1 style="width:60px"> 메뉴ID <input id=mi type=number value=1 style="width:60px"> 수량 <input id=q type=number value=1 style="width:60px"> <button onclick="ord()">주문</button>
<pre id=out></pre>
<script>
const out=t=>document.getElementById('out').textContent=JSON.stringify(t,null,2);
const post=(u,b)=>fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}).then(r=>r.json()).then(out);
const v=id=>document.getElementById(id).value;
function reg(){post('/api/waitings',{phone:v('ph'),partySize:+v('ps')})}
async function rnd(){for(let i=0;i<5;i++){await fetch('/api/waitings',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({phone:'010'+String(Math.floor(Math.random()*1e8)).padStart(8,'0'),partySize:1+Math.floor(Math.random()*4)})})}out('5팀 등록')}
function look(){fetch('/api/waitings/lookup?phone='+v('lp')).then(r=>r.json()).then(out)}
function ord(){post('/api/orders',{tableNo:+v('tn'),items:[{menuId:+v('mi'),qty:+v('q')}],source:'QR'})}
</script>`;

server.listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat().filter(i => i && i.family === 'IPv4' && !i.internal).map(i => i.address);
  console.log(`mock 서버 실행 중 (포트 ${PORT})`);
  ips.forEach(ip => console.log(`  앱 서버 주소: http://${ip}:${PORT}    테스트 페이지: http://${ip}:${PORT}/dev`));
});
