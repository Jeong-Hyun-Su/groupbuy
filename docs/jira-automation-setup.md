# Jira 자동화 적용 절차

Claude 는 `.github/workflows/` 를 직접 고치지 않는다(로컬 훅이 막는다). 워크플로는 초안으로 작성해 사람이 읽고 옮겼다 (SCRUM-29).
흐름·상태·사람 게이트 1~5 는 `docs/plan.md` › Jira 연결 › 상태와 자동화.

| 워크플로 | 적용 | 바뀌는 점 |
|---|---|---|
| `jira-triage.yml` | 새로 추가 | '분석 요청' → Claude 가 keep/split/question 판단·자기 점검 → 셸이 코멘트·`triage.json` 첨부. **하위 작업은 만들지 않는다** |
| `jira-executor.yml` | 기존 파일 교체 | `split-proposed` 면 하위 작업만 생성(게이트 2), 아니면 구현. **PR 은 항상 Draft**(게이트 4). manual·선행 작업 가드, 브랜치 검사 `feature|fix/KEY(-…)`, 질문·실패는 Jira 코멘트 + '질문' |
| `jira-sync.yml` | 새로 추가 | PR 열림 → 검토 중, 머지 → 완료(+부모) |
| `ci.yml` | 기존 파일 교체 | `build` 잡에 skip 0 검사, 새 잡 `secret-scan`(gitleaks) |
| `pr-review.yml` | 기존 파일 교체 | Jira 인수 조건을 읽어 AC 번호별 테스트 대조 표를 코멘트에 붙인다 |

**새 의존성 `gitleaks/gitleaks-action@v2`** (CLAUDE.md: 새 의존성은 이유·대안 먼저)
- 이유: Actions 에 시크릿이 늘었다(Claude·Jira 토큰). Claude 가 커밋을 만들므로 실수로 값이 들어가도 사람이 diff 에서 놓칠 수 있다
- 대안: GitHub 기본 Secret scanning·Push protection(공개 저장소 무료, Settings → Code security). 설정만 켜면 되고 의존성이 없다 — **이것만으로 충분하면 `secret-scan` 잡은 빼도 된다**
- 개인 계정은 gitleaks-action 라이선스 키가 필요 없다(조직 계정만 필요)

## 순서 (위에서부터)

1. **`main` 브랜치 보호 (Ruleset)** — 대상 기본 브랜치, PR 필수·승인 0, 필수 검사 `build`, Bypass list 비움. 2026-09-28 조회 시 Ruleset `Ruleset Automation` 은 있지만 **대상 브랜치·필수 검사가 비어 있어** `main` 에 적용되는 규칙이 0개. executor 에 `Bash(git:*)` 가 열려 있어 이게 먼저다. 확인: `gh api repos/Jeong-Hyun-Su/groupbuy/rules/branches/main` 이 비어 있지 않을 것
2. **PR #4(SCRUM-29) 머지** — 이 브랜치는 그 위에 있다
3. **Jira 워크플로** (프로젝트 설정 → 워크플로 편집)
   - 상태 추가: `분석 요청`, `질문`, `승인 대기`, `승인` — 범주는 모두 "해야 할 일"
   - `분석 요청`·`질문`·`승인 대기` 는 "모든 상태에서" 전이 허용
   - `승인` 은 **`승인 대기` 에서만** 전이 허용 (전역 전이 끄기)
   - 상태 **이름을 정확히** 위와 같게. `jira.sh transition` 이 이름으로 전이를 찾는다
   - 적용 업무 유형: **작업, 스토리, Subtask**. 에픽은 기존 4상태 워크플로에 둔다 (Phase 묶음이라 자동화 대상이 아니고, 실수로 '승인' 해도 A2 가 돌 경로가 없게)
4. **라벨** `manual` — SCRUM-24·25·26·11·16
5. **시크릿** (GitHub 저장소 → Settings → Secrets → Actions)
   - `JIRA_EMAIL` — Atlassian 계정 이메일
   - `JIRA_API_TOKEN` — https://id.atlassian.com/manage-profile/security/api-tokens (만료일 설정)
6. **Jira Automation 규칙 2개** — 웹 요청 공통
   - 한도: Free 는 사이트 전체 **월 150 steps**, 동시 실행 5개. 넘으면 결제 주기 끝까지 규칙이 멈춘다.
     규칙 1회 ≈ 조건 1 + 웹 요청 1 = 약 2 steps, 티켓 하나가 A1·A2 한 번씩이면 약 4 steps → **월 35티켓 안팎**.
     재분석·재승인이 잦으면 줄어든다. 그래서 나머지 전이는 전부 Actions 가 REST 로 한다
   - URL `https://api.github.com/repos/Jeong-Hyun-Su/groupbuy/dispatches`, POST
   - 헤더 `Authorization: Bearer <GitHub fine-grained PAT>`, `Accept: application/vnd.github+json`
   - PAT: 이 저장소만, 권한 Contents: Read and write, 만료일 설정 후 캘린더에 갱신 알림
   - **A1** 트리거 "이슈 전환됨 → 분석 요청", 조건 이슈 유형 ∈ {작업, 스토리}, 본문
     `{"event_type":"jira-triage","client_payload":{"key":"{{issue.key}}"}}`
   - **A2** 트리거 "이슈 전환됨 → 승인", 조건 이슈 유형 ∈ {작업, 스토리, Subtask} 그리고 라벨에 `manual` 없음, 본문
     `{"event_type":"jira-approved","client_payload":{"key":"{{issue.key}}"}}`
   - 본문은 키만 보낸다. 제목·설명은 워크플로가 Jira 에서 직접 읽는다 (jsonEncode·길이 문제를 피한다)
7. **워크플로 반영** — 2026-09-28 완료 (초안을 `.github/workflows/` 로 복사). Automation 규칙(6)은 이 PR 머지 뒤에 켠다

## 확인

1. 헬퍼 단독 (로컬):
   `JIRA_BASE_URL=https://ikerhold1572.atlassian.net JIRA_EMAIL=… JIRA_API_TOKEN=… .github/scripts/jira.sh status SCRUM-5`
2. dry-run (Jira 안 건드림): `.github/scripts/triage-apply.sh propose SCRUM-99 sample.json --dry-run`, `… create …`. skip 검사: `./gradlew build` 후 `.github/scripts/check-skipped.sh` (2026-09-28 로컬: 139개, skip 0)
3. 테스트 티켓 3개(에픽 SCRUM-5 아래, 라벨 `test-automation`)를 '분석 요청' 으로
   - 작은 것(문구 수정) → keep, '승인 대기' + 코멘트
   - 큰 것 → 코멘트에 분해안, 라벨 `split-proposed`, '승인 대기'. **하위 작업은 아직 없어야 한다.** '승인' 으로 옮기면 하위 작업·Blocks 링크 생성, 부모 '진행 중' + 라벨 split. **Blocks 방향이 "선행 → 후행" 인지 화면에서 확인** (REST 의 inward/outward 가 헷갈리기 쉽다. 반대면 `jira.sh block` 의 두 키를 바꾼다)
   - 모호한 것 → '질문'
   - 설명에 답이 없는 조건을 넣은 것 → 자기 점검 열린 질문 → '질문'
   - 부모를 다시 '승인' → 하위 작업이 늘지 않음
4. keep 티켓 '승인' → 진행 중 → **Draft** PR → 검토 중, pr-review 코멘트에 AC 대조 표. CI 에 skip 검사·secret-scan 통과. manual 티켓 '승인' → 안 돔. 선행 미완료 하위 작업 '승인' → '승인 대기' 로 되돌려짐
5. 테스트 PR 머지 → 완료, 마지막 하위 작업 머지 → 부모 완료
6. 테스트 티켓·브랜치·PR 정리

## 확인 필요

- Claude 앱이 연 PR 에 `jira-sync` 가 도는지 — 안 돌아도 executor 가 '검토 중' 으로 옮긴다
- 기존에 만들어 둔 Automation 규칙이 있는지
