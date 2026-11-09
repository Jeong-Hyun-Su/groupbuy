# 토스 웹훅에 대한 틀린 가정

수정: PR #1 squash 커밋 `65c8f1b` (SCRUM-10)

## 증상

설계 v0.1 은 웹훅을 승인 경로로 쓰려 했고(둘 중 먼저 온 쪽이 처리), 서명 검증 코드는 시크릿을 설정하는 순간 실제 웹훅을 전부 거절하는 방식이었다.

## 발견 경위

2026-09-21 "Phase 1 구현을 설계서·README·코드와 대조하는 점검"에서 토스 공식 문서와 설계 v0.1 의 가정을 대조했다
(설계서 부록 C 에 기록됨). v0.1 설계서의 웹훅 설명은 "서명 검증 후 confirm 과 동일 처리 (둘 중 먼저 온 쪽이 처리)" 였다.

## 틀린 가정

### (a) "웹훅이 승인을 대신할 수 있다"

토스는 가맹점이 승인 API 를 호출해야 결제가 완료된다. 공식 문서가 이 문장을 직접 적지는 않지만,
"결제 인증이 유효한 10분 안에 상점에서 결제 승인 API 를 호출하지 않으면 결제 상태가 EXPIRED 로 변경된다"는 설명과 맞물려 간접적으로 뒷받침된다.

### (b) 서명 검증 방식

기존 `WebhookSignatureVerifier` 는 본문만 HMAC-SHA256 한 값을 base64 로 만들어 헤더 문자열과 비교했다.
공식 문서의 방식은 다음과 같다: `{payload}:{tosspayments-webhook-transmission-time}` 을 보안 키로 HMAC-SHA256 하고,
헤더의 `v1:` 뒤 값을 base64 디코딩해 복수 서명 중 하나와 일치하는지 확인한다.
방식이 달라 시크릿을 설정하면 실제 웹훅을 전부 거절했다.

> 참고: 당시 커밋·설계서·README 는 서명 유무에 대해 다른 서술을 했다. 2026-11-09 에 확인한 공식 문서에서는
> `tosspayments-webhook-signature` 가 웹훅 전반의 헤더로 설명된다. 이 문서는 위에 확인된 사실만 적는다.

## 원인 코드

- `WebhookSignatureVerifier` (수정 때 삭제)
- 승인과 웹훅을 같은 경로로 처리하던 웹훅 컨트롤러

## 재현 테스트

- `PaymentConfirmServiceTest` — '웹훅은 승인하지 않고 PG 조회 결과만 반영한다', 'PG 에 승인된 결제가 없는 웹훅은 아무 일도 일으키지 않는다 — 위조 방어'
- `PaymentFlowTest` — 'confirm 과 webhook 이 동시에 도착해도 승인은 한 번만 일어난다 (R6)'
- `WebhookSignatureVerifierTest` 는 검증기와 함께 삭제됐다

## 수정

웹훅은 본문의 `orderId` 만 꺼내 PG 를 조회하고 그 결과만 반영한다 (`PaymentConfirmService.reconcile`, `PaymentWebhookController`).
본문을 믿지 않으므로 위조 웹훅은 PG 에 승인된 결제가 없으면 아무 일도 일으키지 못한다.

## 근거

[토스페이먼츠 웹훅 이벤트](https://docs.tosspayments.com/reference/using-api/webhook-events) — 웹훅 헤더·서명 검증 방식, `PAYMENT_STATUS_CHANGED` 의 EXPIRED 조건

## 배운 것

외부 연동은 설계 전에 공식 문서로 확인하고, 근거 링크를 ADR 에 남긴다.
