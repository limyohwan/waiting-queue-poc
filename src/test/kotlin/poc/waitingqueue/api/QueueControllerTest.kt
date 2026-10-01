package poc.waitingqueue.api

import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import poc.waitingqueue.queue.QueueProperties
import poc.waitingqueue.queue.RankResponse
import poc.waitingqueue.queue.RankService
import poc.waitingqueue.queue.StatsResponse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.kafka.core.KafkaTemplate

class QueueControllerTest {

    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate: KafkaTemplate<String, String> =
        mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
    private val rankService: RankService = mock(RankService::class.java)
    private val sut = QueueController(
        kafkaTemplate,
        rankService,
        QueueProperties(topic = "waiting-queue", capacity = 100, activeTtl = Duration.ofMinutes(5)),
    )

    @Test
    fun `진입 요청은 userId를 key로 토픽에 발행하고 202를 반환한다`() {
        doReturn(CompletableFuture.completedFuture(null))
            .`when`(kafkaTemplate).send("waiting-queue", "user-1", "user-1")

        val response = sut.enter(EnterRequest("user-1"))

        assertEquals(202, response.statusCode.value())
        assertEquals("ACCEPTED", response.body?.get("status"))
        verify(kafkaTemplate).send("waiting-queue", "user-1", "user-1")
    }

    @Test
    fun `userId가 공백이면 400을 반환하고 발행하지 않는다`() {
        val response = sut.enter(EnterRequest("  "))

        assertEquals(400, response.statusCode.value())
        verify(kafkaTemplate, never()).send("waiting-queue", "  ", "  ")
    }

    @Test
    fun `순번 조회는 RankService에 위임한다`() {
        `when`(rankService.rankOf("user-1")).thenReturn(RankResponse("WAITING", rank = 1, total = 10))

        assertEquals(RankResponse("WAITING", rank = 1, total = 10), sut.rank("user-1"))
    }

    @Test
    fun `통계 조회는 RankService에 위임한다`() {
        `when`(rankService.stats()).thenReturn(StatsResponse(waiting = 3, admitted = 7))

        assertEquals(StatsResponse(waiting = 3, admitted = 7), sut.stats())
    }

    @Test
    fun `완료 요청은 자리를 반납하고 COMPLETED를 반환한다`() {
        `when`(rankService.complete("user-1")).thenReturn(true)

        val response = sut.complete("user-1")

        assertEquals(200, response.statusCode.value())
        assertEquals("COMPLETED", response.body?.get("status"))
    }

    @Test
    fun `활성 상태가 아닌 유저의 완료 요청은 NOT_ACTIVE를 반환한다`() {
        `when`(rankService.complete("user-1")).thenReturn(false)

        val response = sut.complete("user-1")

        assertEquals("NOT_ACTIVE", response.body?.get("status"))
    }
}
