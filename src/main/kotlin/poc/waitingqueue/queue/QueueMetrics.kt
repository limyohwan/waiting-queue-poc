package poc.waitingqueue.queue

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 대기열 상태를 Prometheus 메트릭으로 노출하는 커스텀 지표 등록기.
 *
 * Micrometer [Gauge]는 스크랩 시점마다 콜백을 호출해 현재 값을 읽으므로,
 * Prometheus가 `/actuator/prometheus`를 긁을 때(기본 15초 주기)마다 최신 대기/입장 인원이 반영된다.
 *
 * 노출 메트릭:
 * - `waiting_queue_waiting`  : 현재 대기 인원 (ZCARD wq:waiting)
 * - `waiting_queue_admitted` : 현재 활성(서비스 중) 인원 (ZCARD wq:active)
 *
 * Grafana에서 이 지표로 대기열이 쌓이고 빠지는 추이를 실시간 그래프로 볼 수 있다.
 */
@Component
class QueueMetrics(store: WaitingQueueStore, registry: MeterRegistry) {

    init {
        Gauge.builder("waiting_queue_waiting", store) { it.totalWaiting().toDouble() }
            .description("현재 대기 중인 인원 수")
            .register(registry)

        Gauge.builder("waiting_queue_admitted", store) { it.activeCount().toDouble() }
            .description("현재 활성(서비스 중)인 인원 수")
            .register(registry)
    }
}
