#!/usr/bin/env bash
# 테스트 결과에 skip 이 하나라도 있으면 실패한다.
# skip 은 통과가 아니다 (CLAUDE.md). Docker 가 있는데 통합 테스트 17개가 전부 skip 되던 G1 의 재발 방지.

set -euo pipefail

files=$(find . -path '*/build/test-results/*/TEST-*.xml' | wc -l | tr -d ' ')
if [ "$files" -eq 0 ]; then
  echo "테스트 결과 XML 이 없다. 테스트가 돌지 않았다" >&2
  exit 1
fi

read -r tests skipped < <(find . -path '*/build/test-results/*/TEST-*.xml' -exec grep -h -m1 '<testsuite ' {} + |
  sed -E 's/.* tests="([0-9]+)".* skipped="([0-9]+)".*/\1 \2/' |
  awk '{t += $1; s += $2} END {print t + 0, s + 0}')

echo "테스트 $tests 개, skip $skipped 개 (결과 파일 $files 개)"
if [ "$skipped" -gt 0 ]; then
  echo "skip 된 테스트:" >&2
  find . -path '*/build/test-results/*/TEST-*.xml' -exec grep -l '<skipped' {} + >&2 || true
  exit 1
fi
