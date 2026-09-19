// 에뮬레이터 시드 데이터 (스펙 v3). 전화번호는 010-0000-00xx 고정 가짜 번호만 쓴다.
export function fixtures(now = Date.now()) {
  const tables = Array.from({ length: 30 }, (_, i) => [`tables/${i + 1}`, {
    table_no: i + 1, status: 'EMPTY', start_time: null, payment_confirmed: false, extended_minutes: 0, total_amount: 0,
  }]);
  tables[0][1] = { ...tables[0][1], status: 'SEATED_PENDING_PAYMENT', start_time: now - 6 * 60_000, total_amount: 25000 };
  tables[1][1] = { ...tables[1][1], status: 'IN_USE', start_time: now - 86 * 60_000, payment_confirmed: true };
  const menus = [['1', '해물파전', 15000], ['2', '떡볶이', 10000], ['3', '어묵탕', 12000], ['4', '음료', 2000]]
    .map(([id, name, price]) => [`menu/${id}`, { name, price }]);
  // 웨이팅: waiting_private/{전화번호} + waiting_public/{publicId} 짝. private.public_id 로 연결한다.
  const samples = [
    { phone: '01000000001', party_size: 2, is_vip: false, status: 'WAITING', minutesAgo: 12, called: null },
    { phone: '01000000002', party_size: 3, is_vip: true, status: 'WAITING', minutesAgo: 8, called: null },
    { phone: '01000000003', party_size: 4, is_vip: false, status: 'NO_SHOW', minutesAgo: 20, called: 4 },
    { phone: '01000000004', party_size: 2, is_vip: false, status: 'CANCELLED', minutesAgo: 30, called: null },
  ];
  const waitings = samples.map((s, i) => {
    const created_at = now - s.minutesAgo * 60_000;
    const publicId = `seed-public-${i + 1}`;
    return {
      privatePath: `waiting_private/${s.phone}`,
      publicPath: `waiting_public/${publicId}`,
      private: { phone: s.phone, party_size: s.party_size, is_vip: s.is_vip, status: s.status,
        called_at: s.called == null ? null : now - s.called * 60_000, created_at, public_id: publicId },
      public: { is_vip: s.is_vip, status: s.status, created_at },
    };
  });
  const orders = [0, 1].map(i => [null, { table_id: 1, menu_id: String(i + 1),
    menu_name: menus[i][1].name, menu_price: menus[i][1].price, quantity: 1,
    added_by: '에뮬레이터 테스트', status: 'PENDING', created_at: now - 5 * 60_000 }]);
  return { tables, menus, waitings, orders };
}
