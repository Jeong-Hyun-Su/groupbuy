#!/usr/bin/env bash
# jira-triage 결과(triage.json)를 검증하고 Jira 에 반영한다. Claude 는 판단만, 쓰기는 여기서 한다.
#
#   triage-apply.sh propose KEY TRIAGE_JSON [--dry-run]
#       트리아지 직후 (jira-triage). 코멘트 + triage.json 첨부 + 전이.
#       split 이어도 하위 작업은 만들지 않는다. 라벨 split-proposed 만 붙이고 '승인 대기' 로 — 사람 게이트 2
#   triage-apply.sh create KEY TRIAGE_JSON [--dry-run]
#       게이트 2 승인 뒤 (jira-executor). 첨부해 둔 분해안으로 하위 작업을 만든다
#
# --dry-run 이면 Jira 를 건드리지 않고 할 일만 출력한다.

set -euo pipefail

mode=${1:?} key=${2:?} file=${3:?} dry=${4:-}
here=$(cd "$(dirname "$0")" && pwd)
jira() { if [ "$dry" = "--dry-run" ]; then echo "[dry-run] jira.sh $*" >&2; echo "DRY-1"; else "$here/jira.sh" "$@"; fi; }
tmp=$(mktemp -d)

# 형식 검증. 틀리면 실패로 끝내고 워크플로의 실패 스텝이 '질문' 으로 돌린다
jq -e '
  (.decision | IN("keep","split","question"))
  and (.reason | type == "string" and length > 0)
  and ((.acceptance_criteria // []) | all(test("^AC[0-9]+ ")))
  and (if .decision == "split" then (.subtasks | type == "array" and length >= 2 and length <= 6)
       and all(.subtasks[]; (.title | type == "string" and length > 0 and length <= 200))
       else true end)
  and (if .decision == "question" then (.questions | type == "array" and length >= 1) else true end)
' "$file" >/dev/null || { echo "triage.json 형식이 틀렸다" >&2; exit 1; }

# 자기 점검에 열린 질문이 남았으면 keep/split 이라고 해도 질문으로 돌린다.
# 모델이 "질문은 있지만 일단 진행" 하는 것을 셸에서 막는다
jq '(.self_check.open_questions // []) as $q
    | if ($q | length) > 0 and .decision != "question"
      then .decision = "question" | .questions = ((.questions // []) + $q)
           | .reason = "자기 점검에 열린 질문이 남아 질문으로 돌렸다. 원래 판단: " + .reason
      else . end' "$file" > "$tmp/triage.json"
file=$tmp/triage.json
decision=$(jq -r '.decision' "$file")

list() { jq -r --arg f "$1" '(.[$f] // [])[] | "* " + .' "$file"; }
# 비어 있는 항목은 제목째 뺀다 (질문 판정이면 인수 조건·영향 파일이 없다)
section() { local body; body=$(list "$1"); [ -z "$body" ] || printf '*%s*\n%s\n\n' "$2" "$body"; }
check() { jq -r --arg f "$1" --arg l "$2" '"* " + $l + ": " + (if .self_check[$f] == true then "통과" elif .self_check[$f] == false then "(!) 미흡" else "-" end)' "$file"; }

case "$mode" in
  propose)
    {
      echo "h3. Claude 트리아지: $decision"
      jq -r '.reason' "$file"; echo
      if [ "$(jq -r '.manual_recommended // false' "$file")" = "true" ]; then
        echo "*(!) 직접 구현 권고* — 불변식(R1~R8)이나 돈 경로를 건드린다. 직접 하려면 라벨 {{manual}} 을 붙이고 '진행 중' 으로 옮긴다."
        echo
      fi
      section acceptance_criteria "인수 조건"
      section affected_files "영향 파일"
      section risks "위험"
      echo "*자기 점검*"
      check ac_complete "설명의 요구가 인수 조건에 빠짐없이 들어갔다"
      check granularity_ok "입도 (PR 하나 = 파일 3~8개, 새 테이블 ≤ 1, 외부 연동 ≤ 1)"
      check verifiable "인수 조건마다 테스트로 확인할 수 있다"
      case "$decision" in
        split)
          echo; echo "*분해안* (아직 만들지 않았다. '승인' 으로 옮기면 하위 작업이 생긴다)"
          # 번호는 1부터 보여 준다. create 와 같은 규칙으로 범위 밖·자기 참조는 뺀다
          jq -r '(.subtasks | length) as $n | .subtasks | to_entries[] | .key as $i
            | ([(.value.depends_on // [])[] | select(type == "number" and . >= 0 and . < $n and . != $i) | . + 1 | tostring + "번"]) as $d
            | "# " + .value.title + (if ($d | length) > 0 then " (선행: " + ($d | join(", ")) + ")" else "" end)' "$file"
          ;;
        question)
          echo; echo "*질문*"; list questions
          echo; echo "답을 설명에 반영한 뒤 '분석 요청' 으로 다시 옮긴다."
          ;;
      esac
    } > "$tmp/comment.txt"

    jira comment "$key" "$tmp/comment.txt" >/dev/null
    # 게이트 2 승인 뒤 create 와 pr-review 의 인수 조건 대조가 이 첨부를 읽는다
    jira attach "$key" "$file" >/dev/null
    case "$decision" in
      keep)     jira unlabel "$key" split-proposed >/dev/null; jira transition "$key" "승인 대기" >/dev/null ;;
      split)    jira label "$key" split-proposed >/dev/null;   jira transition "$key" "승인 대기" >/dev/null ;;
      question) jira unlabel "$key" split-proposed >/dev/null; jira transition "$key" "질문" >/dev/null ;;
    esac
    ;;

  create)
    [ "$decision" = "split" ] || { echo "분해안이 아니다: $decision" >&2; exit 1; }

    # 재승인해도 하위 작업이 두 벌 생기지 않게 한다
    if [ "$dry" != "--dry-run" ] &&
       [ "$("$here/jira.sh" get "$key" subtasks | jq '.fields.subtasks | length')" -gt 0 ]; then
      echo "$key: 이미 하위 작업이 있다. 만들지 않는다." >&2
      exit 1
    fi

    n=$(jq '.subtasks | length' "$file")
    keys=()
    for i in $(seq 0 $((n - 1))); do
      jq -r --argjson i "$i" --arg parent "$key" '.subtasks[$i] |
        "부모: " + $parent + "\n\n" + (.description // "") +
        "\n\n*인수 조건*\n" + ((.acceptance_criteria // []) | map("* " + .) | join("\n")) +
        "\n\n*참고 설계*: " + (.design_ref // "-")' "$file" > "$tmp/st$i.txt"
      title="[$((i + 1))/$n] $(jq -r --argjson i "$i" '.subtasks[$i].title' "$file")"
      k=$(jira subtask "$key" "$title" "$tmp/st$i.txt")
      keys+=("$k")
      jira transition "$k" "승인 대기" >/dev/null
    done

    # 선행 관계. 범위 밖 번호와 자기 참조는 버린다
    for i in $(seq 0 $((n - 1))); do
      for d in $(jq -r --argjson i "$i" --argjson n "$n" \
          '.subtasks[$i].depends_on // [] | .[] | select(type == "number" and . >= 0 and . < $n and . != $i)' "$file"); do
        jira block "${keys[$d]}" "${keys[$i]}" >/dev/null
      done
    done

    { echo "h3. 분해 승인 → 하위 작업 생성"
      echo "하나 = PR 하나. 선행이 머지된 뒤 각각 '승인' 으로 옮긴다."
      for k in "${keys[@]}"; do echo "* $k"; done; } > "$tmp/split.txt"
    jira comment "$key" "$tmp/split.txt" >/dev/null
    jira unlabel "$key" split-proposed >/dev/null
    jira label "$key" split >/dev/null
    jira transition "$key" "진행 중" >/dev/null
    ;;

  *)
    echo "알 수 없는 모드: $mode" >&2
    exit 2
    ;;
esac

echo "$key: $mode $decision 반영"
