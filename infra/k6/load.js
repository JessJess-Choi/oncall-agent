// 일정한 초당 요청 수로 트래픽을 만든다. 메트릭 기준선이 안정적이어야 장애 구간이 드러난다.
import http from 'k6/http';

const BASE = __ENV.BASE_URL || 'http://order-api:8080';
const COUPONS = ['WELCOME10', 'WELCOME10', 'VIP20', ' vip20 '];
// 만료 쿠폰은 드물게 섞는다 (평소 노이즈: Unknown coupon 경고, 초당 0.25건 정도).
const EXPIRED_RATIO = 0.01;

function steady(rate, exec) {
  return {
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration: '24h',
    preAllocatedVUs: rate * 2,
    maxVUs: rate * 20,
    exec,
  };
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    create: steady(25, 'createOrder'),
    // 고객별 조회가 충분히 많아야 인덱스가 사라졌을 때 동시 순차 탐색이 서로 경합해 지연이 드러난다.
    recent: steady(10, 'recentOrders'),
    lookup: steady(5, 'getOrder'),
    catalog: steady(5, 'listProducts'),
  },
};

function between(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}

export function createOrder() {
  const body = { customerId: between(1, 100000), productId: between(1, 100), quantity: between(1, 3) };
  if (Math.random() < EXPIRED_RATIO) {
    body.couponCode = 'EXPIRED2024';
  } else if (Math.random() < 0.6) {
    body.couponCode = COUPONS[between(0, COUPONS.length - 1)];
  }
  http.post(`${BASE}/orders`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    timeout: '10s',
  });
}

export function recentOrders() {
  http.get(`${BASE}/orders?customerId=${between(1, 100000)}`, { timeout: '10s' });
}

export function getOrder() {
  // 일부는 없는 ID라서 404가 난다 (평소 노이즈).
  http.get(`${BASE}/orders/${between(1, 4200000)}`, { timeout: '10s' });
}

export function listProducts() {
  http.get(`${BASE}/products`, { timeout: '10s' });
}
