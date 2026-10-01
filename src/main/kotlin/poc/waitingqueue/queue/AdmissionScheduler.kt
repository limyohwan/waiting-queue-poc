package poc.waitingqueue.queue

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 대기열 앞의 유저를 **빈 자리만큼만** 입장시키는 스케줄러(자리/정원 기반).
 *
 * 시간이 아니라 빈 슬롯 수가 입장을 결정한다 — 활성 인원을 [QueueProperties.capacity] 이하로 유지하고,
 * 완료(complete)나 TTL 만료로 자리가 나야만 다음 대기자가 입장한다.
 */
@Component
class AdmissionScheduler(
    private val store: WaitingQueueStore,
    private val properties: QueueProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 1초마다(직전 실행 종료 후 1초 간격) 만료 자리를 회수하고 빈 자리만큼 대기자를 입장시킨다.
     *
     * `fixedDelay`라 이전 틱이 끝난 뒤에야 다음 틱이 시작하므로 처리가 밀려도 겹쳐 실행되지 않는다.
     */
    @Scheduled(fixedDelay = 1000)
    fun admitNext() {
        // 1) TTL 만료된 활성 자리를 먼저 회수해야 activeCount가 정확해진다
        store.purgeExpiredActive()

        // 2) 빈 자리 계산 — 정원을 넘지 않는 선에서만 입장시킨다
        val free = properties.capacity - store.activeCount()
        if (free <= 0) {
            return
        }

        // 3) 빈 자리만큼 대기열 앞에서 꺼내(ZPOPMIN) 활성화
        val users = store.popNext(free)
        if (users.isEmpty()) {
            return
        }
        store.activate(users)
        log.info("activated {} users: {}", users.size, users)
    }
}
