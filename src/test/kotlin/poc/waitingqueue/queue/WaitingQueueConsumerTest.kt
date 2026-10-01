package poc.waitingqueue.queue

import java.util.Optional
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyDouble
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class WaitingQueueConsumerTest {

    private val store: WaitingQueueStore = mock(WaitingQueueStore::class.java)
    private val sut = WaitingQueueConsumer(store)

    @Test
    fun `신규 유저는 레코드 timestamp를 score로 대기열에 등록한다`() {
        `when`(store.addIfAbsent("user-1", 1000.0)).thenReturn(true)

        sut.consume(record("user-1", 1000L))

        verify(store).addIfAbsent("user-1", 1000.0)
    }

    @Test
    fun `현재 활성 유저는 대기열에 다시 등록하지 않는다`() {
        `when`(store.isActive("user-1")).thenReturn(true)

        sut.consume(record("user-1", 1000L))

        verify(store, never()).addIfAbsent(anyString(), anyDouble())
    }

    @Test
    fun `중복 유저여도 예외 없이 처리한다`() {
        `when`(store.addIfAbsent("user-1", 1000.0)).thenReturn(false)

        sut.consume(record("user-1", 1000L))

        verify(store).addIfAbsent("user-1", 1000.0)
    }

    private fun record(userId: String, timestamp: Long): ConsumerRecord<String, String> =
        ConsumerRecord(
            "waiting-queue", 0, 0L, timestamp, TimestampType.CREATE_TIME,
            -1, -1, userId, userId, RecordHeaders(), Optional.empty(),
        )
}
