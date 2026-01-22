package org.webnori.ordersyncoffice.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.webnori.ordersyncoffice.config.ManagementStatusTransition
import org.webnori.ordersyncoffice.config.RegionConfig
import org.webnori.ordersyncoffice.domain.*
import org.webnori.ordersyncoffice.mapper.*
import org.webnori.ordersyncoffice.util.DateUtils
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Service
class SyncService(
    private val imwebApiService: ImwebApiService,
    private val storeService: StoreService,
    private val syncOrderMapper: SyncOrderMapper,
    private val syncCategoryMapper: SyncCategoryMapper,
    private val syncOrderCategoryMapper: SyncOrderCategoryMapper,
    private val syncStatusMapper: SyncStatusMapper,
    private val syncOrderStatusHistoryMapper: SyncOrderStatusHistoryMapper,
    private val regionConfig: RegionConfig,
    private val productRegionMappingMapper: ProductRegionMappingMapper, // ✅ 추가
    private val alimtalkService: AlimtalkService,
    private val statusTransition: ManagementStatusTransition,
    private val tokenRetryHelper: TokenRetryHelper
) {
    private val logger = LoggerFactory.getLogger(SyncService::class.java)
    private val objectMapper = jacksonObjectMapper()

    companion object {
        const val SYNC_DAYS = 3  // 최근 3일 (개발 테스트용)
        const val PAGE_SIZE = 50  // API 페이지 사이즈

        // 타임존 설정
        val UTC_ZONE: ZoneId = ZoneId.of("UTC")
        val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        // 날짜 파싱 포맷 (API 응답 형식들)
        private val DATE_FORMATS = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"),
            DateTimeFormatter.ISO_DATE_TIME
        )

        // 환불 상태로 간주되는 결제상태
        val REFUND_PAYMENT_STATUSES = setOf("REFUND_PROCESSING", "REFUND_COMPLETE")

        // 결제 완료 상태
        const val PAYMENT_COMPLETE = "PAYMENT_COMPLETE"

        // 결제 대기 상태 (결제 전 단계)
        val PAYMENT_PENDING_STATUSES = setOf("PAYMENT_PREPARATION", "PAYMENT_PENDING")
    }

    // ========== DB 우선 조회 헬퍼 메서드 ==========

    private fun getDbRegionName(siteCode: String, prodNo: Int?): String? {
        if (prodNo == null) return null
        val row = productRegionMappingMapper.findActiveBySiteCodeAndProdNo(siteCode, prodNo) ?: return null
        return row["regionName"]?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun hasActiveDbMapping(siteCode: String, prodNo: Int?): Boolean {
        if (prodNo == null) return false
        return productRegionMappingMapper.findActiveBySiteCodeAndProdNo(siteCode, prodNo) != null
    }

    // DB 우선 지역명 조회
    fun getRegionNameDbFirst(siteCode: String, prodNo: Int?): String? {
        return getDbRegionName(siteCode, prodNo) ?: regionConfig.getRegionName(prodNo)
    }

    // DB 매핑 포함 동기화 대상 확인(정책2)
    fun isSyncTarget(siteCode: String, prodNo: Int?): Boolean {
        return regionConfig.isValidProductCode(prodNo) || hasActiveDbMapping(siteCode, prodNo)
    }

    /**
     * UTC 시간 문자열을 KST LocalDateTime으로 변환
     * API 응답의 wtime은 UTC로 가정
     */
    fun parseUtcToKst(utcTimeString: String?): LocalDateTime? {
        if (utcTimeString.isNullOrBlank()) return null

        return try {
            var parsedDateTime: LocalDateTime? = null

            // 여러 포맷으로 파싱 시도
            for (formatter in DATE_FORMATS) {
                try {
                    parsedDateTime = LocalDateTime.parse(utcTimeString, formatter)
                    break
                } catch (_: DateTimeParseException) {
                    // 다음 포맷으로 시도
                }
            }

            if (parsedDateTime == null) {
                logger.warn("Failed to parse date string: {}", utcTimeString)
                return null
            }

            // UTC -> KST 변환 (+9시간)
            val utcZoned = ZonedDateTime.of(parsedDateTime, UTC_ZONE)
            val kstZoned = utcZoned.withZoneSameInstant(KST_ZONE)
            kstZoned.toLocalDateTime()
        } catch (e: Exception) {
            logger.warn("Failed to convert UTC to KST: {} - {}", utcTimeString, e.message)
            null
        }
    }

    /**
     * 상품번호에 따른 지역명 반환 (설정 파일에서 조회)
     */
    fun getRegionName(prodNo: Int?): String? {
        return regionConfig.getRegionName(prodNo)
    }

    /**
     * 동기화 대상 상품코드인지 확인
     */
    fun isValidProductCode(prodNo: Int?): Boolean {
        return regionConfig.isValidProductCode(prodNo)
    }

    /**
     * 결제상태 및 주문섹션상태에 따른 관리상태 초기값 결정
     * - 공통 코드(ManagementStatusTransition)로 위임
     */
    fun determineInitialManagementStatus(paymentStatus: String?, orderSectionStatus: String? = null): String {
        return statusTransition.determineInitialManagementStatus(paymentStatus, orderSectionStatus)
    }

    /**
     * 동기화 시 결제상태 변경에 따른 관리상태 자동 업데이트 여부 결정
     * - 공통 코드(ManagementStatusTransition)로 위임
     * @return 변경이 필요하면 새 관리상태, 아니면 null
     */
    fun shouldUpdateManagementStatusOnPaymentChange(
        currentManagementStatus: String?,
        newPaymentStatus: String?
    ): String? {
        return statusTransition.determineStatusTransition(currentManagementStatus, newPaymentStatus)
    }

    /**
     * 주문 동기화 실행 (최근 3일)
     */
    suspend fun syncOrders(siteCode: String): SyncStatus {
        logger.info("=== Starting Order Sync for siteCode: {} ===", siteCode)

        // 이미 실행 중인 동기화가 있는지 확인
        val running = syncStatusMapper.findRunningBySiteCode(siteCode)
        if (running != null) {
            logger.warn("Sync already running for siteCode: {}", siteCode)
            return running
        }

        // 토큰 조회
        val token = storeService.getOAuthToken(siteCode)
            ?: throw IllegalStateException("OAuth token not found for siteCode: $siteCode")

        val store = storeService.getStore(siteCode)
            ?: throw IllegalStateException("Store not found for siteCode: $siteCode")

        val unitCode = store.unitCode
            ?: throw IllegalStateException("UnitCode not found for store: $siteCode")

        // 동기화 기간 계산 (초단위)
        val now = LocalDateTime.now(KST_ZONE)
        val endDateTime = now
        val startDateTime = now.minusDays(SYNC_DAYS.toLong())

        // 표시용 포맷 (KST)
        val displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        // API용 ISO 8601 포맷 (UTC)
        val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

        // UTC 변환 (KST -> UTC, -9시간)
        val startTimeUtc = startDateTime.minusHours(9)
        val endTimeUtc = endDateTime.minusHours(9)
        val startWtimeIso = startTimeUtc.format(isoFormatter)
        val endWtimeIso = endTimeUtc.format(isoFormatter)

        // 동기화 상태 생성 (수동 동기화)
        val syncStatus = SyncStatus(
            siteCode = siteCode,
            syncType = "ORDERS",
            syncMode = "MANUAL",  // 수동 동기화
            status = "RUNNING",
            startDate = startDateTime.format(displayFormatter),
            endDate = endDateTime.format(displayFormatter)
        )
        syncStatusMapper.insert(syncStatus)
        val statusId = syncStatus.id ?: throw IllegalStateException("Failed to create sync status")

        var syncedCount = 0
        var failedCount = 0
        var totalCount = 0

        try {
            // 먼저 카테고리 동기화
            logger.info("Syncing categories...")
            syncCategories(siteCode, token.accessToken, unitCode)

            // 주문 목록 페이징 조회
            var page = 1
            var hasMore = true

            while (hasMore) {
                logger.info("Fetching orders page: {}", page)
                val ordersResponse = imwebApiService.getOrders(
                    accessToken = token.accessToken,
                    unitCode = unitCode,
                    page = page,
                    limit = PAGE_SIZE,
                    startWtime = startWtimeIso,
                    endWtime = endWtimeIso
                )

                val orderList = ordersResponse.data?.list ?: emptyList()
                val totalPages = ordersResponse.data?.totalPage ?: 1

                if (page == 1) {
                    totalCount = ordersResponse.data?.totalCount ?: 0
                    syncStatusMapper.updateStatus(statusId, "RUNNING", 0, 0)
                }

                logger.info("Processing {} orders (page {}/{})", orderList.size, page, totalPages)

                // 각 주문 처리
                for (order in orderList) {
                    val firstProdNo = order.sections?.firstOrNull()
                        ?.sectionItems?.firstOrNull()
                        ?.productInfo?.prodNo

                    // ✅ 정책2 적용
                    if (!isSyncTarget(siteCode, firstProdNo)) {
                        logger.debug("Skipping order {} - prodNo {} is not in sync target", order.orderNo, firstProdNo)
                        continue
                    }

                    try {
                        val syncOrder = processOrder(
                            order = order,
                            siteCode = siteCode,
                            unitCode = unitCode,
                            accessToken = token.accessToken
                        )
                        syncOrderMapper.upsert(syncOrder)

                        val savedOrder = syncOrderMapper.findBySiteCodeAndOrderNo(siteCode, order.orderNo ?: 0L)
                        if (savedOrder?.id != null) {
                            val productCategories = mutableSetOf<String>()
                            for (section in order.sections ?: emptyList()) {
                                for (item in section.sectionItems ?: emptyList()) {
                                    val prodNo = item.productInfo?.prodNo
                                    if (prodNo != null) {
                                        try {
                                            val prodDetail = imwebApiService.getProductDetail(token.accessToken, unitCode, prodNo)
                                            prodDetail.data?.categories?.let { cats ->
                                                productCategories.addAll(cats)
                                            }
                                        } catch (e: Exception) {
                                            logger.debug("Failed to get categories for prodNo {}: {}", prodNo, e.message)
                                        }
                                    }
                                }
                            }
                            processOrderCategories(savedOrder.id, siteCode, productCategories)
                        }

                        syncedCount++
                        logger.debug("Synced order: {}", order.orderNo)
                    } catch (e: Exception) {
                        failedCount++
                        logger.error("Failed to sync order {}: {}", order.orderNo, e.message)
                    }

                    if ((syncedCount + failedCount) % 10 == 0) {
                        syncStatusMapper.updateStatus(statusId, "RUNNING", syncedCount, failedCount)
                    }
                }

                hasMore = page < totalPages
                page++
                delay(100)
            }

            syncStatusMapper.complete(statusId, "COMPLETED", syncedCount, failedCount, null)
            logger.info("=== Order Sync Completed: synced={}, failed={} ===", syncedCount, failedCount)

        } catch (e: Exception) {
            logger.error("Order sync failed: {}", e.message, e)
            syncStatusMapper.complete(statusId, "FAILED", syncedCount, failedCount, e.message)
            throw e
        }

        return syncStatusMapper.findLatestBySiteCodeAndType(siteCode, "ORDERS")
            ?: throw IllegalStateException("Failed to get sync status")
    }

    /**
     * 주문 동기화 실행 (SSE 진행상황 스트림 반환)
     */
    fun syncOrdersWithProgress(siteCode: String, days: Int = SYNC_DAYS): Flow<SyncProgressEvent> = flow {
        logger.info("=== Starting Order Sync with Progress for siteCode: {} ===", siteCode)

        val running = syncStatusMapper.findRunningBySiteCode(siteCode)
        if (running != null) {
            logger.warn("Sync already running for siteCode: {}", siteCode)
            emit(SyncProgressEvent(status = "FAILED", message = "이미 동기화가 진행 중입니다.", progress = 0))
            return@flow
        }

        val token = try {
            storeService.getOAuthToken(siteCode)
                ?: throw IllegalStateException("OAuth token not found for siteCode: $siteCode")
        } catch (e: Exception) {
            emit(SyncProgressEvent(status = "FAILED", message = "인증 토큰을 찾을 수 없습니다: ${e.message}", progress = 0))
            return@flow
        }

        val store = try {
            storeService.getStore(siteCode)
                ?: throw IllegalStateException("Store not found for siteCode: $siteCode")
        } catch (e: Exception) {
            emit(SyncProgressEvent(status = "FAILED", message = "스토어 정보를 찾을 수 없습니다: ${e.message}", progress = 0))
            return@flow
        }

        val unitCode = store.unitCode ?: run {
            emit(SyncProgressEvent(status = "FAILED", message = "UnitCode가 설정되지 않았습니다.", progress = 0))
            return@flow
        }

        val now = LocalDateTime.now(KST_ZONE)
        val endDateTime = now
        val startDateTime = now.minusDays(days.toLong())

        val displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

        val startTimeUtc = startDateTime.minusHours(9)
        val endTimeUtc = endDateTime.minusHours(9)
        val startWtimeIso = startTimeUtc.format(isoFormatter)
        val endWtimeIso = endTimeUtc.format(isoFormatter)

        val syncStatus = SyncStatus(
            siteCode = siteCode,
            syncType = "ORDERS",
            syncMode = "MANUAL",
            status = "RUNNING",
            startDate = startDateTime.format(displayFormatter),
            endDate = endDateTime.format(displayFormatter)
        )
        syncStatusMapper.insert(syncStatus)
        val statusId = syncStatus.id ?: run {
            emit(SyncProgressEvent(status = "FAILED", message = "동기화 상태 생성에 실패했습니다.", progress = 0))
            return@flow
        }

        var syncedCount = 0
        var failedCount = 0
        var totalCount = 0
        var newRecords = 0
        var updatedRecords = 0

        try {
            emit(SyncProgressEvent(status = "IN_PROGRESS", message = "카테고리 정보 동기화 중...", progress = 1))

            logger.info("Syncing categories...")
            syncCategories(siteCode, token.accessToken, unitCode)

            emit(SyncProgressEvent(status = "IN_PROGRESS", message = "주문 목록 조회 중...", progress = 5))

            var page = 1
            var hasMore = true

            while (hasMore) {
                logger.info("Fetching orders page: {}", page)
                val ordersResponse = imwebApiService.getOrders(
                    accessToken = token.accessToken,
                    unitCode = unitCode,
                    page = page,
                    limit = PAGE_SIZE,
                    startWtime = startWtimeIso,
                    endWtime = endWtimeIso
                )

                val orderList = ordersResponse.data?.list ?: emptyList()
                val totalPages = ordersResponse.data?.totalPage ?: 1

                if (page == 1) {
                    totalCount = ordersResponse.data?.totalCount ?: 0
                    syncStatusMapper.updateStatus(statusId, "RUNNING", 0, 0)
                    emit(SyncProgressEvent(status = "IN_PROGRESS", message = "총 ${totalCount}건의 주문 동기화 시작", progress = 10))
                }

                logger.info("Processing {} orders (page {}/{})", orderList.size, page, totalPages)

                for (order in orderList) {
                    val firstProdNo = order.sections?.firstOrNull()
                        ?.sectionItems?.firstOrNull()
                        ?.productInfo?.prodNo

                    // ✅ 정책2 적용
                    if (!isSyncTarget(siteCode, firstProdNo)) {
                        logger.debug("Skipping order {} - prodNo {} is not in sync target", order.orderNo, firstProdNo)
                        continue
                    }

                    try {
                        val existingOrder = syncOrderMapper.findBySiteCodeAndOrderNo(siteCode, order.orderNo ?: 0L)
                        val isNew = existingOrder == null

                        val syncOrder = processOrder(
                            order = order,
                            siteCode = siteCode,
                            unitCode = unitCode,
                            accessToken = token.accessToken
                        )
                        syncOrderMapper.upsert(syncOrder)

                        val savedOrder = syncOrderMapper.findBySiteCodeAndOrderNo(siteCode, order.orderNo ?: 0L)
                        if (savedOrder?.id != null) {
                            val productCategories = mutableSetOf<String>()
                            for (section in order.sections ?: emptyList()) {
                                for (item in section.sectionItems ?: emptyList()) {
                                    val prodNo = item.productInfo?.prodNo
                                    if (prodNo != null) {
                                        try {
                                            val prodDetail = imwebApiService.getProductDetail(token.accessToken, unitCode, prodNo)
                                            prodDetail.data?.categories?.let { cats ->
                                                productCategories.addAll(cats)
                                            }
                                        } catch (e: Exception) {
                                            logger.debug("Failed to get categories for prodNo {}: {}", prodNo, e.message)
                                        }
                                    }
                                }
                            }
                            processOrderCategories(savedOrder.id, siteCode, productCategories)
                        }

                        syncedCount++
                        if (isNew) newRecords++ else updatedRecords++
                        logger.debug("Synced order: {}", order.orderNo)
                    } catch (e: Exception) {
                        failedCount++
                        logger.error("Failed to sync order {}: {}", order.orderNo, e.message)
                    }

                    val processedCount = syncedCount + failedCount
                    if (processedCount % 5 == 0 || processedCount == totalCount) {
                        syncStatusMapper.updateStatus(statusId, "RUNNING", syncedCount, failedCount)

                        val progress = if (totalCount > 0) 10 + (processedCount * 85 / totalCount) else 95
                        emit(SyncProgressEvent(status = "IN_PROGRESS", message = "주문 $processedCount / $totalCount 동기화 중...", progress = minOf(progress, 95)))
                    }
                }

                hasMore = page < totalPages
                page++
                delay(100)
            }

            syncStatusMapper.complete(statusId, "COMPLETED", syncedCount, failedCount, null)
            logger.info("=== Order Sync Completed: synced={}, failed={} ===", syncedCount, failedCount)

            emit(
                SyncProgressEvent(
                    status = "COMPLETED",
                    message = "동기화 완료",
                    progress = 100,
                    result = SyncProgressResult(
                        newRecords = newRecords,
                        updatedRecords = updatedRecords,
                        totalRecords = syncedCount,
                        failedRecords = failedCount
                    )
                )
            )

        } catch (e: Exception) {
            logger.error("Order sync failed: {}", e.message, e)
            syncStatusMapper.complete(statusId, "FAILED", syncedCount, failedCount, e.message)

            emit(
                SyncProgressEvent(
                    status = "FAILED",
                    message = "동기화 실패: ${e.message}",
                    progress = 0,
                    result = SyncProgressResult(
                        newRecords = newRecords,
                        updatedRecords = updatedRecords,
                        totalRecords = syncedCount,
                        failedRecords = failedCount
                    )
                )
            )
        }
    }

    /**
     * 개별 주문 처리 - API 호출하여 상세 정보 취합
     */
    private suspend fun processOrder(
        order: ImwebOrder,
        siteCode: String,
        unitCode: String,
        accessToken: String
    ): SyncOrder {
        val orderNo = order.orderNo ?: 0L

        val payment = order.payments?.firstOrNull()

        val section = order.sections?.firstOrNull()
        val delivery = section?.delivery

        val firstItem = section?.sectionItems?.firstOrNull()
        val productInfo = firstItem?.productInfo
        val prodNo = productInfo?.prodNo

        var productDetail: ImwebProduct? = null
        if (prodNo != null) {
            try {
                val response = imwebApiService.getProductDetail(accessToken, unitCode, prodNo)
                productDetail = response.data
            } catch (e: Exception) {
                logger.warn("Failed to get product detail for prodNo {}: {}", prodNo, e.message)
            }
        }

        var memberDetail: ImwebMember? = null
        val memberUid = order.memberUid
        if (!memberUid.isNullOrBlank() && order.isMember == "Y") {
            try {
                val response = imwebApiService.getMemberDetail(accessToken, unitCode, memberUid)
                memberDetail = response.data
            } catch (e: Exception) {
                logger.warn("Failed to get member detail for memberUid {}: {}", memberUid, e.message)
            }
        }

        val socialLoginType = memberDetail?.socialLogin?.let { social ->
            when {
                social.kakaoId != null -> "kakao"
                social.naverId != null -> "naver"
                social.googleId != null -> "google"
                social.facebookId != null -> "facebook"
                social.appleId != null -> "apple"
                social.lineId != null -> "line"
                else -> null
            }
        }

        val optionInfoJson = productInfo?.optionInfo?.let {
            try { objectMapper.writeValueAsString(it) } catch (_: Exception) { null }
        }

        val formDataJson = order.formData?.let {
            try { objectMapper.writeValueAsString(it) } catch (_: Exception) { null }
        }

        val allProductsJson = order.sections?.flatMap { it.sectionItems ?: emptyList() }?.let {
            try { objectMapper.writeValueAsString(it) } catch (_: Exception) { null }
        }

        val parsedOptions = parseOrderOptions(optionInfoJson)

        return SyncOrder(
            siteCode = siteCode,
            unitCode = unitCode,
            orderNo = orderNo,
            orderStatus = order.orderStatus,
            orderType = order.orderType,
            saleChannel = order.saleChannel,
            device = order.device,
            country = order.country,
            currency = order.currency,
            totalPrice = order.totalPrice ?: 0,
            totalPaymentPrice = order.totalPaymentPrice ?: 0,
            totalDeliveryPrice = order.totalDeliveryPrice ?: 0,
            totalDiscountPrice = order.totalDiscountPrice ?: 0,
            ordererName = order.ordererName,
            ordererEmail = order.ordererEmail,
            ordererCall = order.ordererCall,
            isMember = order.isMember,
            memberCode = order.memberCode,
            memberUid = order.memberUid,
            memberGender = memberDetail?.gender,
            memberBirth = memberDetail?.birth,
            memberJoinTime = memberDetail?.joinTime,
            memberPoint = memberDetail?.point,
            memberGrade = memberDetail?.grade,
            memberSocialLogin = socialLoginType,
            memberSmsAgree = memberDetail?.smsAgree,
            memberEmailAgree = memberDetail?.emailAgree,
            paymentNo = payment?.paymentNo,
            paymentStatus = payment?.paymentStatus,
            paymentMethod = payment?.method,
            pgName = payment?.pgName,
            paidPrice = payment?.paidPrice ?: 0,
            paymentCompleteTime = payment?.paymentCompleteTime,
            receiverName = delivery?.receiverName,
            receiverCall = delivery?.receiverCall,
            deliveryZipcode = delivery?.zipcode,
            deliveryAddr1 = delivery?.addr1,
            deliveryAddr2 = delivery?.addr2,
            deliveryCity = delivery?.city,
            deliveryState = delivery?.state,
            deliveryCountry = delivery?.country ?: delivery?.countryName,
            deliveryMemo = delivery?.memo,
            orderSectionStatus = section?.orderSectionStatus,
            deliveryType = section?.deliveryType,
            prodNo = prodNo,
            prodName = productInfo?.prodName,
            prodCode = productDetail?.prodCode,
            prodStatus = productDetail?.prodStatus,
            prodType = productDetail?.prodType,
            itemPrice = productInfo?.itemPrice ?: 0,
            itemQty = firstItem?.qty ?: 1,
            optionInfo = optionInfoJson,
            prodBrand = productDetail?.brand,
            prodEventWords = productDetail?.eventWords,
            prodReviewCount = productDetail?.reviewCount ?: 0,
            prodIsBadgeBest = productDetail?.isBadgeBest,
            prodIsBadgeHot = productDetail?.isBadgeHot,
            prodIsBadgeNew = productDetail?.isBadgeNew,
            prodSimpleContent = productDetail?.simpleContent,
            prodImageUrl = productDetail?.productImages?.firstOrNull(),
            formData = formDataJson,
            allProducts = allProductsJson,
            optGender = parsedOptions.gender,
            optBirthYear = parsedOptions.birthYear,
            optAge = parsedOptions.age,
            optJob = parsedOptions.job,
            optPreferredDate = DateUtils.normalizePreferredDate(parsedOptions.preferredDate),
            orderEventDateDt = DateUtils.parsePreferredDateToDateTime(parsedOptions.preferredDate),
            managementStatus = determineInitialManagementStatus(payment?.paymentStatus, section?.orderSectionStatus),
            regionName = getRegionNameDbFirst(siteCode, prodNo), // ✅ DB 우선
            orderTime = parseUtcToKst(order.wtime),
            adminUrl = order.adminUrl
        )
    }

    /**
     * 카테고리 동기화
     */
    private suspend fun syncCategories(siteCode: String, accessToken: String, unitCode: String) {
        try {
            val response = imwebApiService.getCategories(accessToken, unitCode)
            val categories = response.data ?: emptyList()

            fun processCategory(category: ImwebCategory, parentCode: String?) {
                val syncCategory = SyncCategory(
                    siteCode = siteCode,
                    categoryCode = category.categoryCode ?: return,
                    name = category.name,
                    parentCode = parentCode
                )
                syncCategoryMapper.upsert(syncCategory)

                category.children?.forEach { child ->
                    processCategory(child, category.categoryCode)
                }
            }

            categories.forEach { processCategory(it, null) }
            logger.info("Synced {} categories", categories.size)

        } catch (e: Exception) {
            logger.error("Failed to sync categories: {}", e.message)
        }
    }

    /**
     * 주문-카테고리 매핑 처리
     */
    private fun processOrderCategories(syncOrderId: Long, siteCode: String, categoryCodes: Set<String>) {
        syncOrderCategoryMapper.deleteBySyncOrderId(syncOrderId)

        categoryCodes.forEach { code ->
            val mapping = SyncOrderCategory(
                syncOrderId = syncOrderId,
                categoryCode = code,
                siteCode = siteCode
            )
            syncOrderCategoryMapper.insert(mapping)
        }

        if (categoryCodes.isNotEmpty()) {
            logger.debug("Mapped {} categories for order {}", categoryCodes.size, syncOrderId)
        }
    }

    /**
     * 수동 주문 생성
     * - order_no는 음수로 생성하여 실제 아임웹 주문과 구분
     * - 기본 관리상태는 "확인필요"
     */
    fun createManualOrder(siteCode: String, request: ManualOrderRequest): ManualOrderResult {
        logger.info("Creating manual order for siteCode: {}, name: {}", siteCode, request.name)

        if (request.name.isBlank()) return ManualOrderResult(false, message = "이름을 입력해주세요.")
        if (request.gender !in listOf("남", "여")) return ManualOrderResult(false, message = "성별을 선택해주세요.")

        val phonePattern = Regex("^01[0-9]{8,9}$")
        if (!phonePattern.matches(request.phone)) {
            return ManualOrderResult(false, message = "전화번호 형식이 올바르지 않습니다. (예: 01012345678)")
        }

        val yearPattern = Regex("^(19|20)\\d{2}$")
        if (!yearPattern.matches(request.birthYear)) {
            return ManualOrderResult(false, message = "출생년도 형식이 올바르지 않습니다. (예: 1990)")
        }

        if (request.preferredDate.isBlank()) {
            return ManualOrderResult(false, message = "참석날짜를 선택해주세요.")
        }

        if (request.prodNo == null) {
            return ManualOrderResult(false, message = "상품을 선택해주세요.")
        }

        try {
            val store = storeService.getStore(siteCode)
            val unitCode = store?.unitCode

            val orderNo = syncOrderMapper.getNextManualOrderNo(siteCode)

            val order = SyncOrder(
                siteCode = siteCode,
                unitCode = unitCode,
                orderNo = orderNo,
                ordererName = request.name,
                ordererCall = request.phone,
                optGender = request.gender,
                optBirthYear = request.birthYear,
                optJob = request.job,
                optPreferredDate = DateUtils.normalizePreferredDate(request.preferredDate),
                orderEventDateDt = DateUtils.parsePreferredDateToDateTime(request.preferredDate),
                prodNo = request.prodNo,
                // ✅ createManualOrder도 DB 우선 통일
                regionName = request.regionName ?: getRegionNameDbFirst(siteCode, request.prodNo),
                managementStatus = "확인필요",
                paymentStatus = "MANUAL_ORDER"
            )

            val result = syncOrderMapper.insertManualOrder(order)

            return if (result > 0) {
                logger.info("Manual order created: orderNo={}, id={}", orderNo, order.id)
                ManualOrderResult(
                    success = true,
                    orderId = order.id,
                    orderNo = orderNo,
                    message = "주문이 생성되었습니다."
                )
            } else {
                ManualOrderResult(false, message = "주문 생성에 실패했습니다.")
            }

        } catch (e: Exception) {
            logger.error("Failed to create manual order: {}", e.message, e)
            return ManualOrderResult(false, message = "주문 생성 중 오류가 발생했습니다: ${e.message}")
        }
    }

    /**
     * 동기화 데이터 전체 초기화
     * - 연관 테이블 순서대로 삭제 (FK 참조 순서 고려)
     * @return 삭제 결과 정보
     */
    fun resetAllSyncData(siteCode: String): SyncResetResult {
        logger.info("=== Starting Sync Data Reset for siteCode: {} ===", siteCode)

        val running = syncStatusMapper.findRunningBySiteCode(siteCode)
        if (running != null) {
            throw IllegalStateException("동기화가 진행 중입니다. 완료 후 다시 시도해주세요.")
        }

        var statusHistoryDeleted = 0
        var orderCategoryDeleted = 0
        var orderDeleted = 0
        var categoryDeleted = 0
        var statusDeleted = 0

        try {
            statusHistoryDeleted = syncOrderStatusHistoryMapper.deleteAllBySiteCode(siteCode)
            logger.info("Deleted {} status history records", statusHistoryDeleted)

            orderCategoryDeleted = syncOrderCategoryMapper.deleteAllBySiteCode(siteCode)
            logger.info("Deleted {} order-category mappings", orderCategoryDeleted)

            orderDeleted = syncOrderMapper.deleteAllBySiteCode(siteCode)
            logger.info("Deleted {} orders", orderDeleted)

            categoryDeleted = syncCategoryMapper.deleteAllBySiteCode(siteCode)
            logger.info("Deleted {} categories", categoryDeleted)

            statusDeleted = syncStatusMapper.deleteAllBySiteCode(siteCode)
            logger.info("Deleted {} sync status records", statusDeleted)

            logger.info("=== Sync Data Reset Completed ===")

            return SyncResetResult(
                success = true,
                statusHistoryDeleted = statusHistoryDeleted,
                orderCategoryDeleted = orderCategoryDeleted,
                orderDeleted = orderDeleted,
                categoryDeleted = categoryDeleted,
                statusDeleted = statusDeleted,
                message = "초기화가 완료되었습니다."
            )
        } catch (e: Exception) {
            logger.error("Failed to reset sync data: {}", e.message, e)
            return SyncResetResult(
                success = false,
                statusHistoryDeleted = statusHistoryDeleted,
                orderCategoryDeleted = orderCategoryDeleted,
                orderDeleted = orderDeleted,
                categoryDeleted = categoryDeleted,
                statusDeleted = statusDeleted,
                message = "초기화 중 오류 발생: ${e.message}"
            )
        }
    }

    /**
     * 매출 합계 조회 (결제완료+확정 상태)
     * @return 매출 합계 정보 (total_sales, male_sales, female_sales, total_count)
     */
    fun getSalesTotal(siteCode: String): Map<String, Any>? {
        return try {
            syncOrderMapper.getSalesTotal(siteCode)
        } catch (e: Exception) {
            logger.error("Failed to get sales total: {}", e.message, e)
            null
        }
    }

    /**
     * 매출 합계 조회 (필터 적용, 확정 상태 기준)
     * @return 매출 합계 정보 (total_sales, male_sales, female_sales, total_count)
     */
    fun getSalesTotalFiltered(siteCode: String, filter: SyncOrderFilterV2): Map<String, Any>? {
        val paymentStatuses = filter.paymentStatuses ?: SyncOrderFilterV2.DEFAULT_PAYMENT_STATUSES
        return try {
            syncOrderMapper.getSalesTotalFiltered(
                siteCode = siteCode,
                keyword = filter.keyword?.ifBlank { null },
                preferredDate = filter.preferredDate?.ifBlank { null },
                managementStatus = filter.managementStatus?.ifBlank { null },
                prodNo = filter.prodNo,
                regionName = filter.regionName?.ifBlank { null },
                paymentStatuses = paymentStatuses,
                paymentMethods = filter.paymentMethods,
                startDate = filter.startDate?.ifBlank { null },
                endDate = filter.endDate?.ifBlank { null }
            )
        } catch (e: Exception) {
            logger.error("Failed to get sales total filtered: {}", e.message, e)
            null
        }
    }

    // =================================================================
    // 전체주문 페이지(tables-all) 전용 메서드
    // =================================================================

    /**
     * 전체주문 페이지용 주문 조회 (성별 분리 없음, 통합 리스트)
     */
    fun getSyncedOrdersFilteredAll(
        siteCode: String,
        filter: SyncOrderFilterAll,
        page: Int,
        size: Int
    ): PagedSyncOrderViewsAll {
        val offset = (page - 1) * size

        val paymentMethods: List<String>? = when (filter.paymentMethodType?.ifBlank { null }) {
            "PG" -> listOf("CARD", "KAKAOPAY")
            "VIRTUAL" -> listOf("VIRTUAL", "BANKTRANSFER")
            else -> null
        }

        val orders = syncOrderMapper.findBySiteCodeFilteredAll(
            siteCode = siteCode,
            keyword = filter.keyword?.ifBlank { null },
            startDate = filter.startDate?.ifBlank { null },
            endDate = filter.endDate?.ifBlank { null },
            paymentStatusFilter = filter.paymentStatusFilter?.ifBlank { null },
            paymentMethods = paymentMethods,
            sortBy = filter.sortBy,
            sortDir = filter.sortDir,
            offset = offset,
            limit = size
        )

        val totalCount = syncOrderMapper.countBySiteCodeFilteredAll(
            siteCode = siteCode,
            keyword = filter.keyword?.ifBlank { null },
            startDate = filter.startDate?.ifBlank { null },
            endDate = filter.endDate?.ifBlank { null },
            paymentStatusFilter = filter.paymentStatusFilter?.ifBlank { null },
            paymentMethods = paymentMethods
        )

        val salesTotalMap = syncOrderMapper.getSalesTotalAll(
            siteCode = siteCode,
            keyword = filter.keyword?.ifBlank { null },
            startDate = filter.startDate?.ifBlank { null },
            endDate = filter.endDate?.ifBlank { null },
            paymentMethods = paymentMethods
        )

        val salesTotal = SalesTotalAll(
            paidTotal = (salesTotalMap?.get("paid_total") as? Number)?.toLong() ?: 0L,
            refundTotal = (salesTotalMap?.get("refund_total") as? Number)?.toLong() ?: 0L
        )

        val orderViews = orders.map { order ->
            val birthYear = order.optBirthYear?.takeIf { it.isNotBlank() }
            val age = birthYear?.let {
                try {
                    java.time.Year.now().value - it.toInt()
                } catch (_: Exception) { null }
            }
            val gender = when (order.optGender) {
                "남" -> "M"
                "여" -> "F"
                else -> order.optGender
            }

            SyncOrderView(
                order = order,
                ordererGender = gender,
                ordererBirthYear = birthYear,
                ordererAge = age
            )
        }

        val totalPages = if (totalCount > 0) ((totalCount + size - 1) / size).toInt() else 0

        return PagedSyncOrderViewsAll(
            orders = orderViews,
            salesTotal = salesTotal,
            page = page,
            size = size,
            totalElements = totalCount,
            totalPages = totalPages
        )
    }
}

/**
 * 초기화 결과 DTO
 */
data class SyncResetResult(
    val success: Boolean,
    val statusHistoryDeleted: Int = 0,
    val orderCategoryDeleted: Int,
    val orderDeleted: Int,
    val categoryDeleted: Int,
    val statusDeleted: Int,
    val message: String
)

/**
 * 수동 주문 생성 요청 DTO
 */
data class ManualOrderRequest(
    val name: String,
    val gender: String,  // "남" or "여"
    val phone: String,
    val birthYear: String,
    val job: String? = null,  // 직업
    val preferredDate: String,
    val prodNo: Int?,
    val regionName: String?
)

/**
 * 수동 주문 생성 결과 DTO
 */
data class ManualOrderResult(
    val success: Boolean,
    val orderId: Long? = null,
    val orderNo: Long? = null,
    val message: String
)
