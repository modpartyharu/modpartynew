package org.webnori.ordersyncoffice.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.webnori.ordersyncoffice.domain.OAuthTokenBatch
import org.webnori.ordersyncoffice.mapper.OAuthTokenBatchMapper
import java.time.LocalDateTime

/**
 * 배치/스케줄러용 OAuth 토큰 관리 서비스
 * 어드민 사용자 토큰과 분리하여 스케줄러가 독립적으로 토큰을 관리
 *
 * 토큰 획득 전략:
 * 1. DB에 저장된 배치용 토큰이 있는지 확인
 * 2. 토큰이 만료되었으면 RefreshToken으로 갱신
 * 3. RefreshToken도 만료되었으면 OAuth 인증 플로우 필요 (관리자 개입)
 */
@Service
class BatchTokenService(
    private val oAuthTokenBatchMapper: OAuthTokenBatchMapper,
    private val imwebApiService: ImwebApiService,
    private val storeService: StoreService
) {
    private val logger = LoggerFactory.getLogger(BatchTokenService::class.java)

    companion object {
        // 토큰 만료 여유시간 (5분 전에 갱신)
        const val TOKEN_REFRESH_MARGIN_MINUTES = 5L
    }

    /**
     * 배치용 유효한 액세스 토큰 획득
     * - 토큰이 없으면 어드민 토큰에서 복사하여 사용
     * - 토큰이 만료되었으면 RefreshToken으로 갱신
     * - RefreshToken도 만료되면 null 반환 (관리자 개입 필요)
     */
    fun getValidAccessToken(siteCode: String): String? {
        logger.debug("Getting valid batch token for siteCode: {}", siteCode)

        // 1. 배치용 토큰 조회
        var batchToken = oAuthTokenBatchMapper.findBySiteCode(siteCode)

        // 2. 배치용 토큰이 없으면 어드민 토큰에서 복사
        if (batchToken == null) {
            logger.info("No batch token found, copying from admin token for siteCode: {}", siteCode)
            batchToken = copyFromAdminToken(siteCode)
            if (batchToken == null) {
                logger.warn("No admin token available to copy for siteCode: {}", siteCode)
                return null
            }
        }

        // 3. 토큰 만료 확인
        val now = LocalDateTime.now()
        val expiresAt = batchToken.expiresAt

        if (expiresAt != null && expiresAt.minusMinutes(TOKEN_REFRESH_MARGIN_MINUTES).isBefore(now)) {
            logger.info("Batch token expired or expiring soon, attempting refresh for siteCode: {}", siteCode)

            // 4. RefreshToken으로 갱신 시도
            val refreshedToken = refreshToken(siteCode, batchToken)
            if (refreshedToken != null) {
                return refreshedToken.accessToken
            }

            // 5. RefreshToken도 만료 - 어드민 토큰에서 다시 복사 시도
            logger.warn("Refresh token also expired, trying to copy from admin token for siteCode: {}", siteCode)
            val copiedToken = copyFromAdminToken(siteCode)
            if (copiedToken != null) {
                return copiedToken.accessToken
            }

            logger.error("Failed to get valid batch token for siteCode: {}", siteCode)
            return null
        }

        return batchToken.accessToken
    }

    /**
     * 어드민 토큰에서 배치용 토큰으로 복사
     */
    private fun copyFromAdminToken(siteCode: String): OAuthTokenBatch? {
        val adminToken = storeService.getOAuthToken(siteCode) ?: return null

        val batchToken = OAuthTokenBatch(
            siteCode = siteCode,
            accessToken = adminToken.accessToken,
            tokenType = adminToken.tokenType,
            expiresIn = adminToken.expiresIn,
            expiresAt = adminToken.expiresAt,
            refreshToken = adminToken.refreshToken,
            refreshTokenExpiresAt = adminToken.refreshTokenExpiresAt,
            scopes = adminToken.scopes,
            issuedAt = adminToken.issuedAt
        )

        // 기존 배치 토큰이 있으면 업데이트, 없으면 인서트
        val existing = oAuthTokenBatchMapper.findBySiteCode(siteCode)
        if (existing != null) {
            oAuthTokenBatchMapper.update(batchToken)
        } else {
            oAuthTokenBatchMapper.insert(batchToken)
        }

        logger.info("Copied admin token to batch token for siteCode: {}", siteCode)
        return batchToken
    }

    /**
     * RefreshToken으로 토큰 갱신
     */
    private fun refreshToken(siteCode: String, currentToken: OAuthTokenBatch): OAuthTokenBatch? {
        val refreshToken = currentToken.refreshToken
        if (refreshToken.isNullOrBlank()) {
            logger.warn("No refresh token available for siteCode: {}", siteCode)
            return null
        }

        // RefreshToken 만료 확인
        val refreshExpiresAt = currentToken.refreshTokenExpiresAt
        if (refreshExpiresAt != null && refreshExpiresAt.isBefore(LocalDateTime.now())) {
            logger.warn("Refresh token expired for siteCode: {}", siteCode)
            return null
        }

        return try {
            runBlocking {
                val response = imwebApiService.refreshToken(refreshToken)
                val tokenData = response.data ?: return@runBlocking null

                val now = LocalDateTime.now()
                // 아임웹 토큰 만료시간: 기본 2시간 (7200초)
                val expiresIn = 7200
                val expiresAt = now.plusSeconds(expiresIn.toLong())
                // RefreshToken 만료: 보통 30일
                val refreshExpiresAt = now.plusDays(30)

                val newToken = OAuthTokenBatch(
                    siteCode = siteCode,
                    accessToken = tokenData.accessToken,
                    tokenType = "Bearer",
                    expiresIn = expiresIn,
                    expiresAt = expiresAt,
                    refreshToken = tokenData.refreshToken ?: refreshToken,
                    refreshTokenExpiresAt = refreshExpiresAt,
                    scopes = tokenData.scope?.joinToString(","),
                    issuedAt = now
                )

                oAuthTokenBatchMapper.update(newToken)
                logger.info("Batch token refreshed successfully for siteCode: {}", siteCode)
                newToken
            }
        } catch (e: Exception) {
            logger.error("Failed to refresh batch token for siteCode: {} - {}", siteCode, e.message)
            null
        }
    }

    /**
     * 배치용 토큰 정보 조회
     */
    fun getBatchToken(siteCode: String): OAuthTokenBatch? {
        return oAuthTokenBatchMapper.findBySiteCode(siteCode)
    }

    /**
     * 배치용 토큰이 유효한지 확인
     */
    fun isTokenValid(siteCode: String): Boolean {
        val token = oAuthTokenBatchMapper.findBySiteCode(siteCode) ?: return false
        val expiresAt = token.expiresAt ?: return false
        return expiresAt.isAfter(LocalDateTime.now())
    }

    /**
     * 배치용 토큰 삭제 (수동 초기화용)
     */
    fun deleteBatchToken(siteCode: String): Int {
        return oAuthTokenBatchMapper.deleteBySiteCode(siteCode)
    }

    /**
     * 어드민 토큰에서 배치 토큰으로 강제 복사 (수동 갱신용)
     */
    fun forceRefreshFromAdmin(siteCode: String): Boolean {
        val copied = copyFromAdminToken(siteCode)
        return copied != null
    }

    /**
     * RefreshToken으로 강제 갱신 시도 (API 401 에러 시 사용)
     * @return 갱신된 액세스 토큰 또는 null
     */
    fun forceRefreshToken(siteCode: String): String? {
        logger.info("Force refreshing batch token for siteCode: {}", siteCode)

        val batchToken = oAuthTokenBatchMapper.findBySiteCode(siteCode)
        if (batchToken == null) {
            logger.warn("No batch token to refresh for siteCode: {}", siteCode)
            return null
        }

        val refreshedToken = refreshToken(siteCode, batchToken)
        return refreshedToken?.accessToken
    }

    /**
     * 어드민 토큰에서 강제 복사 후 액세스 토큰 반환 (API 401 에러 시 사용)
     * @return 복사된 액세스 토큰 또는 null
     */
    fun forceCopyFromAdmin(siteCode: String): String? {
        logger.info("Force copying from admin token for siteCode: {}", siteCode)

        val copiedToken = copyFromAdminToken(siteCode)
        return copiedToken?.accessToken
    }
}
