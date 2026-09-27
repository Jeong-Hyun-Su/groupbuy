# 재개 계획 (2026-09-28)

설계서 v0.2 의 5단계 계획(약 13주)을 **Phase 마다 짧은 마일스톤**으로 다시 자른 것이다. 설계의 "무엇을·왜"는 [설계서](design/공동구매_플랫폼_설계서.md)와 [ADR](adr/)이 기준이고, 이 문서는 **순서·범위·완료 기준·자를 순서·진행 상태**만 다룬다. 설계서 11장(일정)과 13장(부하 규모)보다 이 문서가 우선한다.

## 원칙

- Phase 마다 **동작하는 시스템 + 측정 기록(`docs/loadtest/`) + ADR 확정 + 결과 문서(`docs/phases/`)** 를 남기고 태그 `phase-N` 을 단다
- 문제를 먼저 재현한다. **측정 전에 예측을 적고**, 결과가 예측과 다르면 다르다고 쓴다
- 불변식 R1~R8 은 검증 SQL 이나 테스트로 증명한다. 수치는 원자료에서만 인용한다
- 측정 환경(머신, 컨테이너 자원 한도, DB·풀 설정, k6 설정)을 매번 적는다. 시나리오당 3회, 중앙값
- 기간을 넘기면 늘리지 않고 **자를 순서**대로 자른다
- 변경은 작성자가 설명할 수 있어야 머지한다. Claude 자동 구현(`jira-executor`)은 재개 기간 동안 끈다

## 범위에서 뺀 것

| 항목 | 이유 | 대체 |
|---|---|---|
| React 클라이언트 | 증명 대상(정합성·동시성·운영)과 무관하고 비용이 크다 | springdoc(Swagger UI) + `http/` 요청 파일로 E2E 시나리오. Phase 4 데모는 `EventSource` HTML 한 장 |
| Elasticsearch | 도메인 필연성이 가장 약하다 (설계서 14장 절단 순서 1번) | PostgreSQL 목록·검색 유지 — [ADR-012](adr/012-search-on-postgresql.md) |
| AWS (기본값) | 비용·시간 | 로컬 k3d. 시간이 남으면 EC2 |

## Jira 연결

이슈는 Jira `SCRUM` 프로젝트에서 관리한다. Phase = 에픽이다: Phase 1 `SCRUM-5`, 2 `SCRUM-6`, 3 `SCRUM-7`, 4 `SCRUM-8`, 5 `SCRUM-9`.
브랜치 `feature/SCRUM-N-요약`(문서·설정은 `chore/…`), 커밋·PR 제목 `SCRUM-N: 요약`. Phase 3~5 티켓은 앞 Phase 가 끝날 때 쪼갠다.

Phase 1 티켓:

| 티켓 | 내용 |
|---|---|
| SCRUM-23 | 재개 준비 (PR #3) |
| SCRUM-24 | G2 |
| SCRUM-25 | G10 |
| SCRUM-26 | G3 |
| SCRUM-27 | G5 |
| SCRUM-12 | 선점 만료 상한 |
| SCRUM-13 | JWT |
| SCRUM-14 | springdoc·JaCoCo |
| SCRUM-28 | E2E 요청 파일 |
| SCRUM-11 | 트러블슈팅 기록 |
| SCRUM-16 | LT-01 |

Phase 2 티켓: SCRUM-17 ~ 22.

## Phase 별 계획

### Phase 1 마무리 (약 5일)

| 항목 | 내용 |
|---|---|
| 범위 | ① G2·G3·G10 재현 테스트 → 수정 ② G5 테스트 정정 ③ Spring Security + JWT (`X-User-Id` 대체. 부하 테스트용 토큰 발급 경로는 `loadtest` 프로파일로 한정) ④ springdoc ⑤ JaCoCo ⑥ `http/` E2E 시나리오 ⑦ LT-01 실행·기록 + 커넥션 풀 10/30/50 비교 |
| 완료 기준 | E2E 시나리오(생성 → 참여 → 결제 → 마감 → 환불) 통과 · 도메인 커버리지 80% 리포트 · LT-01 기록 1건(무엇이 먼저 무너졌고 왜) · G2·G3·G10 테스트 통과 · 설계서 4.2 "주인 없는 돈" 조회 0건 |
| 자를 순서 | 풀 크기 비교 → JWT (ADR 로 "인증은 증명 대상 아님" 기록 후 헤더 유지) |

### Phase 2 — 동시성: 정원 초과 0건 (1주)

| 항목 | 내용 |
|---|---|
| 범위 | Redis Lua 선점 (v0.2 #4 만료 시각 `min(TTL, 마감)`, #5 카운터 분리, #6 Lua 의 `close_at` 검사) · 같은 인터페이스로 **보호 없음 / DB `FOR UPDATE` / Redis 분산락 / Lua** 비교 (프로파일 전환. "보호 없음"으로 정원 초과를 먼저 재현) · Redis 유실 탐지·보상 (#10) · 200 스레드 동시성 테스트 · LT-01 재측정, LT-02 |
| 완료 기준 | 부하 후 `COUNT(RESERVED, CONFIRMED) <= capacity` 항상 참 · 후보별 TPS·p99·정합성 비교표 · ADR-004 확정 |
| 자를 순서 | LT-02 → 분산락 후보 → 정합 복구 관리 명령 |

### Phase 3 — 결제 정합성: 돈이 안 맞는 경우 0건 (2주)

| 항목 | 내용 |
|---|---|
| 범위 | Outbox + Kafka + 멱등 컨슈머 (#8) · 마감 `SKIP LOCKED` 다중 인스턴스 · 환불 워커 (지수 백오프, DLQ, MANUAL_REQUIRED — G4 해소) · 환불 멱등 자연 키 확인 (#12) · 자진 취소 + 마감선 (ADR-011) · OPEN 강제 중단 (#11) · **PG 장애 주입 F1~F6** · 포스트모템 1편 (`docs/postmortem/`) |
| 완료 기준 | 100명 딜 마감 중 워커 kill → 재기동 후 100건 완료, 이중 환불 0 · PG 타임아웃 30% 에서 최종 완료율 100% · 인스턴스 3대에서 마감 1회 · F1~F6 결과표 (승인 합계 = 반영 합계) · ADR-005·006·007·011 확정 |
| 자를 순서 | 자진 취소 → LT-04 규모 → F6(벌크헤드) → DLQ 운영 API |

PG 장애 주입 (WireMock 기준. 토스 응답 형태를 흉내):

| ID | 장애 | 기대 최종 상태 |
|---|---|---|
| F1 | 승인 지연 (read timeout 초과) | 결과 불확실 → PG 조회로 확정 |
| F2 | 5xx 30% | 재시도·조회로 대부분 승인, 나머지 FAILED. 이중 승인 0 |
| F3 | **승인 후 응답 유실** (PG 는 승인, 연결 끊김) | 조회 또는 웹훅으로 APPROVED 반영. 가장 중요 |
| F4 | PG 완전 다운 | 빠른 실패, 복구 후 정상화 (서킷브레이커 도입 여부는 이때 결정) |
| F5 | 승인 직후 우리 서버 kill | 재기동 후 웹훅·조회로 복구 |
| F6 | F1 중 다른 API 영향 | 벌크헤드 on/off 의 딜 상세 p99 비교 |

### Phase 4 — 실시간 (1주)

| 항목 | 내용 |
|---|---|
| 범위 | SSE · Redis Pub/Sub 인스턴스 팬아웃 · 초당 2회 병합 · `Last-Event-ID` 재연결 · `EventSource` 데모 HTML |
| 완료 기준 | 인스턴스 2대에서, 노트북에서 가능한 연결 수(먼저 측정해 적는다)로 전파 p95 · 1대 종료 시 재연결·누락 없음 · ADR-008 확정 |
| 자를 순서 | 병합 on/off 비교 → 연결 수 상한 탐색 |

### Phase 5 — 운영 축소 (약 1.5주)

| 항목 | 내용 |
|---|---|
| 범위 | 일 정산 (Spring Batch 후보 — 재실행·chunk 재시작 멱등) · 원장 대조 + **의도적 불일치 주입** (R7) · k3d + api/worker 분리 + HPA · CD (GHCR → rollout, 실패 시 롤백) · Grafana 대시보드 |
| 완료 기준 | 정산 = Σ결제 − Σ환불 − 수수료 · 주입한 불일치 100% 탐지 · HPA 확장·축소 확인 · `main` 머지 후 자동 배포 · ADR-009·010 확정 |
| 자를 순서 | AWS → 커스텀 메트릭 HPA → 운영 화면 |

## 부하 규모 규칙

설계서 13.1 의 규모(VU 5,000, VU 10,000, SSE 10,000, 10,000 TPS)는 앱·DB·k6 가 한 노트북에서 도는 환경에서 재현할 수 없다. 앞으로는:

- VU 수가 아니라 **요청 N건 / 초당 N건(arrival-rate)** 으로 정의한다
- 목표는 절대 수치가 아니라 **무너지는 지점과 원인**이다 (설계서 4.1 의 메모와 같다)
- 각 LT 를 실행하기 전에 이 환경 기준 규모를 `docs/loadtest/` 기록 첫 줄에 적는다
- k6 timeout 은 서버 최대 대기보다 길게 둔다. 클라이언트 timeout 을 서버 실패로 세면 정합성 검증이 거짓 불일치를 낸다

## 발견 목록 (2026-09-28 점검)

| # | 내용 | 상태 |
|---|---|---|
| G1 (SCRUM-23) | Testcontainers 1.21.3 + Docker 29 → 통합 테스트 17개가 **Docker 가 떠 있어도 skip** (`disabledWithoutDocker`) | ✅ 1.21.4 고정으로 해결. 로컬 17개 실행·통과 확인 |
| G2 (SCRUM-24) | 선점 만료 ↔ 결제 확정 경쟁: `ReservationExpiryService` 는 딜 락·`@Version` 없이 RESERVED 를 읽어 `expire()` 한다. `confirmByOrder` 는 `now` 를 **딜 락을 기다리기 전에** 잡으므로, 락 대기가 길면 만료 시각을 넘겨 확정할 수 있다. 그 사이 스캐너가 RESERVED 로 읽었다면 확정 커밋 뒤 행 락이 풀리는 대로 EXPIRED 로 덮어쓴다(READ COMMITTED). 결과: 결제는 APPROVED 인데 참여는 빠지고 환불도 없다 (설계서 4.2 "주인 없는 돈") | 확인 필요 → Phase 1 (재현 테스트 먼저) |
| G3 (SCRUM-26) | 카드 거절(`FAILED`) 뒤 같은 주문으로 재결제가 승인되면 `approve` 가 상태 전이 예외 → 승인 기록 롤백. 웹훅도 같은 예외를 삼킨다. 토스의 orderId 재사용 규칙을 **공식 문서로 확인** 후 판단 | 확인 필요 → Phase 1 |
| G4 | 환불 실패 시 FAILED 딜·미정산 SUCCEEDED 딜을 다시 집는 경로 없음 | Phase 3 환불 워커 |
| G5 (SCRUM-27) | `PaymentFlowTest` 의 "confirm 과 webhook 동시 도착" 테스트가 실제로는 confirm 만 호출 (Fake 의 조회가 null) | Phase 1 |
| G6 | 설계 v0.2 와 코드 불일치: 만료 시각(#4), SETTLED 의미(#9), OPEN 강제 중단(#11) | Phase 2·3·5 |
| G7 | 부하 규모 비현실 | 위 "부하 규모 규칙" |
| G8 (SCRUM-23) | 워크플로 3개가 참조하는 `CLAUDE.md` 부재 | ✅ 추가 |
| G9 (SCRUM-23) | 빈 `modules/search` | ✅ 제거, ADR-012 |
| G10 (SCRUM-25) | `PaymentApprovalRecorder.lockOrCreate` 의 "insert 경쟁은 UNIQUE 가 정리한다" 복구 경로가 동작하지 않는다. UNIQUE 위반 뒤 같은 트랜잭션에서 다시 조회하면 Hibernate 세션이 깨져 있다(`HHH000099 AssertionFailure`, PostgreSQL 은 트랜잭션도 abort). `PaymentFlowTest` 동시 8건 중 6건이 이 경로로 오류를 냈다(테스트 로그). 승인은 1건만 기록돼 돈은 맞지만, 경쟁에서 진 요청은 500 을 받는다. 테스트가 `errors` 를 허용해 가려져 있었다 | 확인됨 → Phase 1 (행을 승인 전에 별도 트랜잭션으로 먼저 만들거나 `INSERT … ON CONFLICT DO NOTHING` 후 `FOR UPDATE`) |

## 백로그 (시간이 남으면)

| 항목 | Phase |
|---|---|
| 목록·"내 참여" 조회를 수백만 행 시드에서 `EXPLAIN ANALYZE` before/after, keyset 페이지네이션 | 2 또는 5 |
| 딜 상세 캐시의 무효화 경쟁(stale) 재현과 커밋 후 무효화 | 2 |
| 서킷브레이커(Resilience4j) 임계치 실험 | 3 |
| EC2 위 k3s 배포 | 5 |

## 진행

| Phase | 상태 | 태그 | 결과 문서 |
|---|---|---|---|
| 1 | 🔨 기능 구현 완료, 마무리 남음 | | |
| 2 | ⏳ | | |
| 3 | ⏳ | | |
| 4 | ⏳ | | |
| 5 | ⏳ | | |
