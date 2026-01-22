package org.webnori.ordersyncoffice.service

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * API 호출 재시도 헬퍼
 *
 * 아임웹 토큰 동시성 문제 해결:
 * - 하나의 최신 토큰만 유효 (다른 사용자가 갱신하면 기존 토큰 무효화)
 * - API 실패 시 DB에서 최신 토큰 조회 후 재시도
 * - 0.5초 대기 후 재시도, 최대 3회
 */
@Component
class TokenRetryHelper(
    private val storeService: StoreService
) {
    private val logger = LoggerFactory.getLogger(TokenRetryHelper::class.java)

    companion object {
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 500L
    }

    /**
     * 토큰 기반 API 호출 재시도 래퍼
     *
     * @param siteCode 사이트 코드
     * @param operation API 호출 함수 (accessToken을 받아 결과 반환)
     * @return API 호출 결과
     * @throws Exception 최대 재시도 후에도 실패 시
     */
    suspend fun <T> executeWithRetry(
        siteCode: String,
        operation: suspend (accessToken: String) -> T
    ): T {
        var lastException: Exception? = null
        var currentToken = storeService.getCurrentAccessToken(siteCode)
            ?: throw IllegalStateException("No token found for siteCode: $siteCode")

        for (attempt in 1..MAX_RETRIES) {
            try {
                logger.debug("[Retry] Attempt {}/{} for siteCode: {}", attempt, MAX_RETRIES, siteCode)
                return operation(currentToken)
            } catch (e: Exception) {
                lastException = e
                val errorMessage = e.message ?: ""

                // 401 에러 또는 토큰 관련 에러인지 확인
                val isTokenError = errorMessage.contains("401") ||
                        errorMessage.contains("Unauthorized") ||
                        errorMessage.contains("token", ignoreCase = true)

                if (attempt < MAX_RETRIES) {
                    if (isTokenError) {
                        logger.warn("[Retry] Token error on attempt {}/{}, waiting {}ms before retry. Error: {}",
                            attempt, MAX_RETRIES, RETRY_DELAY_MS, errorMessage.take(100))
                    } else {
                        logger.warn("[Retry] API error on attempt {}/{}, waiting {}ms before retry. Error: {}",
                            attempt, MAX_RETRIES, RETRY_DELAY_MS, errorMessage.take(100))
                    }

                    // 대기 후 DB에서 최신 토큰 다시 조회
                    delay(RETRY_DELAY_MS)
                    currentToken = storeService.getCurrentAccessToken(siteCode)
                        ?: throw IllegalStateException("Token disappeared during retry for siteCode: $siteCode")

                    logger.debug("[Retry] Fetched fresh token from DB for attempt {}", attempt + 1)
                } else {
                    logger.error("[Retry] All {} attempts failed for siteCode: {}. Last error: {}",
                        MAX_RETRIES, siteCode, errorMessage.take(200))
                }
            }
        }

        throw lastException ?: IllegalStateException("Retry failed without exception")
    }

    /**
     * 토큰 기반 API 호출 (재시도 없이 단일 시도)
     * 실패 시 null 반환
     */
    suspend fun <T> executeOrNull(
        siteCode: String,
        operation: suspend (accessToken: String) -> T
    ): T? {
        val token = storeService.getCurrentAccessToken(siteCode) ?: return null
        return try {
            operation(token)
        } catch (e: Exception) {
            logger.debug("API call failed for siteCode: {}, error: {}", siteCode, e.message)
            null
        }
    }
}
