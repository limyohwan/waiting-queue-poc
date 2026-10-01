package poc.waitingqueue.queue

import org.springframework.data.redis.core.DefaultTypedTuple
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * 대기열 상태를 담는 Redis 접근 계층.
 *
 * 설계 불변식: **모든 Redis 명령은 오직 이 클래스에만 존재한다.**
 * Consumer/RankService/AdmissionScheduler/Controller는 Redis에 직접 접근하지 않고
 * 반드시 이 Store를 통해서만 상태를 읽고 쓴다(관심사 분리).
 *
 * 두 개의 Redis 자료구조를 사용한다:
 * - `wq:waiting` (Sorted Set) : 대기 중인 유저. score = Kafka 레코드 timestamp(진입 순서).
 * - `wq:active`  (Sorted Set) : 활성(서비스 중) 유저. score = **만료시각(epoch ms)** — "만료되는 SET" 패턴.
 *   유저마다 개별 TTL을 표현하기 위해 SET이 아닌 ZSET을 쓴다(만료분은 스케줄러가 청소).
 */
@Component
class WaitingQueueStore(
    private val redis: StringRedisTemplate,
    properties: QueueProperties,
) {

    companion object {
        /** 대기열 ZSET 키. member=userId, score=진입 시각(timestamp) → score 오름차순이 곧 대기 순번. */
        private const val WAITING_KEY = "wq:waiting"

        /** 활성 유저 ZSET 키. member=userId, score=만료시각(epoch ms). 재진입 차단·입장 여부 조회에 사용. */
        private const val ACTIVE_KEY = "wq:active"
    }

    /** 활성 자리를 유지하는 TTL(ms). 활성화 시각 + 이 값이 해당 유저의 만료시각(score)이 된다. */
    private val activeTtlMillis: Long = properties.activeTtl.toMillis()

    /**
     * 대기열에 유저를 추가하되, 이미 존재하면 아무것도 하지 않는다(ZADD NX).
     *
     * 이 멱등성이 중복 제거의 단일 장치다 — 유저의 중복 진입 클릭과 Kafka at-least-once 재전달을
     * 동일하게 처리한다. 이미 대기 중인 유저의 score(순번)는 **절대 갱신되지 않는다**.
     *
     * @param score Kafka 레코드 timestamp. 이 값이 대기 순번의 기준.
     * @return 신규로 추가되었으면 true, 이미 존재해 무시되었으면 false. (Redis 응답 null이면 false로 방어)
     */
    fun addIfAbsent(userId: String, score: Double): Boolean =
        redis.opsForZSet().addIfAbsent(WAITING_KEY, userId, score) ?: false

    /**
     * 대기열에서 유저의 0-기반 순위를 조회한다(ZRANK, score 오름차순).
     * @return 대기 중이면 순위(0부터), 대기열에 없으면 null.
     */
    fun rankOf(userId: String): Long? = redis.opsForZSet().rank(WAITING_KEY, userId)

    /** 현재 대기 인원 수(ZCARD). 키가 없으면 0. */
    fun totalWaiting(): Long = redis.opsForZSet().zCard(WAITING_KEY) ?: 0

    /** 해당 유저가 현재 활성(서비스 중) 상태인지 확인(ZSCORE 존재 여부). */
    fun isActive(userId: String): Boolean = redis.opsForZSet().score(ACTIVE_KEY, userId) != null

    /** 현재 활성 인원 수(ZCARD). 키가 없으면 0. 정확한 값을 위해 [purgeExpiredActive] 이후 호출한다. */
    fun activeCount(): Long = redis.opsForZSet().zCard(ACTIVE_KEY) ?: 0

    /**
     * 대기열 맨 앞(가장 낮은 score = 가장 먼저 진입)에서 최대 [count]명을 원자적으로 꺼낸다(ZPOPMIN).
     *
     * 꺼냄과 동시에 ZSET에서 제거되므로, 여러 스케줄러 인스턴스가 있어도 같은 유저가 중복으로
     * 입장 처리되지 않는다.
     *
     * @return 꺼낸 유저 ID 목록. 대기열이 비어 있으면 빈 리스트.
     */
    fun popNext(count: Long): List<String> =
        redis.opsForZSet().popMin(WAITING_KEY, count)?.mapNotNull { it.value } ?: emptyList()

    /**
     * 만료된(만료시각 <= 현재) 활성 자리를 회수한다(ZREMRANGEBYSCORE). TTL 안전망.
     * 완료 API를 호출하지 않고 사라진 유저의 자리를 되찾아 다음 대기자에게 내준다.
     */
    fun purgeExpiredActive() {
        val now = System.currentTimeMillis().toDouble()
        redis.opsForZSet().removeRangeByScore(ACTIVE_KEY, Double.NEGATIVE_INFINITY, now)
    }

    /**
     * 꺼낸 유저들을 활성 상태로 등록한다(ZADD, score=현재+TTL).
     * score(만료시각)가 곧 TTL 안전망이며, [purgeExpiredActive]가 만료분을 청소한다.
     *
     * @param userIds 활성화할 유저 ID들. 비어 있으면 Redis 호출 없이 즉시 반환.
     */
    fun activate(userIds: Collection<String>) {
        if (userIds.isEmpty()) return
        val expireAt = (System.currentTimeMillis() + activeTtlMillis).toDouble()
        val tuples = userIds.map { DefaultTypedTuple(it, expireAt) }.toSet()
        redis.opsForZSet().add(ACTIVE_KEY, tuples)
    }

    /**
     * 활성 유저를 완료 처리하여 자리를 즉시 반납한다(ZREM).
     * @return 실제로 제거되었으면(활성 상태였으면) true, 활성 아니었으면 false.
     */
    fun complete(userId: String): Boolean =
        (redis.opsForZSet().remove(ACTIVE_KEY, userId) ?: 0) > 0
}
