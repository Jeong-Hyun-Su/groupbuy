# 환불이 한 번 실패하면 딜이 영영 마감되지 않는다

수정: PR #1 squash 커밋 `65c8f1b` (SCRUM-10)

## 증상

판정·환불 중 PG 호출이 실패해 트랜잭션이 롤백되면, 그 딜은 `CLOSING` 에 남고 다시 마감되지 않는다 (R8·R5).

## 발견 경위

2026-09-21 "Phase 1 구현을 설계서·README·코드와 대조하는 점검"에서 찾았다 (설계서 부록 C 에 기록됨).
`DealSettlementProcessor` 의 코드 주석("딜은 CLOSING 에서 되돌아가고 다음 폴링이 재시도한다")을 실제 코드와 대조했다
(수정 전 주석은 `git show 65c8f1b^` 로 확인할 수 있다).
주석과 달리 점유(`OPEN → CLOSING`)는 즉시 커밋돼 롤백 대상이 아니었고, 마감 대상 조회(`findDueToClose`)는 `OPEN` 만 봤으며
`beginClosing` 은 `OPEN` 이 아니면 false 를 돌려줬다. 그래서 롤백 뒤 `CLOSING` 에 남은 딜을 집을 경로가 없었다.
수정 전 README 에도 "1번은 즉시 커밋해야 다른 인스턴스가 이 딜을 건드리지 않는다"고 적혀 있어,
점유가 롤백에서 빠진다는 사실 자체는 문서에도 있었다. 어긋난 것은 "그래서 다시 집힌다"는 주석이다.

## 원인

점유와 판정이 서로 다른 트랜잭션이다. 판정·환불만 롤백되고 `CLOSING` 은 남는다.

- `DealJpaRepository.findDueToClose` — 수정 전 `OPEN` 만 조회
- `DealCommandService.beginClosing` — 수정 전 `OPEN` 이 아니면 false

## 재현 테스트

- `DealClosingFlowTest` — '환불 실패로 롤백된 마감은 CLOSING 에 남고 다음 시도에서 끝난다' (CLOSING 유지 → `findDueToClose` 에 포함 → 재시도로 SETTLED)
- `DealCommandServiceTest` — 'CLOSING 에 남은 딜은 재개할 수 있다 — 환불 롤백 후 다음 폴링이 이어받는다'

## 수정

- `findDueToClose` 가 `OPEN` 과 `CLOSING` 을 함께 조회한다
- `beginClosing` 은 `CLOSING` 을 재개로 보고 true 를 돌려준다
- `finishClosing` 이 딜 행을 잠가 재개 경로의 중복 판정을 막는다 (R8)
- 롤백 전에 PG 취소가 이미 나갔어도 재시도는 같은 Idempotency-Key 를 쓰므로 이중 환불이 없다 (R6)
- Phase 1 은 단일 인스턴스 가정이라 타임아웃 없이 `CLOSING` 을 바로 재개한다.
  다중 인스턴스용 `closing_started_at` 타임아웃과 `SKIP LOCKED` 는 Phase 3 으로 미뤘다 (코드 주석, 설계서 10.3)

## 배운 것

주석이나 문서의 "다시 시도된다"는 보증이 아니다. 상태를 점유한 뒤 후속 처리가 실패했을 때,
그 상태를 다시 집어 가는 조회 조건이 실제로 있는지 코드와 대조해야 한다.
