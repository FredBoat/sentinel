package com.fredboat.sentinel.jda

import com.fredboat.sentinel.SentinelExchanges
import com.fredboat.sentinel.config.JdaProperties
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.utils.SessionController
import net.dv8tion.jda.core.utils.SessionController.SessionConnectNode
import org.junit.Assert.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate

class FederatedSessionControlTest {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(FederatedSessionControlTest::class.java)
        private const val DELAY = SessionController.IDENTIFY_DELAY * 1000L
        private const val LEEYWAY_PER_SHARD = 50
    }

    @Test
    @Tag("slow")
    fun testFull() {
        lateinit var controllers: List<FederatedSessionControl>
        val rabbit = mockRabbit { controllers }
        controllers = listOf(
                createController(rabbit, 0),
                createController(rabbit, 1),
                createController(rabbit, 2)
        )
        doTest(controllers) { nodesStarted ->
            // We should expect these session controllers to be finished after around 8*5+LEEYWAY_PER_SHARD seconds
            Thread.sleep(getAcceptableLatency(controllers))
            nodesStarted.forEachIndexed { i, b -> assertTrue("Node $i was not started", b) }
        }
    }

    @Test
    @Tag("slow")
    fun testMissingFirstController() {
        lateinit var controllers: List<FederatedSessionControl?>
        val rabbit = mockRabbit { controllers }
        controllers = listOf(
                null,
                createController(rabbit, 1),
                createController(rabbit, 2)
        )
        doTest(controllers = controllers,
                nodesStarted = mutableListOf(
                        true, true, true,
                        false, false, false,
                        false, false, false
                )
        ) { nodesStarted ->
            // We should expect these session controllers to be finished after around 5*5+LEEYWAY_PER_SHARD seconds
            Thread.sleep(getAcceptableLatency(controllers))
            nodesStarted.forEachIndexed { i, b -> assertTrue("Node $i was not started", b) }
        }
    }

    @Test
    @Tag("slow")
    fun testMiddleControllerOnly() {
        lateinit var controllers: List<FederatedSessionControl?>
        val rabbit = mockRabbit { controllers }
        controllers = listOf(
                null,
                createController(rabbit, 1),
                null
        )
        doTest(controllers = controllers,
                nodesStarted = mutableListOf(
                        true, true, true,
                        false, false, false,
                        true, true, true
                )
        ) { nodesStarted ->
            // We should expect these session controllers to be finished after around 8*5+LEEYWAY_PER_SHARD seconds
            Thread.sleep(getAcceptableLatency(controllers))
            nodesStarted.forEachIndexed { i, b -> assertTrue("Node $i was not started", b) }
        }
    }

    private fun mockRabbit(controllers: () -> List<FederatedSessionControl?>): RabbitTemplate {
        val mockRabbit = mock(RabbitTemplate::class.java)
        `when`(mockRabbit.convertAndSend(anyString(), anyString(), any<Any>())).thenAnswer {

            assertEquals("Wrong exchange", SentinelExchanges.SESSIONS, it.arguments[0])
            assertEquals("Expected no routing key", "", it.arguments[1])

            val msg = it.arguments[2]
            when(msg) {
                is SessionSyncRequest -> controllers().forEach { it?.onSyncRequest(msg) }
                is SessionInfo -> controllers().forEach { it?.onShardInfo(msg) }
                is ShardConnectEvent -> controllers().forEach { it?.onShardConnect(msg) }
                else -> throw IllegalArgumentException()
            }
            return@thenAnswer null
        }

        return mockRabbit
    }

    private fun createController(rabbit: RabbitTemplate, i: Int): FederatedSessionControl {
        return FederatedSessionControl(
                JdaProperties(shardCount = 9, shardStart = i * 3, shardEnd = i*3 + 2),
                rabbit
        )
    }

    /** Tests that the session controllers run shards in the correct order without clashes */
    private fun doTest(
            controllers: List<FederatedSessionControl?>,
            nodesStarted: MutableList<Boolean> = mutableListOf(
                    false, false, false,
                    false, false, false,
                    false, false, false
            ),
            validator: (nodesStarted: List<Boolean>) -> Unit
    ) {
        val nodes = mutableListOf<SessionConnectNode>()
        var lastConnect = System.currentTimeMillis()
        var hasFirstNodeRun = false
        for (i in 0..8) {
            val mock = mock(SessionConnectNode::class.java)
            nodes.add(i, mock)
            `when`(mock.run(false)).then {
                assertFalse("Node must not be started twice", nodesStarted[i])
                if (i > 0) {
                    nodesStarted.subList(0, i-1).forEachIndexed { j, bool ->
                        if (i == 8) { //8 is the home shard id of FBH with a total of 9 shards, it should start first
                            assertFalse("The node holding the home guild should start first", hasFirstNodeRun)
                        } else {
                            assertTrue("Attempt to run $i before $j", bool)
                        }
                    }
                }
                if(hasFirstNodeRun) assertTrue(
                        "Attempted to run $i. Must wait at least $DELAY ms before next run",
                        lastConnect + DELAY < System.currentTimeMillis()
                )
                hasFirstNodeRun = true
                nodesStarted[i] = true
                log.info("Node $i started, took ${System.currentTimeMillis() - lastConnect}ms")
                lastConnect = System.currentTimeMillis()
                Thread.sleep(100) // Simulate IO
                null
            }
            `when`(nodes[i].shardInfo).thenReturn(JDA.ShardInfo(i, 9))
        }

        nodes.forEachIndexed { i, node -> controllers[i/3]?.appendSession(node) }
        validator(nodesStarted)
    }

    /**
     * Sentinel waits 2000ms to gather info.
     * Each shard is simulated to take 100ms to load.
     * The leeway is how much extra latency is acceptable.
     */
    private fun getAcceptableLatency(controllers: List<FederatedSessionControl?>): Long {
        val shards = controllers.asSequence()
                .filterNotNull()
                .sumBy { it.jdaProps.shardEnd - it.jdaProps.shardStart + 1 }
        val identifyRatelimitTime = 5000 * (shards - 1)
        return 2000 + (LEEYWAY_PER_SHARD + 100L) * shards + identifyRatelimitTime
    }

}