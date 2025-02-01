package com.example

import com.example.routes.chatRoutes
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.gson.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import ch.qos.logback.classic.spi.ILoggingEvent
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

fun main() {
    // 设置环境变量
    System.setProperty("NO_PROXY", "localhost,127.0.0.1")
    System.setProperty("OLLAMA_HOST", "127.0.1:11434")
    
    // 启动 ollama serve
    val processBuilder = ProcessBuilder("ollama", "serve")
    processBuilder.inheritIO()
    val process = processBuilder.start()
    
    // 清理旧日志
    cleanOldLogs()
    
    // 获取应用启动时间
    val startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    
    // 配置日志
    configureLogging(startTime)
    
    // 创建logger
    val logger = LoggerFactory.getLogger("MainApplication")
    logger.info("应用启动于: $startTime")
    
    embeddedServer(Netty, port = 3000) {
        install(ContentNegotiation) {
            gson()
        }
        
        routing {
            // 添加默认路径
            get("/") {
                val currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val clientIp = call.request.local.remoteHost
                val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                
                logger.info("""
                    收到访问请求:
                    - 时间: $currentTime
                    - 客户端IP: $clientIp
                    - User-Agent: $userAgent
                    - 请求路径: ${call.request.uri}
                    - 请求方法: ${call.request.httpMethod.value}
                """.trimIndent())
                
                call.respondText("Hello World! Current time: $currentTime")
            }
            
            chatRoutes()
        }
    }.start(wait = true)
}

private fun cleanOldLogs() {
    val logDir = Paths.get("logs")
    if (logDir.exists()) {
        // 删除所有日志文件
        logDir.listDirectoryEntries("*.log").forEach { file ->
            if (file.isRegularFile()) {
                try {
                    Files.delete(file)
                } catch (e: Exception) {
                    println("无法删除日志文件 ${file.fileName}: ${e.message}")
                }
            }
        }
    }
}

private fun configureLogging(startTime: String) {
    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
    
    // 创建日志目录
    val logDir = Paths.get("logs")
    if (!Files.exists(logDir)) {
        Files.createDirectories(logDir)
    }
    
    // 配置文件追加器
    val fileAppender = RollingFileAppender<ILoggingEvent>().apply {
        context = loggerContext
        name = "FILE"
        file = "logs/application_$startTime.log"
        
        // 配置编码器
        encoder = PatternLayoutEncoder().apply {
            context = loggerContext
            pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
            start()
        }
    }
    
    // 配置滚动策略
    val rollingPolicy = TimeBasedRollingPolicy<ILoggingEvent>().apply {
        context = loggerContext
        fileNamePattern = "logs/application_$startTime.%d{yyyy-MM-dd-HH}.log"
        maxHistory = 168 // 保留7天的日志（每小时一个文件）
        setParent(fileAppender)
        start()
    }
    
    fileAppender.rollingPolicy = rollingPolicy
    fileAppender.start()
    
    // 添加追加器到根日志记录器
    rootLogger.addAppender(fileAppender)
} 