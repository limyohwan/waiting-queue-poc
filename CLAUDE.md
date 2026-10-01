# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

Kafka(완충·유실 방지) + Redis ZSET(중복 제거·순번·입장)을 조합한 대규모 대기열 POC.
상세 설계와 결정 근거는 `docs/superpowers/specs/2026-07-07-waiting-queue-poc-design.md` 참조.

- 스택: Kotlin / Spring Boot 4.1.0 / Java 25 (toolchain 자동 프로비저닝)
- 단일 Gradle 모듈, 패키지 루트: `poc.waitingqueue`

## 명령어

```bash
./gradlew build                 # 빌드 + 전체 테스트
./gradlew test                  # 전체 테스트
./gradlew test --tests "poc.waitingqueue.queue.RankServiceTest"                          # 단일 클래스
./gradlew test --tests "poc.waitingqueue.queue.RankServiceTest.대기 중이면*"              # 단일 메서드(패턴)
./gradlew bootRun               # 앱 실행 (아래 로컬 인프라 선행 필요)
./load-test.sh                  # 부하 테스트 (앱 기동 상태에서 실행, TOTAL/BASE_URL 환경변수로 조절)
```

### 로컬 인프라 (앱 실행 및 load-test.sh 전제 조건)

레포 루트의 `docker-compose.yml`로 띄운다:

```bash
docker compose up -d --build          # 앱(8080) + Redpanda + Valkey 전체
docker compose up -d redpanda valkey  # 인프라만 (앱은 bootRun) — Kafka: localhost:19092 / Valkey: localhost:6379
```

- Redis CLI 접근은 `docker exec valkey valkey-cli ...` (load-test.sh가 이 방식으로 검증)
- 통합 테스트는 이 스택과 무관 — EmbeddedKafka + Testcontainers(redis:7)를 사용하므로 Docker 데몬만 떠 있으면 된다

## 아키텍처 (Kafka 앞 — 3단 배치)

```
POST /queue/enter ── produce(key=userId) ──▶ Kafka: waiting-queue (파티션 3)
  (202 즉시 응답, Redis 안 씀)                    │ @KafkaListener (waiting-queue-worker)
                                                 ▼
GET /queue/rank/{userId} ◀── RankService    WaitingQueueConsumer ── ZADD NX ──▶ ZSET wq:waiting
  (ADMITTED / WAITING+rank / NOT_FOUND)          (score = 레코드 timestamp)
POST /queue/complete/{userId} ── ZREM ──▶     AdmissionScheduler(1초마다, 자리 기반)
  (자리 반납: COMPLETED/NOT_ACTIVE)               purge 만료 → 빈 자리(capacity-active)만큼
                                                 ZPOPMIN → ZSET wq:active (score=만료시각)
```

핵심 설계 결정 (수정 시 반드시 유지할 불변식):

1. **진입 API는 Redis를 건드리지 않는다** — 폭주는 Kafka가 흡수하고, Redis 쓰기 유량은 컨슈머가 결정한다.
2. **ZADD NX가 중복 제거의 단일 장치** — 유저의 중복 진입과 Kafka at-least-once 재전달을 동일하게 멱등 처리. 이미 대기 중인 유저의 순번(score)은 절대 변하지 않는다.
3. **score = Kafka 레코드 timestamp** — 브로커 접수 시각 기준 공정성. key=userId로 같은 유저는 같은 파티션에서 순서 보장.
4. **NOT_FOUND는 정상 응답** — 진입 직후 소비 전 폴링이면 발생하며 클라이언트가 재폴링한다(비동기 배치의 본질적 트레이드오프).
5. **자리(정원) 기반 입장** — 활성 인원을 `capacity`(기본 100) 이하로 유지한다. 스케줄러는 빈 자리(`capacity - activeCount`)만큼만 입장시키고, 완료(`/complete`)나 TTL 만료로 자리가 나야 다음 대기자가 입장한다. 활성 유저는 `wq:active` ZSET(score=만료시각 epoch ms)에 담기며, 만료분은 스케줄러가 `ZREMRANGEBYSCORE`로 청소한다("만료되는 SET" 패턴). 단일 스케줄러 인스턴스 전제(다중이면 정원 일시 초과 가능).

역할 분리: Redis 명령은 전부 `WaitingQueueStore`에만 존재하고, 나머지 컴포넌트(Consumer/RankService/AdmissionScheduler/Controller)는 Store를 통해서만 Redis에 접근한다. 설정은 `QueueProperties`(`queue.topic`, `queue.capacity`, `queue.active-ttl`)로 바인딩된다.

UI: `src/main/resources/static/`의 순수 HTML/JS 2장 — `index.html`(대기실, rank 폴링) / `dashboard.html`(모니터링, `GET /queue/stats` 폴링 + canvas 차트). 외부 라이브러리·CDN 금지. 정적 파일 수정 후에는 bootRun 재기동 필요.

## 테스트

- 단위 테스트: `WaitingQueueStore`를 Mockito mock으로 대체, 테스트명은 한글 백틱 스타일 (`` fun `신규 유저는 ...`() ``)
- 통합 테스트(`WaitingQueueIntegrationTest`): `@EmbeddedKafka` + Testcontainers `redis:7` + `@ServiceConnection`으로 진입→소비→순번→입장→완료 전 구간 검증. `queue.capacity=2`로 오버라이드해 "정원 2 유지 → 1명 완료 → 다음 대기자 입장" 흐름을 관찰한다.

## POC 범위 제외 (의도적 단순화 — 추가 구현하지 말 것)

WebSocket/SSE push(폴링으로 대체), 대기 중 이탈 API(완료 `/complete`와 별개), 인증/userId 검증.
