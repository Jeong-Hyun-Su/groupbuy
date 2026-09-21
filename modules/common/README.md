# common

모든 모듈이 의존하는 기반. **비즈니스 규칙은 여기 두지 않는다.**

| 패키지 | 내용 |
|---|---|
| `domain` | `AggregateRoot` (도메인 이벤트 수집), `Money` 계산 유틸 |
| `error` | `ErrorCode`, `DomainException` — API 에러 응답 표준의 원천 |
| `time` | `TimeProvider` — `Instant.now()` 직접 호출 금지. 테스트에서 시간을 고정하기 위함 |
| `event` | `DomainEvent` 마커, Outbox 엔티티(Phase 3) |
| `resources/db/migration` | Flyway 마이그레이션. 전 모듈의 스키마를 한 곳에서 관리 |
