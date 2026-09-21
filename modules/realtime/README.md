# realtime (Phase 4)

Kafka 이벤트 → Redis Pub/Sub → SSE 팬아웃. 설계서 9.4, 10.4, ADR-08.

- `GET /api/deals/{id}/stream` SSE 엔드포인트, heartbeat, `Last-Event-ID` 재연결
- 딜별 초당 2회 coalesce
- 이벤트 컨슈머로만 동작.
