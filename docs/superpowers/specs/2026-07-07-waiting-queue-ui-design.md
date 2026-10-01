# 대기열 POC UI 설계 (대기실 + 모니터링 대시보드)

- 날짜: 2026-07-07
- 상태: 승인됨
- 목적: 대기열 POC의 동작(폴링 트레이드오프, 유량 입장)을 눈으로 검증할 수 있는 UI 2종을 추가한다.

## 배경

`load-test.sh`로 폭주 흡수·중복 제거·유량 입장은 검증했으나 관찰 수단이 CLI뿐이다.
유저 관점(대기실)과 운영 관점(대시보드) 화면을 추가해 데모와 검증을 쉽게 한다.

## 결정 사항

| 항목 | 선택 | 근거 |
|------|------|------|
| 서빙 | Spring 정적 리소스 (`src/main/resources/static/`) | 별도 서버·빌드·CORS 불필요, POC 규모에 최적 |
| 스택 | 순수 HTML/CSS/JS, 외부 라이브러리·CDN 없음 | 자급자족, 차트는 canvas 직접 렌더링 |
| 실시간성 | 1초 폴링 | POC 설계 철학(폴링, push 없음) 유지 |
| 대시보드 범위 | 조회 전용 | 조작(초기화·벌크 진입) API 추가 없음, 부하는 load-test.sh로 |

## 구성

```
src/main/resources/static/
├── index.html       # 대기실 (유저 관점)
└── dashboard.html   # 모니터링 대시보드 (운영 관점)

신규 백엔드: GET /queue/stats → {"waiting": N, "admitted": M}
```

### 1. 대기실 화면 — `index.html`

- 진입 전: userId 입력(기본값 자동 생성, 예: `guest-x7f3`) + "대기열 진입" 버튼
- 진입 후 1초 간격 `GET /queue/rank/{userId}` 폴링, 상태별 화면 전환:
  - `NOT_FOUND` → "접수 처리 중…" (Kafka 소비 전 — 비동기 배치의 트레이드오프 가시화)
  - `WAITING` → 내 순번 / 전체 대기 인원 + 진행률 표시
  - `ADMITTED` → 입장 완료 화면, 폴링 중단
- 새로고침 시 상태 유지하지 않음 (POC 단순화)

### 2. 모니터링 대시보드 — `dashboard.html`

- `GET /queue/stats` 1초 폴링
- 표시:
  - 대기 인원·입장 인원 숫자 타일
  - 최근 60초 추이 차트 (canvas)
  - 초당 입장 속도 (admitted 증가분을 클라이언트에서 계산)

### 3. 백엔드 변경 (최소)

- `WaitingQueueStore.admittedCount(): Long` 추가 — `SCARD wq:admitted` (O(1))
- `QueueController`에 `GET /queue/stats` 추가 — `totalWaiting()` + `admittedCount()` 반환
- 응답 형식: `{"waiting": 123, "admitted": 45}`

## 테스트

| 종류 | 대상 | 검증 |
|------|------|------|
| 단위 | stats 엔드포인트 | waiting/admitted 수치 반환 (기존 Mockito 스타일, TDD) |
| 수동 | UI 2종 | load-test.sh 실행하며 브라우저에서 대기실 상태 전환·대시보드 추이 관찰 |

## 알려진 한계 (POC 허용)

- `wq:admitted` TTL 10분 만료 시 입장 수치가 줄어들 수 있음
- 대시보드 추이는 브라우저 메모리 기반 — 새로고침 시 그래프 초기화
- 대기실 상태는 새로고침 시 소실 (localStorage 미사용)
