package poc.waitingqueue

import poc.waitingqueue.queue.WaitingQueueStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.web.client.RestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "queue.capacity=2",
    ],
)
@EmbeddedKafka(partitions = 3, topics = ["waiting-queue"])
@Testcontainers
class WaitingQueueIntegrationTest {

    companion object {
        @Container
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7").withExposedPorts(6379)
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var store: WaitingQueueStore

    private val client: RestClient by lazy { RestClient.create("http://localhost:$port") }

    @Test
    fun `활성 인원은 정원을 넘지 않고 완료 시 다음 대기자가 입장한다`() {
        val users = (1..5).map { "user-$it" }
        users.forEach(::enter)
        enter("user-1") // 중복 진입 — ZADD NX로 무시되어야 한다

        // 정원(2)만큼만 활성, 나머지는 대기 — 중복은 제거되어 총 5명(활성2 + 대기3)
        awaitUntil(timeoutMillis = 20_000) { store.activeCount() == 2L }
        assertThat(store.totalWaiting()).isEqualTo(3L)

        // 활성 유저 하나를 완료 → 자리 반납
        val done = users.first { statusOf(it) == "ADMITTED" }
        complete(done)

        // 자리가 나면 다음 대기자가 입장하여 다시 정원(2) 유지, 대기는 2명으로 감소
        awaitUntil(timeoutMillis = 20_000) {
            store.activeCount() == 2L && store.totalWaiting() == 2L
        }
        // 완료한 유저는 활성도 대기도 아님
        assertThat(statusOf(done)).isEqualTo("NOT_FOUND")
    }

    private fun enter(userId: String) {
        client.post().uri("/queue/enter")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("userId" to userId))
            .retrieve()
            .toBodilessEntity()
    }

    private fun complete(userId: String) {
        client.post().uri("/queue/complete/$userId")
            .retrieve()
            .toBodilessEntity()
    }

    private fun statusOf(userId: String): String {
        val body = client.get().uri("/queue/rank/$userId")
            .retrieve()
            .body(Map::class.java)
        return body?.get("status") as String
    }

    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        assertThat(condition()).withFailMessage("시간 내 조건 미충족").isTrue()
    }
}
