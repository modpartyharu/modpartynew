package org.webnori.ordersyncoffice.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.webnori.ordersyncoffice.config.ManagementStatusTransition
import org.webnori.ordersyncoffice.config.RegionConfig
import org.webnori.ordersyncoffice.domain.*
import org.webnori.ordersyncoffice.util.DateUtils
import org.webnori.ordersyncoffice.mapper.*
import java.time.LocalDate
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
                } catch (e: DateTimeParseException) {
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
                    // 첫번째 상품의 prodNo 확인
                    val firstProdNo = order.sections?.firstOrNull()
                        ?.sectionItems?.firstOrNull()
                        ?.productInfo?.prodNo

                    // 동기화 대상 상품코드가 아니면 스킵
                    if (!isValidProductCode(firstProdNo)) {
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

                        // 카테고리 매핑 처리 - 상품 상세에서 카테고리 가져오기
                        val savedOrder = syncOrderMapper.findBySiteCodeAndOrderNo(siteCode, order.orderNo ?: 0L)
                        if (savedOrder?.id != null) {
                            // 각 상품의 카테고리 수집
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

                    // 진행상황 업데이트 (10건마다)
                    if ((syncedCount + failedCount) % 10 == 0) {
                        syncStatusMapper.updateStatus(statusId, "RUNNING", syncedCount, failedCount)
                    }
                }

                hasMore = page < totalPages
                page++

                // API Rate Limit 방지를 위한 딜레이
                delay(100)
            }

            // 완료 처리
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

        // 이미 실행 중인 동기화가 있는지 확인
        val running = syncStatusMapper.findRunningBySiteCode(siteCode)
        if (running != null) {
            logger.warn("Sync already running for siteCode: {}", siteCode)
            emit(SyncProgressEvent(
                status = "FAILED",
                message = "이미 동기화가 진행 중입니다.",
                progress = 0
            ))
            return@flow
        }

        // 토큰 조회
        val token = try {
            storeService.getOAuthToken(siteCode)
                ?: throw IllegalStateException("OAuth token not found for siteCode: $siteCode")
        } catch (e: Exception) {
            emit(SyncProgressEvent(
                status = "FAILED",
                message = "인증 토큰을 찾을 수 없습니다: ${e.message}",
                progress = 0
            ))
            return@flow
        }

        val store = try {
            storeService.getStore(siteCode)
                ?: throw IllegalStateException("Store not found for siteCode: $siteCode")
        } catch (e: Exception) {
            emit(SyncProgressEvent(
                status = "FAILED",
                message = "스토어 정보를 찾을 수 없습니다: ${e.message}",
                progress = 0
            ))
            return@flow
        }

        val unitCode = store.unitCode
            ?: run {
                emit(SyncProgressEvent(
                    status = "FAILED",
                    message = "UnitCode가 설정되지 않았습니다.",
                    progress = 0
                ))
                return@flow
            }

        // 동기화 기간 계산 (선택한 일수 기준)
        val now = LocalDateTime.now(KST_ZONE)
        val endDateTime = now
        val startDateTime = now.minusDays(days.toLong())

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
        val statusId = syncStatus.id ?: run {
            emit(SyncProgressEvent(
                status = "FAILED",
                message = "동기화 상태 생성에 실패했습니다.",
                progress = 0
            ))
            return@flow
        }

        var syncedCount = 0
        var failedCount = 0
        var totalCount = 0
        var newRecords = 0
        var updatedRecords = 0

        try {
            // 초기 진행상황 전송
            emit(SyncProgressEvent(
                status = "IN_PROGRESS",
                message = "카테고리 정보 동기화 중...",
                progress = 1
            ))

            // 먼저 카테고리 동기화
            logger.info("Syncing categories...")
            syncCategories(siteCode, token.accessToken, unitCode)

            emit(SyncProgressEvent(
                status = "IN_PROGRESS",
                message = "주문 목록 조회 중...",
                progress = 5
            ))

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

                    emit(SyncProgressEvent(
                        status = "IN_PROGRESS",
                        message = "총 ${totalCount}건의 주문 동기화 시작",
                        progress = 10
                    ))
                }

                logger.info("Processing {} orders (page {}/{})", orderList.size, page, totalPages)

                // 각 주문 처리
                for (order in orderList) {
                    // 첫번째 상품의 prodNo 확인
                    val firstProdNo = order.sections?.firstOrNull()
                        ?.sectionItems?.firstOrNull()
                        ?.productInfo?.prodNo

                    // 동기화 대상 상품코드가 아니면 스킵
                    if (!isValidProductCode(firstProdNo)) {
                        logger.debug("Skipping order {} - prodNo {} is not in sync target", order.orderNo, firstProdNo)
                        continue
                    }

                    try {
                        // 기존 주문 확인 (upsert 전)
                        val existingOrder = syncOrderMapper.findBySiteCodeAndOrderNo(siteCode, order.orderNo ?: 0L)
                        val isNew = existingOrder == null

                        val syncOrder = processOrder(
                            order = order,
                            siteCode = siteCode,
                            unitCode = unitCode,
                            accessToken = token.accessToken
                        )
                        syncOrderMapper.upsert(syncOrder)

                        // 카테고리 매핑 처리
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

                    // 진행상황 업데이트 (5건마다 또는 처리 완료시)
                    val processedCount = syncedCount + failedCount
                    if (processedCount % 5 == 0 || processedCount == totalCount) {
                        syncStatusMapper.updateStatus(statusId, "RUNNING", syncedCount, failedCount)

                        // 진행률 계산 (10% ~ 95%)
                        val progress = if (totalCount > 0) {
                            10 + (processedCount * 85 / totalCount)
                        } else {
                            95
                        }

                        emit(SyncProgressEvent(
                            status = "IN_PROGRESS",
                            message = "주문 $processedCount / $totalCount 동기화 중...",
                            progress = minOf(progress, 95)
                        ))
                    }
                }

                hasMore = page < totalPages
                page++

                // API Rate Limit 방지를 위한 딜레이
                delay(100)
            }

            // 완료 처리
            syncStatusMapper.complete(statusId, "COMPLETED", syncedCount, failedCount, null)
            logger.info("=== Order Sync Completed: synced={}, failed={} ===", syncedCount, failedCount)

            emit(SyncProgressEvent(
                status = "COMPLETED",
                message = "동기화 완료",
                progress = 100,
                result = SyncProgressResult(
                    newRecords = newRecords,
                    updatedRecords = updatedRecords,
                    totalRecords = syncedCount,
                    failedRecords = failedCount
                )
            ))

        } catch (e: Exception) {
            logger.error("Order sync failed: {}", e.message, e)
            syncStatusMapper.complete(statusId, "FAILED", syncedCount, failedCount, e.message)

            emit(SyncProgressEvent(
                status = "FAILED",
                message = "동기화 실패: ${e.message}",
                progress = 0,
                result = SyncProgressResult(
                    newRecords = newRecords,
                    updatedRecords = updatedRecords,
                    totalRecords = syncedCount,
                    failedRecords = failedCount
                )
            ))
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

        // 첫번째 결제 정보
        val payment = order.payments?.firstOrNull()

        // 첫번째 섹션 정보
        val section = order.sections?.firstOrNull()
        val delivery = section?.delivery

        // 첫번째 상품 정보
        val firstItem = section?.sectionItems?.firstOrNull()
        val productInfo = firstItem?.productInfo
        val prodNo = productInfo?.prodNo

        // 상품 상세 정보 조회
        var productDetail: ImwebProduct? = null
        if (prodNo != null) {
            try {
                val response = imwebApiService.getProductDetail(accessToken, unitCode, prodNo)
                productDetail = response.data
            } catch (e: Exception) {
                logger.warn("Failed to get product detail for prodNo {}: {}", prodNo, e.message)
            }
        }

        // 회원 정보 조회
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

        // 소셜 로그인 타입 추출
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

        // 옵션 정보 JSON 변환
        val optionInfoJson = productInfo?.optionInfo?.let {
            try { objectMapper.writeValueAsString(it) } catch (e: Exception) { null }
        }

        // 폼 데이터 JSON 변환
        val formDataJson = order.formData?.let {
            try { objectMapper.writeValueAsString(it) } catch (e: Exception) { null }
        }

        // 전체 상품 목록 JSON
        val allProductsJson = order.sections?.flatMap { it.sectionItems ?: emptyList() }?.let {
            try { objectMapper.writeValueAsString(it) } catch (e: Exception) { null }
        }

        // optionInfo에서 주문옵션 정보 파싱
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
            regionName = getRegionName(prodNo),
            orderTime = parseUtcToKst(order.wtime),  // UTC -> KST 변환
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

            // 재귀적으로 카테고리 처리
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
        // 기존 매핑 삭제
        syncOrderCategoryMapper.deleteBySyncOrderId(syncOrderId)

        // 카테고리 매핑 저장
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
     * 동기화된 주문 조회 (페이징)
     */
    fun getSyncedOrders(siteCode: String, page: Int, size: Int): PagedSyncOrders {
        val offset = (page - 1) * size
        val orders = syncOrderMapper.findBySiteCodePaged(siteCode, offset, size)
        val totalCount = syncOrderMapper.countBySiteCode(siteCode)
        val totalPages = if (totalCount == 0L) 0 else ((totalCount - 1) / size + 1).toInt()

        return PagedSyncOrders(
            content = orders,
            page = page,
            size = size,
            totalElements = totalCount,
            totalPages = totalPages
        )
    }

    /**
     * 동기화 상태 조회
     */
    fun getSyncStatus(siteCode: String): List<SyncStatus> {
        return syncStatusMapper.findBySiteCode(siteCode)
    }

    /**
     * 동기화 상태 조회 (페이징) - KST 포맷 시간 반환
     * DB에 저장된 시간을 그대로 KST로 표시 (서버 timezone = KST 가정)
     */
    fun getSyncStatusPaged(siteCode: String, page: Int, size: Int): PagedSyncStatusView {
        val offset = (page - 1) * size
        val items = syncStatusMapper.findBySiteCodePaged(siteCode, offset, size)
        val totalCount = syncStatusMapper.countBySiteCode(siteCode)
        val totalPages = if (totalCount == 0L) 0 else ((totalCount - 1) / size + 1).toInt()

        val displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        // SyncStatus -> SyncStatusView 변환 (시간을 문자열로 포맷)
        val viewItems = items.map { status ->
            SyncStatusView(
                id = status.id,
                siteCode = status.siteCode,
                syncType = status.syncType,
                syncMode = status.syncMode,
                status = status.status,
                totalCount = status.totalCount,
                syncedCount = status.syncedCount,
                failedCount = status.failedCount,
                startDate = status.startDate,
                endDate = status.endDate,
                errorMessage = status.errorMessage,
                startedAt = status.startedAt?.format(displayFormatter),
                completedAt = status.completedAt?.format(displayFormatter)
            )
        }

        return PagedSyncStatusView(
            content = viewItems,
            page = page,
            size = size,
            totalElements = totalCount,
            totalPages = totalPages
        )
    }

    /**
     * 최근 동기화 상태 조회
     */
    fun getLatestSyncStatus(siteCode: String, syncType: String): SyncStatus? {
        return syncStatusMapper.findLatestBySiteCodeAndType(siteCode, syncType)
    }

    /**
     * 현재 실행 중인 동기화 조회
     */
    fun getRunningSyncStatus(siteCode: String): SyncStatus? {
        return syncStatusMapper.findRunningBySiteCode(siteCode)
    }

    /**
     * Stale RUNNING 상태 정리 (5분 이상 경과한 RUNNING 상태를 FAILED로 처리)
     * @return 정리된 건수
     */
    fun cleanupStaleRunningStatus(siteCode: String, minutesThreshold: Int = 5): Int {
        val count = syncStatusMapper.failStaleRunning(siteCode, minutesThreshold)
        if (count > 0) {
            logger.info("Cleaned up {} stale RUNNING sync status(es) for siteCode: {}", count, siteCode)
        }
        return count
    }

    /**
     * 동기화된 카테고리 조회
     */
    fun getSyncedCategories(siteCode: String): List<SyncCategory> {
        return syncCategoryMapper.findBySiteCode(siteCode)
    }

    /**
     * 필터링된 동기화 주문 조회 (페이징 + 카테고리명 포함)
     */
    fun getSyncedOrdersFiltered(siteCode: String, filter: SyncOrderFilter, page: Int, size: Int): PagedSyncOrderViews {
        val offset = (page - 1) * size

        val orders = syncOrderMapper.findBySiteCodeFiltered(
            siteCode = siteCode,
            categoryCode = filter.categoryCode,
            paymentMethod = filter.paymentMethod,
            startDate = filter.startDate,
            endDate = filter.endDate,
            regionName = filter.regionName,
            managementStatus = filter.managementStatus,
            prodNo = filter.prodNo,
            searchPhone = filter.searchPhone,
            searchName = filter.searchName,
            sortBy = filter.sortBy,
            sortDir = filter.sortDir,
            offset = offset,
            limit = size
        )

        val totalCount = syncOrderMapper.countBySiteCodeFiltered(
            siteCode = siteCode,
            categoryCode = filter.categoryCode,
            paymentMethod = filter.paymentMethod,
            startDate = filter.startDate,
            endDate = filter.endDate,
            regionName = filter.regionName,
            managementStatus = filter.managementStatus,
            prodNo = filter.prodNo,
            searchPhone = filter.searchPhone,
            searchName = filter.searchName
        )

        val totalPaidPrice = syncOrderMapper.sumPaidPriceFiltered(
            siteCode = siteCode,
            categoryCode = filter.categoryCode,
            paymentMethod = filter.paymentMethod,
            startDate = filter.startDate,
            endDate = filter.endDate,
            regionName = filter.regionName,
            managementStatus = filter.managementStatus,
            prodNo = filter.prodNo,
            searchPhone = filter.searchPhone,
            searchName = filter.searchName
        )

        val totalPages = if (totalCount == 0L) 0 else ((totalCount - 1) / size + 1).toInt()

        // 각 주문에 카테고리명과 옵션 정보 추가
        val orderViews = orders.map { order ->
            val categoryNames = order.id?.let { syncOrderMapper.findCategoryNamesByOrderId(it) } ?: emptyList()

            // DB에 저장된 opt 필드 우선 사용, 없으면 member 정보 사용
            val gender = order.optGender?.let {
                when (it) {
                    "남" -> "M"
                    "여" -> "F"
                    else -> it
                }
            } ?: order.memberGender

            val birthYear = order.optBirthYear ?: order.memberBirth?.take(4)
            val age = order.optAge ?: calculateAge(birthYear)

            SyncOrderView(
                order = order,
                categoryNames = categoryNames,
                ordererGender = gender,
                ordererBirthYear = birthYear,
                ordererAge = age
            )
        }

        return PagedSyncOrderViews(
            content = orderViews,
            page = page,
            size = size,
            totalElements = totalCount,
            totalPages = totalPages,
            totalPaidPrice = totalPaidPrice
        )
    }

    /**
     * V2 필터링 + 성별 분리 + 통계 조회
     * - 기본 결제상태 필터: PAYMENT_COMPLETE, PARTIAL_REFUND_COMPLETE, MANUAL_ORDER
     */
    fun getSyncedOrdersFilteredV2(
        siteCode: String,
        filter: SyncOrderFilterV2,
        page: Int,
        size: Int
    ): PagedSyncOrderViewsV2 {
        val offset = (page - 1) * size

        // 결제상태 필터: 기본값 적용 (결제완료, 부분환불완료, 수동추가)
        val paymentStatuses = filter.paymentStatuses ?: SyncOrderFilterV2.DEFAULT_PAYMENT_STATUSES

        // 남성 주문 조회
        val maleOrders = syncOrderMapper.findBySiteCodeFilteredV2(
            siteCode = siteCode,
            keyword = filter.keyword?.ifBlank { null },
            preferredDate = filter.preferredDate?.ifBlank { null },
            managementStatus = filter.managementStatus?.ifBlank { null },
            prodNo = filter.prodNo,
            regionName = filter.regionName?.ifBlank { null },
            gender = "남",
            paymentStatuses = paymentStatuses,
            paymentMethods = filter.paymentMethods,
            startDate = filter.startDate?.ifBlank { null },
            endDate = filter.endDate?.ifBlank { null },
            sortBy = filter.sortBy,
            sortDir = filter.sortDir,
            offset = offset,
            limit = size
        )

        // 여성 주문 조회
        val femaleOrders = syncOrderMapper.findBySiteCodeFilteredV2(
            siteCode = siteCode,
            keyword = filter.keyword?.ifBlank { null },
            preferredDate = filter.preferredDate?.ifBlank { null },
            managementStatus = filter.managementStatus?.ifBlank { null },
            prodNo = filter.prodNo,
            regionName = filter.regionName?.ifBlank { null },
            gender = "여",
            paymentStatuses = paymentStatuses,
            paymentMethods = filter.paymentMethods,
            startDate = filter.startDate?.ifBlank { null },
            endDate = filter.endDate?.ifBlank { null },
            sortBy = filter.sortBy,
            sortDir = filter.sortDir,
            offset = offset,
            limit = size
        )

        // 전체 카운트 (남/여 합계)
        val maleCount = syncOrderMapper.countBySiteCodeFilteredV2(
            siteCode = siteCode,
            keyword = filter.keyword?.ifBlank { null },
            preferredDate = filter.preferredDate?.ifBlank { null },
            managementStatus = filter.managementStatus?.ifBlank { null },
            prodNo = filter.prodNo,
            regionName = filter.regionName?.ifBlank { null },
            gender = "남",
            paymentStatuses = paymentStatuses,
            paymentMethods = filter.paymentMethods,
            startDate = filter.startDate?.ifBlank { null },
            endDate = filter.endDate?.ifBlank { null }
        )
        val femaleCount = syncOrderMapper.countBySiteCodeFilteredV2(
            siteCode = siteCode,
            keyword = filter.keyword?.ifBlank { null },
            preferredDate = filter.preferredDate?.ifBlank { null },
            managementStatus = filter.managementStatus?.ifBlank { null },
            prodNo = filter.prodNo,
            regionName = filter.regionName?.ifBlank { null },
            gender = "여",
            paymentStatuses = paymentStatuses,
            paymentMethods = filter.paymentMethods,
            startDate = filter.startDate?.ifBlank { null },
            endDate = filter.endDate?.ifBlank { null }
        )
        val totalCount = maleCount + femaleCount
        val totalPages = if (totalCount == 0L) 0 else ((totalCount - 1) / size + 1).toInt()

        // 참석 통계 조회
        val stats = getAttendanceStats(siteCode, filter.prodNo, filter.preferredDate, filter.regionName)

        // View 변환
        val maleOrderViews = maleOrders.map { order -> toSyncOrderView(order) }
        val femaleOrderViews = femaleOrders.map { order -> toSyncOrderView(order) }

        return PagedSyncOrderViewsV2(
            maleOrders = maleOrderViews,
            femaleOrders = femaleOrderViews,
            stats = stats,
            page = page,
            size = size,
            totalElements = totalCount,
            totalPages = totalPages
        )
    }

    /**
     * 참석 통계 조회 (성별/상태별 집계)
     */
    fun getAttendanceStats(siteCode: String, prodNo: Int?, preferredDate: String?, regionName: String? = null): AttendanceStats {
        val rawStats = syncOrderMapper.countByGenderAndStatus(
            siteCode = siteCode,
            prodNo = prodNo,
            preferredDate = preferredDate?.ifBlank { null },
            regionName = regionName?.ifBlank { null }
        )

        var maleConfirmed = 0
        var maleWaiting = 0
        var femaleConfirmed = 0
        var femaleWaiting = 0
        var total = 0

        for (row in rawStats) {
            val gender = row["opt_gender"]?.toString()
            val status = row["management_status"]?.toString()
            val cnt = (row["cnt"] as? Number)?.toInt() ?: 0

            total += cnt

            when {
                gender == "남" && status == "확정" -> maleConfirmed = cnt
                gender == "남" && status == "대기" -> maleWaiting = cnt
                gender == "여" && status == "확정" -> femaleConfirmed = cnt
                gender == "여" && status == "대기" -> femaleWaiting = cnt
            }
        }

        return AttendanceStats(
            total = total,
            maleConfirmed = maleConfirmed,
            maleWaiting = maleWaiting,
            femaleConfirmed = femaleConfirmed,
            femaleWaiting = femaleWaiting,
            maleTotal = maleConfirmed + maleWaiting,
            femaleTotal = femaleConfirmed + femaleWaiting
        )
    }

    /**
     * 참석희망날짜 목록 조회 (DB 기반)
     * @deprecated Imweb API에서 직접 로드하는 방식으로 변경됨.
     *             /api/sync/product-options/{prodNo} 또는 /api/sync/region-options/{regionName} 사용 권장.
     * @see SyncController.getProductOptions
     * @see SyncController.getRegionOptions
     */
    @Deprecated("Use Imweb API endpoints instead: /api/sync/product-options or /api/sync/region-options")
    fun getPreferredDates(siteCode: String, prodNo: Int?): List<String> {
        return syncOrderMapper.findDistinctPreferredDates(siteCode, prodNo)
    }

    /**
     * SyncOrder -> SyncOrderView 변환
     */
    private fun toSyncOrderView(order: SyncOrder): SyncOrderView {
        val categoryNames = order.id?.let { syncOrderMapper.findCategoryNamesByOrderId(it) } ?: emptyList()

        val gender = order.optGender?.let {
            when (it) {
                "남" -> "M"
                "여" -> "F"
                else -> it
            }
        } ?: order.memberGender

        val birthYear = order.optBirthYear ?: order.memberBirth?.take(4)
        val age = order.optAge ?: calculateAge(birthYear)

        return SyncOrderView(
            order = order,
            categoryNames = categoryNames,
            ordererGender = gender,
            ordererBirthYear = birthYear,
            ordererAge = age
        )
    }

    /**
     * optionInfo JSON에서 주문옵션 정보 파싱
     * 성별, 출생년도, 나이, 직업_회사, 참여희망날짜
     */
    data class ParsedOrderOptions(
        val gender: String? = null,        // 남/여
        val birthYear: String? = null,     // 1989, 1995 등
        val age: Int? = null,              // 계산된 나이
        val job: String? = null,           // 직업_회사
        val preferredDate: String? = null  // 참여희망날짜
    )

    private fun parseOrderOptions(optionInfo: String?): ParsedOrderOptions {
        if (optionInfo.isNullOrBlank()) return ParsedOrderOptions()

        try {
            val options = objectMapper.readValue(optionInfo, Map::class.java)
            var gender: String? = null
            var birthYear: String? = null
            var job: String? = null
            var preferredDate: String? = null

            options.forEach { (key, value) ->
                val keyStr = key.toString().lowercase()
                val valueStr = value?.toString() ?: ""

                // 성별 파싱
                if (keyStr.contains("성별") || keyStr.contains("gender")) {
                    gender = when {
                        valueStr.contains("남") || valueStr.lowercase().contains("male") -> "남"
                        valueStr.contains("여") || valueStr.lowercase().contains("female") -> "여"
                        else -> valueStr.takeIf { it.isNotBlank() }
                    }
                }

                // 출생년도 파싱 (출생, 생년, 년도, birth 등)
                if (keyStr.contains("출생") || keyStr.contains("생년") || keyStr.contains("년도") || keyStr.contains("birth")) {
                    // YYYY-MM-DD 또는 YYYY 형식에서 연도만 추출
                    val yearMatch = Regex("(19|20)\\d{2}").find(valueStr)
                    if (yearMatch != null) {
                        birthYear = yearMatch.value
                    } else {
                        // 2자리 년도 처리 (90 -> 1990, 00 -> 2000)
                        val twoDigitMatch = Regex("^\\d{2}$").find(valueStr.trim())
                        if (twoDigitMatch != null) {
                            val twoDigit = twoDigitMatch.value.toInt()
                            birthYear = if (twoDigit > 30) "19$twoDigit" else "20${twoDigit.toString().padStart(2, '0')}"
                        }
                    }
                }

                // 직업_회사 파싱
                if (keyStr.contains("직업") || keyStr.contains("회사") || keyStr.contains("job") || keyStr.contains("company")) {
                    job = valueStr.takeIf { it.isNotBlank() }
                }

                // 참여희망날짜 파싱
                if (keyStr.contains("참여") || keyStr.contains("희망") || keyStr.contains("날짜") || keyStr.contains("date")) {
                    preferredDate = valueStr.takeIf { it.isNotBlank() }
                }
            }

            // 나이 계산
            val age = calculateAge(birthYear)

            return ParsedOrderOptions(
                gender = gender,
                birthYear = birthYear,
                age = age,
                job = job,
                preferredDate = preferredDate
            )
        } catch (e: Exception) {
            logger.debug("Failed to parse optionInfo: {}", e.message)
            return ParsedOrderOptions()
        }
    }

    /**
     * optionInfo JSON에서 성별/출생년도 파싱 (기존 호환용)
     */
    private fun parseGenderAndBirthFromOption(optionInfo: String?): Pair<String?, String?> {
        val parsed = parseOrderOptions(optionInfo)
        // 표시용: 남/여 -> M/F 변환
        val genderCode = when (parsed.gender) {
            "남" -> "M"
            "여" -> "F"
            else -> parsed.gender
        }
        return Pair(genderCode, parsed.birthYear)
    }

    /**
     * 결제수단 목록 조회
     */
    fun getPaymentMethods(siteCode: String): List<String> {
        return syncOrderMapper.findDistinctPaymentMethods(siteCode)
    }

    /**
     * 출생년도로 나이 계산
     */
    fun calculateAge(birthYear: String?): Int? {
        if (birthYear.isNullOrBlank()) return null
        return try {
            val year = birthYear.toInt()
            val currentYear = java.time.Year.now().value
            currentYear - year
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 샘플 쿼리 실행 (화이트리스트 기반, SQL Injection 방지)
     */
    fun executeSampleQuery(siteCode: String, table: String, order: String): List<Map<String, Any?>> {
        return syncOrderMapper.executeSampleQuery(siteCode, table, order)
    }

    /**
     * ID로 주문 조회
     */
    fun findOrderById(id: Long): SyncOrder? {
        return syncOrderMapper.findById(id)
    }

    /**
     * 관리상태 업데이트
     */
    fun updateManagementStatus(id: Long, status: String): Int {
        return syncOrderMapper.updateManagementStatus(id, status)
    }

    /**
     * 관리상태 변경 (이력 포함)
     * @return 변경 결과
     */
    fun changeManagementStatus(request: StatusChangeRequest, siteCode: String): StatusChangeResponse {
        try {
            // 기존 주문 조회
            val order = syncOrderMapper.findById(request.orderId)
                ?: return StatusChangeResponse(
                    success = false,
                    orderId = request.orderId,
                    newStatus = request.newStatus,
                    message = "주문을 찾을 수 없습니다."
                )

            val previousStatus = order.managementStatus

            // 상태 변경
            val updatedRows = if (request.newStatus == "이월" && request.carryoverRound != null) {
                syncOrderMapper.updateManagementStatusWithRound(
                    request.orderId,
                    request.newStatus,
                    request.carryoverRound
                )
            } else {
                // 이월이 아닌 경우 carryoverRound는 null로 초기화
                syncOrderMapper.updateManagementStatusWithRound(
                    request.orderId,
                    request.newStatus,
                    if (request.newStatus == "이월") request.carryoverRound else null
                )
            }

            if (updatedRows == 0) {
                return StatusChangeResponse(
                    success = false,
                    orderId = request.orderId,
                    newStatus = request.newStatus,
                    message = "상태 변경에 실패했습니다."
                )
            }

            // 변경 이력 저장
            val history = SyncOrderStatusHistory(
                syncOrderId = request.orderId,
                siteCode = siteCode,
                previousStatus = previousStatus,
                newStatus = request.newStatus,
                carryoverRound = if (request.newStatus == "이월") request.carryoverRound else null,
                changedBy = "admin"  // TODO: 실제 로그인 사용자로 변경
            )
            syncOrderStatusHistoryMapper.insert(history)

            logger.info("Management status changed: orderId={}, {} -> {}",
                request.orderId, previousStatus, request.newStatus)

            // 알림톡 발송은 상태 변경 시 자동 발송하지 않음
            // 체크박스 선택 후 일괄 발송 버튼으로만 발송 가능

            return StatusChangeResponse(
                success = true,
                orderId = request.orderId,
                newStatus = request.newStatus,
                carryoverRound = if (request.newStatus == "이월") request.carryoverRound else null,
                message = "상태가 변경되었습니다."
            )

        } catch (e: Exception) {
            logger.error("Failed to change management status: {}", e.message, e)
            return StatusChangeResponse(
                success = false,
                orderId = request.orderId,
                newStatus = request.newStatus,
                message = "상태 변경 중 오류가 발생했습니다: ${e.message}"
            )
        }
    }

    /**
     * 주문별 상태 변경 이력 조회
     */
    fun getStatusHistory(syncOrderId: Long): List<SyncOrderStatusHistory> {
        return syncOrderStatusHistoryMapper.findBySyncOrderId(syncOrderId)
    }

    /**
     * 주문별 상태 변경 이력 조회 (최대 N개)
     */
    fun getStatusHistoryLimit(syncOrderId: Long, limit: Int = 10): List<SyncOrderStatusHistory> {
        return syncOrderStatusHistoryMapper.findBySyncOrderIdLimit(syncOrderId, limit)
    }

    /**
     * 리얼타임 결제상태 체크 결과
     */
    data class RealtimeCheckResult(
        val checkedCount: Int,
        val skippedCount: Int,
        val updatedOrders: List<SyncOrder>
    )

    /**
     * 주문 목록의 결제상태 리얼타임 체크
     * - 5분 이내 체크한 주문은 스킵
     * - 결제상태가 변경되었으면 DB 업데이트
     * - 결제대기중 → 결제완료로 변경 시 관리상태도 확인필요로 자동 변경
     * - 토큰 동시성 문제: 재시도 로직 적용 (0.5초 대기, 최대 3회)
     */
    suspend fun realtimeCheckPaymentStatus(siteCode: String, orderIds: List<Long>): RealtimeCheckResult {
        logger.info("Starting realtime payment status check for {} orders", orderIds.size)

        // 5분 이내 체크하지 않은 주문만 필터링
        val ordersToCheck = if (orderIds.isEmpty()) {
            emptyList()
        } else {
            syncOrderMapper.findOrdersNeedingRealtimeCheck(siteCode, orderIds)
        }

        val skippedCount = orderIds.size - ordersToCheck.size

        if (ordersToCheck.isEmpty()) {
            logger.info("All orders were recently checked (within 5 minutes)")
            return RealtimeCheckResult(0, skippedCount, emptyList())
        }

        // 스토어 정보 확인
        val store = storeService.getStore(siteCode)
            ?: run {
                logger.error("Store not found: {}", siteCode)
                return RealtimeCheckResult(0, skippedCount, emptyList())
            }

        // 토큰 존재 여부만 확인 (allowRefresh=false로 갱신 없이)
        val initialToken = storeService.getValidAccessToken(siteCode, allowRefresh = false)
            ?: run {
                logger.error("No token for realtime check: {}", siteCode)
                return RealtimeCheckResult(0, skippedCount, emptyList())
            }

        val unitCode = store.unitCode ?: ""
        val updatedOrders = mutableListOf<SyncOrder>()
        var checkedCount = 0

        logger.info("[리얼타임체크] 체크 대상: {}건, 스킵(5분이내): {}건", ordersToCheck.size, skippedCount)

        // 각 주문에 대해 결제상태 체크 (재시도 로직 적용)
        for (order in ordersToCheck) {
            val orderNo = order.orderNo ?: continue

            try {
                // API 호출 시도 로그
                logger.info("[리얼타임체크] API 호출 시도 - 주문번호: {}, 현재상태: {}, 관리상태: {}",
                    orderNo, order.paymentStatus, order.managementStatus)

                // 재시도 로직 적용하여 API 호출
                val orderData = tokenRetryHelper.executeWithRetry(siteCode) { accessToken ->
                    val response = imwebApiService.getOrderDetail(accessToken, unitCode, orderNo.toString())
                    response.data
                }

                if (orderData == null) {
                    logger.warn("[리얼타임체크] API 응답 없음 - 주문번호: {}", orderNo)
                    syncOrderMapper.updateRealtimeCheckTime(order.id!!)
                    checkedCount++
                    continue
                }

                // 결제 정보 추출
                val newPaymentStatus = orderData.payments?.firstOrNull()?.paymentStatus

                logger.info("[리얼타임체크] API 응답 - 주문번호: {}, API결제상태: {}", orderNo, newPaymentStatus)

                // 결제상태가 변경되었는지 확인
                if (newPaymentStatus != null && newPaymentStatus != order.paymentStatus) {
                    logger.info("[리얼타임체크] 결제상태 변경 감지 - 주문번호: {}, {} -> {}",
                        orderNo, order.paymentStatus, newPaymentStatus)

                    // 공통 전이 규칙으로 관리상태 변경 여부 확인 (로깅용)
                    val newManagementStatus = statusTransition.determineStatusTransition(
                        order.managementStatus, newPaymentStatus
                    )
                    if (newManagementStatus != null) {
                        logger.info("[리얼타임체크] 관리상태 자동변경 - 주문번호: {}, {} -> {}",
                            orderNo, order.managementStatus, newManagementStatus)
                    }

                    // 결제상태 및 관리상태 업데이트
                    syncOrderMapper.updatePaymentStatusAndRealtimeCheck(order.id!!, newPaymentStatus)

                    // 업데이트된 주문 정보 조회하여 반환 목록에 추가
                    val updatedOrder = syncOrderMapper.findById(order.id)
                    if (updatedOrder != null) {
                        updatedOrders.add(updatedOrder)
                    }
                } else {
                    // 변경 없음 - 체크 시간만 업데이트
                    syncOrderMapper.updateRealtimeCheckTime(order.id!!)
                }

                checkedCount++

            } catch (e: Exception) {
                // 재시도 3회 후에도 실패
                logger.error("[리얼타임체크] API 호출 최종 실패 - 주문번호: {}, 오류: {}", orderNo, e.message)
                // 실패해도 계속 진행
            }

            // API 호출 간 짧은 딜레이 (부하 방지)
            kotlinx.coroutines.delay(100)
        }

        logger.info("Realtime check completed: {} checked, {} skipped, {} updated",
            checkedCount, skippedCount, updatedOrders.size)

        return RealtimeCheckResult(checkedCount, skippedCount, updatedOrders)
    }

    /**
     * 수동 주문 생성
     * - order_no는 음수로 생성하여 실제 아임웹 주문과 구분
     * - 기본 관리상태는 "확인필요"
     */
    fun createManualOrder(siteCode: String, request: ManualOrderRequest): ManualOrderResult {
        logger.info("Creating manual order for siteCode: {}, name: {}", siteCode, request.name)

        // 유효성 검사
        if (request.name.isBlank()) {
            return ManualOrderResult(false, message = "이름을 입력해주세요.")
        }

        if (request.gender !in listOf("남", "여")) {
            return ManualOrderResult(false, message = "성별을 선택해주세요.")
        }

        // 전화번호 유효성 검사 (01012345678 형식)
        val phonePattern = Regex("^01[0-9]{8,9}$")
        if (!phonePattern.matches(request.phone)) {
            return ManualOrderResult(false, message = "전화번호 형식이 올바르지 않습니다. (예: 01012345678)")
        }

        // 출생년도 유효성 검사 (4자리 숫자)
        val yearPattern = Regex("^(19|20)\\d{2}$")
        if (!yearPattern.matches(request.birthYear)) {
            return ManualOrderResult(false, message = "출생년도 형식이 올바르지 않습니다. (예: 1990)")
        }

        if (request.preferredDate.isBlank()) {
            return ManualOrderResult(false, message = "참석날짜를 선택해주세요.")
        }

        // 상품코드 필수 검사
        if (request.prodNo == null) {
            return ManualOrderResult(false, message = "상품을 선택해주세요.")
        }

        try {
            // 스토어 정보 조회
            val store = storeService.getStore(siteCode)
            val unitCode = store?.unitCode

            // 음수 주문번호 생성
            val orderNo = syncOrderMapper.getNextManualOrderNo(siteCode)

            // 주문 객체 생성
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
                regionName = request.regionName ?: getRegionName(request.prodNo),
                managementStatus = "확인필요",
                paymentStatus = "MANUAL_ORDER"
            )

            // DB 저장
            val result = syncOrderMapper.insertManualOrder(order)

            if (result > 0) {
                logger.info("Manual order created: orderNo={}, id={}", orderNo, order.id)
                return ManualOrderResult(
                    success = true,
                    orderId = order.id,
                    orderNo = orderNo,
                    message = "주문이 생성되었습니다."
                )
            } else {
                return ManualOrderResult(false, message = "주문 생성에 실패했습니다.")
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

        // 진행 중인 동기화가 있는지 확인
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
            // 1. sync_order_status_history 삭제 (FK 참조로 가장 먼저)
            statusHistoryDeleted = syncOrderStatusHistoryMapper.deleteAllBySiteCode(siteCode)
            logger.info("Deleted {} status history records", statusHistoryDeleted)

            // 2. sync_order_categories 삭제
            orderCategoryDeleted = syncOrderCategoryMapper.deleteAllBySiteCode(siteCode)
            logger.info("Deleted {} order-category mappings", orderCategoryDeleted)

            // 3. sync_orders 삭제
            orderDeleted = syncOrderMapper.deleteAllBySiteCode(siteCode)
            logger.info("Deleted {} orders", orderDeleted)

            // 4. sync_categories 삭제
            categoryDeleted = syncCategoryMapper.deleteAllBySiteCode(siteCode)
            logger.info("Deleted {} categories", categoryDeleted)

            // 5. sync_status 삭제 (동기화 이력)
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

        // 결제유형 -> 결제수단 리스트 변환
        val paymentMethods: List<String>? = when (filter.paymentMethodType?.ifBlank { null }) {
            "PG" -> listOf("CARD", "KAKAOPAY")
            "VIRTUAL" -> listOf("VIRTUAL", "BANKTRANSFER")
            else -> null
        }

        // 전체 주문 조회 (성별 분리 없음)
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

        // 카운트 조회
        val totalCount = syncOrderMapper.countBySiteCodeFilteredAll(
            siteCode = siteCode,
            keyword = filter.keyword?.ifBlank { null },
            startDate = filter.startDate?.ifBlank { null },
            endDate = filter.endDate?.ifBlank { null },
            paymentStatusFilter = filter.paymentStatusFilter?.ifBlank { null },
            paymentMethods = paymentMethods
        )

        // 매출 합계 조회 (결제완료/환불완료 분리)
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

        // SyncOrderView 변환
        val orderViews = orders.map { order ->
            val birthYear = order.optBirthYear?.takeIf { it.isNotBlank() }
            val age = birthYear?.let {
                try {
                    java.time.Year.now().value - it.toInt()
                } catch (e: Exception) { null }
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
