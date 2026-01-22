package org.webnori.ordersyncoffice.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.webnori.ordersyncoffice.domain.*
import org.webnori.ordersyncoffice.repository.OAuthTokenRepository
import org.webnori.ordersyncoffice.repository.StoreInfoRepository
import java.time.LocalDateTime

@Service
class StoreService(
    private val storeInfoRepository: StoreInfoRepository,
    private val oAuthTokenRepository: OAuthTokenRepository
) {
    private val logger = LoggerFactory.getLogger(StoreService::class.java)

    /**
     * 모든 활성 스토어 조회
     */
    fun getAllActiveStores(): List<StoreInfo> {
        return storeInfoRepository.findAllActive()
    }

    /**
     * 모든 스토어 조회
     */
    fun getAllStores(): List<StoreInfo> {
        return storeInfoRepository.findAll()
    }

    /**
     * 스토어 정보 조회
     */
    fun getStore(siteCode: String): StoreInfo? {
        return storeInfoRepository.findBySiteCode(siteCode)
    }

    /**
     * 스토어 정보 저장
     */
    fun saveStore(storeInfo: StoreInfo): StoreInfo {
        logger.info("Saving store info: siteCode={}, siteName={}", storeInfo.siteCode, storeInfo.siteName)
        return storeInfoRepository.save(storeInfo)
    }

    /**
     * Imweb API 응답을 StoreInfo로 변환
     * site-info API는 기본 정보만 반환, unit 정보로 상세 정보 획득 필요
     */
    fun convertSiteInfoToStoreInfo(siteInfo: ImwebSiteInfo): StoreInfo {
        return StoreInfo(
            siteCode = siteInfo.siteCode ?: throw IllegalArgumentException("Site code is required"),
            siteName = null, // unit 정보에서 획득
            siteUrl = null,
            adminEmail = siteInfo.ownerUid,
            companyName = null, // unit 정보에서 획득
            representativeName = null,
            businessNumber = null,
            phone = null,
            address = null,
            isActive = true
        )
    }

    /**
     * Unit 정보로 StoreInfo 업데이트
     */
    fun updateStoreInfoWithUnit(storeInfo: StoreInfo, unitInfo: ImwebUnitInfo): StoreInfo {
        return storeInfo.copy(
            siteName = unitInfo.name,
            siteUrl = unitInfo.primaryDomain,
            companyName = unitInfo.companyName,
            representativeName = unitInfo.presidentName,
            businessNumber = unitInfo.companyRegistrationNo,
            phone = unitInfo.phone,
            adminEmail = unitInfo.email ?: storeInfo.adminEmail,
            address = listOfNotNull(unitInfo.address1, unitInfo.address2).joinToString(" ").ifEmpty { null },
            unitCode = unitInfo.unitCode
        )
    }

    /**
     * OAuth 토큰 조회
     */
    fun getOAuthToken(siteCode: String): OAuthToken? {
        return oAuthTokenRepository.findBySiteCode(siteCode)
    }

    /**
     * OAuth 토큰 저장
     */
    fun saveOAuthToken(oAuthToken: OAuthToken): OAuthToken {
        logger.info("Saving OAuth token for siteCode={}", oAuthToken.siteCode)
        return oAuthTokenRepository.save(oAuthToken)
    }

    /**
     * Imweb Token Response를 OAuthToken으로 변환
     * Imweb API 응답: {statusCode, data: {accessToken, refreshToken, scope}}
     */
    fun convertTokenResponseToOAuthToken(siteCode: String, tokenResponse: ImwebTokenResponse): OAuthToken {
        val now = LocalDateTime.now()
        val tokenData = tokenResponse.data ?: throw IllegalArgumentException("Token data is null")

        // Imweb API는 expires_in을 명시적으로 반환하지 않으므로 기본값 사용 (1시간)
        val defaultExpiresIn = 3600
        val expiresAt = now.plusSeconds(defaultExpiresIn.toLong())
        // Refresh token 만료: 기본 30일
        val refreshTokenExpiresAt = now.plusDays(30)

        return OAuthToken(
            siteCode = siteCode,
            accessToken = tokenData.accessToken,
            tokenType = "Bearer",
            expiresIn = defaultExpiresIn,
            expiresAt = expiresAt,
            refreshToken = tokenData.refreshToken,
            refreshTokenExpiresAt = refreshTokenExpiresAt,
            scopes = tokenData.scope?.joinToString(" "),  // List를 공백 구분 문자열로 변환
            issuedAt = now
        )
    }

    /**
     * 스토어 삭제 (토큰도 함께 삭제됨 - CASCADE)
     */
    fun deleteStore(siteCode: String): Boolean {
        logger.info("Deleting store: siteCode={}", siteCode)
        return storeInfoRepository.delete(siteCode) > 0
    }

    /**
     * 토큰이 만료되었는지 확인
     */
    fun isTokenExpired(siteCode: String): Boolean {
        val token = oAuthTokenRepository.findBySiteCode(siteCode) ?: return true
        return token.expiresAt?.isBefore(LocalDateTime.now()) ?: true
    }

    /**
     * 유효한 액세스 토큰 획득
     *
     * @param siteCode 사이트 코드
     * @param allowRefresh true: 만료 임박/만료 시 갱신 시도 (스케줄러용)
     *                     false: 갱신 없이 현재 토큰 반환 (사용자 액션용)
     *                     - 사용자 액션은 재시도 로직으로 동시성 문제 해결
     * @return 액세스 토큰 또는 null
     */
    fun getValidAccessToken(siteCode: String, allowRefresh: Boolean = true): String? {
        val token = oAuthTokenRepository.findBySiteCode(siteCode) ?: return null

        val now = LocalDateTime.now()
        val expiresAt = token.expiresAt

        // 토큰이 아직 유효한 경우 (만료 5분 전 여유 없이 체크)
        if (expiresAt != null && expiresAt.isAfter(now)) {
            return token.accessToken
        }

        // 토큰이 만료된 경우
        if (allowRefresh) {
            // 스케줄러: 갱신 필요 알림 (BatchTokenService가 처리)
            logger.warn("Token expired for siteCode={}, refresh needed", siteCode)
        } else {
            // 사용자 액션: 만료된 토큰이라도 반환 (재시도 로직에서 처리)
            logger.debug("Token may be expired for siteCode={}, returning for retry logic", siteCode)
            return token.accessToken
        }

        return null
    }

    /**
     * 현재 저장된 액세스 토큰 조회 (만료 여부 무관, 캐싱 없이 DB 직접 조회)
     * 재시도 로직에서 최신 토큰 조회용
     */
    fun getCurrentAccessToken(siteCode: String): String? {
        return oAuthTokenRepository.findBySiteCode(siteCode)?.accessToken
    }

    /**
     * OAuth 토큰 삭제 (재인증 시 기존 토큰 무효화)
     */
    fun deleteOAuthToken(siteCode: String): Boolean {
        logger.info("Deleting OAuth token for siteCode={}", siteCode)
        return oAuthTokenRepository.delete(siteCode) > 0
    }
}
