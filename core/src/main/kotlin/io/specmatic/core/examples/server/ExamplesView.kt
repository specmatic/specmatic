package io.specmatic.core.examples.server

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.Feature
import io.specmatic.core.examples.server.ExamplesInteractiveServer.Companion.getExistingExampleFile
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.File

class ExamplesView {
    companion object {
        fun getEndpoints(feature: Feature, examplesDir: File): List<Endpoint> {
            return feature.scenarios.map {
                Endpoint(
                    path = convertPathParameterStyle(it.path),
                    rawPath = it.path,
                    method = it.method,
                    responseStatus = it.httpResponsePattern.status,
                    contentType = it.httpRequestPattern.headersPattern.contentType,
                    exampleFile = getExistingExampleFile(it, examplesDir)
                )
            }.filterEndpoints().sortEndpoints()
        }

        private fun List<Endpoint>.sortEndpoints(): List<Endpoint> {
            return this.sortedWith(compareBy({ it.path }, { it.method }, { it.responseStatus }))
        }

        private fun List<Endpoint>.filterEndpoints(): List<Endpoint> {
            return this.filter { it.responseStatus in 200..299 }
        }

        fun List<Endpoint>.groupEndpoints(): Map<String, Map<String, List<Endpoint>>> {
            return this.groupBy { it.path }.mapValues { pathGroup ->
                pathGroup.value.groupBy { it.method }
            }
        }

        fun Map<String, Map<String, List<Endpoint>>>.toTableRows(): List<TableRow> {
            return this.flatMap { (_, methodGroup) ->
                val pathSpan = methodGroup.values.sumOf { it.size }
                val methodSet: MutableSet<String> = mutableSetOf()
                var showPath = true

                methodGroup.flatMap { (method, endpoints) ->
                    endpoints.map {
                        TableRow(
                            rawPath = it.rawPath,
                            path = it.path,
                            method = it.method,
                            responseStatus = it.responseStatus.toString(),
                            pathSpan = pathSpan,
                            methodSpan = endpoints.size,
                            showPath = showPath,
                            showMethod = !methodSet.contains(method),
                            contentType = it.contentType ?: "",
                            example = it.exampleFile?.absolutePath,
                            exampleName = it.exampleFile?.nameWithoutExtension
                        ).also { methodSet.add(method); showPath = false }
                    }
                }
            }
        }
    }
}

data class TableRow(
    val rawPath: String,
    val path: String,
    val method: String,
    val responseStatus: String,
    val contentType: String,
    val pathSpan: Int,
    val methodSpan: Int,
    val showPath: Boolean,
    val showMethod: Boolean,
    val example: String? = null,
    val exampleName: String? = null
)

data class Endpoint(
    val path: String,
    val rawPath: String,
    val method: String,
    val responseStatus: Int,
    val contentType: String? = null,
    val exampleFile: File? = null
)

class HtmlTemplateConfiguration {
    companion object {
        private fun configureTemplateEngine(): TemplateEngine {
            val templateResolver = ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                templateMode = TemplateMode.HTML
                characterEncoding = "UTF-8"
            }

            return TemplateEngine().apply {
                setTemplateResolver(templateResolver)
            }
        }

        fun process(templateName: String, variables: Map<String, Any>): String {
            val templateEngine = configureTemplateEngine()
            return templateEngine.process(templateName, Context().apply {
                setVariables(variables)
            })
        }
    }
}

