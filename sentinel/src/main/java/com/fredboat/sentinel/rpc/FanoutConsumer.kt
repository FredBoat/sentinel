package com.fredboat.sentinel.rpc

import com.fredboat.sentinel.SentinelExchanges
import com.fredboat.sentinel.config.JdaProperties
import com.fredboat.sentinel.config.RoutingKey
import com.fredboat.sentinel.entities.FredBoatHello
import com.fredboat.sentinel.entities.SentinelHello
import net.dv8tion.jda.bot.sharding.ShardManager
import net.dv8tion.jda.core.entities.Game
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.RabbitHandler
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
@RabbitListener(queues = ["#{fanoutQueue.name}"], errorHandler = "rabbitListenerErrorHandler")
class FanoutConsumer(
        private val template: RabbitTemplate,
        private val jdaProperties: JdaProperties,
        private val key: RoutingKey,
        @param:Qualifier("guildSubscriptions")
        private val subscriptions: MutableSet<Long>,
        private val shardManager: ShardManager
) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(FanoutConsumer::class.java)
    }

    init {
        sendHello()
    }

    @RabbitHandler
    fun onHello(request: FredBoatHello) {
        if (request.startup) {
            log.info("FredBoat says hello \uD83D\uDC4B - Clearing subscriptions")
            subscriptions.clear()
        } else {
            log.info("FredBoat says hello \uD83D\uDC4B")
        }

        sendHello()
        val game = if (request.game.isBlank()) null else Game.playing(request.game)
        // Null means reset
        shardManager.setGame(game)
    }

    private fun sendHello() {
        val message = jdaProperties.run {  SentinelHello(
                shardStart,
                shardEnd,
                shardCount,
                key.id
        )}
        template.convertAndSend(SentinelExchanges.EVENTS, message)
    }

}