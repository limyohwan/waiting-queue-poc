package poc.waitingqueue.queue

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Kafka `waiting-queue` 토픽을 소비해 대기열(Redis ZSET)에 유저를 등록하는 워커.
 *
 * 진입 API가 아니라 **이 컨슈머가 Redis 쓰기 유량을 결정한다** — 진입 폭주는 Kafka가 흡수하고,
 * Redis에는 컨슈머가 소비하는 속도만큼만 부하가 걸린다.
 */
@Component
class WaitingQueueConsumer(private val store: WaitingQueueStore) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 레코드 하나를 대기열에 등록한다.
     *
     * - `groupId = waiting-queue-worker` : 이 그룹 내에서 파티션이 분산 소비되어 수평 확장 가능.
     * - score = `record.timestamp()`(브로커 접수 시각) : 진입 순서 기준의 공정성. key=userId 발행이라
     *   같은 유저는 같은 파티션에서 순서가 보장된다.
     */
    @KafkaListener(topics = ["\${queue.topic}"], groupId = "waiting-queue-worker")
    fun consume(record: ConsumerRecord<String, String>) {
        val userId = record.value()
        // 1) 현재 활성(서비스 중)인 유저의 지연 도착 레코드(중복 진입·Kafka 재전달)가 대기열에 다시 들어오는 것을 차단.
        //    addIfAbsent만으로는 막을 수 없다 — 입장 시 대기열 ZSET에서 제거됐으므로 NX가 신규로 오인해 재등록하기 때문.
        //    (완료한 유저는 활성이 아니므로 재진입이 허용된다.)
        if (store.isActive(userId)) {
            log.debug("already active, re-entry ignored: userId={}", userId)
            return
        }
        // 2) ZADD NX로 등록 시도. 이미 대기 중이면 false가 돌아오고 순번은 유지된다(멱등).
        val added = store.addIfAbsent(userId, record.timestamp().toDouble())
        if (added) {
            log.info("enqueued: userId={}", userId)
        } else {
            log.debug("duplicate enter ignored: userId={}", userId)
        }
    }
}
