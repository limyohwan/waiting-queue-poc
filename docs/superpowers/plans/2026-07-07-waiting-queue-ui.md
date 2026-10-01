# 대기열 UI (대기실 + 모니터링 대시보드) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 대기열 POC에 유저 관점 대기실 화면과 운영 관점 모니터링 대시보드를 추가한다.

**Architecture:** Spring 정적 리소스(`src/main/resources/static/`)로 HTML 2장을 서빙하고, 대시보드용 집계 API `GET /queue/stats` 하나만 백엔드에 추가한다. 실시간성은 1초 폴링(설계 철학 유지), 차트는 canvas 직접 렌더링.

**Tech Stack:** Kotlin/Spring Boot 4.1.0 (기존), 순수 HTML/CSS/JS (외부 라이브러리·CDN 없음)

## Global Constraints

- 외부 라이브러리·CDN 금지 — 모든 CSS/JS는 파일 안에 인라인
- 폴링 간격 1초 (대기실 rank 폴링, 대시보드 stats 폴링 동일)
- UI 카피는 한글
- 백엔드 테스트는 기존 스타일(Mockito mock, 한글 백틱 테스트명) 유지
- 커밋 메시지는 한글 `feat: ...` 컨벤션
- 차트 팔레트는 검증 완료된 값 사용: 대기=blue(`#2a78d6`/dark `#3987e5`), 입장=aqua(`#1baf7a`/dark `#199e70`). light 모드 aqua는 대비 3:1 미만이므로 직접 라벨(잉크색 텍스트 + 색상 점) 필수
- 텍스트는 항상 잉크 토큰 색(시리즈 색 금지), 색상 점(chip)이 시리즈 식별을 담당

---

### Task 1: 통계 API — `GET /queue/stats`

**Files:**
- Modify: `src/main/kotlin/poc/waitingqueue/queue/WaitingQueueStore.kt` (admittedCount 추가)
- Modify: `src/main/kotlin/poc/waitingqueue/queue/RankService.kt` (StatsResponse + stats 추가)
- Modify: `src/main/kotlin/poc/waitingqueue/api/QueueController.kt` (stats 엔드포인트 추가)
- Test: `src/test/kotlin/poc/waitingqueue/queue/RankServiceTest.kt`
- Test: `src/test/kotlin/poc/waitingqueue/api/QueueControllerTest.kt`

**Interfaces:**
- Consumes: `WaitingQueueStore.totalWaiting(): Long` (기존)
- Produces: `GET /queue/stats` → `200 {"waiting": <Long>, "admitted": <Long>}` — Task 3 대시보드가 이 형식을 폴링한다. `RankService.stats(): StatsResponse`, `WaitingQueueStore.admittedCount(): Long`

- [ ] **Step 1: 실패하는 테스트 작성 — RankServiceTest**

`src/test/kotlin/poc/waitingqueue/queue/RankServiceTest.kt`에 테스트 추가:

```kotlin
@Test
fun `통계는 대기 인원과 입장 인원을 반환한다`() {
    `when`(store.totalWaiting()).thenReturn(3L)
    `when`(store.admittedCount()).thenReturn(7L)

    assertEquals(StatsResponse(waiting = 3, admitted = 7), sut.stats())
}
```

(기존 파일의 import·mock 구성을 그대로 사용. `assertEquals`는 `kotlin.test.assertEquals` — 파일에 없으면 import 추가)

- [ ] **Step 2: 실패하는 테스트 작성 — QueueControllerTest**

`src/test/kotlin/poc/waitingqueue/api/QueueControllerTest.kt`에 테스트 추가:

```kotlin
@Test
fun `통계 조회는 RankService에 위임한다`() {
    `when`(rankService.stats()).thenReturn(StatsResponse(waiting = 3, admitted = 7))

    assertEquals(StatsResponse(waiting = 3, admitted = 7), sut.stats())
}
```

import 추가: `import poc.waitingqueue.queue.StatsResponse`

- [ ] **Step 3: 컴파일 실패(=RED) 확인**

Run: `./gradlew test --tests "poc.waitingqueue.queue.RankServiceTest" --tests "poc.waitingqueue.api.QueueControllerTest"`
Expected: FAIL — `admittedCount`, `StatsResponse`, `stats` 미정의 컴파일 에러

- [ ] **Step 4: 최소 구현**

`WaitingQueueStore.kt`에 추가 (isAdmitted 아래):

```kotlin
fun admittedCount(): Long = redis.opsForSet().size(ADMITTED_KEY) ?: 0
```

`RankService.kt`에 추가 — 파일 상단 `RankResponse` 아래에 data class, 클래스 안에 메서드:

```kotlin
data class StatsResponse(
    val waiting: Long,
    val admitted: Long,
)
```

```kotlin
fun stats(): StatsResponse =
    StatsResponse(waiting = store.totalWaiting(), admitted = store.admittedCount())
```

`QueueController.kt`에 추가 (rank 메서드 아래):

```kotlin
@GetMapping("/stats")
fun stats(): StatsResponse = rankService.stats()
```

import 추가: `import poc.waitingqueue.queue.StatsResponse`

- [ ] **Step 5: 테스트 통과(=GREEN) 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (전체 테스트 통과)

- [ ] **Step 6: 커밋**

```bash
git add src/main/kotlin src/test/kotlin
git commit -m "feat: 대기열 통계 API 추가 (GET /queue/stats)"
```

---

### Task 2: 대기실 화면 — `static/index.html`

**Files:**
- Create: `src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `POST /queue/enter` `{"userId": string}` → `202 {"status":"ACCEPTED"}` | `400`; `GET /queue/rank/{userId}` → `{"status":"ADMITTED"|"WAITING"|"NOT_FOUND","rank":Long?,"total":Long?}` (기존 API)
- Produces: `http://localhost:8080/` 에서 서빙되는 대기실 페이지

- [ ] **Step 1: index.html 작성**

아래 전체 내용으로 `src/main/resources/static/index.html` 생성:

```html
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>입장 대기열</title>
<style>
:root {
  --plane: #f9f9f7; --surface: #fcfcfb; --ink: #0b0b0b; --ink-2: #52514e;
  --muted: #898781; --line: #e1e0d9; --ring: rgba(11,11,11,.10);
  --accent: #2a78d6; --ok: #006300;
}
@media (prefers-color-scheme: dark) {
  :root {
    --plane: #0d0d0d; --surface: #1a1a19; --ink: #ffffff; --ink-2: #c3c2b7;
    --muted: #898781; --line: #2c2c2a; --ring: rgba(255,255,255,.10);
    --accent: #3987e5; --ok: #0ca30c;
  }
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body {
  font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
  background: var(--plane); color: var(--ink);
  min-height: 100vh; display: grid; place-items: center; padding: 24px;
}
.card {
  width: min(420px, 100%); background: var(--surface);
  border: 1px solid var(--ring); border-radius: 20px;
  padding: 48px 32px 40px; text-align: center;
  box-shadow: 0 1px 2px var(--ring);
}
.eyebrow { font-size: 13px; letter-spacing: .12em; color: var(--muted); text-transform: uppercase; }
h1 { font-size: 22px; font-weight: 700; margin: 8px 0 32px; }
[hidden] { display: none !important; }

/* 진입 전 */
label { display: block; font-size: 13px; color: var(--ink-2); text-align: left; margin-bottom: 6px; }
input {
  width: 100%; padding: 12px 14px; font-size: 15px; font-family: inherit;
  color: var(--ink); background: var(--plane);
  border: 1px solid var(--line); border-radius: 10px; outline: none;
}
input:focus { border-color: var(--accent); }
button {
  width: 100%; margin-top: 16px; padding: 14px; font-size: 15px; font-weight: 600;
  font-family: inherit; color: #fff; background: var(--accent);
  border: none; border-radius: 10px; cursor: pointer;
}
button:hover { filter: brightness(1.08); }
button:active { transform: translateY(1px); }
.error { margin-top: 12px; font-size: 13px; color: #d03b3b; min-height: 18px; }

/* 대기 중 */
.pulse {
  display: inline-block; width: 8px; height: 8px; border-radius: 50%;
  background: var(--accent); margin-right: 8px; vertical-align: 2px;
  animation: pulse 1.2s ease-in-out infinite;
}
@keyframes pulse { 0%,100% { opacity: .3 } 50% { opacity: 1 } }
@media (prefers-reduced-motion: reduce) { .pulse { animation: none } }
.rank-label { font-size: 14px; color: var(--ink-2); }
.rank-num { font-size: 72px; font-weight: 800; line-height: 1.1; margin: 4px 0; }
.rank-total { font-size: 14px; color: var(--muted); margin-bottom: 28px; }
.bar { height: 6px; background: var(--line); border-radius: 3px; overflow: hidden; }
.bar > div { height: 100%; width: 0%; background: var(--accent); border-radius: 3px; transition: width .6s ease; }
.hint { margin-top: 24px; font-size: 12px; color: var(--muted); }
.uid { font-size: 12px; color: var(--muted); margin-top: 8px; }

/* 입장 완료 */
.check {
  width: 64px; height: 64px; margin: 0 auto 20px; border-radius: 50%;
  background: var(--ok); color: #fff; font-size: 32px; line-height: 64px;
}
.admitted-msg { font-size: 15px; color: var(--ink-2); }
</style>
</head>
<body>
<main class="card">
  <p class="eyebrow">Waiting Queue POC</p>

  <!-- 상태 1: 진입 전 -->
  <section id="view-enter">
    <h1>입장 대기열</h1>
    <label for="userId">사용자 ID</label>
    <input id="userId" autocomplete="off" spellcheck="false">
    <button id="enterBtn">대기열 진입</button>
    <p id="enterError" class="error"></p>
  </section>

  <!-- 상태 2: 접수 처리 중 (NOT_FOUND 폴링 구간) -->
  <section id="view-pending" hidden>
    <h1>접수 처리 중</h1>
    <p class="rank-label"><span class="pulse"></span>대기열에 등록하고 있어요…</p>
    <p class="hint">진입 요청이 브로커를 거쳐 처리되는 중입니다. 잠시만 기다려 주세요.</p>
    <p class="uid" id="pendingUid"></p>
  </section>

  <!-- 상태 3: 대기 중 -->
  <section id="view-waiting" hidden>
    <h1>내 순서를 기다리고 있어요</h1>
    <p class="rank-label">내 순번</p>
    <p class="rank-num" id="rank">–</p>
    <p class="rank-total" id="total"></p>
    <div class="bar"><div id="progress"></div></div>
    <p class="hint"><span class="pulse"></span>초당 일정 인원씩 자동 입장됩니다. 화면을 닫지 마세요.</p>
    <p class="uid" id="waitingUid"></p>
  </section>

  <!-- 상태 4: 입장 완료 -->
  <section id="view-admitted" hidden>
    <div class="check">✓</div>
    <h1>입장이 완료되었습니다</h1>
    <p class="admitted-msg">서비스로 이동할 수 있어요.</p>
    <p class="uid" id="admittedUid"></p>
  </section>
</main>

<script>
const $ = (id) => document.getElementById(id);
const views = ["view-enter", "view-pending", "view-waiting", "view-admitted"];
const show = (id) => views.forEach((v) => { $(v).hidden = v !== id; });

$("userId").value = "guest-" + Math.random().toString(36).slice(2, 6);

let timer = null;
let firstTotal = null; // 진행률 기준점: 처음 관측한 전체 대기 인원

async function enter() {
  const userId = $("userId").value.trim();
  if (!userId) { $("enterError").textContent = "사용자 ID를 입력해 주세요."; return; }
  $("enterError").textContent = "";
  try {
    const res = await fetch("/queue/enter", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId }),
    });
    if (!res.ok) throw new Error("HTTP " + res.status);
  } catch (e) {
    $("enterError").textContent = "진입 요청에 실패했어요. 잠시 후 다시 시도해 주세요.";
    return;
  }
  ["pendingUid", "waitingUid", "admittedUid"].forEach((id) => { $(id).textContent = "ID: " + userId; });
  show("view-pending");
  timer = setInterval(() => poll(userId), 1000);
  poll(userId);
}

async function poll(userId) {
  let data;
  try {
    const res = await fetch("/queue/rank/" + encodeURIComponent(userId));
    if (!res.ok) throw new Error("HTTP " + res.status);
    data = await res.json();
  } catch (e) {
    return; // 일시 오류는 다음 폴링에서 복구
  }
  if (data.status === "ADMITTED") {
    clearInterval(timer);
    show("view-admitted");
  } else if (data.status === "WAITING") {
    $("rank").textContent = data.rank.toLocaleString();
    $("total").textContent = "전체 대기 " + data.total.toLocaleString() + "명";
    if (firstTotal === null) firstTotal = Math.max(data.rank, data.total);
    const done = Math.max(0, Math.min(1, 1 - (data.rank - 1) / firstTotal));
    $("progress").style.width = (done * 100).toFixed(1) + "%";
    show("view-waiting");
  }
  // NOT_FOUND: 접수 처리 중 화면 유지, 재폴링
}

$("enterBtn").addEventListener("click", enter);
$("userId").addEventListener("keydown", (e) => { if (e.key === "Enter") enter(); });
</script>
</body>
</html>
```

- [ ] **Step 2: 앱 재기동 및 서빙 확인**

기존 bootRun 프로세스를 종료 후 재기동 (`./gradlew bootRun` 백그라운드), 이후:

Run: `curl -s http://localhost:8080/ | grep '<title>'`
Expected: `<title>입장 대기열</title>`

- [ ] **Step 3: 브라우저 동작 확인**

브라우저(Playwright MCP 가능)에서 `http://localhost:8080/` 열기:
1. 자동 생성된 userId 확인, "대기열 진입" 클릭
2. "접수 처리 중" 또는 "대기 중" 화면 전환 확인 (대기 인원이 없으면 1~2초 내 바로 입장 완료로 전환됨)
3. 최종적으로 "입장이 완료되었습니다" 화면 확인
4. 스크린샷 캡처

- [ ] **Step 4: 커밋**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: 대기실 화면 추가 (진입·순번 폴링·입장 완료)"
```

---

### Task 3: 모니터링 대시보드 — `static/dashboard.html`

**Files:**
- Create: `src/main/resources/static/dashboard.html`

**Interfaces:**
- Consumes: `GET /queue/stats` → `200 {"waiting": Long, "admitted": Long}` (Task 1 산출물)
- Produces: `http://localhost:8080/dashboard.html` 에서 서빙되는 조회 전용 대시보드

**차트 규칙 (dataviz 검증 결과 반영):**
- 시리즈 색: 대기=`--series-wait`(blue `#2a78d6`/dark `#3987e5`), 입장=`--series-admit`(aqua `#1baf7a`/dark `#199e70`) — 검증 스크립트 PASS
- light 모드 aqua 대비 2.74:1(WARN) → 직접 라벨 필수: 선 끝에 색상 점 + 잉크색 텍스트
- 텍스트(값·라벨·범례)는 잉크 토큰, 시리즈 색은 점/선만
- 2px 선, 은은한 그리드, 단일 y축, hover 크로스헤어 + 툴팁

- [ ] **Step 1: dashboard.html 작성**

아래 전체 내용으로 `src/main/resources/static/dashboard.html` 생성:

```html
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>대기열 모니터링</title>
<style>
:root {
  --plane: #f9f9f7; --surface: #fcfcfb; --ink: #0b0b0b; --ink-2: #52514e;
  --muted: #898781; --grid: #e1e0d9; --axis: #c3c2b7; --ring: rgba(11,11,11,.10);
  --series-wait: #2a78d6; --series-admit: #1baf7a; --bad: #d03b3b;
}
@media (prefers-color-scheme: dark) {
  :root {
    --plane: #0d0d0d; --surface: #1a1a19; --ink: #ffffff; --ink-2: #c3c2b7;
    --muted: #898781; --grid: #2c2c2a; --axis: #383835; --ring: rgba(255,255,255,.10);
    --series-wait: #3987e5; --series-admit: #199e70; --bad: #e66767;
  }
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body {
  font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
  background: var(--plane); color: var(--ink);
  min-height: 100vh; padding: 32px clamp(16px, 4vw, 48px);
}
header { display: flex; align-items: baseline; gap: 12px; margin-bottom: 24px; flex-wrap: wrap; }
h1 { font-size: 20px; font-weight: 700; }
.conn { font-size: 12px; color: var(--muted); }
.conn.down { color: var(--bad); }

.tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-bottom: 20px; }
.tile { background: var(--surface); border: 1px solid var(--ring); border-radius: 14px; padding: 18px 20px; }
.tile .k { font-size: 12px; color: var(--muted); display: flex; align-items: center; gap: 6px; }
.tile .v { font-size: 34px; font-weight: 800; margin-top: 4px; font-variant-numeric: tabular-nums; }
.tile .u { font-size: 13px; font-weight: 400; color: var(--ink-2); margin-left: 2px; }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dot.wait { background: var(--series-wait); }
.dot.admit { background: var(--series-admit); }

.panel { background: var(--surface); border: 1px solid var(--ring); border-radius: 14px; padding: 20px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; flex-wrap: wrap; gap: 8px; }
.panel h2 { font-size: 14px; font-weight: 600; }
.legend { display: flex; gap: 16px; font-size: 12px; color: var(--ink-2); }
.legend span { display: inline-flex; align-items: center; gap: 6px; }
.chart-wrap { position: relative; height: 260px; }
canvas { width: 100%; height: 100%; display: block; }
.tooltip {
  position: absolute; pointer-events: none; visibility: hidden;
  background: var(--surface); border: 1px solid var(--ring); border-radius: 8px;
  padding: 8px 10px; font-size: 12px; color: var(--ink-2);
  box-shadow: 0 2px 8px var(--ring); white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.tooltip b { color: var(--ink); font-weight: 600; }
</style>
</head>
<body>
<header>
  <h1>대기열 모니터링</h1>
  <span class="conn" id="conn">연결 중…</span>
</header>

<div class="tiles">
  <div class="tile"><div class="k"><span class="dot wait"></span>대기 인원</div><div class="v" id="waiting">–</div></div>
  <div class="tile"><div class="k"><span class="dot admit"></span>입장 인원</div><div class="v" id="admitted">–</div></div>
  <div class="tile"><div class="k">초당 입장 속도 <span style="font-weight:400">(최근 5초 평균)</span></div><div class="v" id="rate">–<span class="u">명/초</span></div></div>
</div>

<section class="panel">
  <div class="panel-head">
    <h2>최근 60초 추이</h2>
    <div class="legend">
      <span><span class="dot wait"></span>대기</span>
      <span><span class="dot admit"></span>입장</span>
    </div>
  </div>
  <div class="chart-wrap">
    <canvas id="chart"></canvas>
    <div class="tooltip" id="tooltip"></div>
  </div>
</section>

<script>
const $ = (id) => document.getElementById(id);
const MAX_POINTS = 60;
const samples = []; // { waiting, admitted } 1초 간격, 최대 60개
const rates = [];   // 최근 5개 admitted 증가분
let lastAdmitted = null;
let hoverIndex = null;

const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

async function poll() {
  let s;
  try {
    const res = await fetch("/queue/stats");
    if (!res.ok) throw new Error("HTTP " + res.status);
    s = await res.json();
    $("conn").textContent = "1초마다 갱신 중";
    $("conn").classList.remove("down");
  } catch (e) {
    $("conn").textContent = "서버 응답 없음 — 재시도 중";
    $("conn").classList.add("down");
    return;
  }
  if (lastAdmitted !== null) {
    rates.push(Math.max(0, s.admitted - lastAdmitted));
    if (rates.length > 5) rates.shift();
  }
  lastAdmitted = s.admitted;
  samples.push({ waiting: s.waiting, admitted: s.admitted });
  if (samples.length > MAX_POINTS) samples.shift();

  $("waiting").textContent = s.waiting.toLocaleString();
  $("admitted").textContent = s.admitted.toLocaleString();
  const avg = rates.length ? rates.reduce((a, b) => a + b, 0) / rates.length : 0;
  $("rate").innerHTML = avg.toFixed(1) + '<span class="u">명/초</span>';
  draw();
}

const PAD = { top: 12, right: 76, bottom: 22, left: 44 };

function draw() {
  const canvas = $("chart");
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  canvas.width = w * dpr; canvas.height = h * dpr;
  const ctx = canvas.getContext("2d");
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);
  ctx.font = '11px system-ui, -apple-system, "Segoe UI", sans-serif';
  if (samples.length < 2) return;

  const plotW = w - PAD.left - PAD.right, plotH = h - PAD.top - PAD.bottom;
  const maxVal = Math.max(1, ...samples.map((p) => Math.max(p.waiting, p.admitted)));
  const step = niceStep(maxVal / 4);
  const yMax = Math.ceil(maxVal / step) * step;
  const x = (i) => PAD.left + (i / (MAX_POINTS - 1)) * plotW;
  const y = (v) => PAD.top + plotH - (v / yMax) * plotH;
  const offset = MAX_POINTS - samples.length; // 오른쪽 정렬: 최신이 항상 우측 끝

  // 그리드 + y라벨 (은은하게)
  ctx.strokeStyle = css("--grid"); ctx.lineWidth = 1;
  ctx.fillStyle = css("--muted"); ctx.textAlign = "right"; ctx.textBaseline = "middle";
  for (let v = 0; v <= yMax; v += step) {
    ctx.beginPath(); ctx.moveTo(PAD.left, y(v)); ctx.lineTo(w - PAD.right, y(v)); ctx.stroke();
    ctx.fillText(v.toLocaleString(), PAD.left - 8, y(v));
  }
  // x라벨 (초 전)
  ctx.textAlign = "center"; ctx.textBaseline = "top";
  [-60, -30, 0].forEach((sec) => {
    const i = MAX_POINTS - 1 + sec;
    if (i < 0) return;
    ctx.fillText(sec === 0 ? "지금" : `${-sec}초 전`, x(i), PAD.top + plotH + 8);
  });

  // 시리즈 (2px 선)
  const series = [
    { key: "waiting", color: css("--series-wait"), label: "대기" },
    { key: "admitted", color: css("--series-admit"), label: "입장" },
  ];
  series.forEach((sr) => {
    ctx.strokeStyle = sr.color; ctx.lineWidth = 2;
    ctx.lineJoin = "round"; ctx.lineCap = "round";
    ctx.beginPath();
    samples.forEach((p, i) => {
      const px = x(offset + i), py = y(p[sr.key]);
      i === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py);
    });
    ctx.stroke();
  });

  // 직접 라벨: 선 끝에 색상 점 + 잉크색 텍스트 (겹치면 세로로 밀어냄)
  const last = samples[samples.length - 1];
  const ends = series.map((sr) => ({ ...sr, py: y(last[sr.key]), val: last[sr.key] }))
    .sort((a, b) => a.py - b.py);
  for (let i = 1; i < ends.length; i++) {
    if (ends[i].py - ends[i - 1].py < 14) ends[i].py = ends[i - 1].py + 14;
  }
  ctx.textAlign = "left"; ctx.textBaseline = "middle";
  ends.forEach((e) => {
    const px = x(MAX_POINTS - 1);
    ctx.fillStyle = e.color;
    ctx.beginPath(); ctx.arc(px + 8, e.py, 3, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = css("--ink");
    ctx.fillText(`${e.label} ${e.val.toLocaleString()}`, px + 15, e.py);
  });

  // hover 크로스헤어
  if (hoverIndex !== null && hoverIndex >= offset) {
    const px = x(hoverIndex);
    ctx.strokeStyle = css("--axis"); ctx.lineWidth = 1;
    ctx.setLineDash([3, 3]);
    ctx.beginPath(); ctx.moveTo(px, PAD.top); ctx.lineTo(px, PAD.top + plotH); ctx.stroke();
    ctx.setLineDash([]);
    const p = samples[hoverIndex - offset];
    series.forEach((sr) => {
      ctx.fillStyle = sr.color;
      ctx.beginPath(); ctx.arc(px, y(p[sr.key]), 4, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = css("--surface"); ctx.lineWidth = 2; ctx.stroke();
    });
  }
}

function niceStep(raw) {
  const pow = Math.pow(10, Math.floor(Math.log10(Math.max(1, raw))));
  for (const m of [1, 2, 5, 10]) if (m * pow >= raw) return m * pow;
  return 10 * pow;
}

// 툴팁
const wrap = document.querySelector(".chart-wrap");
wrap.addEventListener("mousemove", (e) => {
  const canvas = $("chart");
  const rect = canvas.getBoundingClientRect();
  const plotW = rect.width - PAD.left - PAD.right;
  const rel = (e.clientX - rect.left - PAD.left) / plotW;
  const i = Math.round(rel * (MAX_POINTS - 1));
  const offset = MAX_POINTS - samples.length;
  if (i < offset || i > MAX_POINTS - 1 || samples.length < 2) { hideTooltip(); return; }
  hoverIndex = i;
  const p = samples[i - offset];
  const secAgo = MAX_POINTS - 1 - i;
  const tip = $("tooltip");
  tip.innerHTML = `<b>${secAgo === 0 ? "지금" : secAgo + "초 전"}</b><br>대기 <b>${p.waiting.toLocaleString()}</b> · 입장 <b>${p.admitted.toLocaleString()}</b>`;
  tip.style.visibility = "visible";
  const tx = Math.min(e.clientX - rect.left + 12, rect.width - tip.offsetWidth - 4);
  tip.style.left = tx + "px";
  tip.style.top = Math.max(0, e.clientY - rect.top - tip.offsetHeight - 10) + "px";
  draw();
});
wrap.addEventListener("mouseleave", hideTooltip);
function hideTooltip() { hoverIndex = null; $("tooltip").style.visibility = "hidden"; draw(); }

window.addEventListener("resize", draw);
setInterval(poll, 1000);
poll();
</script>
</body>
</html>
```

- [ ] **Step 2: 서빙 확인**

(Task 2에서 재기동했다면 정적 리소스 갱신을 위해 다시 재기동)

Run: `curl -s http://localhost:8080/dashboard.html | grep '<title>'`
Expected: `<title>대기열 모니터링</title>`

- [ ] **Step 3: 부하 걸고 브라우저 확인**

1. `docker exec valkey valkey-cli DEL wq:waiting wq:admitted`로 초기화
2. 브라우저에서 `http://localhost:8080/dashboard.html` 열기 — 타일 0/0, "1초마다 갱신 중" 확인
3. `./load-test.sh` 실행
4. 대기 인원이 치솟았다가 초당 ~10명씩 줄고, 입장 인원이 계단식으로 오르는 추이 확인. 초당 입장 속도 ≈ 10 확인
5. 차트 hover 시 크로스헤어 + 툴팁 확인
6. light/dark 각각 스크린샷 캡처

- [ ] **Step 4: 커밋**

```bash
git add src/main/resources/static/dashboard.html
git commit -m "feat: 모니터링 대시보드 추가 (stats 폴링 + 추이 차트)"
```

---

## Self-Review 결과

- **스펙 커버리지:** 대기실(index.html)=Task 2, 대시보드(dashboard.html)=Task 3, stats API=Task 1, 조회 전용·1초 폴링·CDN 없음=Global Constraints — 누락 없음
- **플레이스홀더:** 없음 (모든 코드 완전체)
- **타입 일관성:** `StatsResponse(waiting: Long, admitted: Long)` ↔ JSON `{"waiting","admitted"}` ↔ 대시보드 JS `s.waiting`/`s.admitted` 일치. `stats()` 시그니처 Task 1 정의 = Task 3 소비 일치
