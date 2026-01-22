package org.webnori.ordersyncoffice.controller

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.webnori.ordersyncoffice.mapper.SyncStatusMapper
import org.webnori.ordersyncoffice.mapper.StoreInfoMapper
import jakarta.servlet.http.HttpSession

/**
 * 관리자 기능 컨트롤러
 * - 수동 동기화 관리
 */
@Controller
@RequestMapping("/admin")
class AdminController(
    private val storeInfoMapper: StoreInfoMapper,
    private val syncStatusMapper: SyncStatusMapper
) {

    /**
     * 수동 동기화 관리 페이지
     */
    @GetMapping("/manual-sync")
    fun manualSyncPage(model: Model, session: HttpSession): String {
        val siteCode = session.getAttribute("authenticated_site_code") as? String ?: return "redirect:/auth/login"
        val store = storeInfoMapper.findBySiteCode(siteCode) ?: return "redirect:/auth/login"

        // 진행 중인 동기화 확인
        val runningSync = syncStatusMapper.findRunningBySiteCode(siteCode)

        // 최근 동기화 정보
        val latestSync = syncStatusMapper.findLatestBySiteCodeAndType(siteCode, "ORDERS")

        model.addAttribute("active", "admin-manual-sync")
        model.addAttribute("selectedStore", store)
        model.addAttribute("runningSync", runningSync)
        model.addAttribute("latestSync", latestSync)

        return "admin/manual-sync"
    }
}
