# groupbuy

마감 시점 최종 인원으로 가격이 확정되는 공동구매 서비스. 정가로 선결제하고, 마감 때 차액을 부분취소한다. 돈을 다루므로 **정합성이 기능보다 먼저다.** 포트폴리오 프로젝트이고, Phase 마다 문제 재현 → 측정 → ADR 을 남긴다.

- 설계(무엇을·왜): `docs/design/공동구매_플랫폼_설계서.md`, `docs/adr/`
- 순서·범위·완료 기준·진행: `docs/plan.md` (설계서 11·13장보다 우선)

## Stack
Kotlin 2.2.0, Spring Boot 3.5.6, JDK 21, JPA, PostgreSQL 16, Flyway, Gradle 8.14.3(멀티모듈, `buildSrc` 컨벤션 플러그인), JUnit5 + mockk, Testcontainers 1.21.4, ArchUnit, k6. Redis·Kafka 는 Phase 2·3 부터.

## Run
- 빌드 + 전체 테스트: `./gradlew build` (Docker 필요. OrbStack)
- 모듈 테스트: `./gradlew :apps:api:test`, `./gradlew :modules:payment:test --tests '*PaymentTest'`
- 아키텍처 규칙: `./gradlew :arch-test:test`
- 로컬 인프라: `cd infra && docker compose up -d` (postgres, redis. Kafka 는 `--profile kafka`)
- 실행: `./gradlew :apps:api:bootRun` (8080), `./gradlew :apps:worker:bootRun` (스케줄러)
- 통합 테스트는 Docker 가 없으면 skip 된다(`disabledWithoutDocker`). **skip 은 통과가 아니다.** 결과에 skipped 가 있으면 원인을 먼저 해결한다

## Layout
- `modules/{common,deal,participation,payment,settlement,realtime}`: 비즈니스 모듈. 모듈 안은 `api → application → domain ← infrastructure`, `domain` 은 Spring 을 모른다
- 모듈 의존 방향: `deal ← participation ← payment`. `settlement`, `realtime` 은 이벤트로만 연결. `arch-test` 가 강제하므로 규칙을 바꾸려면 테스트와 설계서 5.3 을 같이 고친다
- `apps/api`(HTTP, 조립, cross-module 조회), `apps/worker`(스케줄러, 컨슈머)
- Flyway 마이그레이션: `modules/common/src/main/resources/db/migration/`
- 부하 스크립트·시드: `infra/k6/`, `infra/seed/`. 측정 기록: `docs/loadtest/`. Phase 결과: `docs/phases/`

## Invariants
어떤 상황(동시 요청, 장애, 재처리)에서도 지킨다. 건드리는 변경에는 테스트가 있어야 한다.
- R1 확정 참여자 수 ≤ 정원 — 깨지면 재고 이상의 주문
- R2 한 사용자는 한 딜에 최대 1건 — 인원 조작, 할인율 부정 달성
- R3 할인율은 마감 시점 최종 인원으로만 확정, 전원 동일 — 참여 순서에 따른 불공정
- R4 최종 부담액 = 정확히 `정가 × (1 − 확정 할인율)` — 과다 청구, 환불 누락
- R5 무산된 딜의 모든 결제는 전액 환불 — 상품 없이 돈만 받음
- R6 같은 환불은 몇 번 실행돼도 PG 에 한 번만 — 이중 환불
- R7 내부 결제·환불 합계 = PG 기록 — 정산 오류
- R8 마감은 정확히 한 번 — 환불·정산 중복

## Don't
이 저장소에서 실제로 터졌던 결함 유형이다 (`pr-review.yml` 과 같다).
- PG 등 외부 시스템에 부작용을 낸 뒤 예외로 롤백하지 않는다 — 기록이 사라져 보상 근거가 없어진다. 결과값으로 돌려주고, 기록하고, 보상한다
- 상태를 점유(CLOSING 등)한 뒤 후속 처리가 실패했을 때 다시 집어갈 경로 없이 두지 않는다 — 영구 고착
- 외부 API 동작(웹훅 도착 시점, 서명, 멱등성, 주문번호 재사용)을 추측하지 않는다 — 공식 문서로 확인하고 ADR 에 링크를 남긴다
- 실패를 하나로 뭉개지 않는다. 확정 실패 / 결과 불확실 두 갈래로 나눈다 — 불확실을 FAILED 로 못 박으면 받은 돈을 잃는다
- 같은 클래스 안에서 `@Transactional` 메서드를 호출하지 않는다 — 프록시를 타지 않아 트랜잭션이 열리지 않는다
- PG 호출을 DB 트랜잭션 안에 두지 않는다 (Phase 1 의 동기 환불은 의도된 예외, `docs/plan.md` Phase 3 에서 해소) — 커넥션 점유
- `buildSrc` 의 `kotlin.version`·`testcontainers.version` 고정을 지우지 않는다 — 컴파일러 버전 불일치, Docker 29 에서 테스트 skip
- 이미 적용된 Flyway 파일을 수정하지 않는다. 새 버전을 추가한다
- 새 의존성은 이유와 대안을 먼저 제안한다

## Workflow
- 브랜치 `feature/SCRUM-N`, `fix/SCRUM-N`, 문서·설정은 `chore/…`. 커밋 `SCRUM-N: 요약` (한국어)
- Jira 자동화(트리아지·자동 구현·상태 동기화)와 사람 게이트는 `docs/automation.md`. 라벨 `manual` 티켓은 자동 구현하지 않는다. `.github/workflows/` 는 초안만 쓰고 사람이 반영한다
- 측정하는 작업은 **측정 전에 예측**을 `docs/phases/` 에 적고, 수치는 `docs/loadtest/` 원자료에서만 인용한다
- Phase 가 끝나면 결과 문서, ADR 확정, `docs/plan.md` 진행표, 태그 `phase-N`
- "완료"는 테스트 명령과 출력으로 증명한다
