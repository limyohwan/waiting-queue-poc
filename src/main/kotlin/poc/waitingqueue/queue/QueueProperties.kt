package poc.waitingqueue.queue

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 대기열 관련 외부 설정값. `application.yml`의 `queue.*` 프리픽스에 바인딩된다.
 *
 * @property topic 진입 이벤트를 발행/소비하는 Kafka 토픽명 (`queue.topic`).
 *   Producer(진입 API)와 Consumer(@KafkaListener) 양쪽이 동일 값을 참조해야 한다.
 * @property capacity 동시에 활성(서비스 중) 상태를 유지할 수 있는 최대 인원 (`queue.capacity`).
 *   스케줄러는 이 정원을 넘지 않는 선에서 빈 자리만큼만 대기자를 입장시킨다.
 * @property activeTtl 활성 유저의 자리를 자동 회수하는 TTL 안전망 (`queue.active-ttl`).
 *   완료 API 호출 없이 사라진 유저(브라우저 종료 등)의 자리를 이 시간 후 되찾는다.
 */
@ConfigurationProperties(prefix = "queue")
data class QueueProperties(
    val topic: String,
    val capacity: Long,
    val activeTtl: Duration,
)
