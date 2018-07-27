package com.fredboat.sentinel.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.filter.ThresholdFilter
import com.fredboat.sentinel.Launcher
import io.sentry.Sentry
import io.sentry.logback.SentryAppender
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import java.io.IOException
import java.util.*

/**
 * Created by napster on 27.07.18.
 */
@Configuration
class SentryConfig(sentryProperties: SentryProperties) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(JdaProperties::class.java)
    }

    private val sentryAppenderName = "SENTRY"

    init {
        val dsn = sentryProperties.dsn
        if (!dsn.isEmpty()) {
            log.info("DSN {}", dsn)
            turnOn(dsn, sentryProperties.tags)
        } else {
            turnOff()
        }
    }

    private final fun turnOn(dsn: String, tags: Map<String, String>) {
        log.info("Turning on sentry")
        val sentryClient = Sentry.init(dsn)

        tags.forEach { name, value ->
            run {
                log.info("Adding MDC: {} = {}", name, value)
                sentryClient.addTag(name, value)
            }
        }

        // set the git commit hash this was build on as the release
        val gitProps = Properties()
        try {
            gitProps.load(Launcher::class.java.classLoader.getResourceAsStream("git.properties"))
        } catch (e: NullPointerException) {
            log.error("Failed to load git repo information", e)
        } catch (e: IOException) {
            log.error("Failed to load git repo information", e)
        }

        val commitHash = gitProps.getProperty("git.commit.id")
        if (commitHash != null && !commitHash.isEmpty()) {
            log.info("Setting sentry release to commit hash {}", commitHash)
            sentryClient.release = commitHash
        } else {
            log.warn("No git commit hash found to set up sentry release")
        }

        getSentryLogbackAppender().start()
    }

    private final fun turnOff() {
        log.warn("Turning off sentry")
        Sentry.close()
        getSentryLogbackAppender().stop()
    }

    //programmatically creates a sentry appender
    @Synchronized
    private fun getSentryLogbackAppender(): SentryAppender {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)

        var sentryAppender: SentryAppender? = root.getAppender(sentryAppenderName) as? SentryAppender
        if (sentryAppender == null) {
            sentryAppender = SentryAppender()
            sentryAppender.name = sentryAppenderName

            val warningsOrAboveFilter = ThresholdFilter()
            warningsOrAboveFilter.setLevel(Level.WARN.levelStr)
            warningsOrAboveFilter.start()
            sentryAppender.addFilter(warningsOrAboveFilter)

            sentryAppender.context = loggerContext
            root.addAppender(sentryAppender)
        }
        return sentryAppender
    }
}
