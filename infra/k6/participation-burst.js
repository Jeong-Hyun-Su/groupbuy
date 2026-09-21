// LT-01: 단일 딜 참여 폭주. 정원 200 인 딜에 5,000 VU 가 동시에 참여를 시도한다.
// 검증: 정확히 200 건 성공, 나머지는 409 DEAL_FULL. 정원 초과·중복 0건은 테스트 후 DB 쿼리로 확인.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const DEAL_ID = __ENV.DEAL_ID || '1';

const reserved = new Counter('participation_reserved');
const full = new Counter('participation_full');
const duplicate = new Counter('participation_duplicate');
const otherError = new Counter('participation_other_error');
const reserveLatency = new Trend('reserve_latency', true);

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 5000,
      iterations: 5000,     // VU 당 1회 = 서로 다른 사용자 5,000 명
      maxDuration: '2m',
    },
  },
  thresholds: {
    'http_req_failed{expected:true}': ['rate<0.01'],
    reserve_latency: ['p(99)<200'],           // 설계서 4.1
  },
};

export default function () {
  const userId = __VU;   // VU 번호를 사용자 ID 로. 사용자 5,000 명은 사전 시드 필요
  const res = http.post(
    `${BASE_URL}/api/deals/${DEAL_ID}/participations`,
    null,
    { headers: { 'X-User-Id': String(userId) }, tags: { expected: 'true' } },
  );
  reserveLatency.add(res.timings.duration);

  if (res.status === 200 || res.status === 201) {
    reserved.add(1);
  } else if (res.status === 409) {
    const body = res.json();
    if (body.code === 'DEAL_FULL') full.add(1);
    else if (body.code === 'DUPLICATE_PARTICIPATION') duplicate.add(1);
    else otherError.add(1);
  } else {
    otherError.add(1);
  }

  check(res, { 'status is 2xx or 409': (r) => r.status < 300 || r.status === 409 });
}

// 테스트 후 정합성 검증 (psql):
//   select count(*) from participations where deal_id = 1 and status in ('RESERVED','CONFIRMED');  -- = 200
//   select deal_id, user_id, count(*) from participations group by 1,2 having count(*) > 1;         -- = 0 rows
