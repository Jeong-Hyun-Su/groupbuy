# 부하테스트 (설계서 13장)

```bash
# 설치: https://grafana.com/docs/k6/latest/set-up/install-k6/
k6 run -e BASE_URL=http://localhost:8080 -e DEAL_ID=1 participation-burst.js
```

| 스크립트 | 시나리오 | Phase |
|---|---|---|
| `participation-burst.js` | LT-01 단일 딜 참여 폭주 (정원 200, VU 5,000) | 1→2 |
| `deal-detail.js` | 딜 상세 조회 처리량 | 1→2 |

결과는 `docs/loadtest/YYYY-MM-DD-LT-01.md` 에 13.2 양식으로 기록한다.
k6 는 별도 머신(또는 최소한 별도 프로세스 CPU 격리)에서 실행해 생성기 병목을 피한다.
