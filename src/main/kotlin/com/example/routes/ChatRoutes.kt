package com.example.routes

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.*
import java.io.IOException

// 添加模型类型常量
enum class ModelType {
    OLLAMA,
    GLM,
    KIMI
}

data class ModelConfig(
    val host: String,
    val endpoint: String,
    val requestBody: Map<String, Any>
)

// 当前使用的模型类型
const val CURRENT_MODEL_TYPE = "GLM"  // 可以改为 "OLLAMA" 或 "GLM"

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double? = null,
    val stream: Boolean? = false
)

data class Message(
    val role: String,
    val content: JsonElement  // 使用 JsonElement 来支持字符串或数组类型
)

fun Route.chatRoutes() {
    // 设置 NO_PROXY 环境变量
    System.setProperty("NO_PROXY", "localhost,127.0.0.1") 
    
    val client = HttpClient(CIO) {
        engine {
            proxy = null
            requestTimeout = 1_800_000  // 30 分钟超时
            maxConnectionsCount = 1000
            endpoint {
                maxConnectionsPerRoute = 100
                pipelineMaxSize = 20
                keepAliveTime = 5000
                connectTimeout = 5000
                connectAttempts = 5
            }
        }
    }
    
    // 设置环境变量
    System.setProperty("http.proxyHost", "")
    System.setProperty("http.proxyPort", "")
    System.setProperty("https.proxyHost", "")
    System.setProperty("https.proxyPort", "")
    
    val logger = LoggerFactory.getLogger("ChatRoutes")
    val gson = Gson()
    val prettyGson = GsonBuilder().setPrettyPrinting().create()

    // 处理聊天请求
    post("/api/v1/chat/completions") {
        val modelType = when (CURRENT_MODEL_TYPE.uppercase()) {
            "GLM" -> ModelType.GLM
            "KIMI" -> ModelType.KIMI
            else -> ModelType.OLLAMA
        }
        handleChatRequest(call, client, logger, gson, prettyGson, modelType)
    }
}

fun createOllamaConfig(request: ChatRequest): ModelConfig = ModelConfig(
    host = System.getenv("OLLAMA_HOST") ?: "127.0.0.1:11434",
    endpoint = "/api/chat",
    requestBody = mapOf(
        "model" to (request.model.takeIf { it.isNotBlank() } ?: "deepseek-r1:1.5b"),
        "messages" to request.messages.map { message ->
            mapOf(
                "role" to message.role,
                "content" to when {
                    message.content.isJsonArray -> {
                        message.content.asJsonArray
                            .mapNotNull { 
                                if (it.isJsonObject && it.asJsonObject.has("text")) {
                                    it.asJsonObject.get("text").asString
                                } else {
                                    null
                                }
                            }
                            .joinToString("\n")
                    }
                    message.content.isJsonPrimitive -> message.content.asString
                    else -> message.content.toString()
                }
            )
        },
        "temperature" to (request.temperature ?: 0.7),
        "stream" to (request.stream ?: false),
        "max_tokens" to 4096,
        "context_length" to 4096
    )
)

fun createGlmConfig(request: ChatRequest): ModelConfig = ModelConfig(
    host = "https://open.bigmodel.cn",
    endpoint = "/api/paas/v4/chat/completions",
    requestBody = mapOf(
        "model" to "glm-4-long",
        "messages" to request.messages.map { message ->
            mapOf(
                "role" to message.role,
                "content" to when {
                    message.content.isJsonArray -> {
                        message.content.asJsonArray
                            .mapNotNull { 
                                if (it.isJsonObject && it.asJsonObject.has("text")) {
                                    it.asJsonObject.get("text").asString
                                } else {
                                    null
                                }
                            }
                            .joinToString("\n")
                    }
                    message.content.isJsonPrimitive -> message.content.asString
                    else -> message.content.toString()
                }
            )
        },
        "temperature" to (request.temperature ?: 0.7),
        "stream" to (request.stream ?: false)
    )
)

fun createKimiConfig(request: ChatRequest): ModelConfig = ModelConfig(
    host = "https://api.moonshot.cn",
    endpoint = "/v1/chat/completions",
    requestBody = mapOf(
        "model" to "moonshot-v1-8k",
        "messages" to request.messages.map { message ->
            mapOf(
                "role" to message.role,
                "content" to when {
                    message.content.isJsonArray -> {
                        message.content.asJsonArray
                            .mapNotNull { 
                                if (it.isJsonObject && it.asJsonObject.has("text")) {
                                    it.asJsonObject.get("text").asString
                                } else {
                                    null
                                }
                            }
                            .joinToString("\n")
                    }
                    message.content.isJsonPrimitive -> message.content.asString
                    else -> message.content.toString()
                }
            )
        },
        "temperature" to (request.temperature ?: 0.7),
        "stream" to (request.stream ?: false)
    )
)

private suspend fun handleChatRequest(
    call: ApplicationCall,
    client: HttpClient,
    logger: Logger,
    gson: Gson,
    prettyGson: Gson,
    modelType: ModelType
) {
    try {
        // 读取请求体
        val requestText = call.receiveText()
        val request = gson.fromJson(requestText, ChatRequest::class.java)
        
        // 获取所有请求头
        val headers = call.request.headers
        
        // 记录请求信息
        logger.info("""
            收到 ${modelType.name} 聊天请求:
            --- 请求头 ---
            ${headers.entries().joinToString("\n                ") { "${it.key}: ${it.value.joinToString(", ")}" }}
            
            --- 请求体 ---
            ${prettyGson.toJson(request).split("\n").joinToString("\n                ")}
        """.trimIndent())
        
        // 根据模型类型准备请求
        val modelConfig = when (modelType) {
            ModelType.OLLAMA -> createOllamaConfig(request)
            ModelType.GLM -> createGlmConfig(request)
            ModelType.KIMI -> createKimiConfig(request)
        }
        
        logger.info("""
            发送到 ${modelType.name} 的请求体:
            ${prettyGson.toJson(modelConfig.requestBody).split("\n").joinToString("\n                    ")}
        """.trimIndent())
        
        val response = client.post(when (modelType) {
            ModelType.OLLAMA -> "http://${modelConfig.host}${modelConfig.endpoint}"
            ModelType.GLM -> "${modelConfig.host}${modelConfig.endpoint}"
            ModelType.KIMI -> "${modelConfig.host}${modelConfig.endpoint}"
        }) {
            contentType(ContentType.Application.Json)
            
            // 转发原始请求头
            headers.forEach { key, values ->
                if (key.lowercase() !in listOf("content-length", "host", "connection")) {
                    values.forEach { value ->
                        header(key, value)
                    }
                }
            }
            
            setBody(gson.toJson(modelConfig.requestBody))
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("${modelType.name} 返回错误: ${response.status} - $errorBody")
            throw IOException("${modelType.name} API 错误: ${response.status} - $errorBody")
        }

        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondTextWriter(contentType = ContentType.Application.Json) {
            val channel = response.bodyAsChannel()
            var buffer = ByteArray(8192)
            var retryCount = 0
            val maxRetries = 5
            
            try {
                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                    if (bytesRead < 0) {
                        if (retryCount < maxRetries) {
                            retryCount++
                            logger.warn("读取响应失败，正在进行第 $retryCount 次重试")
                            kotlinx.coroutines.delay(1000)
                            continue
                        } else {
                            logger.error("达到最大重试次数，放弃读取")
                            throw IOException("读取响应失败，已重试 $maxRetries 次")
                        }
                    }
                    if (bytesRead == 0) {
                        kotlinx.coroutines.delay(500)
                        continue
                    }
                    
                    val chunkText = buffer.decodeToString(endIndex = bytesRead)
                    if (chunkText.contains("error")) {
                        logger.error("收到错误响应: $chunkText")
                        throw IOException("${modelType.name} 返回错误: $chunkText")
                    }
                    
                    if (chunkText.trim().isEmpty()) {
                        logger.warn("收到空响应")
                        continue
                    }

                    if (modelType == ModelType.OLLAMA) {
                        try {
                            val responseObj = gson.fromJson(chunkText, Map::class.java)
                            if (responseObj == null || !responseObj.containsKey("message")) {
                                logger.error("无效的响应格式: $chunkText")
                                throw IOException("无效的响应格式")
                            }
                        } catch (e: Exception) {
                            logger.error("解析响应时发生错误: ${e.message}, 响应内容: $chunkText")
                        }
                    }
                    
                    write(chunkText)
                    flush()
                    
                    logger.info("收到 ${modelType.name} 响应块: $chunkText")
                }
            } catch (e: Exception) {
                logger.error("读取 ${modelType.name} 响应时发生错误: ${e.message}", e)
                throw e
            }
        }
        
    } catch (e: Exception) {
        logger.error("处理 ${modelType.name} 聊天请求时发生错误: ${e.message}", e)
        val errorResponse = mapOf(
            "error" to mapOf(
                "message" to (e.message ?: "未知错误"),
                "type" to e.javaClass.simpleName
            )
        )
        call.respond(HttpStatusCode.InternalServerError, errorResponse)
    }
} 