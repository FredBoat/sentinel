package com.fredboat.sentinel.jda

import com.fredboat.sentinel.SentinelExchanges
import com.fredboat.sentinel.config.JdaProperties
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.utils.SessionController
import net.dv8tion.jda.core.utils.SessionController.SessionConnectNode
import net.dv8tion.jda.core.utils.SessionControllerAdapter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.RabbitHandler
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

private val log: Logger = LoggerFactory.getLogger(FederatedSessionControl::class.java)
/** Time between broadcasting status */
private const val BROADCAST_INTERVAL = 5_000
/** Status updates older than this timeout are ignored to prevent ghosts */
private const val STATUS_TIMEOUT = 12_000
/** Discord guild id of FredBoat Hangout */
private const val homeGuildId = 174820236481134592L

@Service
@RabbitListener(queues = ["#{sessionsQueue.name}"], errorHandler = "rabbitListenerErrorHandler")
class FederatedSessionControl(
        val jdaProps: JdaProperties,
        val rabbit: RabbitTemplate
) : SessionController {

    private val adapter = SessionControllerAdapter()
    private val localQueue = ConcurrentHashMap<Int, SessionConnectNode>()
    @Volatile
    private var globalRatelimit = -1L
    @Volatile
    private var worker: Thread? = null
    @Volatile
    private var lastConnect = 0L
    private val sessionInfo = ConcurrentHashMap<Int, ShardSessionInfo>()
    private var lastBroadcast = 0L

    override fun getGlobalRatelimit() = globalRatelimit

    override fun setGlobalRatelimit(ratelimit: Long) {
        rabbit.convertAndSend(SentinelExchanges.SESSIONS, "", SetGlobalRatelimit(ratelimit))
        globalRatelimit = ratelimit
    }

    @RabbitHandler
    fun handleRatelimitSet(event: SetGlobalRatelimit) {
        globalRatelimit = event.new
    }

    override fun appendSession(node: SessionConnectNode) {
        localQueue[node.shardInfo.shardId] = node
        if (worker == null || worker?.state == Thread.State.TERMINATED) {
            if (worker?.state == Thread.State.TERMINATED) log.warn("Session worker was terminated. Starting a new one")

            worker = Thread(workerRunnable).apply {
                name = "sentinel-session-worker"
                setUncaughtExceptionHandler { _, e -> log.error("Exception in worker", e) }
                start()
            }
        }
        sendSessionInfo()
    }

    override fun removeSession(node: SessionConnectNode) {
        if (!localQueue.remove(node.shardInfo.shardId, node))
            log.warn("Attempted to remove ${node.shardInfo.shardString}, but it was already removed")
    }

    @Suppress("HasPlatformType")
    override fun getGateway(api: JDA) = adapter.getGateway(api)

    @Suppress("HasPlatformType")
    override fun getGatewayBot(api: JDA) = adapter.getGatewayBot(api)

    private val workerRunnable = Runnable {
        log.info("Session worker started, requesting data from other Sentinels")
        rabbit.convertAndSend(SentinelExchanges.SESSIONS, "", SessionSyncRequest())
        Thread.sleep(2000) // Ample time
        val ourShards = jdaProps.run { shardEnd - shardStart + 1 }
        log.info("Gathered info for [${sessionInfo.size + ourShards}/${jdaProps.shardCount}] shards.")

        while (true) {
            try {
                if (!isWaitingOnOtherInstances() && localQueue.isNotEmpty()) {
                    // Make sure we wait before starting the next shard
                    val toWait = lastConnect + SessionController.IDENTIFY_DELAY * 1000 - System.currentTimeMillis()
                    if (toWait > 0) Thread.sleep(toWait)

                    val node = getNextNode()
                    node.run(false) // We'll always want to use false to get the right timestamp
                    localQueue.remove(node.shardInfo.shardId)
                    lastConnect = System.currentTimeMillis()
                    rabbit.convertAndSend(SentinelExchanges.SESSIONS, "", ShardConnectEvent(jdaProps.shardCount))
                    sendSessionInfo()
                    Thread.sleep(SessionController.IDENTIFY_DELAY * 1000L)
                }
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                log.error("Unexpected exception in session worker", e)
            }
            Thread.sleep(50)
            if (lastBroadcast + BROADCAST_INTERVAL < System.currentTimeMillis()) sendSessionInfo()
        }
    }

    /**
     * Gets whatever [SessionConnectNode] has the lowest shard id.
     * Set must not be empty
     */
    private fun getNextNode() = localQueue.reduceEntries(1) { acc, entry ->
        return@reduceEntries if (entry.key < acc.key || isHomeShard(entry.value)) entry else acc
    }.value

    private fun isHomeShard(node: SessionConnectNode): Boolean {
        return homeShardId() == node.shardInfo.shardId
    }

    private fun homeShardId(): Int {
        return ((homeGuildId shr 22) % jdaProps.shardCount).toInt()
    }

    private fun isHomeShardOurs(): Boolean {
        return jdaProps.shardStart <= homeShardId() && homeShardId() <= jdaProps.shardEnd
    }

    private fun isWaitingOnOtherInstances(): Boolean {
        // if we manage the home shard and it needs connecting we have priority
        if (isHomeShardOurs() && localQueue.containsKey(homeShardId())) {
            return false
        }
        for (id in 0..(jdaProps.shardCount - 1)) {
            sessionInfo[id]?.let {
                // Is this shard queued and is it not too old?
                if (it.messageTime + STATUS_TIMEOUT < System.currentTimeMillis()) {
                    log.info("No new update for shard $id for ${System.currentTimeMillis() - it.messageTime}ms so ignoring")
                    sessionInfo.remove(id)
                    return@let
                }
                if (it.queued && id < jdaProps.shardStart) return true  // a shard below the ones managed by us is queued
                if (it.queued && id == homeShardId()) return true       // the home shard is queued on one of the other nodes
            }
        }
        return false
    }

    @RabbitHandler
    fun onShardConnect(event: ShardConnectEvent) {
        if (jdaProps.shardCount != event.shardCount) {
            log.warn("Conflicting shard count, ignoring. ${jdaProps.shardCount} != ${event.shardCount}")
            return
        }
        lastConnect = Math.max(lastConnect, event.connectTime)
    }

    @RabbitHandler
    fun onShardInfo(event: SessionInfo) {
        if (jdaProps.shardCount != event.shardCount) {
            log.warn("Conflicting shard count, ignoring. ${jdaProps.shardCount} != ${event.shardCount}")
            return
        }
        event.info.forEach {
            // Ignore shards we own
            if (it.id >= jdaProps.shardStart && it.id <= jdaProps.shardEnd) return@forEach
            sessionInfo[it.id] = it
        }
    }

    fun sendSessionInfo() {
        val list = mutableListOf<ShardSessionInfo>()
        for (id in jdaProps.shardStart..jdaProps.shardEnd) {
            list.add(ShardSessionInfo(id, localQueue.containsKey(id), System.currentTimeMillis()))
        }
        rabbit.convertAndSend(SentinelExchanges.SESSIONS, "", SessionInfo(jdaProps.shardCount, list))
        lastBroadcast = System.currentTimeMillis()
    }

    @RabbitHandler
    fun onSyncRequest(request: SessionSyncRequest) = sendSessionInfo()

}

/** Sent when a new instance needs all the info */
class SessionSyncRequest

class ShardConnectEvent(val shardCount: Int) {
    val connectTime = System.currentTimeMillis()
}

class SetGlobalRatelimit(val new: Long)

data class SessionInfo(
        val shardCount: Int,
        val info: List<ShardSessionInfo>
)

data class ShardSessionInfo(val id: Int, val queued: Boolean, val messageTime: Long)
