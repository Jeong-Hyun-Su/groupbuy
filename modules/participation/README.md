# participation

자리 선점, 참여 확정, 취소, 선점 만료. **R1(정원)·R2(중복) 책임 모듈.**

| Phase | 내용 |
|---|---|
| 1 | ✅ `Participation`·`Order` 엔티티, 딜 행 `FOR UPDATE` 잠금 아래 중복·정원 검사 + 선점(TTL 10분) + 정가 주문 생성. 만료·취소된 행은 같은 사용자가 재선점 시 재사용 (UNIQUE(deal_id,user_id)) |
| 2 | Redis Lua 선점(설계서 7.2), 선점 TTL 만료 스캐너, 3종 동시성 구현 비교 |
| 3 | Kafka 이벤트 발행/소비, 자진 취소 |

의존: `deal` (딜 상태·정원 조회). `payment` 에는 의존하지 않는다 — 결제 확정은 이벤트로 수신.

`ParticipantCountProvider` 는 딜 상세 조합(`apps/api` query)이 현재 인원을 읽는 포트.
Phase 1 은 DB count(`DbParticipantCountProvider`), Phase 2 부터 Redis.

| 패키지 | 내용 |
|---|---|
| `domain` | `Participation`(상태 전이·TTL), `Order`(정가 주문·확정 금액·차액), `ParticipationRepository`/`OrderRepository` 포트, `domain.event` |
| `application` | `ParticipationCommandService.participate` — UC-04 1~3단계 한 트랜잭션. `ParticipationProperties`(`groupbuy.participation.reservation-ttl`) |
| `api` | `POST /api/deals/{dealId}/participations` (`X-User-Id`) → 주문번호·금액·선점 만료 시각 |
| `infrastructure` | JPA 어댑터, DB count 프로바이더 |
