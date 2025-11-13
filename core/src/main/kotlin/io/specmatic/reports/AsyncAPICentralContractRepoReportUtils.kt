package io.specmatic.reports

import com.asyncapi.schemas.asyncapi.Reference
import com.asyncapi.v3._0_0.model.operation.reply.OperationReply
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exceptionCauseMessage
import org.yaml.snakeyaml.Yaml
import java.io.File
import com.asyncapi.v2._6_0.model.AsyncAPI as AsyncAPI_2_6_0
import com.asyncapi.v3._0_0.model.AsyncAPI as AsyncAPI_3_0_0

fun getAsyncAPISpecificationRows(
    specifications: List<File>,
    currentWorkingDir: String
): List<SpecificationRow> {
    return specifications.map { file ->
        SpecificationRow(
            specification = file.relativeTo(File("").canonicalFile).path,
            serviceType = SpecType.ASYNCAPI.name,
            specType = SpecType.ASYNCAPI.name,
            operations = asyncAPIOperationsFrom(file)
        )
    }
}

fun hasAsyncApiFileExtension(specPath: String): Boolean {
    return specPath.endsWith(".yaml") ||
            specPath.endsWith(".yml") ||
            specPath.endsWith(".json")
}

fun isAsyncAPI(specPath: String): Boolean {
    return try {
        Yaml().load<MutableMap<String, Any?>>(File(specPath).reader()).contains("asyncapi")
    } catch(e: Throwable) {
        logger.log(e, "Could not parse $specPath")
        false
    }
}

private fun asyncAPIOperationsFrom(file: File): List<AsyncAPISpecificationOperation> {
    return when {
        asyncAPIVersionOf(file) == "3.0.0" -> asyncAPIV3OperationsFrom(file)
        asyncAPIVersionOf(file).startsWith("2.") -> asyncAPIV2OperationsFrom(file)
        else -> emptyList()
    }
}

private fun asyncAPIV3OperationsFrom(file: File): List<AsyncAPISpecificationOperation> {
    val asyncAPI = try {
        ObjectMapper(YAMLFactory()).readValue(
            file.readText(),
            AsyncAPI_3_0_0::class.java
        )
    } catch (e: Exception) {
        logger.log("Could not parse ${file.path} due to the following error:")
        logger.log(exceptionCauseMessage(e))
        null
    }
    if(asyncAPI == null) return emptyList()

    return asyncAPI.operations.orEmpty().map { (name, op) ->
        op as com.asyncapi.v3._0_0.model.operation.Operation
        val replyOp = (op.reply as? OperationReply).takeIf { op.reply != null }
        AsyncAPISpecificationOperation(
            operationId = name,
            channel = op.channel.name(),
            replyChannel = replyOp?.channel?.name(),
            action = op.action.name.lowercase()
        )
    }
}

private fun asyncAPIV2OperationsFrom(file: File): List<AsyncAPISpecificationOperation> {
    val asyncAPI = try {
        ObjectMapper(YAMLFactory()).readValue(
            file.readText(),
            AsyncAPI_2_6_0::class.java
        )
    } catch (e: Exception) {
        logger.log("Could not parse ${file.path} due to the following error:")
        logger.log(exceptionCauseMessage(e))
        null
    }
    if(asyncAPI == null) return emptyList()

    return asyncAPI.channels.map { (channelName, channel) ->
        channel.publish?.let { op ->
            AsyncAPISpecificationOperation(
                operationId = op.operationId.orEmpty(),
                channel = channelName,
                action = "publish"
            )
        } ?: channel.subscribe?.let { op ->
            AsyncAPISpecificationOperation(
                operationId = op.operationId.orEmpty(),
                channel = channelName,
                action = "subscribe"
            )
        }
    }.filterNotNull()
}

private fun asyncAPIVersionOf(file: File): String = ObjectMapper(YAMLFactory()).readTree(file).let {
    it.findPath("asyncapi").asText()
}

private fun Reference.name(): String = ref.substringAfterLast("/")
