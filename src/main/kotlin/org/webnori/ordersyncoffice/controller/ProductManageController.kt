package org.webnori.ordersyncoffice.controller

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.webnori.ordersyncoffice.config.RegionConfig
import org.webnori.ordersyncoffice.mapper.ProductRegionMappingMapper
import org.webnori.ordersyncoffice.mapper.StoreInfoMapper

@Controller
class ProductManageController(
    private val regionConfig: RegionConfig,
    private val productRegionMappingMapper: ProductRegionMappingMapper,
    private val storeInfoMapper: StoreInfoMapper
) {

    @GetMapping("/products/mapping")
    fun page(model: Model, session: HttpSession): String {
        val siteCode = session.getAttribute("authenticated_site_code") as? String ?: return "redirect:/auth/login"
        val store = storeInfoMapper.findBySiteCode(siteCode) ?: return "redirect:/auth/login"

        model.addAttribute("active", "product-manage")
        model.addAttribute("selectedStore", store)

        model.addAttribute("regions", regionConfig.getRegionNames())
        model.addAttribute("mappings", productRegionMappingMapper.findActiveBySiteCode(siteCode))

        return "products/mapping"
    }

    @PostMapping("/products/mapping")
    fun upsert(
        @RequestParam("prodNo") prodNo: Int,
        @RequestParam("regionName") regionName: String,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String {
        val siteCode = session.getAttribute("authenticated_site_code") as? String ?: return "redirect:/auth/login"

        // 서버측 검증: regionName은 RegionConfig 목록 안에 있어야 함
        val validRegions = regionConfig.getRegionNames()
        if (!validRegions.contains(regionName)) {
            redirectAttributes.addFlashAttribute("error", "유효하지 않은 지역입니다: $regionName")
            return "redirect:/products/mapping"
        }

        productRegionMappingMapper.upsert(siteCode, prodNo, regionName)
        redirectAttributes.addFlashAttribute("success", "저장되었습니다")
        return "redirect:/products/mapping"
    }

    @PostMapping("/products/mapping/deactivate")
    fun deactivate(
        @RequestParam("prodNo") prodNo: Int,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String {
        val siteCode = session.getAttribute("authenticated_site_code") as? String ?: return "redirect:/auth/login"

        productRegionMappingMapper.deactivate(siteCode, prodNo)
        redirectAttributes.addFlashAttribute("success", "비활성화되었습니다")
        return "redirect:/products/mapping"
    }
}
