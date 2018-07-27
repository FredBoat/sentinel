package com.fredboat.sentinel.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fredboat.sentinel.metrics.Counters
import org.aopalliance.aop.Advice
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.AsyncRabbitTemplate
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.RabbitListenerErrorHandler
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.retry.interceptor.RetryInterceptorBuilder
import org.springframework.retry.interceptor.RetryOperationsInterceptor
import java.net.InetAddress
import java.util.*


@Configuration
class RabbitConfig {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(RabbitConfig::class.java)
    }

    @Bean
    fun routingKey(): RoutingKey {
        val rand = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        val id = "${InetAddress.getLocalHost().hostName}-$rand"
        log.info("Unique identifier for this session: $id")
        return RoutingKey(id)
    }

    @Bean
    fun jsonMessageConverter(): MessageConverter {
        // We must register this Kotlin module to get deserialization to work with data classes
        val mapper = ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerKotlinModule()
        return Jackson2JsonMessageConverter(mapper)
    }

    @Bean
    fun asyncTemplate(underlying: RabbitTemplate) = AsyncRabbitTemplate(underlying)

    @Bean
    fun rabbitListenerErrorHandler() = RabbitListenerErrorHandler { _, msg, e ->
        val name = msg.payload?.javaClass?.simpleName ?: "unknown"
        Counters.failedRequests.labels(name).inc()
        log.error("Error handling request $name", e)
        null
    }

    /* Don't retry ad infinitum */
    @Bean
    fun retryOperationsInterceptor() = RetryInterceptorBuilder
            .stateless()
            .maxAttempts(1)
            .build()!!

    @Bean
    fun rabbitListenerContainerFactory(
            configurer: SimpleRabbitListenerContainerFactoryConfigurer,
            connectionFactory: ConnectionFactory,
            retryOperationsInterceptor: RetryOperationsInterceptor
    ): SimpleRabbitListenerContainerFactory {
        val factory = SimpleRabbitListenerContainerFactory()
        configurer.configure(factory, connectionFactory)
        val chain = factory.adviceChain?.toMutableList() ?: mutableListOf<Advice>()
        chain.add(retryOperationsInterceptor)
        factory.setAdviceChain(*chain.toTypedArray())
        return factory
    }

}