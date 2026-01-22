package org.webnori.ordersyncoffice.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 스케줄러 실시간 로그 서비스
 * - 인메모리 로그 버퍼 관리 (최근 100개 유지)
 * - SSE를 통한 실시간 로그 스트리밍
 */
@Service
class SchedulerLogService {

    companion object {
        const val MAX_LOG_SIZE = 100  // 최대 로그 보관 개수
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }

    // 로그 버퍼 (최근 로그 유지)
    private val logBuffer = ConcurrentLinkedDeque<SchedulerLogEntry>()

    // SSE 브로드캐스트를 위한 SharedFlow
    private val _logFlow = MutableSharedFlow<SchedulerLogEntry>(replay = 0, extraBufferCapacity = 100)
    val logFlow: Flow<SchedulerLogEntry> = _logFlow.asSharedFlow()

    /**
     * 로그 추가 및 브로드캐스트
     */
    fun log(level: LogLevel, message: String, siteCode: String? = null) {
        val entry = SchedulerLogEntry(
            timestamp = LocalDateTime.now(),
            level = level,
            message = message,
            siteCode = siteCode
        )

        // 버퍼에 추가
        logBuffer.addFirst(entry)

        // 버퍼 크기 제한
        while (logBuffer.size > MAX_LOG_SIZE) {
            logBuffer.removeLast()
        }

        // SSE로 브로드캐스트
        _logFlow.tryEmit(entry)
    }

    // 편의 메서드들
    fun info(message: String, siteCode: String? = null) = log(LogLevel.INFO, message, siteCode)
    fun debug(message: String, siteCode: String? = null) = log(LogLevel.DEBUG, message, siteCode)
    fun warn(message: String, siteCode: String? = null) = log(LogLevel.WARN, message, siteCode)
    fun error(message: String, siteCode: String? = null) = log(LogLevel.ERROR, message, siteCode)
    fun success(message: String, siteCode: String? = null) = log(LogLevel.SUCCESS, message, siteCode)

    /**
     * 현재 버퍼의 로그 목록 조회
     */
    fun getRecentLogs(limit: Int = MAX_LOG_SIZE): List<SchedulerLogEntry> {
        return logBuffer.take(limit).toList()
    }

    /**
     * 로그 버퍼 초기화
     */
    fun clearLogs() {
        logBuffer.clear()
    }

    /**
     * 특정 사이트의 로그만 필터링
     */
    fun getLogsBySiteCode(siteCode: String, limit: Int = 50): List<SchedulerLogEntry> {
        return logBuffer.filter { it.siteCode == siteCode }.take(limit).toList()
    }
}

/**
 * 로그 레벨
 */
enum class LogLevel {
    DEBUG,   // 디버그 정보
    INFO,    // 일반 정보
    WARN,    // 경고
    ERROR,   // 에러
    SUCCESS  // 성공 (녹색)
}

/**
 * 로그 엔트리
 */
data class SchedulerLogEntry(
    val timestamp: LocalDateTime,
    val level: LogLevel,
    val message: String,
    val siteCode: String? = null
) {
    fun toFormattedString(): String {
        val timeStr = timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        val levelStr = level.name.padEnd(7)
        val siteStr = siteCode?.let { "[$it] " } ?: ""
        return "[$timeStr] $levelStr $siteStr$message"
    }

    fun toJson(): Map<String, Any?> {
        return mapOf(
            "timestamp" to timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")),
            "time" to timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
            "level" to level.name,
            "message" to message,
            "siteCode" to siteCode,
            "formatted" to toFormattedString()
        )
    }
}
