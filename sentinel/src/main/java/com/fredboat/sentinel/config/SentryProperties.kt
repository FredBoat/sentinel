package com.fredboat.sentinel.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Created by napster on 27.07.18.
 */
@Component
@ConfigurationProperties(prefix = "sentry")
class SentryProperties(
        var dsn: String = "",
        var tags: Map<String, String> = HashMap()
)
