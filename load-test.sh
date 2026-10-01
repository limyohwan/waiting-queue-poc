#!/usr/bin/env bash
# 대기열 부하 테스트 — 500 요청(유니크 400 + 중복 100)을 동시 발사해
# 폭주 흡수(Kafka), 중복 제거(ZADD NX), 순번 조회, 자리 기반 입장(활성 ≤ 정원)을 검증한다.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TOTAL="${TOTAL:-500}"
UNIQUE=$((TOTAL * 8 / 10))
CONCURRENCY=50

echo "== ${TOTAL}건 동시 발사 (유니크 ${UNIQUE}명, 중복 $((TOTAL - UNIQUE))건, 동시성 ${CONCURRENCY})"
seq 1 "$TOTAL" \
  | awk -v u="$UNIQUE" '{ id = ($1 > u) ? $1 - u : $1; print "user-" id }' \
  | xargs -P "$CONCURRENCY" -I{} curl -s -o /dev/null -X POST "$BASE_URL/queue/enter" \
      -H "Content-Type: application/json" -d '{"userId":"{}"}'
echo "발사 완료"

echo
echo "== 3초 후 Redis 상태 (컨슈머 소비 + 자리 기반 입장 진행 중)"
sleep 3
WAITING=$(docker exec valkey valkey-cli ZCARD wq:waiting)
ACTIVE=$(docker exec valkey valkey-cli ZCARD wq:active)
echo "대기 중: ${WAITING}명 / 활성(입장): ${ACTIVE}명 / 합계: $((WAITING + ACTIVE))명"
echo "  (기대: 활성은 정원 이하로 유지, 합계 = ${UNIQUE}명 — 중복 제거 검증)"

echo
echo "== 순번 조회 샘플"
for u in user-1 user-$((UNIQUE / 2)) user-$UNIQUE; do
  printf "%s: " "$u"
  curl -s "$BASE_URL/queue/rank/$u"
  echo
done
