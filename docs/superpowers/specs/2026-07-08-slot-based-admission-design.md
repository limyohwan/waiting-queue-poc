# 자리(정원) 기반 입장 모델 설계

**작성일**: 2026-07-08
**상태**: 승인됨 (구현 계획 대기)

## 배경

현재 대기열은 **시간 기반 입장**이다 — `AdmissionScheduler`가 1초마다 `admit-per-tick`명을 무조건 `ZPOPMIN`해서 입장시킨다. 입장 유량이 시간에만 의존하고, 이미 입장한 유저가 "끝났는지"는 고려하지 않는다.

이를 **자리(정원) 기반 입장**으로 전환한다: 활성 인원을 최대 `capacity`(=100)명으로 유지하고, 완료(또는 TTL 만료)로 **자리가 비어야만** 다음 대기자가 입장한다. 티켓팅·게임 로그인 대기열의 동시 수용 인원 제한 모델이다.

## 결정 사항 (브레인스토밍 확정)

| 항목 | 결정 |
|------|------|
| 자리 회수 트리거 | **명시적 완료 API + TTL 안전망** |
| TTL 안전망 시간 | **5분** |
| 기존 시간 기반 로직 | **완전 대체** (공존 아님) |
| 정원(capacity) | **100** |

## 데이터 모델

핵심 변경: 활성 유저마다 **개별 TTL(5분)** 이 필요하다. 현재 `wq:admitted`(단일 TTL SET)로는 유저별 만료를 표현할 수 없다. 따라서 **"만료되는 SET" 패턴**으로 ZSET을 사용한다.

| 키 | 타입 | member / score | 용도 | 변경 |
|----|------|----------------|------|------|
| `wq:waiting` | ZSET | userId / 진입 timestamp | 대기열 (순번) | 변경 없음 |
| `wq:active` | ZSET | userId / **만료시각(epoch ms)** | 활성 유저 (서비스 중) | 기존 `wq:admitted` SET 대체 |

- score(만료시각)가 곧 TTL 안전망. 만료된 member는 스케줄러가 청소한다.
- 활성 인원 = 만료분 청소 후 `ZCARD wq:active`.

## 컴포넌트별 변경

### WaitingQueueStore (Redis 접근 계층)

모든 Redis 명령은 여전히 이 클래스에만 존재한다(불변식 유지). `wq:admitted` SET 관련 메서드를 `wq:active` ZSET 기준으로 교체한다.

| 메서드 | 동작 |
|--------|------|
| `addIfAbsent(userId, score)` | 변경 없음 (ZADD NX on wq:waiting) |
| `rankOf` / `totalWaiting` / `popNext` | 변경 없음 |
| `purgeExpiredActive(now)` | **신규** — `ZREMRANGEBYSCORE wq:active -inf {now}` (만료 자리 회수) |
| `activeCount()` | **변경** — `ZCARD wq:active` (기존 admittedCount 대체) |
| `activate(userIds, expireAt)` | **변경** — 꺼낸 유저를 `wq:active`에 score=expireAt로 ZADD (기존 admit 대체) |
| `isActive(userId)` | **변경** — `ZSCORE wq:active {userId}` 존재 여부 (기존 isAdmitted 대체) |
| `complete(userId): Boolean` | **신규** — `ZREM wq:active {userId}`, 제거 성공 여부 반환 |

### AdmissionScheduler (1초마다)

```
1. store.purgeExpiredActive(now)                 // 만료 자리 회수 (TTL 안전망)
2. val free = capacity - store.activeCount()
3. if (free <= 0) return
4. val users = store.popNext(free)
5. if (users.isNotEmpty()) store.activate(users, now + activeTtl)
```

활성 인원은 정원(100)을 넘지 않는다. 완료/만료로 자리가 나야만 대기자가 입장한다.

### QueueController

- **신규**: `POST /queue/complete/{userId}` → `store.complete(userId)` → `{"status":"COMPLETED"}` 또는 활성 아님이면 `{"status":"NOT_ACTIVE"}`
- 기존 `/queue/enter`, `/queue/rank/{userId}`, `/queue/stats` 변경 없음.

### RankService

- `isAdmitted` → `isActive` 참조로 변경. 상태 판정 순서·로직은 동일.
- `ADMITTED` 상태의 의미가 "현재 활성(서비스 중)"으로 바뀐다(누적이 아니라 ≤100 유지).
- `stats()`의 `admitted` 필드는 **이름 유지, 의미만 "현재 활성 수"로 변경** → UI(dashboard.html) 무변경.

### WaitingQueueConsumer

- 재진입 차단 체크 `isAdmitted` → `isActive`. **현재 활성 유저만** 재큐잉 차단하고, 완료한 유저는 재진입 허용한다.

### QueueMetrics

- 코드 무변경. `waiting_queue_admitted` 게이지는 `activeCount()`를 읽으므로 자동으로 "현재 활성 수"를 노출한다.

### 설정 (QueueProperties / application.yml)

```yaml
queue:
  topic: waiting-queue
  capacity: 100        # admit-per-tick 대체
  active-ttl: 5m       # TTL 안전망
```

`QueueProperties`: `admitPerTick: Long` 제거, `capacity: Long` + `activeTtl: Duration` 추가.

## 상태 전이

```
(없음) ──enter──▶ waiting ──scheduler(빈자리)──▶ active ──complete/TTL만료──▶ (없음, 재진입 가능)
```

- `rank/{userId}`: active면 ADMITTED, waiting이면 WAITING+순번, 둘 다 아니면 NOT_FOUND
- 완료 후에는 NOT_FOUND (완료 상태를 별도 추적하지 않음 — YAGNI)

## POC 트레이드오프 (의도적 단순화)

- **단일 스케줄러 인스턴스 전제**: 다중 인스턴스면 스케줄러의 `activeCount()`와 `popNext()` 사이 레이스로 정원(100)을 일시 초과할 수 있다. Lua 스크립트로 원자화 가능하나 POC 범위에서는 제외.
- 완료 상태 영속 추적 안 함: 완료한 유저는 즉시 재진입 가능 상태가 된다.

## 영향 범위

- 소스: `QueueProperties`, `WaitingQueueStore`, `AdmissionScheduler`, `RankService`, `QueueController` (QueueMetrics는 의미만 변경)
- 설정: `application.yml`
- 테스트: `RankServiceTest`, `AdmissionSchedulerTest`, `QueueControllerTest`, `WaitingQueueConsumerTest`, `WaitingQueueIntegrationTest` 업데이트
- 문서: `CLAUDE.md`의 아키텍처·불변식 섹션 갱신

## 검증 방법

- 단위 테스트: 빈 자리만큼만 입장, 정원 초과 안 함, 완료 시 자리 반납, 만료 자리 회수
- 통합 테스트: `capacity=2`로 오버라이드 → 진입 다수 → 2명만 active → 1명 complete → 1명 추가 입장 확인
- 부하 테스트: 1만 건 진입 → active가 100에서 유지되는지, complete 호출로 대기가 빠지는지 관찰
