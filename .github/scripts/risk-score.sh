#!/usr/bin/env bash
# PR 리스크 점수 → risk:* 라벨. 돈 경로를 건드렸는데 티켓이 manual 이 아니면 경고 코멘트.
# 파일 경로로만 판단한다(모델 판단 아님). 리뷰·머지 흐름은 바꾸지 않는다 —
# 라벨은 게이트 4 에서 얼마나 꼼꼼히 볼지의 신호일 뿐, 자동 머지·차단은 없다.
#
#   risk-score.sh PR번호 [--dry-run]   --dry-run 이면 점수만 출력하고 PR 은 건드리지 않는다
#
# 환경변수: GH_TOKEN(또는 gh 로그인), GH_REPO(저장소 밖에서 돌 때).
# 경고 판정은 JIRA_BASE_URL·JIRA_EMAIL·JIRA_API_TOKEN 이 있을 때만 한다 (같은 폴더의 jira.sh).
#
# 점수 (최대 100) — 구간: 0~15 low · 16~35 medium · 36~60 high · 61~ critical
#   돈 경로·불변식 40/30 · 마이그레이션 25 · 스코프 0~20(문서 제외 줄 수) · 테스트 없는 코드 변경 15

set -euo pipefail

# 경로 패턴(ERE). 모듈 구조가 바뀌면 여기만 고친다
MONEY='^modules/(payment|settlement)/src/main/'                            # 40 — R4~R7
INVARIANT='^modules/(participation|deal)/src/main/.*/(domain|application)/' # 30 — R1~R3·R8
MIGRATION='/db/migration/.*\.sql$'
MAIN_CODE='/src/main/.*\.kt$'
TEST_CODE='/src/test/'
MARKER='<!-- risk-score:manual-warning -->'

pr=${1:?PR 번호가 필요하다}
dry=${2:-}

pr_json=$(gh pr view "$pr" --json files,headRefName,title,labels,comments)
files=$(jq -r '.files[].path' <<<"$pr_json")
# 스코프는 문서(.md)를 뺀 줄 수로 본다. 설계서·계획을 같이 고친 PR 이 커 보이지 않게
lines=$(jq '[.files[] | select(.path | test("\\.md$") | not) | .additions + .deletions] | add // 0' <<<"$pr_json")

has() { grep -qE "$1" <<<"$files"; }

money=0
if has "$MONEY"; then money=40; elif has "$INVARIANT"; then money=30; fi
migration=0
has "$MIGRATION" && migration=25
if   [ "$lines" -le 100 ]; then scope=0
elif [ "$lines" -le 300 ]; then scope=8
elif [ "$lines" -le 800 ]; then scope=14
else scope=20; fi
tests=0
if has "$MAIN_CODE" && ! has "$TEST_CODE"; then tests=15; fi

score=$((money + migration + scope + tests))
if   [ "$score" -le 15 ]; then label=risk:low
elif [ "$score" -le 35 ]; then label=risk:medium
elif [ "$score" -le 60 ]; then label=risk:high
else label=risk:critical; fi

echo "PR #$pr  $label ($score)  돈 경로 $money · 마이그레이션 $migration · 스코프 $scope(문서 제외 ${lines}줄) · 테스트 누락 $tests"

# 경고: 돈 경로를 건드렸고, 연결된 티켓(jira-sync 와 같은 규칙: 브랜치 접두 또는 제목 접두)이 manual 이 아닐 때
warn=
if [ "$money" -gt 0 ]; then
  head=$(jq -r '.headRefName' <<<"$pr_json")
  title=$(jq -r '.title' <<<"$pr_json")
  key=$(printf '%s\n' "$head" | grep -oE '^(feature|fix|chore)/SCRUM-[0-9]+' | grep -oE 'SCRUM-[0-9]+' || true)
  [ -n "$key" ] || key=$(printf '%s\n' "$title" | grep -oE '^SCRUM-[0-9]+:' | tr -d : || true)
  if [ -z "$key" ]; then
    echo "경고 판정: 티켓 키 없음 — 건너뜀"
  elif [ -z "${JIRA_API_TOKEN:-}" ]; then
    echo "경고 판정: Jira 토큰 없음 — 건너뜀 ($key)"
  elif ! issue=$("$(dirname "$0")/jira.sh" get "$key" labels); then
    echo "경고 판정: $key 조회 실패 — 건너뜀"
  elif jq -e '.fields.labels | index("manual")' <<<"$issue" >/dev/null; then
    echo "경고 판정: $key 는 manual — 경고 없음"
  else
    warn=$key
    echo "경고 판정: $key 가 manual 이 아닌데 돈 경로를 건드림 — 경고"
  fi
fi

[ "$dry" = "--dry-run" ] && exit 0

# 라벨은 REST 로 바꾼다 (gh pr edit 는 GraphQL 이라 GITHUB_TOKEN 권한에서 막히는 경우가 있다)
for old in $(jq -r '.labels[].name | select(startswith("risk:"))' <<<"$pr_json"); do
  [ "$old" = "$label" ] || gh api -X DELETE "repos/{owner}/{repo}/issues/$pr/labels/$old" >/dev/null
done
jq -e --arg l "$label" 'any(.labels[]; .name == $l)' <<<"$pr_json" >/dev/null ||
  gh api -X POST "repos/{owner}/{repo}/issues/$pr/labels" -f "labels[]=$label" >/dev/null

# 경고 코멘트는 PR 당 한 번만 (푸시마다 다시 돌아도 쌓이지 않게)
if [ -n "$warn" ] && ! jq -e --arg m "$MARKER" 'any(.comments[]; .body | contains($m))' <<<"$pr_json" >/dev/null; then
  gh pr comment "$pr" --body "$MARKER
**돈 경로를 건드렸는데 $warn 이 \`manual\` 이 아니다.** ($label, $score점)

$(grep -E "$MONEY|$INVARIANT" <<<"$files" | sed 's/^/- `/; s/$/`/')

게이트 4 에서 이 변경을 **내 말로 설명할 수 있는지** 확인한다. 설명할 수 없으면 PR 을 닫고 티켓에 \`manual\` 을 붙여 직접 구현한다."
fi
