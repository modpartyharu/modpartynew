package org.webnori.ordersyncoffice.controller

import jakarta.servlet.http.HttpSession
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.webnori.ordersyncoffice.mapper.SyncOrderMapper
import org.webnori.ordersyncoffice.repository.AppVersionRepository
import org.webnori.ordersyncoffice.service.StoreService

@Controller
class HomeController(
    private val appVersionRepository: AppVersionRepository,
    private val storeService: StoreService,
    private val syncOrderMapper: SyncOrderMapper
) {

    @GetMapping("/")
    fun dashboard(
        model: Model,
        session: HttpSession,
        @RequestParam(defaultValue = "3") weeks: Int
    ): String {
        val version = appVersionRepository.getLatestVersion()
        model.addAttribute("version", version?.version ?: "Unknown")
        model.addAttribute("active", "dashboard")

        // OAuth로 인증된 현재 스토어만 표시 (보안 강화)
        val authenticatedSiteCode = session.getAttribute("authenticated_site_code") as? String

        if (authenticatedSiteCode != null) {
            val authenticatedStore = storeService.getStore(authenticatedSiteCode)
            model.addAttribute("selectedStore", authenticatedStore)
            model.addAttribute("selectedSiteCode", authenticatedSiteCode)

            // 토큰 상태 확인 (만료 여부)
            val token = storeService.getOAuthToken(authenticatedSiteCode)
            val hasValidToken = token != null && !storeService.isTokenExpired(authenticatedSiteCode)
            model.addAttribute("hasValidToken", hasValidToken)

            // 대시보드 집계 데이터 (일자별/지역별 성별 통계)
            val validWeeks = if (weeks in 1..4) weeks else 3
            val aggregationData = syncOrderMapper.getDashboardAggregation(authenticatedSiteCode, validWeeks) ?: emptyList()
            model.addAttribute("aggregationData", aggregationData)
            model.addAttribute("selectedWeeks", validWeeks)

            // 최근 주문 목록 (30건)
            val recentOrders = syncOrderMapper.findRecentOrders(authenticatedSiteCode, 30) ?: emptyList()
            model.addAttribute("recentOrders", recentOrders)
        }

        return "dashboard"
    }

    @GetMapping("/app-status")
    fun appStatus(model: Model, session: HttpSession): String {
        val version = appVersionRepository.getLatestVersion()
        model.addAttribute("version", version?.version ?: "Unknown")
        model.addAttribute("active", "app-status")

        // OAuth로 인증된 현재 스토어만 표시 (보안 강화)
        val authenticatedSiteCode = session.getAttribute("authenticated_site_code") as? String

        if (authenticatedSiteCode != null) {
            val authenticatedStore = storeService.getStore(authenticatedSiteCode)
            model.addAttribute("selectedStore", authenticatedStore)
            model.addAttribute("selectedSiteCode", authenticatedSiteCode)

            // 토큰 상태 확인 (만료 여부)
            val token = storeService.getOAuthToken(authenticatedSiteCode)
            val hasValidToken = token != null && !storeService.isTokenExpired(authenticatedSiteCode)
            model.addAttribute("hasValidToken", hasValidToken)
        }

        // 이전에 연동한 스토어 목록 (재인증 필요)
        val otherStores = storeService.getAllStores().filter { it.siteCode != authenticatedSiteCode }
        model.addAttribute("otherStores", otherStores)

        return "app-status"
    }

    /**
     * 스토어 재인증 - 보안 강화
     * 기존 토큰 삭제 후 OAuth 재인증 플로우 시작
     * 모든 스토어 접근은 OAuth 인증을 통해서만 가능 (비밀번호 재입력 필요)
     */
    @PostMapping("/store/reauth")
    fun reauthStore(
        @RequestParam siteCode: String,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String {
        // 1. 기존 OAuth 토큰 삭제 (재사용 방지)
        storeService.deleteOAuthToken(siteCode)

        // 2. 기존 세션 정보 클리어
        session.removeAttribute("authenticated_site_code")
        SecurityContextHolder.clearContext()

        // 3. 로그인 페이지로 리다이렉트 (비밀번호 재입력 필요)
        redirectAttributes.addFlashAttribute("info", "재인증이 필요합니다. 다시 로그인해주세요.")
        return "redirect:/auth/login"
    }

    @PostMapping("/store/delete")
    fun deleteStore(
        @RequestParam siteCode: String,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String {
        val store = storeService.getStore(siteCode)
        if (store == null) {
            redirectAttributes.addFlashAttribute("error", "스토어를 찾을 수 없습니다.")
            return "redirect:/"
        }

        // 현재 인증된 스토어인 경우 세션 클리어
        val authenticatedSiteCode = session.getAttribute("authenticated_site_code") as? String
        if (authenticatedSiteCode == siteCode) {
            session.invalidate()
            SecurityContextHolder.clearContext()
            redirectAttributes.addFlashAttribute("success", "${store.siteName ?: siteCode} 스토어가 삭제되었습니다. 다시 로그인해주세요.")
            storeService.deleteStore(siteCode)
            return "redirect:/auth/login"
        }

        storeService.deleteStore(siteCode)
        redirectAttributes.addFlashAttribute("success", "${store.siteName ?: siteCode} 스토어가 삭제되었습니다.")
        return "redirect:/"
    }
}
