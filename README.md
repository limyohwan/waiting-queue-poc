# waiting-queue-poc

Kafka(폭주 흡수·유실 방지) + Redis ZSET(중복 제거·순번·입장)을 조합한 대규모 대기열 PoC.

- Kotlin / Spring Boot 4.1 / Java 25
- Kafka 호환: Redpanda, Redis 호환: Valkey

## 아키텍처

```
POST /queue/enter ── produce(key=userId) ──▶ Kafka: waiting-queue (파티션 3)
  (202 즉시 응답, Redis 안 씀)                    │ @KafkaListener
                                                 ▼
GET /queue/rank/{userId} ◀── RankService    WaitingQueueConsumer ── ZADD NX ──▶ ZSET wq:waiting
                                                 (score = 레코드 timestamp)
POST /queue/complete/{userId} ── ZREM ──▶     AdmissionScheduler (1초마다)
  (자리 반납)                                     빈 자리(capacity - active)만큼
                                                 ZPOPMIN → ZSET wq:active (score = 만료시각)
```

- **진입 API는 Redis를 건드리지 않는다**: 폭주는 Kafka가 흡수하고, Redis 쓰기 속도는 컨슈머가 정한다.
- **ZADD NX로 중복 제거**: 유저 중복 진입과 Kafka 재전달을 같은 방식으로 멱등 처리한다. 이미 대기 중인 유저의 순번은 바뀌지 않는다.
- **자리(정원) 기반 입장**: 활성 인원을 `queue.capacity`(기본 100) 이하로 유지한다. 완료 호출이나 TTL(`queue.active-ttl`, 기본 5분) 만료로 자리가 나야 다음 대기자가 들어온다.

자세한 설계는 [`docs/superpowers/specs`](docs/superpowers/specs) 참고.

## 실행

Docker만 있으면 된다.

```bash
docker compose up -d --build
```

- 대기실 UI: http://localhost:8080/index.html
- 모니터링 대시보드: http://localhost:8080/dashboard.html

앱을 로컬에서 직접 띄우려면 인프라만 올리고 `bootRun` 한다 (JDK 25는 Gradle이 자동으로 받는다).

```bash
docker compose up -d redpanda valkey
./gradlew bootRun
```

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/queue/enter` | 대기열 진입 (`{"userId":"user-1"}`), 202 응답 |
| GET | `/queue/rank/{userId}` | 상태 조회: `ADMITTED` / `WAITING`+순번 / `NOT_FOUND` |
| POST | `/queue/complete/{userId}` | 이용 완료, 자리 반납 |
| GET | `/queue/stats` | 대기·활성 인원 통계 |

진입 직후 컨슈머가 아직 소비하지 않았으면 `NOT_FOUND`가 나온다. 정상 동작이며 클라이언트가 다시 폴링하면 된다.

## 부하 테스트

앱이 떠 있는 상태에서 실행한다. 500건(유니크 400 + 중복 100)을 동시에 보내고 중복 제거와 정원 유지를 확인한다.

```bash
./load-test.sh                 # TOTAL=1000 ./load-test.sh 처럼 건수 조절 가능
```

## 테스트

```bash
./gradlew test
```

통합 테스트는 EmbeddedKafka + Testcontainers(redis:7)를 쓰므로 Docker 데몬이 떠 있어야 한다.

## 범위 밖 (의도적 단순화)

WebSocket/SSE 푸시(폴링으로 대체), 대기 중 이탈 API, 인증/userId 검증, 다중 스케줄러 인스턴스.
