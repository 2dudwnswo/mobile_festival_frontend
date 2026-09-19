export function fixtures(now = Date.now()) {
  const tables = Array.from({ length: 30 }, (_, i) => [`tables/${i + 1}`, {
    table_no: i + 1, status: 'EMPTY', start_time: null, payment_confirmed: false, extended_minutes: 0, total_amount: 0,
  }]);
  tables[0][1] = { ...tables[0][1], status: 'SEATED_PENDING_PAYMENT', start_time: now - 6 * 60_000, total_amount: 25000 };
  tables[1][1] = { ...tables[1][1], status: 'IN_USE', start_time: now - 86 * 60_000, payment_confirmed: true };
  const menus = [['1', '해물파전', 15000], ['2', '떡볶이', 10000], ['3', '어묵탕', 12000], ['4', '음료', 2000]]
    .map(([id, name, price]) => [`menu/${id}`, { name, price }]);
  const waitings = ['WAITING', 'WAITING', 'NO_SHOW'].map((status, i) => [null, {
    phone: `0100000000${i + 1}`, party_size: i + 2, is_vip: i === 1,
    status, created_at: now - (10 - i) * 60_000, called_at: i === 2 ? now - 4 * 60_000 : null,
  }]);
  const orders = [0, 1].map(i => [null, { table_id: 1, menu_id: String(i + 1),
    menu_name: menus[i][1].name, menu_price: menus[i][1].price, quantity: 1,
    added_by: '에뮬레이터 테스트', status: 'PENDING', created_at: now - 5 * 60_000 }]);
  return { tables, menus, waitings, orders };
}
