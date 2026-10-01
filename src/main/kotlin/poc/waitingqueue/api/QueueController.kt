package poc.waitingqueue.api

import poc.waitingqueue.queue.QueueProperties
import poc.waitingqueue.queue.RankResponse
import poc.waitingqueue.queue.RankService
import poc.waitingqueue.queue.StatsResponse
import org.springframework.http.ResponseEntity
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 진입 요청 바디. userId는 검증 전이므로 nullable로 받는다. */
data class EnterRequest(val userId: String?)

/**
 * 대기열 HTTP 엔드포인트.
 *
 * 세 개의 API로 구성된다:
 * - `POST /queue/enter`        : 진입(Kafka 발행 후 즉시 202)
 * - `GET  /queue/rank/{userId}`: 순번 조회
 * - `GET  /queue/stats`        : 대기/입장 통계(대시보드용)
 */
@RestController
@RequestMapping("/queue")
class QueueController(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val rankService: RankService,
    private val properties: QueueProperties,
) {

    /**
     * 대기열 진입. **Redis를 건드리지 않고** Kafka에만 발행한 뒤 즉시 202(ACCEPTED)를 반환한다.
     *
     * 진입 폭주는 브로커가 흡수하고, 실제 대기열 등록은 컨슈머가 자기 속도로 처리한다(비동기 배치).
     * - `key = userId` 로 발행 → 같은 유저는 같은 파티션으로 가 순서가 보장되고, ZADD NX와 함께 멱등성을 이룬다.
     * - userId가 비었으면 발행 없이 400(INVALID_USER_ID). (POC 범위상 그 이상의 인증/검증은 하지 않음)
     */
    @PostMapping("/enter")
    fun enter(@RequestBody request: EnterRequest): ResponseEntity<Map<String, String>> {
        val userId = request.userId?.trim()
        if (userId.isNullOrEmpty()) {
            return ResponseEntity.badRequest().body(mapOf("status" to "INVALID_USER_ID"))
        }
        kafkaTemplate.send(properties.topic, userId, userId)
        return ResponseEntity.accepted().body(mapOf("status" to "ACCEPTED"))
    }

    /**
     * 유저의 현재 순번/상태 조회. 클라이언트(대기실 화면)가 주기적으로 폴링한다.
     * NOT_FOUND는 진입 직후 아직 소비 전인 정상 상태이므로 클라이언트가 재폴링한다.
     */
    @GetMapping("/rank/{userId}")
    fun rank(@PathVariable userId: String): RankResponse {
        return rankService.rankOf(userId)
    }

    /** 대기/입장 인원 통계. 모니터링 대시보드가 폴링한다. */
    @GetMapping("/stats")
    fun stats(): StatsResponse = rankService.stats()

    /**
     * 활성 유저의 완료 처리 — 자리를 반납해 다음 대기자가 입장할 수 있게 한다.
     * 활성 상태였으면 COMPLETED, 아니면 NOT_ACTIVE를 반환한다(둘 다 200).
     */
    @PostMapping("/complete/{userId}")
    fun complete(@PathVariable userId: String): ResponseEntity<Map<String, String>> {
        val released = rankService.complete(userId)
        val status = if (released) "COMPLETED" else "NOT_ACTIVE"
        return ResponseEntity.ok(mapOf("status" to status))
    }
}
