# payment

PG 연동, 결제 승인, 환불 실행, 멱등성. **R4~R6 책임 모듈.**

| Phase | 내용 |
|---|---|
| 1 | ✅ `Payment` 엔티티(멱등 승인), 토스 승인 어댑터, `POST /api/payments/confirm`, 웹훅 수신(PG 조회로 검증), 마감 트랜잭션 내 동기 환불, 승인 후 확정 불가 건 전액 환불(ADR-07) |
| 3 | `Refund` 워커(폴링 + SKIP LOCKED), 지수 백오프, Idempotency-Key, 재시도 전 상태 조회, MANUAL_REQUIRED |

`PaymentGateway` 포트 뒤에 토스 어댑터와 테스트용 Fake(WireMock) 를 둔다. 장애 주입 테스트(LT-06)는 Fake 로 한다.

## 구조 (Phase 1)

| 패키지 | 내용 |
|---|---|
| `domain` | `Payment`(READY→APPROVED, 같은 키 재승인은 no-op), `PaymentGateway` 포트, `PaymentGatewayException`(retryable 구분) |
| `application` | `PaymentConfirmService`(`confirm`: 검증 → PG 승인 → 반영 / `reconcile`: PG 조회 → 반영), `PaymentApprovalRecorder`(트랜잭션 경계), `RefundService` |
| `api` | `POST /api/payments/confirm`, `POST /api/payments/webhook` |
| `infrastructure` | `TossPaymentGateway`, `PaymentProperties`(PG 접속 정보는 어댑터 관심사라 여기 둔다) |

### 멱등성이 걸린 곳

1. `payments.order_no` UNIQUE — 같은 주문에 결제 행이 둘 생기지 않는다
2. `findByOrderNoForUpdate` 행 잠금 — confirm/webhook 동시 도착을 직렬화
3. `Payment.approve` — 같은 결제 키면 아무것도 바꾸지 않고 `false` 반환

### 트랜잭션 경계

PG 호출은 트랜잭션 **밖**이다. 승인 응답(수 초)만큼 DB 커넥션을 붙잡으면 커넥션 풀이 먼저 마른다.
대신 "PG 는 승인했는데 우리 DB 는 모르는" 창이 생기는데, 두 군데서 메운다.
- 승인 호출이 실패로 보이면(타임아웃, `ALREADY_PROCESSED_PAYMENT`) **PG 조회로 재확인**한 뒤에만 실패로 기록한다
- 웹훅(`reconcile`)이 PG 조회 결과를 반영한다. 토스는 결제 웹훅에 서명을 주지 않으므로 본문은 `orderId` 말고 믿지 않는다

### 승인했는데 참여를 확정할 수 없을 때 (ADR-07)

선점 만료·마감 직후에 승인된 결제. 승인 **전** `ensureConfirmable` 로 걸러내고, 그 사이를 빠져나간 건은
`confirmByOrder` 가 예외가 아니라 `REJECTED` 를 돌려준다 → 결제는 `APPROVED` 로 커밋 → 트랜잭션 밖에서 전액 환불.
예외로 처리하면 승인 기록이 롤백돼 **환불할 근거 자체가 사라진다**.
Phase 3 에서 승인 전 `READY` 행을 먼저 커밋해 고아 승인을 추적 가능하게 만든다.
