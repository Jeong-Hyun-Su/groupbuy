# payment

PG 연동, 결제 승인, 환불 실행, 멱등성. **R4~R6 책임 모듈.**

| Phase | 내용 |
|---|---|
| 1 | ✅ `Payment` 엔티티(멱등 승인), 토스 승인 어댑터, `POST /api/payments/confirm`, 웹훅 수신 + HMAC 서명 검증. ⬜ 마감 트랜잭션 내 동기 환불 |
| 3 | `Refund` 워커(폴링 + SKIP LOCKED), 지수 백오프, Idempotency-Key, 재시도 전 상태 조회, MANUAL_REQUIRED |

`PaymentGateway` 포트 뒤에 토스 어댑터와 테스트용 Fake(WireMock) 를 둔다. 장애 주입 테스트(LT-06)는 Fake 로 한다.

## 구조 (Phase 1)

| 패키지 | 내용 |
|---|---|
| `domain` | `Payment`(READY→APPROVED, 같은 키 재승인은 no-op), `PaymentGateway`·`WebhookSecretProvider` 포트, `PaymentGatewayException`(retryable 구분) |
| `application` | `PaymentConfirmService`(검증 → PG 호출 → 반영), `PaymentApprovalRecorder`(트랜잭션 경계), `WebhookSignatureVerifier` |
| `api` | `POST /api/payments/confirm`, `POST /api/payments/webhook` |
| `infrastructure` | `TossPaymentGateway`, `PaymentProperties`(PG 접속 정보는 어댑터 관심사라 여기 둔다) |

### 멱등성이 걸린 곳

1. `payments.order_no` UNIQUE — 같은 주문에 결제 행이 둘 생기지 않는다
2. `findByOrderNoForUpdate` 행 잠금 — confirm/webhook 동시 도착을 직렬화
3. `Payment.approve` — 같은 결제 키면 아무것도 바꾸지 않고 `false` 반환

### 트랜잭션 경계

PG 호출은 트랜잭션 **밖**이다. 승인 응답(수 초)만큼 DB 커넥션을 붙잡으면 커넥션 풀이 먼저 마른다.
대신 "PG 는 승인했는데 우리 DB 는 모르는" 창이 생기는데, Phase 1 은 웹훅 재수신으로 메운다.
Phase 3 에서 승인 전 `READY` 행을 먼저 커밋해 고아 승인을 추적 가능하게 만든다.
