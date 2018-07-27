package com.fredboat.sentinel.api

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.StringWriter
import java.util.Arrays
import kotlin.collections.HashSet

/**
 * Created by napster on 27.07.18.
 */
@RestController
@RequestMapping("/metrics")
class MetricsEndpoint {

    val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

    @GetMapping(produces = [(TextFormat.CONTENT_TYPE_004)])
    fun getMetrics(@RequestParam(name = "name[]", required = false) includedParam: Array<String>?): String {
        return buildAnswer(includedParam)
    }

    private fun buildAnswer(includedParam: Array<String>?): String {
        val params: Set<String> =
                if (includedParam == null) {
                    emptySet()
                } else {
                    HashSet(Arrays.asList(*includedParam))
                }

        val writer = StringWriter()
        writer.use {
            TextFormat.write004(it, registry.filteredMetricFamilySamples(params))
            it.flush()
        }

        return writer.toString()
    }
}
