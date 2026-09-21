# groupbuy — 실시간 공동구매 플랫폼

일정 시간 동안 참여자를 모아, 모인 인원에 따라 할인율이 올라가고, **마감 시점의 최종 인원으로 가격이 확정**되는 공동구매 서비스.

이 프로젝트가 증명하려는 것: 결제·정산 정합성, 대규모 트래픽에서의 동시성 제어, 실시간 전파, 이벤트 기반 아키텍처, 운영(k8s·CI/CD).
설계 전문은 [docs/design/공동구매_플랫폼_설계서.md](docs/design/공동구매_플랫폼_설계서.md), 주요 결정은 [docs/adr](docs/adr).

## 상태

| Phase | 목표 | 상태 |
|---|---|---|
| 1 | MVP — 도메인 규칙이 코드로 표현된 동작하는 시스템 | 🔨 진행 중 |
| 2 | 동시성 — 정원 초과 0건 | ⏳ |
| 3 | 결제 정합성 — 돈이 안 맞는 경우 0건 | ⏳ |
| 4 | 실시간 — 만 명이 같은 게이지를 본다 | ⏳ |
| 5 | 운영 — 정산·검색·k8s·CD | ⏳ |

CI(`.github/workflows/ci.yml` — 빌드·테스트·ArchUnit)는 Phase 1 부터 돈다. Phase 5 는 배포(CD)다.

## 시작하기

```bash
# 1. 빌드 + 테스트 (Docker 없어도 OK — Testcontainers 테스트만 자동 skip. 첫 실행은 의존성 다운로드로 수 분 소요)
./gradlew build

# 2. 실행 (PostgreSQL 필요)
cd infra && docker compose up -d && cd ..
./gradlew :apps:api:bootRun       # http://localhost:8080
./gradlew :apps:worker:bootRun    # 스케줄러

# 3. 아키텍처 규칙만 검사
./gradlew :arch-test:test
```

요구사항: JDK 21 (툴체인이 자동 설치하므로 로컬에 없어도 무관). Docker 는 실행과 통합 테스트에 필요. Gradle 은 wrapper 가 받는다.

> **빌드 메모 (2026-09-16 검증)**
> - Kotlin 플러그인 버전은 `buildSrc/build.gradle.kts` 한 곳에서만 관리한다. Spring Boot BOM 이 강제하는 `kotlin.version` 은
>   컨벤션 플러그인이 플러그인 버전으로 덮어쓴다 — 이 둘이 어긋나면 컴파일러 도구 버전이 갈려 빌드가 깨진다.
> - Docker 가 없으면 Testcontainers 기반 테스트(`apps/api`)는 **건너뛴다**. CI(ubuntu-latest) 에서는 항상 실행된다.
>   로컬에서 통합 테스트까지 돌리려면 Docker Desktop 또는 colima 를 설치하라.

## 구조

```
groupbuy/
├── apps/
│   ├── api/            HTTP API + SSE. 모듈 조립과 cross-module 읽기 모델(query)만 둔다
│   └── worker/         스케줄러 + Kafka 컨슈머 (Phase 3~)
├── modules/
│   ├── common/         에러, 시간, 금액 계산, 이벤트 마커, Flyway 마이그레이션
│   ├── deal/           딜 애그리거트, 상태 전이, 마감 판정 ← 도메인 핵심
│   ├── participation/  선점·참여·취소, Redis 원자 연산 (Phase 2)
│   ├── payment/        PG 연동, 승인, 환불, 멱등성
│   ├── settlement/     정산, 원장 대조 (Phase 5)
│   ├── realtime/       Redis Pub/Sub → SSE (Phase 4)
│   └── search/         Elasticsearch (Phase 5)
├── arch-test/          ArchUnit — 모듈 의존 방향·레이어 규칙 강제
├── infra/              docker-compose, k6 부하 스크립트, k8s (Phase 5)
└── docs/
    ├── design/         설계서
    ├── adr/            아키텍처 결정 기록
    └── loadtest/       부하테스트 결과 (13.2 양식)
```

### 모듈 의존 규칙 (arch-test 가 강제)

```
          common
            ▲
   ┌────────┼─────────┐
 deal ◄ participation ◄ payment       settlement · realtime · search
   ▲        ▲            ▲             (이벤트 컨슈머 전용, 직접 호출 없음)
   └────────┴────────────┴── apps/api, apps/worker (조립·조합)
```

모듈 내부: `api → application → domain ← infrastructure`. `domain` 은 Spring 을 모른다.

## Phase 1 체크리스트

- [x] 멀티모듈 스켈레톤, convention plugin, ArchUnit 규칙
- [x] Flyway V1 (deals, deal_tiers, participations, orders, payments, refunds)
- [x] `Deal` 애그리거트 + 상태 전이 + 마감 판정 + 테스트 (R1, R3, R5, R8)
- [x] `DiscountPolicy` 티어 규칙 + 경계 테스트
- [x] `Money` 계산 규칙 + 테스트
- [x] 판매자 딜 등록/취소 API, 딜 상세 조회 API
- [x] `Participation` 엔티티, `Order` 엔티티, DB `FOR UPDATE` 카운팅 참여 API (`POST /api/deals/{id}/participations`)
- [x] 내 참여 목록 API (`GET /api/me/participations`), 딜 상세의 `myParticipation`
- [x] 토스페이먼츠 승인 어댑터, `POST /api/payments/confirm`, 웹훅 (`POST /api/payments/webhook`, PG 조회로 검증)
- [x] `DealClosingOrchestrator` (worker) — 마감 점유 + 판정 + 동기 환불
- [x] 선점 만료 스캐너 (`ReservationExpiryService`, 10초 폴링)
- [x] 딜 목록 API (DB 기반, `GET /api/deals?sort=closing_soon|latest&q=&status=&page=&size=`)
- [x] 승인 후 확정 불가 건 전액 환불 (ADR-07), 롤백된 마감의 재개
- [ ] Spring Security + JWT (`X-User-Id` 헤더 대체), OpenAPI(springdoc), JaCoCo
- [ ] React 클라이언트 (목록/상세/참여/마이페이지)
- [x] 사용자·판매자·상품 시드 데이터 (`infra/seed/seed.sql`, 사용자 5,000명 + LT-01 딜)
- [ ] LT-01 실행 → `docs/loadtest/` 에 붕괴 지점 기록 → Phase 2 동기

## 설계 핵심 (요약)

- 할인율은 **마감 시점 최종 인원**으로만 확정한다. 참여 시점 할인율은 예상값이다 (ADR-002)
- 정가 **선결제 후 마감 시 차액 부분취소**. 환불 파이프라인이 핵심 품질이다 (ADR-003)
- 마감 순간 결제 중이던 참여는 인원에서 **제외**하고, 이후 승인되면 전액 취소한다 (ADR-007)
- 마감은 DB 폴링 + `SKIP LOCKED` + `CLOSING` 중간 상태로 **정확히 한 번** 실행한다 (ADR-005)
- Phase 1 의 참여 동시성은 **딜 행 `FOR UPDATE` 잠금**으로 직렬화한다. 의도된 병목이며 LT-01 이 Phase 2(Redis Lua, ADR-004)의 동기가 된다

## 결제 연동

`groupbuy.payment.toss.*` 로 설정한다. 키는 환경변수로 주입한다.

```bash
export TOSS_SECRET_KEY=test_sk_...        # 토스 개발자센터 테스트 시크릿 키
```

토스는 **우리 서버가 승인 API 를 불러야** 결제가 완료된다. 그래서 두 경로의 역할이 다르다.

- `confirm` — 클라이언트 리다이렉트 후. 사전 검증(금액·선점 만료·딜 상태) → PG 승인 → 결과 반영
- `webhook` — 승인하지 않는다. `orderId` 로 **PG 를 조회한 결과**만 반영한다. 승인 직후 프로세스가 죽은 경우의 복구 경로다.
  토스 결제 웹훅에는 서명이 없어서 본문은 믿지 않는다 — 조회 결과만 쓰면 위조 웹훅은 아무 일도 못 한다

둘이 겹쳐도 결제 행 잠금 + `Payment.approve` 멱등으로 한 번만 반영된다 (R6). PG 호출은 트랜잭션 밖이다.

| 상황 | 처리 |
|---|---|
| 카드 거절 등 확정 실패 | PG 조회로 미승인 확인 후 `payments.status = FAILED`. 선점은 TTL 만료로 해제 |
| 타임아웃·5xx 등 결과 불확실 | PG 조회 → 승인돼 있으면 반영, 아니면 아무것도 확정하지 않고 `PAYMENT_PENDING` |
| 승인됐는데 선점 만료·딜 마감 (ADR-07) | 결제는 `APPROVED` 로 남기고 즉시 전액 환불. 승인 기록을 롤백하면 환불 근거가 사라진다 |

## 부하테스트 준비

```bash
psql -h localhost -U groupbuy -d groupbuy -f infra/seed/seed.sql   # 사용자 5,000명 + 정원 200 딜(id=1)
k6 run -e BASE_URL=http://localhost:8080 -e DEAL_ID=1 infra/k6/participation-burst.js
```

자세한 내용은 [infra/seed/README.md](infra/seed/README.md). 결과는 `docs/loadtest/` 에 설계서 13.2 양식으로 남긴다.

## 마감 처리

`apps/worker` 가 5초마다 마감 대상을 폴링한다. 딜 하나의 마감은 네 단계다.

1. **점유** — `OPEN → CLOSING`. 행 잠금 + 상태 전이로 한 인스턴스만 성공한다 (R8). 여기서 신규 참여도 막힌다
2. **확정 인원 집계** — `CONFIRMED` 만 센다. 결제 중이던 `RESERVED` 는 제외 (ADR-007)
3. **판정** — 확정 인원의 티어로 할인율을 정한다. 전원 동일 (R3)
4. **환불** — 성사면 차액, 무산이면 전액 (R4, R5)

트랜잭션은 둘로 나뉜다. 1번은 즉시 커밋해야 다른 인스턴스가 이 딜을 건드리지 않는다.
2~4번은 `DealSettlementProcessor` 가 한 트랜잭션으로 묶어, PG 환불 호출이 하나라도 실패하면 판정까지 되돌린다.
그 딜은 `CLOSING` 에 남고 다음 폴링이 재개한다. 이미 나간 PG 취소는 같은 `Idempotency-Key` 로 재요청되므로 이중 환불은 없다 (R6).

> **Phase 1 의 의도된 한계** — 4번이 동기다. 참여자 100명이면 PG 호출 100번이 한 트랜잭션에 묶여
> 그동안 DB 커넥션을 붙잡는다. LT-04 로 이 붕괴 지점을 측정해 Phase 3(환불 워커 + 지수 백오프)의 근거로 삼는다.
> 부분 성공을 허용하지 않는 것은 선택이다. 일부만 환불된 채 `SUCCEEDED` 로 굳으면 R4·R5 가 깨진다.

## 임시 인증

JWT 도입 전까지 `X-User-Id`(구매자), `X-Seller-Id`(판매자) 헤더로 호출자를 식별한다. 시드 데이터 작업과 함께 정리한다.
- 모듈러 모놀리스로 시작하고, api/worker 배포만 분리한다 (ADR-001, 009)
