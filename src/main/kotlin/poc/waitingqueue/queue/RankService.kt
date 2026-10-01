package poc.waitingqueue.queue

import org.springframework.stereotype.Service

/**
 * 순번 조회 API(`GET /queue/rank/{userId}`)의 응답 모델.
 *
 * @property status 유저 상태. 셋 중 하나:
 *   - `ADMITTED`  : 입장 완료 → rank/total은 null.
 *   - `WAITING`   : 대기 중 → rank(1-기반 순번), total(전체 대기 인원) 포함.
 *   - `NOT_FOUND` : 대기열에도 입장 목록에도 없음(진입 직후 아직 소비 전인 정상 상태).
 * @property rank 1-기반 대기 순번. WAITING일 때만 채워진다.
 * @property total 전체 대기 인원. WAITING일 때만 채워진다.
 */
data class RankResponse(
    val status: String,
    val rank: Long? = null,
    val total: Long? = null,
)

/**
 * 모니터링 대시보드용 통계 응답(`GET /queue/stats`).
 * @property waiting 현재 대기 인원, @property admitted 입장 완료 인원.
 */
data class StatsResponse(
    val waiting: Long,
    val admitted: Long,
)

/**
 * 대기열 상태를 조회하고 완료 처리를 위임하는 서비스.
 * 상태 로직은 두지 않고 [WaitingQueueStore]에 위임만 한다.
 */
@Service
class RankService(private val store: WaitingQueueStore) {

    /**
     * 유저의 현재 상태를 판정한다. 판정 순서가 중요하다:
     * 1. **활성 여부 먼저 확인** — 활성(입장) 유저는 대기열에서 빠졌으므로 rankOf가 null이 되는데,
     *    이를 NOT_FOUND로 오판하지 않도록 isActive를 먼저 본다.
     * 2. 대기열 순위 조회 — 없으면 NOT_FOUND(아직 컨슈머가 소비 전이거나, 진입 안 했거나, 완료됨).
     * 3. 있으면 WAITING — Redis의 0-기반 rank에 +1 하여 사람이 읽는 1-기반 순번으로 변환.
     */
    fun rankOf(userId: String): RankResponse {
        if (store.isActive(userId)) {
            return RankResponse("ADMITTED")
        }
        val rank = store.rankOf(userId) ?: return RankResponse("NOT_FOUND")
        return RankResponse("WAITING", rank = rank + 1, total = store.totalWaiting())
    }

    /** 대기 인원과 현재 활성 인원 스냅샷을 반환한다(대시보드 폴링용). */
    fun stats(): StatsResponse =
        StatsResponse(waiting = store.totalWaiting(), admitted = store.activeCount())

    /**
     * 활성 유저를 완료 처리해 자리를 반납한다.
     * @return 실제로 반납되었으면(활성 상태였으면) true, 활성 아니었으면 false.
     */
    fun complete(userId: String): Boolean = store.complete(userId)
}
