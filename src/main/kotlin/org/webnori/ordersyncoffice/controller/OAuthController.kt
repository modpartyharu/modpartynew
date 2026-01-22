package org.webnori.ordersyncoffice.controller

import jakarta.servlet.http.HttpSession
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.webnori.ordersyncoffice.service.ImwebApiService
import org.webnori.ordersyncoffice.service.StoreService

@Controller
@RequestMapping("/auth")
class AuthController(
    private val imwebApiService: ImwebApiService,
    private val storeService: StoreService,
    @org.springframework.beans.factory.annotation.Value("\${app.login-password}")
    private val appLoginPassword: String
) {
    private val logger = LoggerFactory.getLogger(AuthController::class.java)

    /**
     * 로그인 페이지 - OAuth 인증 시작점
     * 보안강화: DB 기반 스토어 목록 대신 localStorage 기반 siteCode 저장 사용
     */
    @GetMapping("/login")
    fun loginPage(model: Model, session: HttpSession): String {
        // 이미 인증된 사용자는 대시보드로 리다이렉트
        val authenticatedSiteCode = session.getAttribute("authenticated_site_code") as? String
        if (authenticatedSiteCode != null) {
            return "redirect:/"
        }

        // DB 기반 스토어 목록 제거 - 보안 강화
        // siteCode 저장은 클라이언트 localStorage에서 처리
        return "login"
    }

    /**
     * OAuth 인증 시작 - Imweb 인증 페이지로 리다이렉트
     * 보안 강화: adminEmail 필수 입력, OAuth 콜백에서 ownerUid와 비교 검증
     * 추가 보안: 앱 비밀번호 검증
     */
    @GetMapping("/authorize")
    fun authorize(
        @RequestParam siteCode: String,
        @RequestParam adminEmail: String,
        @RequestParam appPassword: String,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String {
        logger.info("=== OAuth Authorization Request ===")
        logger.info("Site Code: {}", siteCode)
        logger.info("Admin Email: {}", adminEmail)

        // 앱 비밀번호 검증
        if (appPassword.isBlank() || appPassword != appLoginPassword) {
            logger.warn("Invalid app password attempt for siteCode: {}", siteCode)
            redirectAttributes.addFlashAttribute("error", "앱 비밀번호가 올바르지 않습니다.")
            return "redirect:/auth/login"
        }
        logger.info("App password validated successfully")

        // 이메일 유효성 검사
        if (adminEmail.isBlank() || !adminEmail.contains("@")) {
            logger.warn("Invalid admin email: {}", adminEmail)
            redirectAttributes.addFlashAttribute("error", "올바른 관리자 이메일을 입력해주세요.")
            return "redirect:/auth/login"
        }

        // CSRF 방지를 위한 state 토큰 생성
        val state = imwebApiService.generateStateToken()
        session.setAttribute("oauth_state", state)
        session.setAttribute("pending_site_code", siteCode)
        session.setAttribute("pending_admin_email", adminEmail.lowercase().trim())
        logger.info("Generated state token and stored in session with admin email")

        // OAuth 인증 URL 생성 및 리다이렉트
        val authorizeUrl = imwebApiService.buildAuthorizeUrl(siteCode, state)
        logger.info("Redirecting to Imweb OAuth authorization page")

        return "redirect:$authorizeUrl"
    }

    /**
     * 로그아웃
     */
    @PostMapping("/logout")
    fun logout(session: HttpSession): String {
        logger.info("=== User Logout ===")
        session.invalidate()
        SecurityContextHolder.clearContext()
        return "redirect:/auth/login?logout"
    }
}

@Controller
@RequestMapping("/oauth")
class OAuthCallbackController(
    private val imwebApiService: ImwebApiService,
    private val storeService: StoreService
) {
    private val logger = LoggerFactory.getLogger(OAuthCallbackController::class.java)

    /**
     * OAuth 콜백 - 인증 성공 시 자동 로그인/회원가입
     */
    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(required = false, name = "error_description") errorDescription: String?,
        @RequestParam(required = false) errorCode: String?,
        @RequestParam(required = false) message: String?,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String = runBlocking {
        logger.info("=== OAuth Callback Request ===")
        logger.info("Authorization Code: {}", code)
        logger.info("State Parameter: {}", state)
        logger.info("Error: {}", error)
        logger.info("Error Description: {}", errorDescription)
        logger.info("Imweb ErrorCode: {}", errorCode)
        logger.info("Imweb Message: {}", message)

        // Imweb 전용 에러 응답 처리 (errorCode, message 파라미터 사용)
        if (errorCode != null) {
            logger.error("Imweb OAuth Error: {} - {}", errorCode, message)
            redirectAttributes.addFlashAttribute("error", "OAuth 인증 실패: ${message ?: "에러코드 $errorCode"}")
            return@runBlocking "redirect:/auth/login"
        }

        // 표준 OAuth 에러 응답 처리
        if (error != null) {
            logger.error("OAuth Error: {} - {}", error, errorDescription)
            redirectAttributes.addFlashAttribute("error", "OAuth 인증 실패: ${errorDescription ?: error}")
            return@runBlocking "redirect:/auth/login"
        }

        // code가 없는 경우
        if (code.isNullOrBlank()) {
            logger.error("Authorization code is missing")
            redirectAttributes.addFlashAttribute("error", "인증 코드가 없습니다. 다시 시도해주세요.")
            return@runBlocking "redirect:/auth/login"
        }

        // state가 없는 경우
        if (state.isNullOrBlank()) {
            logger.error("State parameter is missing")
            redirectAttributes.addFlashAttribute("error", "State 파라미터가 없습니다. 다시 시도해주세요.")
            return@runBlocking "redirect:/auth/login"
        }

        try {
            // State 토큰 검증
            val storedState = session.getAttribute("oauth_state") as? String
            val pendingSiteCode = session.getAttribute("pending_site_code") as? String
            val pendingAdminEmail = session.getAttribute("pending_admin_email") as? String

            logger.info("Stored State: {}", storedState)
            logger.info("Pending Site Code: {}", pendingSiteCode)
            logger.info("Pending Admin Email: {}", pendingAdminEmail)

            if (storedState == null || pendingAdminEmail == null) {
                logger.error("=== OAuth Callback Failed: Missing Session Data ===")
                redirectAttributes.addFlashAttribute("error", "세션이 만료되었습니다. 다시 시도해주세요.")
                return@runBlocking "redirect:/auth/login"
            }

            if (state != storedState) {
                logger.error("=== OAuth Callback Failed: State Mismatch ===")
                redirectAttributes.addFlashAttribute("error", "잘못된 요청입니다. (State 불일치)")
                return@runBlocking "redirect:/auth/login"
            }

            logger.info("State validation passed")

            // Authorization Code를 Access Token으로 교환
            logger.info("=== Starting Token Exchange Process ===")
            val tokenResponse = imwebApiService.exchangeCodeForToken(code)

            val tokenData = tokenResponse.data
            if (tokenData == null) {
                logger.error("Token response data is null")
                redirectAttributes.addFlashAttribute("error", "토큰 응답이 올바르지 않습니다.")
                return@runBlocking "redirect:/auth/login"
            }

            // 사이트 정보 조회
            logger.info("=== Fetching Site Information ===")
            val siteInfoResponse = imwebApiService.getSiteInfo(tokenData.accessToken)

            if (siteInfoResponse.statusCode != 200 || siteInfoResponse.data == null) {
                logger.error("Failed to fetch site info: statusCode={}", siteInfoResponse.statusCode)
                redirectAttributes.addFlashAttribute("error", "사이트 정보를 가져오는데 실패했습니다.")
                return@runBlocking "redirect:/auth/login"
            }

            val siteInfo = siteInfoResponse.data
            val siteCode = siteInfo.siteCode ?: throw IllegalStateException("Site code is null")

            // 보안 검증: 입력한 관리자 이메일과 실제 소유자 이메일 비교
            val ownerUid = siteInfo.ownerUid?.lowercase()?.trim()
            logger.info("=== Admin Email Verification ===")
            logger.info("Input Admin Email: {}", pendingAdminEmail)
            logger.info("Imweb Owner UID: {}", ownerUid)

            if (ownerUid == null || ownerUid != pendingAdminEmail) {
                logger.error("=== OAuth Callback Failed: Admin Email Mismatch ===")
                logger.error("Expected: {}, Got: {}", pendingAdminEmail, ownerUid)
                // 세션 정리
                session.removeAttribute("oauth_state")
                session.removeAttribute("pending_site_code")
                session.removeAttribute("pending_admin_email")
                redirectAttributes.addFlashAttribute("error", "관리자 이메일이 일치하지 않습니다. 사이트 소유자 이메일로 로그인해주세요.")
                return@runBlocking "redirect:/auth/login"
            }

            logger.info("Admin email verification passed")

            // 스토어 정보 저장 (간편가입 - 없으면 생성, 있으면 업데이트)
            logger.info("=== Saving Store Information (Auto Register/Update) ===")
            var storeInfo = storeService.convertSiteInfoToStoreInfo(siteInfo)

            // Unit 정보 조회하여 상세 정보 업데이트
            val unitList = siteInfo.unitList
            if (!unitList.isNullOrEmpty()) {
                try {
                    val unitCode = unitList.first().unitCode
                    if (unitCode != null) {
                        logger.info("Fetching unit info: unitCode={}", unitCode)
                        val unitInfoResponse = imwebApiService.getUnitInfo(tokenData.accessToken, unitCode)
                        if (unitInfoResponse.statusCode == 200 && unitInfoResponse.data != null) {
                            storeInfo = storeService.updateStoreInfoWithUnit(storeInfo, unitInfoResponse.data)
                            logger.info("Store info updated with unit info: siteName={}", storeInfo.siteName)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to fetch unit info, continuing with basic store info: {}", e.message)
                }
            }

            storeService.saveStore(storeInfo)
            logger.info("Store info saved: siteCode={}, siteName={}", siteCode, storeInfo.siteName)

            // OAuth 토큰 저장
            logger.info("=== Saving OAuth Token ===")
            val oAuthToken = storeService.convertTokenResponseToOAuthToken(siteCode, tokenResponse)
            storeService.saveOAuthToken(oAuthToken)
            logger.info("OAuth token saved for siteCode={}", siteCode)

            // 세션 정리
            session.removeAttribute("oauth_state")
            session.removeAttribute("pending_site_code")
            session.removeAttribute("pending_admin_email")

            // Spring Security 인증 설정 (OAuth 성공 시 자동 로그인)
            val authorities = listOf(SimpleGrantedAuthority("ROLE_USER"))
            val authentication = UsernamePasswordAuthenticationToken(siteCode, null, authorities)
            SecurityContextHolder.getContext().authentication = authentication

            // 세션에 인증 정보 저장
            session.setAttribute("authenticated_site_code", siteCode)
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext())
            logger.info("Session authenticated: siteCode={}", siteCode)

            logger.info("=== OAuth Process Completed Successfully ===")
            redirectAttributes.addFlashAttribute("success", "${storeInfo.siteName ?: siteCode} 스토어로 로그인되었습니다.")
            "redirect:/"

        } catch (e: Exception) {
            logger.error("=== OAuth Callback Error ===", e)
            logger.error("Error Type: {}", e.javaClass.simpleName)
            logger.error("Error Message: {}", e.message)
            redirectAttributes.addFlashAttribute("error", "인증에 실패했습니다: ${e.message}")
            "redirect:/auth/login"
        }
    }
}
