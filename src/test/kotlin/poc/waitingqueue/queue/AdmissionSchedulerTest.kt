package poc.waitingqueue.queue

import java.time.Duration
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyList
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AdmissionSchedulerTest {

    private val store: WaitingQueueStore = mock(WaitingQueueStore::class.java)
    private val sut = AdmissionScheduler(
        store,
        QueueProperties(topic = "waiting-queue", capacity = 100, activeTtl = Duration.ofMinutes(5)),
    )

    @Test
    fun `빈 자리만큼만 대기열에서 꺼내 활성화한다`() {
        `when`(store.activeCount()).thenReturn(98L) // 정원 100 - 98 = 빈 자리 2
        `when`(store.popNext(2)).thenReturn(listOf("user-1", "user-2"))

        sut.admitNext()

        verify(store).activate(listOf("user-1", "user-2"))
    }

    @Test
    fun `매 틱마다 만료된 자리를 먼저 회수한다`() {
        `when`(store.activeCount()).thenReturn(100L)

        sut.admitNext()

        verify(store).purgeExpiredActive()
    }

    @Test
    fun `정원이 가득 차면 대기열에서 꺼내지 않는다`() {
        `when`(store.activeCount()).thenReturn(100L) // 빈 자리 0

        sut.admitNext()

        verify(store, never()).popNext(anyLong())
        verify(store, never()).activate(anyList())
    }

    @Test
    fun `빈 자리가 있어도 대기자가 없으면 활성화하지 않는다`() {
        `when`(store.activeCount()).thenReturn(50L)
        `when`(store.popNext(50)).thenReturn(emptyList())

        sut.admitNext()

        verify(store, never()).activate(anyList())
    }
}
