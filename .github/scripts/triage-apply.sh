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
      # 맨 위 요약. 검토하는 사람이 "무엇을 결정하는지"를 여기서 읽는다
      manual_rec=$(jq -r 'if (.manual_recommended // false) then "예" else "아니오" end' "$file")
      echo "*요약*"
      case "$decision" in
        keep)     echo "* 판정: keep — PR 하나로 구현할 수 있다"
                  if [ "$manual_rec" = "예" ]; then
                    echo "* '승인' 으로 옮기면: *{{manual}} 라벨이 없는 한 바로 자동 구현을 시작*한다 (직접 구현이면 라벨부터 붙인다)"
                  else
                    echo "* '승인' 으로 옮기면: *바로 자동 구현을 시작*해 Draft PR 을 연다"
                  fi ;;
        split)    echo "* 판정: split — 하위 작업 $(jq '.subtasks | length' "$file")개로 쪼개야 한다"
                  echo "* '승인' 으로 옮기면: 하위 작업만 만든다 (구현은 아직 시작하지 않는다)" ;;
        question) echo "* 판정: question — 요구사항이 불명확해 멈췄다"
                  echo "* '승인' 은 할 수 없다. 설명을 고친 뒤 '분석 요청' 으로 다시 옮긴다" ;;
      esac
      echo "* 직접 구현 권고: $manual_rec"
      if [ -n "${GITHUB_RUN_ID:-}" ]; then
        echo "* 분석 실행: ${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/$GITHUB_RUN_ID"
      fi
      echo
      if [ "$manual_rec" = "예" ]; then
        echo "{panel:title=(!) 직접 구현 권고|borderColor=#c9372c}"
        echo "불변식(R1~R8)이나 돈 경로를 건드린다. 직접 하려면 라벨 {{manual}} 을 붙이고 '진행 중' 으로 옮긴다."
        echo "{panel}"
        echo
      fi
      echo "*판단 이유*"
      jq -r '.reason' "$file"; echo
      section acceptance_criteria "인수 조건"
      jq -r '(.affected_files // []) | if length > 0 then "*영향 파일*\n" + (map("* {{" + . + "}}") | join("\n")) + "\n" else empty end' "$file"
      section risks "위험"
      # 자기 점검은 Claude 가 스스로 매긴 점수라 통과는 숨기고, 미흡한 것만 보인다
      miss=$(check ac_complete "설명의 요구가 인수 조건에 빠짐없이 들어갔다"; \
             check granularity_ok "입도 (PR 하나 = 파일 3~8개, 새 테이블 ≤ 1, 외부 연동 ≤ 1)"; \
             check verifiable "인수 조건마다 테스트로 확인할 수 있다")
      miss=$(printf '%s\n' "$miss" | grep '(!) 미흡' || true)
      if [ -n "$miss" ]; then printf '*(!) 자기 점검에서 미흡*\n%s\n\n' "$miss"; fi
      # keep·split 에도 구현 전에 확인할 점을 남길 수 있다 (막지는 않는다)
      if [ "$decision" != "question" ]; then section questions "확인할 점 (구현을 막지는 않음)"; fi
      case "$decision" in
        split)
          echo "*분해안* (아직 만들지 않았다. '승인' 으로 옮기면 하위 작업이 생긴다)"
          # 번호는 1부터 보여 준다. create 와 같은 규칙으로 범위 밖·자기 참조는 뺀다
          jq -r '(.subtasks | length) as $n | .subtasks | to_entries[] | .key as $i
            | ([(.value.depends_on // [])[] | select(type == "number" and . >= 0 and . < $n and . != $i) | . + 1 | tostring + "번"]) as $d
            | "# " + .value.title + (if ($d | length) > 0 then " (선행: " + ($d | join(", ")) + ")" else "" end)' "$file"
          echo
          ;;
        question)
          echo "*질문*"; list questions
          echo; echo "답을 설명에 반영한 뒤 '분석 요청' 으로 다시 옮긴다."; echo
          ;;
      esac
      # 검토 기록 안내. 이 코멘트는 Claude 가 읽지 않는다 — 기록용이라 구현에 영향을 주지 않는다
      echo "----"
      echo "*검토 기록* (승인하기 전에 코멘트로 남긴다. Claude 는 코멘트를 읽지 않으니 기록용이다)"
      echo "{noformat}"
      echo "검토: 인수 조건 AC1~ 확인 / 위험 확인"
      echo "조정: (설명을 고쳤으면 무엇을) / 없음"
      echo "결정: 승인 | 직접 구현 | 보류"
      echo "{noformat}"
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
    # 트리아지가 직접 구현을 권고했으면 하위 작업에 manual 을 붙인다. 코멘트만 남기면 쪼개진 하위 작업이
    # 권고를 잃고 승인 즉시 자동 구현된다. 자동으로 돌려도 되면 사람이 라벨을 뗀다
    manual=$(jq -r '.manual_recommended // false' "$file")
    keys=()
    for i in $(seq 0 $((n - 1))); do
      jq -r --argjson i "$i" --arg parent "$key" '.subtasks[$i] |
        "부모: " + $parent + "\n\n" + (.description // "") +
        "\n\n*인수 조건*\n" + ((.acceptance_criteria // []) | map("* " + .) | join("\n")) +
        "\n\n*참고 설계*: " + (.design_ref // "-")' "$file" > "$tmp/st$i.txt"
      title="[$((i + 1))/$n] $(jq -r --argjson i "$i" '.subtasks[$i].title' "$file")"
      k=$(jira subtask "$key" "$title" "$tmp/st$i.txt")
      keys+=("$k")
      if [ "$manual" = "true" ]; then jira label "$k" manual >/dev/null; fi
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
      if [ "$dry" != "--dry-run" ]; then "$here/jira.sh" approval "$key" 2>/dev/null | sed 's/^/* /' || true; fi
      echo "하나 = PR 하나. 선행이 머지된 뒤 각각 '승인' 으로 옮긴다."
      if [ "$manual" = "true" ]; then echo "직접 구현 권고라 하위 작업에 라벨 {{manual}} 을 붙였다. 자동 구현해도 되는 것은 라벨을 뗀다."; fi
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
