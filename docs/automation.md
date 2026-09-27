# Jira ↔ Claude ↔ GitHub 자동화

Jira 티켓을 Claude 가 분석(그대로 / 분해 / 질문)하고, 사람이 승인하면 GitHub Actions 가 구현 → Draft PR → 리뷰 → 머지 → Jira 완료까지 잇는다.
**자동화는 게이트 사이만 잇고, 게이트를 넘기는 건 사람이다.** 변경은 작성자가 설명할 수 있어야 머지한다(`docs/plan.md` 원칙).

도입: SCRUM-29 (PR #4 허용 목록, PR #5 트리아지·게이트, 2026-09-28).

## 흐름

```
🔒1 분석 요청 ─(jira-triage)─▶ 승인 대기        Claude: 인수 조건·분해안·자기 점검. Jira 에는 코멘트·첨부만
🔒2 승인 ─┬ keep  → 곧바로 구현 (게이트 3 을 겸한다)
          └ split → 하위 작업 생성(각각 승인 대기), 부모는 진행 중
🔒3 하위 작업 승인 ─(jira-executor)─▶ 진행 중 ─▶ Draft PR ─▶ 검토 중
       └ 기계 게이트: CI build · 아키텍처 규칙 · skip 0 / pr-review: 결함 유형 + AC 대조
🔒4 Draft → Ready: 코드를 읽고 설명할 수 있을 때. 고칠 것은 @claude 로 요청 (claude-mention)
🔒5 머지 (사람만) ─(jira-sync)─▶ 완료. 하위 작업이 다 끝나면 부모도 완료
불명확·실패는 어느 단계든 ─▶ 질문 (답을 설명에 반영하고 다시 분석 요청 / 승인 대기)
직접 구현: 라벨 manual, 승인 대기 ─▶ 진행 중 (자동 구현을 부르지 않는다)
```

## 사람 게이트

| 게이트 | 사람이 보는 것 | 넘기는 방법 | 강제 수단 |
|---|---|---|---|
| 1 분석 요청 | 티켓을 다 썼나 | `해야 할 일` → `분석 요청` | 트리아지는 이 전이로만 돈다 (Automation A1) |
| 2 분해 승인 | 트리아지 코멘트: 인수 조건·영향 파일·위험·자기 점검·분해안·manual 권고 | `승인 대기` → `승인` | `승인` 은 `승인 대기` 에서만. 하위 작업은 이때 처음 생긴다 |
| 3 구현 착수 | 하위 작업 하나의 인수 조건, 선행 작업 머지 여부 | 하위 작업 `승인 대기` → `승인` | 선행 미완료·`manual`·이미 분해된 부모면 executor 가 멈춘다 |
| 4 PR 검토 | CI·pr-review 결과, AC 대조 표, 코드를 내 말로 설명할 수 있나 | Draft → Ready for review | executor 가 PR 을 항상 Draft 로 되돌린다 |
| 5 머지 | 게이트 4 를 넘긴 PR | 머지 버튼 | Claude 에 머지 권한 없음(`gh pr merge` 미허용), Ruleset 이 `main` 직접 푸시를 막는다 |

## Jira 상태

작업·스토리·Subtask 에만 적용한다. **에픽은 기존 4상태** — Phase 묶음이라 자동화 대상이 아니고, 실수로 승인해도 자동 구현이 돌 경로가 없게.

| # | 상태 | 범주 | 들어오는 곳 | 누가 옮기나 |
|---|---|---|---|---|
| 1 | 해야 할 일 | 할 일 | 생성 | 사람 |
| 2 | 분석 요청 | 할 일 | 모든 상태 | 사람 → 트리아지 |
| 3 | 질문 | 할 일 | 모든 상태 | Actions (막힘·실패) |
| 4 | 승인 대기 | 할 일 | 모든 상태 | Actions (트리아지 끝, 하위 작업 생성) |
| 5 | 승인 | 할 일 | **승인 대기에서만** | 사람 → 분해 또는 구현 |
| 6 | 진행 중 | 진행 중 | 모든 상태 | Actions / 직접 구현 시 사람 |
| 7 | 검토 중 | 진행 중 | 모든 상태 | Actions (PR 열림) |
| 8 | 완료 | 완료 | 모든 상태 | Actions (PR 머지). 안 할 일은 해결 "하지 않음" |

라벨:
- `manual` — 직접 구현. 돈 경로·불변식(R1~R8)을 건드리는 티켓. 대상 SCRUM-24·25·26(돈 버그), 11(트러블슈팅 기록), 16(LT-01)
- `split-proposed` — 트리아지가 분해를 제안함. 게이트 2 승인 시 하위 작업 생성
- `split` — 이미 분해된 부모. 다시 승인해도 구현하지 않는다

## 구성 요소

| 파일 | 트리거 | 하는 일 |
|---|---|---|
| `.github/workflows/jira-triage.yml` | `repository_dispatch: jira-triage` (A1) | 티켓을 Jira 에서 읽어 Claude(opus, Read·Glob·Grep·Write 만)가 `triage.json` 작성 → `triage-apply.sh propose` |
| `.github/workflows/jira-executor.yml` | `repository_dispatch: jira-approved` (A2) | 가드(브랜치·manual·split·선행 작업) → `split-proposed` 면 하위 작업만 생성, 아니면 Claude(sonnet) 구현 → Draft PR → 검토 중. 질문·실패는 Jira 코멘트 + `질문` |
| `.github/workflows/jira-sync.yml` | PR opened·reopened·closed | 브랜치·제목의 `SCRUM-N` → 검토 중 / 머지 시 완료(+부모) |
| `.github/workflows/pr-review.yml` | PR opened·synchronize (코드 변경) | 결함 유형 6가지 + Jira 인수 조건(AC) 번호별 테스트 대조 표 |
| `.github/workflows/ci.yml` | push main, PR | `./gradlew build`, 아키텍처 규칙, skip 0 검사 |
| `.github/workflows/claude-mention.yml` | 소유자의 `@claude` 코멘트 | PR 브랜치에 수정 커밋 (게이트 4 재작업 경로) |
| `.github/scripts/jira.sh` | 워크플로 셸 스텝 | Jira REST v2 헬퍼: get·status·transition(이름으로)·comment·subtask·block·label·unlabel·attach·attachment |
| `.github/scripts/triage-apply.sh` | triage·executor | `triage.json` 검증 → propose(코멘트·첨부·전이) / create(하위 작업·Blocks 링크) |
| `.github/scripts/check-skipped.sh` | ci | JUnit 결과의 skip 합계가 0 이 아니면 실패 |

`triage.json` 형식 (Claude 가 쓰고 셸이 `jq` 로 검증, Jira 첨부로 보관):

```json
{
  "decision": "keep | split | question",
  "reason": "판단 이유",
  "acceptance_criteria": ["AC1 …"],
  "affected_files": ["경로"],
  "risks": ["R4 …"],
  "manual_recommended": false,
  "self_check": {"ac_complete": true, "granularity_ok": true, "verifiable": true, "open_questions": []},
  "subtasks": [{"title": "", "description": "", "acceptance_criteria": [], "design_ref": "", "depends_on": []}],
  "questions": []
}
```

## 설계 원칙과 이유

- **Claude 는 판단만, Jira 쓰기는 셸.** Jira 토큰을 Claude 도구에 넘기지 않는다. 생성 개수·형식을 셸이 검증하므로 모델이 규칙을 무시해도 결과가 틀어지지 않는다
- **멈춤·되돌림 판단은 프롬프트가 아니라 셸에서.** 중복 실행, manual, 선행 작업, Draft 강제, 열린 질문 → question 전환이 모두 셸이다. 프롬프트 규칙은 모델이 어길 수 있다
- **분해 기준은 `/plan` 과 같다.** PR 하나 = 파일 3~8개, 새 테이블 1개 이하, 외부 연동 1개 이하. 하위 작업 하나 = PR 하나
- **Automation 규칙은 2개만.** Jira Free 는 사이트 전체 월 150 steps(동시 5). 규칙 1회 ≈ 2 steps, 티켓당 약 4 steps → 월 35티켓 안팎. 나머지 전이는 Actions 가 REST 로 한다
- **payload 는 키만.** 제목·설명은 워크플로가 Jira 에서 직접 읽는다. `jsonEncode`·길이·이스케이프 문제를 피한다
- **REST v2.** v3 는 코멘트 본문이 ADF JSON 이라 셸에서 다루기 번거롭다. 상태는 id 가 아니라 이름으로 전이를 찾는다
- **비밀정보 검사는 GitHub 기본 Secret scanning·Push protection.** 새어 나갈 수 있는 값이 전부 형식이 알려진 토큰이라 gitleaks 는 중복

하지 않은 것 (규모에 비해 과함): 레포별 PRD·도메인→레포 라우팅(레포 1개), RAG·코드그래프·벡터 DB, git worktree(Actions 러너가 격리), 자동 재구현 루프(사람이 모르는 사이 코드가 바뀌면 게이트 4 가 무의미), 평가 결과 별도 채점 파일.

## 설정 현황 (2026-09-28)

| 항목 | 상태 |
|---|---|
| `main` Ruleset — 기본 브랜치, PR 필수·승인 0, 필수 검사 `build`, Bypass 없음 | ✅ `gh api repos/Jeong-Hyun-Su/groupbuy/rules/branches/main` 으로 확인 |
| Jira 상태 4개 추가, `승인` 은 `승인 대기` 에서만, 에픽 제외 | ✅ (`승인` 존재는 승인 대기 티켓에서 확인 필요) |
| 시크릿 `JIRA_EMAIL`·`JIRA_API_TOKEN` (scope 없는 API 토큰) | ✅ PR #5 에서 jira-sync·pr-review 가 실제 인증 |
| 워크플로 반영 | ✅ PR #5 머지, jira-sync 가 SCRUM-29 를 완료로 옮김 |
| Secret scanning·Push protection | ✅ 켜져 있음 |
| 라벨 `manual` (24·25·26·11·16) | ⬜ **A2 연결 전에** |
| Jira Automation A1·A2 + GitHub fine-grained PAT | ⬜ |
| 테스트 티켓으로 확인 절차 | ⬜ |

### Jira Automation 규칙

웹 요청 공통: `POST https://api.github.com/repos/Jeong-Hyun-Su/groupbuy/dispatches`, 헤더 `Authorization: Bearer <PAT>`, `Accept: application/vnd.github+json`.
PAT 는 fine-grained, 이 저장소만, Contents: Read and write, 만료일 설정 후 캘린더에 갱신 알림.

| 규칙 | 트리거 | 조건 | 본문 |
|---|---|---|---|
| A1 | 이슈 전환됨 → `분석 요청` | 유형 ∈ {작업, 스토리} | `{"event_type":"jira-triage","client_payload":{"key":"{{issue.key}}"}}` |
| A2 | 이슈 전환됨 → `승인` | 유형 ∈ {작업, 스토리, Subtask}, 라벨에 `manual` 없음 | `{"event_type":"jira-approved","client_payload":{"key":"{{issue.key}}"}}` |

## 확인 절차

1. 헬퍼 단독 (로컬): `JIRA_BASE_URL=https://ikerhold1572.atlassian.net JIRA_EMAIL=… JIRA_API_TOKEN=… .github/scripts/jira.sh status SCRUM-5`
2. dry-run (Jira 안 건드림): `.github/scripts/triage-apply.sh propose SCRUM-99 sample.json --dry-run`, `… create …`. skip 검사: `./gradlew build` 후 `.github/scripts/check-skipped.sh`
3. 테스트 티켓(에픽 SCRUM-5 아래, 라벨 `test-automation`)을 `분석 요청` 으로
   - 작은 것 → keep, `승인 대기` + 코멘트
   - 큰 것 → 분해안 코멘트, 라벨 `split-proposed`, `승인 대기`, **하위 작업은 아직 없음.** `승인` → 하위 작업·Blocks 링크, 부모 `진행 중` + `split`. **Blocks 방향이 선행 → 후행인지 화면에서 확인** (반대면 `jira.sh block` 의 두 키를 바꾼다)
   - 모호한 것 / 설명에 답이 없는 조건 → `질문`
   - 부모를 다시 `승인` → 하위 작업이 늘지 않음
4. keep 티켓 `승인` → 진행 중 → Draft PR → 검토 중, pr-review 에 AC 대조 표. manual 티켓 `승인` → 안 돎. 선행 미완료 하위 작업 `승인` → `승인 대기` 로 되돌려짐
5. 테스트 PR 머지 → 완료, 마지막 하위 작업 머지 → 부모 완료
6. 테스트 티켓·브랜치·PR 정리

## 알려진 동작과 함정

- **워크플로 파일을 바꾸는 PR 에서는 Claude 리뷰가 건너뛰어진다.** claude-code-action 이 "워크플로가 기본 브랜치와 같아야 한다"고 검증한다. 체크는 성공으로 뜬다. 머지 뒤 다음 PR 부터 돈다
- **Claude 는 `.github/workflows/` 를 직접 고치지 않는다** (로컬 PreToolUse 훅). 초안을 쓰고 사람이 복사한다
- **Ruleset 은 대상 브랜치를 지정해야 적용된다.** 비워 두면 active 여도 `main` 에 걸리는 규칙이 0개다. `rules/branches/main` 으로 실제 적용을 확인한다
- **Jira API 토큰은 scope 없는 것.** scope 토큰은 `api.atlassian.com/ex/jira/<cloudId>` 경유라 사이트 주소 호출과 맞지 않는다
- **브랜치 검사는 끝을 본다.** `feature/SCRUM-1*` 은 SCRUM-12 에도 걸린다 → `(feature|fix)/KEY(-|$)`
- Claude 앱이 연 PR 에 `jira-sync` 가 도는지는 확인 필요. 안 돌아도 executor 가 `검토 중` 으로 옮긴다
