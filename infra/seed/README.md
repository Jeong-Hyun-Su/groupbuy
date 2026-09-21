# 시드 데이터

부하테스트와 로컬 개발용. **운영에서는 절대 실행하지 않는다.**

```bash
docker compose -f ../docker-compose.yml up -d
./gradlew :apps:api:bootRun       # Flyway 가 스키마를 만들 때까지 기다린다
psql -h localhost -U groupbuy -d groupbuy -f seed.sql   # 비밀번호: groupbuy
```

| 데이터 | 수량 | 비고 |
|---|---|---|
| 사용자 | 5,000 | id 1~5000 고정. k6 가 `X-User-Id` 로 그대로 쓴다 |
| 판매자 | 10 | |
| 상품 | 100 | 5종 가격대 순환 |
| LT-01 딜 | 1 | id = 1, 정원 200, 최소 10, 티어 10/30/50명 → 10/20/30% |
| 목록용 딜 | 30 | id 101~130, 마감 시각을 1~48시간으로 흩뿌림 |

여러 번 실행해도 안전하다. LT-01 딜(id=1)은 재실행 시 `OPEN` 으로 되돌아가 마감 시각이 2시간 뒤로 갱신되므로,
부하테스트를 반복할 때 이 파일만 다시 돌리면 된다. 참여 기록은 지우지 않으니 완전 초기화가 필요하면:

```sql
TRUNCATE refunds, payments, participations, orders RESTART IDENTITY CASCADE;
```
