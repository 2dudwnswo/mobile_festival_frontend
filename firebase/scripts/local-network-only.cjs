// 에뮬레이터 CLI와 테스트의 외부 TCP 연결을 소켓 생성 전에 거절한다.
const net = require('node:net');
const loopback = new Set(['127.0.0.1', 'localhost', '::1']);
const originalConnect = net.Socket.prototype.connect;
net.Socket.prototype.connect = function (...args) {
  const first = Array.isArray(args[0]) ? args[0] : args;
  const options = typeof first[0] === 'object' ? first[0] : { host: typeof first[1] === 'string' ? first[1] : 'localhost' };
  const host = options.host || options.hostname || 'localhost';
  if (!loopback.has(host)) throw new Error('외부 네트워크 차단: 에뮬레이터 도구는 loopback만 허용합니다');
  return originalConnect.apply(this, args);
};
const originalFetch = globalThis.fetch;
if (originalFetch) globalThis.fetch = (input, ...args) => {
  const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
  if (!loopback.has(url.hostname)) throw new Error('외부 HTTP 차단: 에뮬레이터 전용');
  return originalFetch(input, ...args);
};
