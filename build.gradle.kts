plugins {
    kotlin("jvm") version "1.9.22"
    application
}

val ktor_version = "2.3.7"
val kotlin_version = "1.9.22"
val logback_version = "1.4.11"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Ktor 服务器依赖
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    
    // Ktor 客户端依赖
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    
    // GSON 序列化
    implementation("io.ktor:ktor-serialization-gson:$ktor_version")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // 日志相关依赖
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("org.slf4j:slf4j-api:2.0.9")
}

application {
    mainClass.set("com.example.ApplicationKt")
} 