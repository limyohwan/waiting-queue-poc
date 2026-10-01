package poc.waitingqueue.queue

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class RankServiceTest {

    private val store: WaitingQueueStore = mock(WaitingQueueStore::class.java)
    private val sut = RankService(store)

    @Test
    fun `활성(입장) 상태 유저는 ADMITTED를 반환한다`() {
        `when`(store.isActive("user-1")).thenReturn(true)

        assertEquals(RankResponse("ADMITTED"), sut.rankOf("user-1"))
    }

    @Test
    fun `대기 중인 유저는 1-기반 순번과 전체 대기 수를 반환한다`() {
        `when`(store.isActive("user-1")).thenReturn(false)
        `when`(store.rankOf("user-1")).thenReturn(4L)
        `when`(store.totalWaiting()).thenReturn(100L)

        assertEquals(RankResponse("WAITING", rank = 5, total = 100), sut.rankOf("user-1"))
    }

    @Test
    fun `통계는 대기 인원과 현재 활성 인원을 반환한다`() {
        `when`(store.totalWaiting()).thenReturn(3L)
        `when`(store.activeCount()).thenReturn(7L)

        assertEquals(StatsResponse(waiting = 3, admitted = 7), sut.stats())
    }

    @Test
    fun `어디에도 없는 유저는 NOT_FOUND를 반환한다`() {
        `when`(store.isActive("user-1")).thenReturn(false)
        `when`(store.rankOf("user-1")).thenReturn(null)

        assertEquals(RankResponse("NOT_FOUND"), sut.rankOf("user-1"))
    }

    @Test
    fun `완료 처리는 store에 위임하고 반납 여부를 반환한다`() {
        `when`(store.complete("user-1")).thenReturn(true)

        assertTrue(sut.complete("user-1"))
    }
}
