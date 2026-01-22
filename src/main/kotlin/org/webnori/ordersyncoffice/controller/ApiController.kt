package org.webnori.ordersyncoffice.controller

import jakarta.servlet.http.HttpSession
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.webnori.ordersyncoffice.service.ImwebApiService
import org.webnori.ordersyncoffice.service.StoreService

@Controller
@RequestMapping("/api-view")
class ApiController(
    private val imwebApiService: ImwebApiService,
    private val storeService: StoreService
) {
    private val logger = LoggerFactory.getLogger(ApiController::class.java)

    @GetMapping("/orders")
    fun ordersPage(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(required = false) startWtime: String?,
        @RequestParam(required = false) endWtime: String?,
        @RequestParam(required = false) orderSectionStatus: String?,
        @RequestParam(required = false) paymentStatus: String?,
        @RequestParam(required = false) paymentMethod: String?,
        @RequestParam(required = false) country: String?,
        model: Model,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String = runBlocking {
        model.addAttribute("active", "orders")

        val siteCode = session.getAttribute("authenticated_site_code") as? String
        if (siteCode == null) {
            redirectAttributes.addFlashAttribute("error", "먼저 스토어를 선택해주세요.")
            return@runBlocking "redirect:/"
        }

        val store = storeService.getStore(siteCode)
        model.addAttribute("selectedStore", store)

        val accessToken = storeService.getValidAccessToken(siteCode)
        if (accessToken == null) {
            redirectAttributes.addFlashAttribute("error", "토큰이 만료되었습니다. 다시 인증해주세요.")
            return@runBlocking "redirect:/"
        }

        // unitCode는 주문 조회에 필요
        val unitCode = store?.unitCode
        if (unitCode == null) {
            model.addAttribute("error", "스토어에 unitCode가 설정되지 않았습니다.")
            model.addAttribute("orders", emptyList<Any>())
            model.addAttribute("currentPage", page)
            model.addAttribute("limit", limit)
            model.addAttribute("startWtime", startWtime)
            model.addAttribute("endWtime", endWtime)
            model.addAttribute("orderSectionStatus", orderSectionStatus)
            model.addAttribute("paymentStatus", paymentStatus)
            model.addAttribute("paymentMethod", paymentMethod)
            model.addAttribute("country", country)
            return@runBlocking "orders/list"
        }

        try {
            val ordersResponse = imwebApiService.getOrders(
                accessToken = accessToken,
                unitCode = unitCode,
                page = page,
                limit = limit,
                startWtime = startWtime,
                endWtime = endWtime,
                orderSectionStatus = orderSectionStatus,
                paymentStatus = paymentStatus,
                paymentMethod = paymentMethod,
                country = country
            )

            if (ordersResponse.statusCode == 200) {
                model.addAttribute("orders", ordersResponse.data?.list ?: emptyList<Any>())
                model.addAttribute("totalCount", ordersResponse.data?.totalCount ?: 0)
                model.addAttribute("totalPage", ordersResponse.data?.totalPage ?: 1)
            } else {
                model.addAttribute("error", "주문 조회 실패: statusCode=${ordersResponse.statusCode}")
                model.addAttribute("orders", emptyList<Any>())
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch orders", e)
            model.addAttribute("error", "주문 조회 중 오류가 발생했습니다: ${e.message}")
            model.addAttribute("orders", emptyList<Any>())
        }

        model.addAttribute("currentPage", page)
        model.addAttribute("limit", limit)
        model.addAttribute("startWtime", startWtime)
        model.addAttribute("endWtime", endWtime)
        model.addAttribute("orderSectionStatus", orderSectionStatus)
        model.addAttribute("paymentStatus", paymentStatus)
        model.addAttribute("paymentMethod", paymentMethod)
        model.addAttribute("country", country)

        "orders/list"
    }

    @GetMapping("/products")
    fun productsPage(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(required = false) prodStatus: String?,
        model: Model,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String = runBlocking {
        model.addAttribute("active", "products")

        val siteCode = session.getAttribute("authenticated_site_code") as? String
        if (siteCode == null) {
            redirectAttributes.addFlashAttribute("error", "먼저 스토어를 선택해주세요.")
            return@runBlocking "redirect:/"
        }

        val store = storeService.getStore(siteCode)
        model.addAttribute("selectedStore", store)

        val accessToken = storeService.getValidAccessToken(siteCode)
        if (accessToken == null) {
            redirectAttributes.addFlashAttribute("error", "토큰이 만료되었습니다. 다시 인증해주세요.")
            return@runBlocking "redirect:/"
        }

        // unitCode는 상품 조회에 필수
        val unitCode = store?.unitCode
        if (unitCode == null) {
            model.addAttribute("error", "스토어에 unitCode가 설정되지 않았습니다.")
            model.addAttribute("products", emptyList<Any>())
            model.addAttribute("currentPage", page)
            model.addAttribute("limit", limit)
            model.addAttribute("prodStatus", prodStatus)
            return@runBlocking "products/list"
        }

        try {
            val productsResponse = imwebApiService.getProducts(
                accessToken = accessToken,
                unitCode = unitCode,
                page = page,
                limit = limit,
                prodStatus = prodStatus
            )

            if (productsResponse.statusCode == 200) {
                model.addAttribute("products", productsResponse.data?.list ?: emptyList<Any>())
                model.addAttribute("totalCount", productsResponse.data?.totalCount ?: 0)
                model.addAttribute("totalPage", productsResponse.data?.totalPage ?: 1)
            } else {
                model.addAttribute("error", "상품 조회 실패: statusCode=${productsResponse.statusCode}")
                model.addAttribute("products", emptyList<Any>())
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch products", e)
            model.addAttribute("error", "상품 조회 중 오류가 발생했습니다: ${e.message}")
            model.addAttribute("products", emptyList<Any>())
        }

        model.addAttribute("currentPage", page)
        model.addAttribute("limit", limit)
        model.addAttribute("prodStatus", prodStatus)

        "products/list"
    }

    /**
     * 상품 상세 조회 (REST API for AJAX)
     */
    @GetMapping("/products/{prodNo}/detail")
    @ResponseBody
    fun getProductDetail(
        @PathVariable prodNo: Int,
        session: HttpSession
    ): ResponseEntity<Map<String, Any?>> = runBlocking {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return@runBlocking ResponseEntity.badRequest().body(mapOf<String, Any?>("error" to "인증 필요"))

        val store = storeService.getStore(siteCode)
        val accessToken = storeService.getValidAccessToken(siteCode)
            ?: return@runBlocking ResponseEntity.badRequest().body(mapOf<String, Any?>("error" to "토큰 만료"))

        val unitCode = store?.unitCode
            ?: return@runBlocking ResponseEntity.badRequest().body(mapOf<String, Any?>("error" to "unitCode 없음"))

        try {
            val productResponse = imwebApiService.getProductDetail(accessToken, unitCode, prodNo)
            if (productResponse.statusCode == 200 && productResponse.data != null) {
                val product = productResponse.data
                ResponseEntity.ok(mapOf<String, Any?>(
                    "prodNo" to product.prodNo,
                    "name" to product.name,
                    "seoTitle" to product.seoTitle,
                    "seoDescription" to product.seoDescription,
                    "categories" to product.categories
                ))
            } else {
                ResponseEntity.badRequest().body(mapOf<String, Any?>("error" to "상품 조회 실패"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch product detail", e)
            ResponseEntity.internalServerError().body(mapOf<String, Any?>("error" to e.message))
        }
    }

    /**
     * 회원 상세 조회 (REST API for AJAX)
     */
    @GetMapping("/members/{memberUid}")
    @ResponseBody
    fun getMemberDetail(
        @PathVariable memberUid: String,
        session: HttpSession
    ): ResponseEntity<Map<String, Any?>> = runBlocking {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return@runBlocking ResponseEntity.badRequest().body(mapOf<String, Any?>("error" to "인증 필요"))

        val store = storeService.getStore(siteCode)
        val accessToken = storeService.getValidAccessToken(siteCode)
            ?: return@runBlocking ResponseEntity.badRequest().body(mapOf<String, Any?>("error" to "토큰 만료"))

        val unitCode = store?.unitCode
            ?: return@runBlocking ResponseEntity.badRequest().body(mapOf<String, Any?>("error" to "unitCode 없음"))

        try {
            val memberResponse = imwebApiService.getMemberDetail(accessToken, unitCode, memberUid)
            if (memberResponse.statusCode == 200 && memberResponse.data != null) {
                val member = memberResponse.data
                ResponseEntity.ok(mapOf<String, Any?>(
                    "memberCode" to member.memberCode,
                    "name" to member.name,
                    "email" to member.email,
                    "callnum" to member.callnum,
                    "gender" to member.gender,
                    "birth" to member.birth,
                    "address" to member.address,
                    "addressDetail" to member.addressDetail,
                    "joinTime" to member.joinTime,
                    "point" to member.point,
                    "socialLogin" to member.socialLogin
                ))
            } else {
                ResponseEntity.badRequest().body(mapOf<String, Any?>("error" to "회원 조회 실패"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch member detail", e)
            ResponseEntity.internalServerError().body(mapOf<String, Any?>("error" to e.message))
        }
    }

    /**
     * 카테고리 목록 조회 (REST API for AJAX)
     */
    @GetMapping("/categories")
    @ResponseBody
    fun getCategories(
        session: HttpSession
    ): ResponseEntity<Any> = runBlocking {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return@runBlocking ResponseEntity.badRequest().body(mapOf("error" to "인증 필요"))

        val store = storeService.getStore(siteCode)
        val accessToken = storeService.getValidAccessToken(siteCode)
            ?: return@runBlocking ResponseEntity.badRequest().body(mapOf("error" to "토큰 만료"))

        val unitCode = store?.unitCode
            ?: return@runBlocking ResponseEntity.badRequest().body(mapOf("error" to "unitCode 없음"))

        try {
            val categoryResponse = imwebApiService.getCategories(accessToken, unitCode)
            if (categoryResponse.statusCode == 200 && categoryResponse.data != null) {
                ResponseEntity.ok(categoryResponse.data)
            } else {
                ResponseEntity.badRequest().body(mapOf("error" to "카테고리 조회 실패"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch categories", e)
            ResponseEntity.internalServerError().body(mapOf("error" to e.message))
        }
    }
}
