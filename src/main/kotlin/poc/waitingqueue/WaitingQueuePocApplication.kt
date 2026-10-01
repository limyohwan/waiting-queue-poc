package poc.waitingqueue

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 대기열 POC 애플리케이션 진입점.
 *
 * Kafka(폭주 완충·유실 방지) + Redis ZSET(중복 제거·순번·입장)을 조합한 대규모 대기열 구조를 검증한다.
 *
 * 활성화한 스프링 기능:
 * - [ConfigurationPropertiesScan] : `queue.*` 프로퍼티를 [poc.waitingqueue.queue.QueueProperties]로 바인딩
 * - [EnableScheduling] : [poc.waitingqueue.queue.AdmissionScheduler]의 주기적 입장 처리(@Scheduled) 활성화
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class WaitingQueuePocApplication

fun main(args: Array<String>) {
    // 스프링 부트 컨텍스트 기동 — 내장 웹서버, Kafka 리스너, 스케줄러가 함께 올라온다
    runApplication<WaitingQueuePocApplication>(*args)
}
