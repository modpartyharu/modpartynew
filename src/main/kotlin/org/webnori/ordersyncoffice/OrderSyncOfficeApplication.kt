package org.webnori.ordersyncoffice

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import org.webnori.ordersyncoffice.repository.AppVersionRepository
import java.util.TimeZone

@SpringBootApplication
@EnableScheduling
class OrderSyncOfficeApplication {

    private val logger = LoggerFactory.getLogger(OrderSyncOfficeApplication::class.java)

    /**
     * 애플리케이션 기본 타임존을 Seoul로 설정
     * - 날짜/시간 표시시 KST로 작동
     */
    @PostConstruct
    fun setTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        logger.info("Default timezone set to: Asia/Seoul (KST)")
    }

    @Bean
    fun init(appVersionRepository: AppVersionRepository) = CommandLineRunner {
        val version = appVersionRepository.getLatestVersion()
        if (version != null) {
            logger.info("Application version: {}", version.version)
        } else {
            logger.warn("Application version not found in database")
        }
    }
}

fun main(args: Array<String>) {
    runApplication<OrderSyncOfficeApplication>(*args)
}
