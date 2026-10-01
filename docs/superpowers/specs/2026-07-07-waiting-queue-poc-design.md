# 대기열(Waiting Queue) POC 설계

- 날짜: 2026-07-07
- 상태: 승인 대기
- 목적: Kafka(완충·유실 방지) + Redis ZSET(중복 제거·순번·입장)을 조합한 대규모 대기열의 표준 아키텍처를 로컬에서 검증한다.

## 배경

"Kafka 앞 / Redis 뒤" 3단 배치로 폭주 흡수 + 멱등 쓰기(at-least-once × ZADD NX)를 검증한다.

## 스택

| 항목 | 선택 | 근거 |
|------|------|------|
| 언어/프레임워크 | Kotlin / Spring Boot **4.1.0** | 현재 최신 GA (start.spring.io 기본값) |
| JVM | Java **25** (LTS) | 최신 LTS. toolchain 자동 프로비저닝 |
| 메시징 | spring-kafka (Boot BOM 관리) | 로컬 Redpanda(`localhost:19092`) 재사용 |
| 상태 저장소 | Spring Data Redis + **Valkey 8** | `~/workspace/valkey-local` 독립 compose (redpanda-local과 대칭), RESP 호환이라 코드 동일 |
| 스캐폴딩 | start.spring.io (web, data-redis, kafka) | 버전 궁합 보장 |

## 아키텍처 (Kafka 앞 — 3단 표준형)

```
클라이언트
  │ POST /queue/enter {userId}
  ▼
진입 API ─── produce(key=userId) ───▶ Kafka: waiting-queue (파티션 3)
  │ 202 ACCEPTED 즉시 응답                 │ consume (그룹 waiting-queue-worker)
  ▼                                       ▼
클라이언트 폴링                        대기열 컨슈머 ── ZADD NX ──▶ Redis ZSET: wq:waiting
  │ GET /queue/rank/{userId}                                        (score=레코드 timestamp)
  ▼
순번 API ── ZRANK/ZCARD + SISMEMBER ──▶ Redis        입장 스케줄러(1초마다)
                                          ▲            ZPOPMIN N명 → SADD wq:admitted (TTL 10분)
                                          └────────────┘
```

### 설계 결정

1. **API는 Redis를 쓰지 않는다** (진입 시). 폭주는 전부 Kafka가 흡수하고, Redis 쓰기 유량은 컨슈머가 결정한다.
2. **key=userId** — 같은 유저의 이벤트는 같은 파티션에서 순서 보장.
3. **ZADD NX가 중복 제거의 단일 장치** — 유저의 중복 진입 요청과 Kafka at-least-once 재전달을 동일하게 무해화(멱등).
4. **score = Kafka 레코드 timestamp** — 진입 접수 시각 기준 공정성. 동점은 ZSET의 member 사전순으로 결정(POC에서 허용).
5. **순번 응답은 비동기** — 진입 직후 폴링 시 소비 전이면 `NOT_FOUND`가 정상이며 클라이언트는 재폴링한다(이 배치의 본질적 트레이드오프).

## 구성 요소

### 1. 진입 API — `POST /queue/enter`
- 요청: `{"userId": "user-123"}` (비어있으면 400)
- 동작: `waiting-queue` 토픽에 key=userId, value=userId 발행
- 응답: `202 {"status": "ACCEPTED"}` (발행 실패 시 500)

### 2. 대기열 컨슈머 — `@KafkaListener(topics="waiting-queue", groupId="waiting-queue-worker")`
- `ZADD NX wq:waiting score=record.timestamp() member=userId`
- 이미 존재(중복)면 무시 — 순번 불변

### 3. 순번 API — `GET /queue/rank/{userId}`
- `wq:admitted`에 있으면 → `{"status":"ADMITTED"}`
- `wq:waiting`에 있으면 → `{"status":"WAITING","rank":<ZRANK+1>,"total":<ZCARD>}` (rank는 1부터)
- 둘 다 없으면 → `{"status":"NOT_FOUND"}` (소비 전 또는 미진입 — 재폴링 대상)

### 4. 입장 스케줄러 — `@Scheduled(fixedDelay=1000)`
- `ZPOPMIN wq:waiting N` (N = `queue.admit-per-tick`, 기본 10)
- 꺼낸 유저를 `wq:admitted` SET에 추가, SET TTL 10분 갱신
- 초당 N명 입장이라는 유량 제어 시뮬레이션

### 5. 부하 스크립트 — `load-test.sh`
- 유저 500명 진입을 동시 발사, 이 중 20%는 중복 userId
- 완료 후 출력: `ZCARD wq:waiting + SCARD wq:admitted`(중복 제거 검증: 유니크 400명), 순번 조회 샘플 3건
- 사용 도구: curl + xargs 병렬

## 설정 (application.yml)

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:19092
    # key/value 모두 String 직렬화
  data:
    redis:
      host: localhost
      port: 6379
queue:
  topic: waiting-queue
  admit-per-tick: 10
```

## 테스트 (TDD)

| 종류 | 대상 | 검증 |
|------|------|------|
| 단위 | 컨슈머 | 신규 유저 ZADD / 중복 유저 무시(순번 불변) |
| 단위 | 순번 서비스 | ADMITTED / WAITING(rank·total) / NOT_FOUND 3분기 |
| 단위 | 입장 스케줄러 | tick당 N명만 admitted로 이동, waiting에서 제거 |
| 통합 | 전 구간 | EmbeddedKafka + Testcontainers Redis: 진입→소비→순번→입장 1시나리오 |

## POC 범위 제외 (의도적 단순화)

- 입장 후 재진입 방지(admitted 체크는 조회 전용)
- WebSocket/SSE push — 폴링으로 대체
- 대기열 이탈(취소) API
- 인증/userId 검증 — 클라이언트 제공 문자열 신뢰
- Redis 영속화(AOF)·Kafka 재구축 시나리오 — 문서 언급만

## 완료 기준

1. `docker compose up`(redis) + 기동 중인 Redpanda로 앱이 뜬다
2. 전체 테스트 통과
3. `load-test.sh` 실행 시: 500 요청 → 유니크 400명만 대기열 등록, 순번 조회 정상, 초당 10명씩 입장
