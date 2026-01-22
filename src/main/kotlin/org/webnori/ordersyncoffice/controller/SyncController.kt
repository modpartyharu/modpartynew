package org.webnori.ordersyncoffice.controller

import jakarta.servlet.http.HttpSession
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.webnori.ordersyncoffice.domain.SyncOrderFilter
import org.webnori.ordersyncoffice.domain.SyncOrderFilterV2
import org.webnori.ordersyncoffice.domain.SyncOrderFilterAll
import org.webnori.ordersyncoffice.domain.SyncProgressEvent
import org.webnori.ordersyncoffice.domain.SyncStatus
import org.webnori.ordersyncoffice.domain.StatusChangeRequest
import org.webnori.ordersyncoffice.mapper.SyncOrderMapper
import org.webnori.ordersyncoffice.config.RegionConfig
import org.webnori.ordersyncoffice.util.DateUtils
import org.webnori.ordersyncoffice.service.AlimtalkService
import org.webnori.ordersyncoffice.service.BatchSendResult
import org.webnori.ordersyncoffice.service.BatchTokenService
import org.webnori.ordersyncoffice.service.ImwebApiService
import org.webnori.ordersyncoffice.service.SchedulerLogEntry
import org.webnori.ordersyncoffice.service.SchedulerLogService
import org.webnori.ordersyncoffice.service.StoreService
import org.webnori.ordersyncoffice.service.SyncResetResult
import org.webnori.ordersyncoffice.service.SyncSchedulerService
import org.webnori.ordersyncoffice.service.SyncService
import org.webnori.ordersyncoffice.service.ManualOrderRequest
import org.webnori.ordersyncoffice.service.TokenRetryHelper
import reactor.core.publisher.Flux

@Controller
@RequestMapping("/sync")
class SyncController(
    private val storeService: StoreService,
    private val syncService: SyncService,
    private val syncSchedulerService: SyncSchedulerService,
    private val alimtalkService: AlimtalkService
) {
    private val logger = LoggerFactory.getLogger(SyncController::class.java)

    @GetMapping("/status")
    fun statusPage(
        model: Model,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String {
        model.addAttribute("active", "sync-status")

        val siteCode = session.getAttribute("authenticated_site_code") as? String
        if (siteCode == null) {
            redirectAttributes.addFlashAttribute("error", "먼저 스토어를 선택해주세요.")
            return "redirect:/"
        }

        val store = storeService.getStore(siteCode)
        model.addAttribute("selectedStore", store)

        // 5분 이상 경과한 RUNNING 상태를 FAILED로 자동 처리
        val cleanedUp = syncService.cleanupStaleRunningStatus(siteCode)
        if (cleanedUp > 0) {
            model.addAttribute("staleCleanedUp", cleanedUp)
        }

        // 동기화 상태 정보
        val syncStatusList = syncService.getSyncStatus(siteCode)
        val latestSync = syncService.getLatestSyncStatus(siteCode, "ORDERS")
        val runningSync = syncService.getRunningSyncStatus(siteCode)

        model.addAttribute("syncStatusList", syncStatusList)
        model.addAttribute("latestSync", latestSync)
        model.addAttribute("runningSync", runningSync)

        // 스케줄러 상태 정보
        val schedulerStatus = syncSchedulerService.getSchedulerStatus(siteCode)
        model.addAttribute("schedulerStatus", schedulerStatus)

        // 알림톡 테스트 전화번호
        val testPhone = alimtalkService.getTestPhone(siteCode)
        model.addAttribute("alimtalkTestPhone", testPhone ?: "")

        return "sync/status"
    }

    @GetMapping("/alimtalk")
    fun alimtalkPage(
        model: Model,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String {
        model.addAttribute("active", "sync-alimtalk")

        val siteCode = session.getAttribute("authenticated_site_code") as? String
        if (siteCode == null) {
            redirectAttributes.addFlashAttribute("error", "먼저 스토어를 선택해주세요.")
            return "redirect:/"
        }

        val store = storeService.getStore(siteCode)
        model.addAttribute("selectedStore", store)

        // 알림톡 테스트 전화번호
        val testPhone = alimtalkService.getTestPhone(siteCode)
        model.addAttribute("testPhone", testPhone ?: "")

        // 템플릿 목록
        val templates = alimtalkService.getAllTemplates()
        model.addAttribute("templates", templates)

        return "sync/alimtalk"
    }

    @GetMapping("/tables")
    fun tablesPage(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) preferredDate: String?,
        @RequestParam(required = false) managementStatus: String?,
        @RequestParam(required = false) prodNo: Int?,
        @RequestParam(required = false) regionName: String?,
        @RequestParam(defaultValue = "orderTime") sortBy: String,
        @RequestParam(defaultValue = "DESC") sortDir: String,
        model: Model,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String {
        // 메뉴 활성 상태 결정 (prodNo 또는 regionName)
        val activeMenu = when {
            prodNo != null -> "order-$prodNo"
            !regionName.isNullOrBlank() -> "region-$regionName"
            else -> "sync-tables"
        }
        model.addAttribute("active", activeMenu)

        val siteCode = session.getAttribute("authenticated_site_code") as? String
        if (siteCode == null) {
            redirectAttributes.addFlashAttribute("error", "먼저 스토어를 선택해주세요.")
            return "redirect:/"
        }

        val store = storeService.getStore(siteCode)
        model.addAttribute("selectedStore", store)

        // 정렬 방향 유효성 검사
        val validSortDir = if (sortDir.uppercase() in setOf("ASC", "DESC")) sortDir.uppercase() else "DESC"

        // V2 필터 조건 생성
        val filterV2 = SyncOrderFilterV2(
            keyword = keyword?.ifBlank { null },
            preferredDate = preferredDate?.ifBlank { null },
            managementStatus = managementStatus?.ifBlank { null },
            prodNo = prodNo,
            regionName = regionName?.ifBlank { null },
            sortBy = sortBy,
            sortDir = validSortDir
        )

        // V2 필터링 + 성별 분리 + 통계 조회
        val pagedOrdersV2 = syncService.getSyncedOrdersFilteredV2(siteCode, filterV2, page, size)

        // 남성/여성 리스트 분리
        model.addAttribute("maleOrders", pagedOrdersV2.maleOrders)
        model.addAttribute("femaleOrders", pagedOrdersV2.femaleOrders)

        // 참석 통계
        model.addAttribute("stats", pagedOrdersV2.stats)

        // 페이징 정보
        model.addAttribute("currentPage", pagedOrdersV2.page)
        model.addAttribute("pageSize", pagedOrdersV2.size)
        model.addAttribute("totalElements", pagedOrdersV2.totalElements)
        model.addAttribute("totalPages", pagedOrdersV2.totalPages)

        // 참석희망날짜 목록: Imweb API에서 동적 로드 (JS에서 처리)
        // DB fallback 제거 - /api/sync/product-options/{prodNo} 또는 /api/sync/region-options/{regionName} 사용

        // 관리상태 목록 (필터용)
        val managementStatuses = listOf("확인필요", "확정", "대기", "이월", "불참", "환불", "결제대기중")
        model.addAttribute("managementStatuses", managementStatuses)

        // 현재 필터 상태
        model.addAttribute("selectedKeyword", keyword ?: "")
        model.addAttribute("selectedPreferredDate", preferredDate ?: "")
        model.addAttribute("selectedManagementStatus", managementStatus ?: "")
        model.addAttribute("selectedProdNo", prodNo)
        model.addAttribute("selectedRegionName", regionName ?: "")
        model.addAttribute("selectedSortBy", sortBy)
        model.addAttribute("selectedSortDir", validSortDir)

        // 필터가 없을 때만 매출 합계 표시
        val hasNoFilter = keyword.isNullOrBlank() &&
            preferredDate.isNullOrBlank() &&
            managementStatus.isNullOrBlank() &&
            prodNo == null &&
            regionName.isNullOrBlank()

        if (hasNoFilter) {
            val salesTotal = syncService.getSalesTotal(siteCode)
            model.addAttribute("salesTotal", salesTotal)
        }
        model.addAttribute("hasNoFilter", hasNoFilter)

        return "sync/tables"
    }

    @GetMapping("/tables-all")
    fun tablesAllPage(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?,
        @RequestParam(required = false) paymentStatusFilter: String?,  // 결제상태 필터: PAID(결제), REFUND(환불)
        @RequestParam(required = false) paymentMethodType: String?,
        @RequestParam(defaultValue = "orderTime") sortBy: String,
        @RequestParam(defaultValue = "DESC") sortDir: String,
        model: Model,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String {
        model.addAttribute("active", "sync-tables-all")

        val siteCode = session.getAttribute("authenticated_site_code") as? String
        if (siteCode == null) {
            redirectAttributes.addFlashAttribute("error", "먼저 스토어를 선택해주세요.")
            return "redirect:/"
        }

        val store = storeService.getStore(siteCode)
        model.addAttribute("selectedStore", store)

        // 정렬 방향 유효성 검사
        val validSortDir = if (sortDir.uppercase() in setOf("ASC", "DESC")) sortDir.uppercase() else "DESC"

        // 전체주문 페이지용 필터 생성
        val filterAll = SyncOrderFilterAll(
            keyword = keyword?.ifBlank { null },
            startDate = startDate?.ifBlank { null },
            endDate = endDate?.ifBlank { null },
            paymentStatusFilter = paymentStatusFilter?.ifBlank { null },
            paymentMethodType = paymentMethodType?.ifBlank { null },
            sortBy = sortBy,
            sortDir = validSortDir
        )

        // 전체 주문 조회 (성별 분리 없음)
        val pagedOrders = syncService.getSyncedOrdersFilteredAll(siteCode, filterAll, page, size)

        // 전체 리스트
        model.addAttribute("orders", pagedOrders.orders)

        // 매출 합계 (결제완료/환불완료 분리)
        model.addAttribute("salesTotal", pagedOrders.salesTotal)

        // 페이징 정보
        model.addAttribute("currentPage", pagedOrders.page)
        model.addAttribute("pageSize", pagedOrders.size)
        model.addAttribute("totalElements", pagedOrders.totalElements)
        model.addAttribute("totalPages", pagedOrders.totalPages)

        // 현재 필터 상태
        model.addAttribute("selectedKeyword", keyword ?: "")
        model.addAttribute("selectedStartDate", startDate ?: "")
        model.addAttribute("selectedEndDate", endDate ?: "")
        model.addAttribute("selectedPaymentStatusFilter", paymentStatusFilter ?: "")
        model.addAttribute("selectedPaymentMethodType", paymentMethodType ?: "")
        model.addAttribute("selectedSortBy", sortBy)
        model.addAttribute("selectedSortDir", validSortDir)

        return "sync/tables-all"
    }
}

/**
 * Sync REST API Controller
 */
@RestController
@RequestMapping("/api/sync")
class SyncApiController(
    private val syncService: SyncService,
    private val syncSchedulerService: SyncSchedulerService,
    private val batchTokenService: BatchTokenService,
    private val schedulerLogService: SchedulerLogService,
    private val alimtalkService: AlimtalkService,
    private val syncOrderMapper: SyncOrderMapper,
    private val imwebApiService: ImwebApiService,
    private val storeService: StoreService,
    private val regionConfig: RegionConfig,
    private val tokenRetryHelper: TokenRetryHelper
) {
    private val logger = LoggerFactory.getLogger(SyncApiController::class.java)

    /**
     * 주문 동기화 시작 (기존 API - 동기 방식)
     */
    @PostMapping("/orders/start")
    fun startOrderSync(session: HttpSession): ResponseEntity<Map<String, Any>> = runBlocking {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return@runBlocking ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        try {
            // 이미 실행 중인지 확인
            val running = syncService.getRunningSyncStatus(siteCode)
            if (running != null) {
                return@runBlocking ResponseEntity.ok(
                    mapOf(
                        "success" to false,
                        "message" to "이미 동기화가 진행 중입니다.",
                        "status" to running
                    )
                )
            }

            // 동기화 시작
            val status = syncService.syncOrders(siteCode)
            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "message" to "동기화가 완료되었습니다.",
                    "status" to status
                )
            )
        } catch (e: Exception) {
            logger.error("Sync failed: {}", e.message, e)
            ResponseEntity.ok(
                mapOf(
                    "success" to false,
                    "message" to "동기화 실패: ${e.message}"
                )
            )
        }
    }

    /**
     * 주문 동기화 시작 (SSE 스트림 방식 - 실시간 진행상황)
     * @param days 동기화할 일수 (기본값: 1일, 최대: 3일)
     */
    @GetMapping("/orders/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamOrderSync(
        session: HttpSession,
        @RequestParam(defaultValue = "1") days: Int
    ): Flux<ServerSentEvent<SyncProgressEvent>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return Flux.just(
                ServerSentEvent.builder<SyncProgressEvent>()
                    .event("error")
                    .data(SyncProgressEvent(
                        status = "FAILED",
                        message = "로그인이 필요합니다.",
                        progress = 0
                    ))
                    .build()
            )

        // 일수 범위 제한 (1~30일)
        val syncDays = days.coerceIn(1, 30)
        logger.info("Starting SSE stream for order sync, siteCode: {}, days: {}", siteCode, syncDays)

        return Flux.from(
            syncService.syncOrdersWithProgress(siteCode, syncDays)
                .map { event ->
                    ServerSentEvent.builder<SyncProgressEvent>()
                        .event(event.status.lowercase())
                        .data(event)
                        .build()
                }
                .asPublisher()
        )
    }

    /**
     * 동기화 상태 조회
     */
    @GetMapping("/status")
    fun getSyncStatus(session: HttpSession): ResponseEntity<Map<String, Any?>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false as Any?, "message" to "로그인이 필요합니다." as Any?)
            )

        val running = syncService.getRunningSyncStatus(siteCode)
        val latest = syncService.getLatestSyncStatus(siteCode, "ORDERS")
        val history = syncService.getSyncStatus(siteCode)

        return ResponseEntity.ok(
            mapOf(
                "success" to true as Any?,
                "running" to running as Any?,
                "latest" to latest as Any?,
                "history" to history as Any?
            )
        )
    }

    /**
     * 동기화 이력 조회 (페이징)
     */
    @GetMapping("/status/history")
    fun getSyncStatusHistory(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        session: HttpSession
    ): ResponseEntity<Map<String, Any?>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false as Any?, "message" to "로그인이 필요합니다." as Any?)
            )

        val pagedHistory = syncService.getSyncStatusPaged(siteCode, page, size)

        return ResponseEntity.ok(
            mapOf(
                "success" to true as Any?,
                "data" to pagedHistory as Any?
            )
        )
    }

    /**
     * 동기화된 주문 조회 (페이징)
     */
    @GetMapping("/orders")
    fun getSyncedOrders(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        val pagedOrders = syncService.getSyncedOrders(siteCode, page, size)

        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "data" to pagedOrders
            )
        )
    }

    /**
     * 샘플 쿼리 조회 (SQL Injection 방지 - 테이블명/정렬 화이트리스트)
     */
    @GetMapping("/sample-query")
    fun sampleQuery(
        @RequestParam(defaultValue = "sync_orders") table: String,
        @RequestParam(defaultValue = "DESC") order: String,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        // 화이트리스트로 SQL Injection 방지
        val allowedTables = setOf("sync_orders", "sync_categories", "sync_order_categories")
        val allowedOrders = setOf("ASC", "DESC")

        val safeTable = if (table in allowedTables) table else "sync_orders"
        val safeOrder = if (order.uppercase() in allowedOrders) order.uppercase() else "DESC"

        return try {
            val results = syncService.executeSampleQuery(siteCode, safeTable, safeOrder)
            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "table" to safeTable,
                    "order" to safeOrder,
                    "data" to results
                )
            )
        } catch (e: Exception) {
            logger.error("Sample query failed: {}", e.message)
            ResponseEntity.ok(
                mapOf(
                    "success" to false,
                    "message" to "쿼리 실행 실패: ${e.message}"
                )
            )
        }
    }

    /**
     * 관리상태 변경 API
     * 상태 전이 규칙:
     * - 환불 → 환불(대기자환불) or 환불(참가취소,변심)
     * - 환불(대기자환불) → 모든 상태 (환불(참가취소,변심) 제외)
     * - 환불(참가취소,변심) → 모든 상태 (환불(대기자환불) 제외)
     * - 일반 상태 → 모든 상태
     */
    @PostMapping("/orders/{id}/management-status")
    fun updateManagementStatus(
        @PathVariable id: Long,
        @RequestBody request: Map<String, String>,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        val newStatus = request["status"]
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "상태값이 필요합니다.")
            )

        // 허용된 상태값 확인
        val allowedStatuses = setOf(
            "확인필요", "확정", "대기", "이월", "불참",
            "환불", "환불(대기자환불)", "환불(참가취소,변심)"
        )
        if (newStatus !in allowedStatuses) {
            return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "유효하지 않은 상태값입니다: $newStatus")
            )
        }

        return try {
            // 현재 주문 조회
            val order = syncService.findOrderById(id)
                ?: return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "주문을 찾을 수 없습니다.")
                )

            // 사이트 코드 확인
            if (order.siteCode != siteCode) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "권한이 없습니다.")
                )
            }

            // 상태 전이 규칙 확인
            val currentStatus = order.managementStatus ?: "확인필요"
            val validTransition = isValidStatusTransition(currentStatus, newStatus)
            if (!validTransition) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "'$currentStatus'에서 '$newStatus'로 변경할 수 없습니다.")
                )
            }

            // 상태 업데이트
            val updated = syncService.updateManagementStatus(id, newStatus)
            if (updated > 0) {
                ResponseEntity.ok(
                    mapOf("success" to true, "message" to "상태가 변경되었습니다.", "newStatus" to newStatus)
                )
            } else {
                ResponseEntity.ok(
                    mapOf("success" to false, "message" to "상태 변경에 실패했습니다.")
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to update management status: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "상태 변경 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    /**
     * 상태 전이 규칙 확인
     * - 환불 관련 상태(환불, 환불(대기자환불), 환불(참가취소,변심))는 자동감지에 의해서만 전환
     * - 일반 상태 -> 환불 관련 상태로 수동 전이 불가
     * - 환불 상태 -> 세부 환불 상태로만 전이 가능
     * - 세부 환불 상태 -> 일반 상태로 복귀 가능
     */
    private fun isValidStatusTransition(current: String, next: String): Boolean {
        // 환불 관련 상태 집합
        val refundStatuses = setOf("환불", "환불(대기자환불)", "환불(참가취소,변심)")
        val refundSubStatuses = setOf("환불(대기자환불)", "환불(참가취소,변심)")
        // 일반 상태 집합
        val normalStatuses = setOf("확인필요", "확정", "대기", "이월", "불참", "결제대기중")

        return when (current) {
            "환불" -> next in refundSubStatuses // 환불에서는 세부 환불로만 전이
            "환불(대기자환불)" -> next in normalStatuses // 세부 환불에서 일반 상태로 복귀 가능
            "환불(참가취소,변심)" -> next in normalStatuses // 세부 환불에서 일반 상태로 복귀 가능
            else -> next !in refundStatuses // 일반 상태에서 환불 관련 상태로 전이 불가
        }
    }

    /**
     * 관리상태 변경 API v2 (이력 포함)
     * - 상태 변경 + 이력 저장
     * - 이월 시 회차 지정 가능
     */
    @PostMapping("/orders/{id}/change-status")
    fun changeManagementStatus(
        @PathVariable id: Long,
        @RequestBody request: Map<String, Any?>,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        val newStatus = request["status"] as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "상태값이 필요합니다.")
            )

        val carryoverRound = (request["carryoverRound"] as? Number)?.toInt()

        // 허용된 상태값 확인
        val allowedStatuses = setOf(
            "확인필요", "확정", "대기", "이월", "불참",
            "환불", "환불(대기자환불)", "환불(참가취소,변심)"
        )
        if (newStatus !in allowedStatuses) {
            return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "유효하지 않은 상태값입니다: $newStatus")
            )
        }

        // 이월 회차는 더 이상 필수가 아님 (참여희망날짜로 대체)

        return try {
            // 현재 주문 조회
            val order = syncService.findOrderById(id)
                ?: return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "주문을 찾을 수 없습니다.")
                )

            // 사이트 코드 확인
            if (order.siteCode != siteCode) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "권한이 없습니다.")
                )
            }

            // 상태 전이 규칙 확인
            val currentStatus = order.managementStatus ?: "확인필요"
            val validTransition = isValidStatusTransition(currentStatus, newStatus)
            if (!validTransition) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "'$currentStatus'에서 '$newStatus'로 변경할 수 없습니다.")
                )
            }

            // 상태 변경 (이력 포함)
            val changeRequest = StatusChangeRequest(
                orderId = id,
                newStatus = newStatus,
                carryoverRound = if (newStatus == "이월") carryoverRound else null
            )
            val result = syncService.changeManagementStatus(changeRequest, siteCode)

            if (result.success) {
                // 변경 후 주문 다시 조회하여 알림톡 발송 가능 여부 확인
                val updatedOrder = syncService.findOrderById(id)
                val canSendAlimtalk = updatedOrder != null &&
                    updatedOrder.managementStatus == "확정" &&
                    !updatedOrder.alimtalkSent
                val alimtalkSent = updatedOrder?.alimtalkSent ?: false

                ResponseEntity.ok(
                    mapOf(
                        "success" to true,
                        "message" to result.message,
                        "orderId" to result.orderId,
                        "newStatus" to result.newStatus,
                        "carryoverRound" to (result.carryoverRound ?: 0),
                        "canSendAlimtalk" to canSendAlimtalk,
                        "alimtalkSent" to alimtalkSent
                    )
                )
            } else {
                ResponseEntity.ok(
                    mapOf("success" to false, "message" to result.message)
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to change management status: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "상태 변경 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    /**
     * 주문 상세 정보 조회 API (상태 변경 이력 포함)
     */
    @GetMapping("/orders/{id}/detail")
    fun getOrderDetail(
        @PathVariable id: Long,
        session: HttpSession
    ): ResponseEntity<Map<String, Any?>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false as Any?, "message" to "로그인이 필요합니다." as Any?)
            )

        return try {
            val order = syncService.findOrderById(id)
                ?: return ResponseEntity.badRequest().body(
                    mapOf("success" to false as Any?, "message" to "주문을 찾을 수 없습니다." as Any?)
                )

            if (order.siteCode != siteCode) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false as Any?, "message" to "권한이 없습니다." as Any?)
                )
            }

            // 상태 변경 이력 조회 (최대 10개)
            val statusHistory = syncService.getStatusHistoryLimit(id, 10)

            // 현재 상태에서 가능한 상태 목록
            val currentStatus = order.managementStatus ?: "확인필요"
            val availableStatuses = getAvailableStatusesForCurrent(currentStatus)

            ResponseEntity.ok(
                mapOf(
                    "success" to true as Any?,
                    "order" to mapOf(
                        "id" to order.id,
                        "orderNo" to order.orderNo,
                        "ordererName" to order.ordererName,
                        "ordererEmail" to order.ordererEmail,
                        "ordererCall" to order.ordererCall,
                        "managementStatus" to currentStatus,
                        "carryoverRound" to order.carryoverRound,
                        "optionInfo" to order.optionInfo,
                        "prodName" to order.prodName,
                        "regionName" to order.regionName,
                        "paidPrice" to order.paidPrice,
                        "paymentMethod" to order.paymentMethod
                    ) as Any?,
                    "statusHistory" to statusHistory.map { history ->
                        mapOf(
                            "id" to history.id,
                            "previousStatus" to history.previousStatus,
                            "newStatus" to history.newStatus,
                            "carryoverRound" to history.carryoverRound,
                            "changedBy" to history.changedBy,
                            "changedAt" to history.changedAt?.toString()
                        )
                    } as Any?,
                    "availableStatuses" to availableStatuses as Any?
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to get order detail: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false as Any?, "message" to "조회 중 오류가 발생했습니다: ${e.message}" as Any?)
            )
        }
    }

    /**
     * 가능한 상태 전이 목록 조회 API
     */
    @GetMapping("/orders/{id}/available-statuses")
    fun getAvailableStatuses(
        @PathVariable id: Long,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        return try {
            val order = syncService.findOrderById(id)
                ?: return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "주문을 찾을 수 없습니다.")
                )

            if (order.siteCode != siteCode) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "권한이 없습니다.")
                )
            }

            val currentStatus = order.managementStatus ?: "확인필요"
            val availableStatuses = getAvailableStatusesForCurrent(currentStatus)

            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "currentStatus" to currentStatus,
                    "availableStatuses" to availableStatuses
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to get available statuses: {}", e.message)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "조회 중 오류가 발생했습니다.")
            )
        }
    }

    /**
     * 현재 상태에서 전이 가능한 상태 목록 반환
     * 참고: 결제대기중은 시스템 자동 설정 상태이므로 수동 전이 목록에 포함하지 않음
     * 참고: 불참은 수동 전이 목록에서 제외 (기존 데이터 호환을 위해 allowedStatuses에는 유지)
     * 참고: 환불 관련 상태(환불, 환불(대기자환불), 환불(참가취소,변심))는 자동감지에 의해서만 전환
     *       - 일반 상태에서 환불 관련 상태로 수동 전이 불가
     *       - 환불 상태에서만 세부 환불 상태로 전이 가능
     */
    private fun getAvailableStatusesForCurrent(current: String): List<Map<String, String>> {
        // 환불 관련 상태 집합 (자동감지에 의해서만 전환)
        val refundStatuses = setOf("환불", "환불(대기자환불)", "환불(참가취소,변심)")
        val refundSubStatuses = setOf("환불(대기자환불)", "환불(참가취소,변심)")

        // 일반 상태 목록 (수동 선택 가능)
        val normalStatuses = listOf(
            mapOf("value" to "확인필요", "label" to "확인필요", "color" to "yellow"),
            mapOf("value" to "확정", "label" to "확정", "color" to "green"),
            mapOf("value" to "대기", "label" to "대기", "color" to "blue"),
            mapOf("value" to "이월", "label" to "이월", "color" to "purple")
        )

        // 세부 환불 상태 목록 (환불 상태에서만 선택 가능)
        val refundSubStatusList = listOf(
            mapOf("value" to "환불(대기자환불)", "label" to "환불(대기자환불)", "color" to "red"),
            mapOf("value" to "환불(참가취소,변심)", "label" to "환불(참가취소,변심)", "color" to "red")
        )

        return when (current) {
            "환불" -> refundSubStatusList // 환불 상태에서는 세부 환불 상태만 선택 가능
            "환불(대기자환불)" -> normalStatuses.filter { it["value"] != current } // 세부 환불에서 일반 상태로 복귀 가능
            "환불(참가취소,변심)" -> normalStatuses.filter { it["value"] != current } // 세부 환불에서 일반 상태로 복귀 가능
            else -> normalStatuses.filter { it["value"] != current } // 일반 상태에서는 다른 일반 상태만 선택 가능
        }
    }

    /**
     * 동기화 데이터 전체 초기화 API
     * - 확인 문구 "지금삭제" 입력 필수
     * - 모든 연관 테이블 순서대로 삭제
     */
    @PostMapping("/reset")
    fun resetSyncData(
        @RequestBody request: Map<String, String>,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        // 확인 문구 검증
        val confirmText = request["confirmText"]
        if (confirmText != "지금삭제") {
            return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "확인 문구가 일치하지 않습니다.")
            )
        }

        return try {
            val result = syncService.resetAllSyncData(siteCode)

            if (result.success) {
                ResponseEntity.ok(
                    mapOf(
                        "success" to true,
                        "message" to result.message,
                        "deletedCounts" to mapOf(
                            "statusHistory" to result.statusHistoryDeleted,
                            "orderCategories" to result.orderCategoryDeleted,
                            "orders" to result.orderDeleted,
                            "categories" to result.categoryDeleted,
                            "syncHistory" to result.statusDeleted
                        )
                    )
                )
            } else {
                ResponseEntity.ok(
                    mapOf("success" to false, "message" to result.message)
                )
            }
        } catch (e: IllegalStateException) {
            ResponseEntity.ok(
                mapOf("success" to false, "message" to (e.message ?: "알 수 없는 오류"))
            )
        } catch (e: Exception) {
            logger.error("Failed to reset sync data: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "초기화 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    // ========== 스케줄러 관련 API ==========

    /**
     * 스케줄러 상태 조회
     */
    @GetMapping("/scheduler/status")
    fun getSchedulerStatus(session: HttpSession): ResponseEntity<Map<String, Any?>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false as Any?, "message" to "로그인이 필요합니다." as Any?)
            )

        val status = syncSchedulerService.getSchedulerStatus(siteCode)
        return ResponseEntity.ok(
            mapOf(
                "success" to true as Any?,
                "data" to mapOf(
                    "isEnabled" to status.isEnabled,
                    "lastRunAt" to status.lastRunAt,
                    "lastSuccessAt" to status.lastSuccessAt,
                    "lastErrorMessage" to status.lastErrorMessage,
                    "nextRunAt" to status.nextRunAt,
                    "runIntervalMinutes" to status.runIntervalMinutes
                ) as Any?
            )
        )
    }

    /**
     * 스케줄러 활성화/비활성화 토글
     */
    @PostMapping("/scheduler/toggle")
    fun toggleScheduler(session: HttpSession): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        return try {
            // 스케줄러 활성화 전 배치 토큰 확인/복사
            val hasValidToken = batchTokenService.isTokenValid(siteCode)
            if (!hasValidToken) {
                // 어드민 토큰에서 배치 토큰으로 복사 시도
                val copied = batchTokenService.forceRefreshFromAdmin(siteCode)
                if (!copied) {
                    return ResponseEntity.ok(
                        mapOf(
                            "success" to false,
                            "message" to "배치용 토큰이 없습니다. OAuth 인증을 먼저 완료해주세요."
                        )
                    )
                }
            }

            val result = syncSchedulerService.toggleScheduler(siteCode)
            val newStatus = syncSchedulerService.getSchedulerStatus(siteCode)

            ResponseEntity.ok(
                mapOf(
                    "success" to result,
                    "message" to if (newStatus.isEnabled) "스케줄러가 활성화되었습니다." else "스케줄러가 비활성화되었습니다.",
                    "isEnabled" to newStatus.isEnabled
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to toggle scheduler: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "스케줄러 상태 변경 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    /**
     * 스케줄러 활성화 (간격 설정 포함)
     */
    @PostMapping("/scheduler/enable")
    fun enableScheduler(
        @RequestParam(defaultValue = "10") intervalMinutes: Int,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        return try {
            // 배치 토큰 확인/복사
            val hasValidToken = batchTokenService.isTokenValid(siteCode)
            if (!hasValidToken) {
                val copied = batchTokenService.forceRefreshFromAdmin(siteCode)
                if (!copied) {
                    return ResponseEntity.ok(
                        mapOf(
                            "success" to false,
                            "message" to "배치용 토큰이 없습니다. OAuth 인증을 먼저 완료해주세요."
                        )
                    )
                }
            }

            val result = syncSchedulerService.setSchedulerEnabledWithInterval(siteCode, true, intervalMinutes)
            val status = syncSchedulerService.getSchedulerStatus(siteCode)
            ResponseEntity.ok(
                mapOf(
                    "success" to result,
                    "message" to if (result) "스케줄러가 ${status.runIntervalMinutes}분 간격으로 활성화되었습니다." else "스케줄러 활성화에 실패했습니다.",
                    "isEnabled" to result,
                    "intervalMinutes" to status.runIntervalMinutes
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to enable scheduler: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "스케줄러 활성화 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    /**
     * 스케줄러 간격 변경
     */
    @PostMapping("/scheduler/interval")
    fun updateSchedulerInterval(
        @RequestParam intervalMinutes: Int,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        return try {
            val result = syncSchedulerService.updateSchedulerInterval(siteCode, intervalMinutes)
            val status = syncSchedulerService.getSchedulerStatus(siteCode)
            ResponseEntity.ok(
                mapOf(
                    "success" to result,
                    "message" to if (result) "스케줄러 간격이 ${status.runIntervalMinutes}분으로 변경되었습니다." else "스케줄러 간격 변경에 실패했습니다.",
                    "intervalMinutes" to status.runIntervalMinutes
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to update scheduler interval: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "스케줄러 간격 변경 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    /**
     * 스케줄러 비활성화
     */
    @PostMapping("/scheduler/disable")
    fun disableScheduler(session: HttpSession): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        return try {
            val result = syncSchedulerService.setSchedulerEnabled(siteCode, false)
            ResponseEntity.ok(
                mapOf(
                    "success" to result,
                    "message" to if (result) "스케줄러가 비활성화되었습니다." else "스케줄러 비활성화에 실패했습니다.",
                    "isEnabled" to false
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to disable scheduler: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "스케줄러 비활성화 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    /**
     * 배치 토큰 수동 갱신 (어드민 토큰에서 복사)
     */
    @PostMapping("/scheduler/refresh-token")
    fun refreshBatchToken(session: HttpSession): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        return try {
            val result = batchTokenService.forceRefreshFromAdmin(siteCode)
            ResponseEntity.ok(
                mapOf(
                    "success" to result,
                    "message" to if (result) "배치 토큰이 갱신되었습니다." else "배치 토큰 갱신에 실패했습니다. OAuth 인증을 다시 진행해주세요."
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to refresh batch token: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "배치 토큰 갱신 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    /**
     * 증분 동기화 수동 실행 (테스트용)
     */
    @PostMapping("/scheduler/run-now")
    fun runIncrementalSyncNow(session: HttpSession): ResponseEntity<Map<String, Any>> = runBlocking {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return@runBlocking ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        try {
            val result = syncSchedulerService.executeIncrementalSync(siteCode)
            ResponseEntity.ok(
                mapOf(
                    "success" to result.success,
                    "message" to result.message,
                    "syncedCount" to result.syncedCount,
                    "failedCount" to result.failedCount,
                    "startTime" to (result.startTime ?: ""),
                    "endTime" to (result.endTime ?: "")
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to run incremental sync: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "증분 동기화 실행 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    // ========== 스케줄러 로그 SSE 스트리밍 ==========

    /**
     * 스케줄러 로그 실시간 스트리밍 (SSE)
     * 콘솔 로그처럼 실시간으로 스케줄러 작동 상황을 모니터링
     */
    @GetMapping("/logs/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamSchedulerLogs(): Flux<ServerSentEvent<Map<String, Any?>>> {
        return Flux.from(
            schedulerLogService.logFlow.map { entry: SchedulerLogEntry ->
                ServerSentEvent.builder<Map<String, Any?>>()
                    .event("log")
                    .data(entry.toJson())
                    .build()
            }.asPublisher()
        )
    }

    /**
     * 최근 스케줄러 로그 조회 (초기 로드용)
     */
    @GetMapping("/logs/recent")
    fun getRecentLogs(
        @RequestParam(defaultValue = "50") limit: Int
    ): ResponseEntity<Map<String, Any>> {
        val logs = schedulerLogService.getRecentLogs(limit)
        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "logs" to logs.map { it.toJson() },
                "count" to logs.size
            )
        )
    }

    /**
     * 스케줄러 로그 초기화
     */
    @PostMapping("/logs/clear")
    fun clearLogs(session: HttpSession): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        schedulerLogService.clearLogs()
        return ResponseEntity.ok(
            mapOf("success" to true, "message" to "로그가 초기화되었습니다.")
        )
    }

    // ========== 알림톡 관련 API ==========

    /**
     * 알림톡 테스트 전화번호 조회
     */
    @GetMapping("/alimtalk/test-phone")
    fun getAlimtalkTestPhone(session: HttpSession): ResponseEntity<Map<String, Any?>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false as Any?, "message" to "로그인이 필요합니다." as Any?)
            )

        val testPhone = alimtalkService.getTestPhone(siteCode)
        return ResponseEntity.ok(
            mapOf(
                "success" to true as Any?,
                "testPhone" to testPhone as Any?
            )
        )
    }

    /**
     * 알림톡 테스트 전화번호 설정
     */
    @PostMapping("/alimtalk/test-phone")
    fun setAlimtalkTestPhone(
        @RequestBody request: Map<String, String?>,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        val phone = request["phone"]

        return try {
            val result = alimtalkService.setTestPhone(siteCode, phone)
            if (result) {
                ResponseEntity.ok(
                    mapOf(
                        "success" to true,
                        "message" to if (phone.isNullOrBlank()) "테스트 전화번호가 삭제되었습니다." else "테스트 전화번호가 설정되었습니다.",
                        "phone" to (phone?.replace("-", "")?.trim() ?: "")
                    )
                )
            } else {
                ResponseEntity.ok(
                    mapOf("success" to false, "message" to "설정에 실패했습니다.")
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to set test phone: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "설정 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    /**
     * 알림톡 템플릿 목록 조회
     */
    @GetMapping("/alimtalk/templates")
    fun getAlimtalkTemplates(session: HttpSession): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        val templates = alimtalkService.getAllTemplates()
        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "templates" to templates.map { t ->
                    mapOf(
                        "templateId" to t.templateId,
                        "templateName" to t.templateName,
                        "messageContent" to t.messageContent,
                        "smsContent" to t.smsContent,
                        "hasVariables" to t.hasVariables,
                        "variables" to t.variables,
                        "buttons" to t.buttons.map { b ->
                            mapOf("urlPc" to b.urlPc, "urlMobile" to b.urlMobile)
                        }
                    )
                }
            )
        )
    }

    /**
     * 알림톡 템플릿 상세 조회
     */
    @GetMapping("/alimtalk/templates/{templateId}")
    fun getAlimtalkTemplate(
        @PathVariable templateId: Int,
        session: HttpSession
    ): ResponseEntity<Map<String, Any?>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false as Any?, "message" to "로그인이 필요합니다." as Any?)
            )

        val template = alimtalkService.getTemplateInfo(templateId)
            ?: return ResponseEntity.ok(
                mapOf("success" to false as Any?, "message" to "템플릿을 찾을 수 없습니다." as Any?)
            )

        return ResponseEntity.ok(
            mapOf(
                "success" to true as Any?,
                "template" to mapOf(
                    "templateId" to template.templateId,
                    "templateName" to template.templateName,
                    "messageContent" to template.messageContent,
                    "smsContent" to template.smsContent,
                    "hasVariables" to template.hasVariables,
                    "variables" to template.variables,
                    "buttons" to template.buttons.map { b ->
                        mapOf("urlPc" to b.urlPc, "urlMobile" to b.urlMobile)
                    }
                ) as Any?
            )
        )
    }

    /**
     * 알림톡 테스트 발송
     */
    @PostMapping("/alimtalk/send-test")
    fun sendAlimtalkTest(
        @RequestBody request: Map<String, Any>,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        val templateId = (request["templateId"] as? Number)?.toInt()
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "템플릿 ID가 필요합니다.")
            )

        val receiverPhone = request["receiverPhone"] as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "수신 전화번호가 필요합니다.")
            )

        @Suppress("UNCHECKED_CAST")
        val variables = request["variables"] as? Map<String, String>

        return try {
            val result = alimtalkService.sendTest(siteCode, templateId, receiverPhone, variables)
            ResponseEntity.ok(
                mapOf(
                    "success" to result.success,
                    "message" to result.message,
                    "code" to (result.code ?: 0)
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to send test alimtalk: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "발송 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    /**
     * 알림톡 발송 이력 조회 (페이징)
     */
    @GetMapping("/alimtalk/history")
    fun getAlimtalkHistory(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        session: HttpSession
    ): ResponseEntity<Map<String, Any?>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false as Any?, "message" to "로그인이 필요합니다." as Any?)
            )

        val history = alimtalkService.getHistory(siteCode, page, size)
        val totalCount = alimtalkService.countHistory(siteCode)
        val totalPages = if (totalCount == 0L) 0 else ((totalCount - 1) / size + 1).toInt()

        return ResponseEntity.ok(
            mapOf(
                "success" to true as Any?,
                "data" to mapOf(
                    "content" to history.map { h ->
                        mapOf(
                            "id" to h.id,
                            "orderNo" to h.orderNo,
                            "templateId" to h.templateId,
                            "templateName" to h.templateName,
                            "receiverPhone" to h.receiverPhone,
                            "receiverName" to h.receiverName,
                            "sendType" to h.sendType,
                            "triggerStatus" to h.triggerStatus,
                            "isSuccess" to h.isSuccess,
                            "resultMessage" to h.resultMessage,
                            "sentAt" to h.sentAt?.toString()
                        )
                    },
                    "page" to page,
                    "size" to size,
                    "totalElements" to totalCount,
                    "totalPages" to totalPages
                ) as Any?
            )
        )
    }

    // ========== 리얼타임 결제상태 체크 API ==========

    /**
     * 주문 목록의 결제상태 리얼타임 체크
     * - 5분 이내 체크한 주문은 스킵
     * - 결제상태가 변경되었으면 DB 업데이트
     * - 결제대기중 → 결제완료로 변경 시 관리상태도 확인필요로 자동 변경
     */
    @PostMapping("/orders/realtime-check")
    fun realtimeCheckPaymentStatus(
        @RequestBody request: Map<String, Any>,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> = runBlocking {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return@runBlocking ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        @Suppress("UNCHECKED_CAST")
        val orderIds = (request["orderIds"] as? List<Number>)?.map { it.toLong() }
            ?: return@runBlocking ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "주문 ID 목록이 필요합니다.")
            )

        if (orderIds.isEmpty()) {
            return@runBlocking ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "message" to "체크할 주문이 없습니다.",
                    "checkedCount" to 0,
                    "updatedOrders" to emptyList<Any>()
                )
            )
        }

        try {
            val result = syncService.realtimeCheckPaymentStatus(siteCode, orderIds)
            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "message" to "${result.checkedCount}건 체크, ${result.updatedOrders.size}건 상태 변경",
                    "checkedCount" to result.checkedCount,
                    "skippedCount" to result.skippedCount,
                    "updatedOrders" to result.updatedOrders.map { order ->
                        mapOf(
                            "id" to order.id,
                            "orderNo" to order.orderNo,
                            "paymentStatus" to order.paymentStatus,
                            "managementStatus" to order.managementStatus,
                            "canSendAlimtalk" to (order.managementStatus == "확정" && !order.alimtalkSent),
                            "alimtalkSent" to order.alimtalkSent
                        )
                    }
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to realtime check payment status: {}", e.message, e)
            ResponseEntity.ok(
                mapOf(
                    "success" to false,
                    "message" to "결제상태 체크 중 오류가 발생했습니다: ${e.message}"
                )
            )
        }
    }

    /**
     * 알림톡 일괄 발송 API
     * - 선택된 주문 ID 목록에 대해 알림톡 일괄 발송
     * - 발송 조건: 관리상태=확정 AND 알림톡 미발송
     */
    @PostMapping("/alimtalk/batch-send")
    fun batchSendAlimtalk(
        @RequestBody request: Map<String, Any>,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        @Suppress("UNCHECKED_CAST")
        val orderIds = (request["orderIds"] as? List<Number>)?.map { it.toLong() }
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "주문 ID 목록이 필요합니다.")
            )

        if (orderIds.isEmpty()) {
            return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "선택된 주문이 없습니다.")
            )
        }

        return try {
            // 주문 조회
            val orders = syncOrderMapper.findByIdsForAlimtalk(orderIds)
                .filter { it.siteCode == siteCode }  // 권한 확인

            if (orders.isEmpty()) {
                return ResponseEntity.ok(
                    mapOf("success" to false, "message" to "조회된 주문이 없습니다.")
                )
            }

            // 일괄 발송
            val result: BatchSendResult = alimtalkService.sendBatchForOrders(siteCode, orders) { orderId, success ->
                if (success) {
                    syncOrderMapper.updateAlimtalkSent(orderId, true)
                }
            }

            val isSuccess = result.successCount > 0
            ResponseEntity.ok(
                mapOf(
                    "success" to isSuccess,
                    "message" to result.message,
                    "totalCount" to result.totalCount,
                    "successCount" to result.successCount,
                    "failedCount" to result.failedCount,
                    "skippedCount" to result.skippedCount
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to batch send alimtalk: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "일괄 발송 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    // ========== 상품 옵션 관련 API ==========

    /**
     * 상품 옵션 조회 API (참여희망날짜 목록)
     * - 상품코드로 상품 옵션 조회
     * - "참여희망날짜" 옵션의 값 목록 반환
     * - 토큰 동시성 문제: 재시도 로직 적용 (0.5초 대기, 최대 3회)
     */
    @GetMapping("/product-options/{prodNo}")
    fun getProductOptions(
        @PathVariable prodNo: Int,
        session: HttpSession
    ): ResponseEntity<Map<String, Any?>> = runBlocking {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return@runBlocking ResponseEntity.badRequest().body(
                mapOf("success" to false as Any?, "message" to "로그인이 필요합니다." as Any?)
            )

        try {
            // 스토어 정보 조회 (unitCode 필요)
            val store = storeService.getStore(siteCode)
            val unitCode = store?.unitCode
                ?: return@runBlocking ResponseEntity.ok(
                    mapOf("success" to false as Any?, "message" to "스토어 정보가 없습니다." as Any?)
                )

            // 토큰 존재 여부 확인 (allowRefresh=false, 갱신 없이)
            storeService.getValidAccessToken(siteCode, allowRefresh = false)
                ?: return@runBlocking ResponseEntity.ok(
                    mapOf("success" to false as Any?, "message" to "유효한 토큰이 없습니다. 재인증이 필요합니다." as Any?)
                )

            // 재시도 로직 적용하여 상품 옵션 조회
            val response = tokenRetryHelper.executeWithRetry(siteCode) { accessToken ->
                imwebApiService.getProductOptions(accessToken, unitCode, prodNo)
            }

            if (response.statusCode != 200) {
                return@runBlocking ResponseEntity.ok(
                    mapOf("success" to false as Any?, "message" to "상품 옵션 조회 실패: statusCode=${response.statusCode}" as Any?)
                )
            }

            // "참여희망날짜" 옵션 찾기
            val preferredDateOption = response.data?.list?.find {
                it.name?.contains("참여희망날짜") == true || it.name?.contains("희망날짜") == true
            }

            val rawDates = preferredDateOption?.optionValueList?.mapNotNull {
                it.optionValueName
            } ?: emptyList()

            // 날짜 정규화 (공백 제거) 및 3개월 이내 필터링 후 정렬
            val normalizedDates = rawDates.mapNotNull { DateUtils.normalizePreferredDate(it) }
            val filteredDates = DateUtils.filterDatesWithinMonths(normalizedDates, 3)
            val preferredDates = DateUtils.sortPreferredDates(filteredDates)

            val result = mutableMapOf<String, Any?>()
            result["success"] = true
            result["prodNo"] = prodNo
            result["preferredDates"] = preferredDates

            ResponseEntity.ok(result.toMap())
        } catch (e: Exception) {
            logger.error("Failed to get product options: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false as Any?, "message" to "상품 옵션 조회 중 오류가 발생했습니다: ${e.message}" as Any?)
            )
        }
    }

    /**
     * 지역 기반 상품 옵션 조회 API (참여희망날짜 목록)
     * - 지역명으로 해당 지역의 모든 상품 옵션 조회
     * - 모든 상품의 "참여희망날짜" 옵션을 합쳐서 반환
     * - 토큰 동시성 문제: 재시도 로직 적용 (0.5초 대기, 최대 3회)
     */
    @GetMapping("/region-options/{regionName}")
    fun getRegionOptions(
        @PathVariable regionName: String,
        session: HttpSession
    ): ResponseEntity<Map<String, Any?>> = runBlocking {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return@runBlocking ResponseEntity.badRequest().body(
                mapOf("success" to false as Any?, "message" to "로그인이 필요합니다." as Any?)
            )

        try {
            // 지역에 해당하는 상품코드 목록 조회
            val prodNos = regionConfig.getProductCodesByRegion(regionName)
            if (prodNos.isEmpty()) {
                return@runBlocking ResponseEntity.ok(
                    mapOf("success" to false as Any?, "message" to "해당 지역의 상품이 없습니다." as Any?)
                )
            }

            // 스토어 정보 조회 (unitCode 필요)
            val store = storeService.getStore(siteCode)
            val unitCode = store?.unitCode
                ?: return@runBlocking ResponseEntity.ok(
                    mapOf("success" to false as Any?, "message" to "스토어 정보가 없습니다." as Any?)
                )

            // 토큰 존재 여부 확인 (allowRefresh=false, 갱신 없이)
            storeService.getValidAccessToken(siteCode, allowRefresh = false)
                ?: return@runBlocking ResponseEntity.ok(
                    mapOf("success" to false as Any?, "message" to "유효한 토큰이 없습니다. 재인증이 필요합니다." as Any?)
                )

            // 모든 상품의 참여희망날짜 수집 (재시도 로직 적용)
            val allPreferredDates = mutableSetOf<String>()

            for (prodNo in prodNos) {
                try {
                    val response = tokenRetryHelper.executeWithRetry(siteCode) { accessToken ->
                        imwebApiService.getProductOptions(accessToken, unitCode, prodNo)
                    }
                    if (response.statusCode == 200) {
                        val preferredDateOption = response.data?.list?.find {
                            it.name?.contains("참여희망날짜") == true || it.name?.contains("희망날짜") == true
                        }
                        preferredDateOption?.optionValueList?.forEach { optionValue ->
                            // 날짜 정규화 (공백 제거) 후 추가
                            optionValue.optionValueName?.let { date ->
                                DateUtils.normalizePreferredDate(date)?.let { allPreferredDates.add(it) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to get options for prodNo {} after retries: {}", prodNo, e.message)
                }
            }

            // 3개월 이내 필터링 후 정렬 (오름차순 - 가까운 날짜가 위로)
            val filteredDates = DateUtils.filterDatesWithinMonths(allPreferredDates, 3)
            val sortedDates = DateUtils.sortPreferredDates(filteredDates)

            val result = mutableMapOf<String, Any?>()
            result["success"] = true
            result["regionName"] = regionName
            result["prodNos"] = prodNos
            result["preferredDates"] = sortedDates

            ResponseEntity.ok(result.toMap())
        } catch (e: Exception) {
            logger.error("Failed to get region options: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false as Any?, "message" to "지역 옵션 조회 중 오류가 발생했습니다: ${e.message}" as Any?)
            )
        }
    }

    /**
     * 주문의 참여희망날짜 변경 API
     * - 이월 등 관리 시 참여희망날짜 변경 가능
     */
    @PostMapping("/orders/{id}/preferred-date")
    fun updatePreferredDate(
        @PathVariable id: Long,
        @RequestBody request: Map<String, String>,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        val newPreferredDate = request["preferredDate"]
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "참여희망날짜 값이 필요합니다.")
            )

        return try {
            // 주문 조회 및 권한 확인
            val order = syncService.findOrderById(id)
                ?: return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "주문을 찾을 수 없습니다.")
                )

            if (order.siteCode != siteCode) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "권한이 없습니다.")
                )
            }

            // 참여희망날짜 업데이트 (order_event_date_dt도 함께 갱신)
            val newEventDateDt = DateUtils.parsePreferredDateToDateTime(newPreferredDate)
            val updated = syncOrderMapper.updateOptPreferredDate(id, newPreferredDate, newEventDateDt)
            if (updated > 0) {
                ResponseEntity.ok(
                    mapOf(
                        "success" to true,
                        "message" to "참여희망날짜가 변경되었습니다.",
                        "orderId" to id,
                        "newPreferredDate" to newPreferredDate
                    )
                )
            } else {
                ResponseEntity.ok(
                    mapOf("success" to false, "message" to "참여희망날짜 변경에 실패했습니다.")
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to update preferred date: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "참여희망날짜 변경 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    /**
     * 주문자 정보 수정 (이름, 출생년도, 직업, 연락처)
     */
    @PostMapping("/orders/{id}/orderer-info")
    fun updateOrdererInfo(
        @PathVariable id: Long,
        @RequestBody request: Map<String, String?>,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        val ordererName = request["ordererName"]
        val optBirthYear = request["optBirthYear"]
        val optJob = request["optJob"]
        val ordererCall = request["ordererCall"]
        val optProfile = request["optProfile"]
        val optPremium = request["optPremium"]

        // 출생년도 유효성 검사 (4자리 숫자)
        if (!optBirthYear.isNullOrBlank()) {
            if (!optBirthYear.matches(Regex("^(19|20)\\d{2}$"))) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "출생년도는 1900~2099 사이의 4자리 숫자여야 합니다.")
                )
            }
        }

        // 프로필 URL 유효성 검사 (http/https)
        if (!optProfile.isNullOrBlank()) {
            if (!optProfile.matches(Regex("^https?://.*"))) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "프로필 URL은 http:// 또는 https://로 시작해야 합니다.")
                )
            }
        }

        return try {
            // 주문 조회 및 권한 확인
            val order = syncService.findOrderById(id)
                ?: return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "주문을 찾을 수 없습니다.")
                )

            if (order.siteCode != siteCode) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "권한이 없습니다.")
                )
            }

            // 주문자 정보 업데이트
            val updated = syncOrderMapper.updateOrdererInfo(
                id = id,
                ordererName = ordererName,
                optBirthYear = optBirthYear,
                optJob = optJob,
                ordererCall = ordererCall,
                optProfile = optProfile,
                optPremium = optPremium
            )

            if (updated > 0) {
                ResponseEntity.ok(
                    mapOf(
                        "success" to true,
                        "message" to "주문자 정보가 수정되었습니다.",
                        "orderId" to id
                    )
                )
            } else {
                ResponseEntity.ok(
                    mapOf("success" to false, "message" to "주문자 정보 수정에 실패했습니다.")
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to update orderer info: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "주문자 정보 수정 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    // ========== 지역 및 상품 정보 API ==========

    /**
     * 지역별 상품 목록 조회 API
     * - 지역명으로 해당 지역에 속한 상품코드 목록 반환
     * - DB에서 상품명 조회하여 함께 반환
     */
    @GetMapping("/region/{regionName}/products")
    fun getRegionProducts(
        @PathVariable regionName: String,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        val productCodes = regionConfig.getProductCodesByRegion(regionName)
        if (productCodes.isEmpty()) {
            return ResponseEntity.ok(
                mapOf("success" to false, "message" to "해당 지역의 상품이 없습니다.")
            )
        }

        // 상품코드와 이름 매핑 (DB에서 상품명 조회)
        val products = productCodes.map { prodNo ->
            val productName = syncOrderMapper.findProductNameByProdNo(siteCode, prodNo)
            mapOf(
                "prodNo" to prodNo,
                "name" to getProductDisplayName(prodNo, regionName, productName)
            )
        }

        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "regionName" to regionName,
                "products" to products
            )
        )
    }

    /**
     * 상품 표시 이름 생성
     * - DB 상품명이 있으면 "상품명 (상품코드)" 형태로 표시
     * - 없으면 "지역명 (상품코드)" 형태로 표시
     */
    private fun getProductDisplayName(prodNo: Int, regionName: String, productName: String?): String {
        return if (!productName.isNullOrBlank()) {
            "$productName ($prodNo)"
        } else {
            "$regionName ($prodNo)"
        }
    }

    // ========== 수동 주문 생성 API ==========

    /**
     * 수동 주문 생성 API
     * - 주문번호는 음수로 자동 생성 (실제 아임웹 주문과 구분)
     * - 기본 관리상태는 "확인필요"
     * - prodNo 필수
     */
    @PostMapping("/orders/create")
    fun createManualOrder(
        @RequestBody request: Map<String, Any?>,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        // 요청 데이터 추출
        val name = request["name"] as? String ?: ""
        val gender = request["gender"] as? String ?: ""
        val phone = request["phone"] as? String ?: ""
        val birthYear = request["birthYear"] as? String ?: ""
        val job = request["job"] as? String
        val preferredDate = request["preferredDate"] as? String ?: ""
        val prodNo = (request["prodNo"] as? Number)?.toInt()
        val regionName = request["regionName"] as? String

        // ManualOrderRequest 생성
        val orderRequest = ManualOrderRequest(
            name = name,
            gender = gender,
            phone = phone,
            birthYear = birthYear,
            job = job,
            preferredDate = preferredDate,
            prodNo = prodNo,
            regionName = regionName
        )

        return try {
            val result = syncService.createManualOrder(siteCode, orderRequest)

            if (result.success) {
                ResponseEntity.ok(
                    mapOf(
                        "success" to true,
                        "message" to result.message,
                        "orderId" to (result.orderId ?: 0L),
                        "orderNo" to (result.orderNo ?: 0L)
                    )
                )
            } else {
                ResponseEntity.ok(
                    mapOf("success" to false, "message" to result.message)
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to create manual order: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "주문 생성 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }

    /**
     * 수동 추가 주문 삭제 API
     * - 수동으로 추가한 주문만 삭제 가능 (is_manual_order = true)
     * - 아임웹에서 동기화된 주문은 삭제 불가
     */
    @DeleteMapping("/orders/{id}")
    fun deleteManualOrder(
        @PathVariable id: Long,
        session: HttpSession
    ): ResponseEntity<Map<String, Any>> {
        val siteCode = session.getAttribute("authenticated_site_code") as? String
            ?: return ResponseEntity.badRequest().body(
                mapOf("success" to false, "message" to "로그인이 필요합니다.")
            )

        return try {
            // 주문 조회
            val order = syncService.findOrderById(id)
                ?: return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "주문을 찾을 수 없습니다.")
                )

            // 사이트 코드 확인
            if (order.siteCode != siteCode) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "권한이 없습니다.")
                )
            }

            // 수동 추가 주문인지 확인 (is_manual_order = true인 경우만)
            if (!order.isManual()) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "수동 추가된 주문만 삭제할 수 있습니다.")
                )
            }

            // 삭제 실행
            val deleted = syncOrderMapper.deleteManualOrder(id, siteCode)
            if (deleted > 0) {
                ResponseEntity.ok(
                    mapOf(
                        "success" to true,
                        "message" to "주문이 삭제되었습니다.",
                        "orderId" to id
                    )
                )
            } else {
                ResponseEntity.ok(
                    mapOf("success" to false, "message" to "삭제에 실패했습니다.")
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to delete manual order: {}", e.message, e)
            ResponseEntity.ok(
                mapOf("success" to false, "message" to "삭제 중 오류가 발생했습니다: ${e.message}")
            )
        }
    }
}
