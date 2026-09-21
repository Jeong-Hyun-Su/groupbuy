// 딜 상세 조회 처리량. currentCount 를 DB count 로 읽는 Phase 1 과 Redis 로 읽는 Phase 2 를 비교한다.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const DEAL_ID = __ENV.DEAL_ID || '1';

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
      stages: [
        { target: 1000, duration: '1m' },
        { target: 3000, duration: '2m' },
        { target: 3000, duration: '2m' },
        { target: 0, duration: '30s' },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(99)<100'],   // 설계서 4.1
    http_req_failed: ['rate<0.001'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/api/deals/${DEAL_ID}`);
  check(res, { 'status 200': (r) => r.status === 200 });
}
