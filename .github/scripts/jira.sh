#!/usr/bin/env bash
# Jira REST 헬퍼. 워크플로의 셸 스텝이 쓴다. Claude 에게는 Jira 토큰을 넘기지 않는다.
#
#   jira.sh get KEY [FIELDS]            이슈 JSON (기본 필드: status,labels,subtasks,issuelinks,parent,issuetype)
#   jira.sh status KEY                  현재 상태 이름
#   jira.sh transition KEY 상태이름     상태 이름으로 전이. 이미 그 상태면 아무것도 안 한다
#   jira.sh comment KEY FILE            FILE 내용을 코멘트로 (위키 마크업)
#   jira.sh subtask PARENT TITLE FILE   하위 작업 생성, 새 키 출력
#   jira.sh block BLOCKER BLOCKED       BLOCKER 가 BLOCKED 를 막는다 (Blocks 링크)
#   jira.sh label KEY LABEL             라벨 추가
#   jira.sh unlabel KEY LABEL           라벨 제거
#   jira.sh attach KEY FILE             첨부파일 올리기
#   jira.sh approval KEY                마지막 '승인' 전이의 승인자·시각과 트리아지 코멘트 링크 (3줄)
#   jira.sh attachment KEY NAME OUT     이름이 NAME 인 첨부 중 가장 최근 것을 OUT 으로 받기
#
# 환경변수: JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN
# REST v2 를 쓴다. v3 는 본문이 ADF JSON 이라 셸에서 다루기 번거롭다.

set -euo pipefail

: "${JIRA_BASE_URL:?}" "${JIRA_EMAIL:?}" "${JIRA_API_TOKEN:?}"

api() {
  local method=$1 path=$2 body=${3:-}
  local args=(-sS --fail-with-body -X "$method" -u "$JIRA_EMAIL:$JIRA_API_TOKEN"
    -H 'Accept: application/json' "$JIRA_BASE_URL/rest/api/2$path")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data "$body")
  curl "${args[@]}"
}

cmd=${1:?명령이 필요하다}
shift

case "$cmd" in
  get)
    api GET "/issue/$1?fields=${2:-status,labels,subtasks,issuelinks,parent,issuetype}"
    ;;

  status)
    api GET "/issue/$1?fields=status" | jq -r '.fields.status.name'
    ;;

  transition)
    key=$1 target=$2
    [ "$(api GET "/issue/$key?fields=status" | jq -r '.fields.status.name')" = "$target" ] && exit 0
    # 상태 id 를 하드코딩하지 않는다. 워크플로를 고쳐도 이름만 같으면 동작한다
    id=$(api GET "/issue/$key/transitions" |
      jq -r --arg t "$target" 'first(.transitions[] | select(.to.name == $t) | .id) // empty')
    if [ -z "$id" ]; then
      echo "$key: '$target' 로 가는 전이가 없다" >&2
      exit 1
    fi
    api POST "/issue/$key/transitions" "$(jq -nc --arg id "$id" '{transition:{id:$id}}')" >/dev/null
    ;;

  comment)
    api POST "/issue/$1/comment" "$(jq -nc --rawfile b "$2" '{body:$b}')" >/dev/null
    ;;

  subtask)
    parent=$1 title=$2 file=$3
    project=${parent%%-*}
    api POST "/issue" "$(jq -nc --arg p "$project" --arg parent "$parent" --arg s "$title" --rawfile d "$file" \
      '{fields:{project:{key:$p}, parent:{key:$parent}, issuetype:{name:"Subtask"}, summary:$s, description:$d}}')" |
      jq -r '.key'
    ;;

  block)
    # 생성 요청에서는 inwardIssue 가 "blocks" 쪽이다. 반대로 넣으면 선행·후행이 뒤집힌다 (2026-09-28 SCRUM-33/34 로 확인)
    api POST "/issueLink" "$(jq -nc --arg a "$1" --arg b "$2" \
      '{type:{name:"Blocks"}, inwardIssue:{key:$a}, outwardIssue:{key:$b}}')" >/dev/null
    ;;

  label)
    api PUT "/issue/$1" "$(jq -nc --arg l "$2" '{update:{labels:[{add:$l}]}}')" >/dev/null
    ;;

  unlabel)
    api PUT "/issue/$1" "$(jq -nc --arg l "$2" '{update:{labels:[{remove:$l}]}}')" >/dev/null
    ;;

  attach)
    curl -sS --fail-with-body -X POST -u "$JIRA_EMAIL:$JIRA_API_TOKEN" \
      -H 'X-Atlassian-Token: no-check' -F "file=@$2" \
      "$JIRA_BASE_URL/rest/api/2/issue/$1/attachments" >/dev/null
    ;;

  attachment)
    url=$(api GET "/issue/$1?fields=attachment" |
      jq -r --arg n "$2" '[.fields.attachment[] | select(.filename == $n)] | sort_by(.created) | last | .content // empty')
    if [ -z "$url" ]; then
      echo "$1: 첨부 $2 가 없다" >&2
      exit 1
    fi
    curl -sS --fail -L -u "$JIRA_EMAIL:$JIRA_API_TOKEN" -o "$3" "$url"
    ;;

  approval)
    # 변경 이력에서 가장 최근에 '승인' 으로 옮긴 사람을 찾는다. 승인 규칙(A2)을 고치지 않아도 된다
    api GET "/issue/$1?expand=changelog&fields=comment" | jq -r --arg base "$JIRA_BASE_URL" --arg k "$1" '
      ([.changelog.histories[]? | select(any(.items[]; .field == "status" and .toString == "승인"))] | sort_by(.created) | last) as $h
      | ([.fields.comment.comments[]? | select(.body | startswith("h3. Claude 트리아지"))] | last) as $c
      | "승인자: " + ($h.author.displayName // "알 수 없음"),
        "승인 시각: " + (($h.created // "-")[0:16] | sub("T"; " ")),
        "분석 코멘트: " + (if $c then $base + "/browse/" + $k + "?focusedCommentId=" + $c.id else "- (이 티켓에는 없음. 하위 작업이면 부모 티켓 참고)" end)'
    ;;

  *)
    echo "알 수 없는 명령: $cmd" >&2
    exit 2
    ;;
esac
