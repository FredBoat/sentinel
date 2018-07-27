package com.fredboat.sentinel.config

import com.fredboat.sentinel.SentinelExchanges
import org.springframework.amqp.core.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RabbitQueuesConfig {

    /* Events */

    @Bean
    fun eventExchange() = DirectExchange(SentinelExchanges.EVENTS)

    @Bean
    fun eventQueue() = Queue(SentinelExchanges.EVENTS, false)

    /* Requests */

    @Bean
    fun requestExchange() = DirectExchange(SentinelExchanges.REQUESTS)

    @Bean
    fun requestQueue() = AnonymousQueue()

    @Bean
    fun requestBinding(
            @Qualifier("requestExchange") requestExchange: DirectExchange,
            @Qualifier("requestQueue") requestQueue: Queue,
            key: RoutingKey
    ): Binding {
        return BindingBuilder.bind(requestQueue).to(requestExchange).with(key.id)
    }

    /* Fanout */

    /** This queue auto-deletes */
    @Bean
    fun fanoutQueue(): Queue {
        return AnonymousQueue()
    }

    /** The fanout where we will receive broadcast messages from FredBoat */
    @Bean
    fun fanoutExchange(@Qualifier("fanoutQueue") fanoutQueue: Queue): FanoutExchange {
        return FanoutExchange(SentinelExchanges.FANOUT, false, false)
    }

    /** Receive messages from [fanout] to [fanoutQueue] */
    @Bean
    fun fanoutBinding(
            @Qualifier("fanoutQueue") fanoutQueue: Queue,
            @Qualifier("fanoutExchange") fanout: FanoutExchange
    ): Binding {
        return BindingBuilder.bind(fanoutQueue).to(fanout)
    }

    /* Sessions */

    /** This queue auto-deletes */
    @Bean
    fun sessionsQueue(): Queue {
        return AnonymousQueue()
    }

    /** The fanout where we will receive broadcast messages from FredBoat */
    @Bean
    fun sessionsExchange(@Qualifier("sessionsQueue") sessionsQueue: Queue): FanoutExchange {
        return FanoutExchange(SentinelExchanges.SESSIONS, false, false)
    }

    /** Receive messages from [sessionsFanout] to [sessionsQueue] */
    @Bean
    fun sessionsBinding(
            @Qualifier("sessionsQueue") sessionsQueue: Queue,
            @Qualifier("sessionsExchange") sessionsFanout: FanoutExchange
    ): Binding {
        return BindingBuilder.bind(sessionsQueue).to(sessionsFanout)
    }

}