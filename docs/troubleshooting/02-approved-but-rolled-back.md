# PG 는 돈을 받았는데 DB 에는 결제 행이 없다

수정: PR #1 squash 커밋 `65c8f1b` (SCRUM-10)

## 증상

선점 만료·마감 직후에 승인된 결제가 승인 기록 없이 사라져, 환불할 근거가 없어진다 (R5).

## 발견 경위

2026-09-21 "Phase 1 구현을 설계서·README·코드와 대조하는 점검"에서 찾았다 (설계서 부록 C 에 기록됨).
설계서 ADR-07 과 UC-04 5b("승인 직전 딜이 마감됨 → 승인 취소(전액)")를 구현과 대조했다.
설계는 있었지만 구현이 없었다. 수정 커밋 메시지는 이를 "R5 위반, ADR-07 미구현" 으로 적었다.

## 원인

`Participation.confirm` 이 확정 불가 시 예외를 던졌고, 그 예외가 승인 기록 트랜잭션까지 롤백했다.

- `ParticipationConfirmService.confirmByOrder`
- `PaymentApprovalRecorder.record`

## 확정과 마감 점유를 같은 행 잠금으로 직렬화한 이유

확정(`confirmByOrder`)과 마감 점유(`beginClosing`)가 같은 딜 행 잠금(`findByIdForUpdate`)을 쓰면 둘의 순서가 하나로 정해진다.
마감보다 먼저 커밋되면 확정 인원에 들어가고, 늦으면 `CLOSING` 을 보고 `REJECTED` 가 된다.
잠그지 않으면 "마감 집계 직후에 CONFIRMED 가 된" 참여가 생겨 환불도 정산도 받지 못한다 (`ParticipationConfirmService` 주석).

## 재현 테스트

- `ParticipationConfirmServiceTest` — '선점이 만료됐으면 예외 없이 REJECTED 다 — 승인 기록을 롤백시키면 안 된다', '마감 점유 뒤에 도착한 승인은 REJECTED 다 (ADR-07)'
- `PaymentConfirmServiceTest` — '승인 직후 확정이 거부되면 승인 기록은 남기고 전액 환불한다 (R5)', '선점이 만료된 주문은 PG 승인을 호출하지 않는다 (ADR-07)'

## 수정

- `confirmByOrder` 가 예외 대신 `ConfirmOutcome.REJECTED` 를 반환한다
- `record` 가 결제를 `APPROVED` 로 커밋한 뒤 refundRequired 를 돌려주고, 호출자가 트랜잭션 밖에서 전액 환불한다
- 승인 전 `ensureConfirmable` 사전 검사로 대부분은 승인 자체를 막는다 ("승인이 없으면 환불도 없다")

## 배운 것

외부 시스템에 부작용이 생긴 뒤에는 예외로 롤백하지 않는다.
결과값으로 돌려주고, 기록을 남긴 뒤, 보상한다.
