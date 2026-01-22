package org.webnori.ordersyncoffice.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.webnori.ordersyncoffice.config.RegionConfig
import org.webnori.ordersyncoffice.domain.*
import org.webnori.ordersyncoffice.util.DateUtils
import org.webnori.ordersyncoffice.mapper.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 스케줄러 기반 자동 동기화 서비스
 *
 * 기능:
 * - 1분 단위로 반복 실행
 * - 스케줄러 On/Off 가능 (기본 Off)
 * - 마지막 동기화 이후 변경된 주문만 증분 동기화
 * - 자동 유형은 1건 이상일 때만 이력 기록
 * - 마지막 자동 스케줄러 작동시간 1개는 항상 기록
 */
@Service
class SyncSchedulerService(
    private val schedulerStatusMapper: SchedulerStatusMapper,
    private val syncStatusMapper: SyncStatusMapper,
    private val syncOrderMapper: SyncOrderMapper,
    private val syncCategoryMapper: SyncCategoryMapper,
    private val syncOrderCategoryMapper: SyncOrderCategoryMapper,
    private val batchTokenService: BatchTokenService,
    private val imwebApiService: ImwebApiService,
    private val storeService: StoreService,
    private val regionConfig: RegionConfig,
    private val productRegionMappingMapper: ProductRegionMappingMapper,
    private val schedulerLogService: SchedulerLogService,
    private val syncService: SyncService  // 공통 유틸 메서드 사용
) {
    private val logger = LoggerFactory.getLogger(SyncSchedulerService::class.java)
    private val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    companion object {
        const val SCHEDULER_TYPE = "ORDER_SYNC"
        const val PAGE_SIZE = 50
        const val OVERLAPPING_SYNC_HOURS = 24L  // 중첩 증분 동기화: 항상 24시간 전부터 조회
        const val DEFAULT_INTERVAL_MINUTES = 10  // 기본 10분 간격

        val UTC_ZONE: ZoneId = ZoneId.of("UTC")
        val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        // 환불 상태로 간주되는 결제상태 (초기 관리상태 결정 시 사용)
        val REFUND_PAYMENT_STATUSES = setOf("REFUND_PROCESSING", "REFUND_COMPLETE")
    }
    private fun getDbRegionName(siteCode: String, prodNo: Int?): String? {
        if (prodNo == null) return null
        val row = productRegionMappingMapper.findActiveBySiteCodeAndProdNo(siteCode, prodNo) ?: return null
        return row["regionName"]?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun hasActiveDbMapping(siteCode: String, prodNo: Int?): Boolean {
        if (prodNo == null) return false
        return productRegionMappingMapper.findActiveBySiteCodeAndProdNo(siteCode, prodNo) != null
    }
    /**
     * 1분마다 실행되어 스케줄러 상태를 체크
     * 설정된 간격(runIntervalMinutes)에 따라 nextRunAt 시간이 지났을 때만 동기화 실행
     */
    @Scheduled(fixedRate = 60000)  // 1분마다 체크
    fun runScheduledSync() {
        logger.debug("Scheduler tick - checking for enabled schedulers")

        // 활성화된 스케줄러 목록 조회
        val enabledSchedulers = schedulerStatusMapper.findAllEnabled()
        if (enabledSchedulers.isEmpty()) {
            logger.debug("No enabled schedulers found")
            return
        }

        val now = LocalDateTime.now()

        for (scheduler in enabledSchedulers) {
            if (scheduler.schedulerType != SCHEDULER_TYPE) continue

            // nextRunAt 확인: 실행 시간이 되었는지 체크
            val nextRunAt = scheduler.nextRunAt
            if (nextRunAt != null && nextRunAt.isAfter(now)) {
                // 아직 실행 시간이 안됨
                logger.debug("Not yet time to run for siteCode: {} (next: {})", scheduler.siteCode, nextRunAt)
                continue
            }

            try {
                logger.info("Running scheduled sync for siteCode: {} (interval: {}min)", scheduler.siteCode, scheduler.runIntervalMinutes)
                schedulerLogService.info("========== 증분 동기화 시작 (${scheduler.runIntervalMinutes}분 간격) ==========", scheduler.siteCode)
                runBlocking {
                    executeIncrementalSync(scheduler.siteCode)
                }
            } catch (e: Exception) {
                logger.error("Scheduled sync failed for siteCode: {} - {}", scheduler.siteCode, e.message)
                schedulerLogService.error("동기화 실패: ${e.message}", scheduler.siteCode)
                schedulerStatusMapper.updateLastError(scheduler.siteCode, SCHEDULER_TYPE, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 증분 동기화 실행
     * - 마지막 완료 시간 이후 변경된 주문만 동기화
     * - 결과가 1건 이상일 때만 이력 기록
     * - 0건이어도 마지막 실행 시간은 기록
     */
    suspend fun executeIncrementalSync(siteCode: String): IncrementalSyncResult {
        logger.info("=== Starting Incremental Sync for siteCode: {} ===", siteCode)

        // 스케줄러 실행 시간 기록
        schedulerStatusMapper.updateLastRun(siteCode, SCHEDULER_TYPE)

        // 배치용 토큰 획득 (재시도 가능하도록 var로 선언)
        schedulerLogService.debug("배치 토큰 확인 중...", siteCode)
        val initialToken = batchTokenService.getValidAccessToken(siteCode)
        if (initialToken == null) {
            val errorMsg = "배치 토큰 획득 실패"
            logger.error(errorMsg)
            schedulerLogService.error(errorMsg, siteCode)
            schedulerStatusMapper.updateLastError(siteCode, SCHEDULER_TYPE, errorMsg)
            return IncrementalSyncResult(success = false, message = errorMsg)
        }
        var accessToken: String = initialToken
        schedulerLogService.debug("배치 토큰 확인 완료", siteCode)

        // 스토어 정보 조회
        val store = storeService.getStore(siteCode)
        if (store == null) {
            val errorMsg = "스토어 정보 조회 실패"
            logger.error(errorMsg)
            schedulerLogService.error(errorMsg, siteCode)
            schedulerStatusMapper.updateLastError(siteCode, SCHEDULER_TYPE, errorMsg)
            return IncrementalSyncResult(success = false, message = errorMsg)
        }

        val unitCode = store.unitCode
        if (unitCode == null) {
            val errorMsg = "UnitCode 없음"
            logger.error(errorMsg)
            schedulerLogService.error(errorMsg, siteCode)
            schedulerStatusMapper.updateLastError(siteCode, SCHEDULER_TYPE, errorMsg)
            return IncrementalSyncResult(success = false, message = errorMsg)
        }
        schedulerLogService.debug("스토어 정보: unitCode=$unitCode", siteCode)

        // 동기화 기간 계산 - 중첩 증분 동기화 방식
        // 항상 현재시간-24h ~ 현재 범위로 조회하여 Create/Read/Update 모두 커버
        val displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        // API용 ISO 8601 포맷
        val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

        // 현재 시간 (UTC) - DB의 CURRENT_TIMESTAMP와 동일한 타임존 사용
        val nowUtc = LocalDateTime.now(UTC_ZONE)

        // 중첩 증분 동기화: 항상 24시간 전부터 조회 (릴레이 방식 대신)
        val startTimeUtc = nowUtc.minusHours(OVERLAPPING_SYNC_HOURS)
        val endTimeUtc = nowUtc
        schedulerLogService.info("중첩 증분 동기화: 최근 ${OVERLAPPING_SYNC_HOURS}시간 범위 조회", siteCode)

        // API 호출용 (이미 UTC이므로 변환 불필요)
        val startWtimeIso = startTimeUtc.format(isoFormatter)
        val endWtimeIso = endTimeUtc.format(isoFormatter)

        // 이력 저장용 (UTC -> KST 변환하여 표시)
        val startTimeKst = startTimeUtc.plusHours(9)
        val endTimeKst = endTimeUtc.plusHours(9)
        val startDateStr = startTimeKst.format(displayFormatter)
        val endDateStr = endTimeKst.format(displayFormatter)

        logger.info("Sync period: {} ~ {} (API: {} ~ {})", startDateStr, endDateStr, startWtimeIso, endWtimeIso)
        schedulerLogService.info("조회 기간(KST): $startDateStr ~ $endDateStr", siteCode)
        schedulerLogService.debug("API 호출 시간(UTC): $startWtimeIso ~ $endWtimeIso", siteCode)

        var syncedCount = 0
        var failedCount = 0
        val processedOrderNos = mutableSetOf<Long>()  // 중복 처리 방지

        // 토큰 재시도 로직: 1순위 리프레시, 2순위 어드민 토큰 복사
        var tokenRetryCount = 0
        val maxTokenRetries = 2

        try {
            // 주문 생성일 기준 조회
            var page = 1
            var hasMore = true
            var totalOrders = 0

            schedulerLogService.info("신규 주문 조회 시작 (주문생성일 기준)...", siteCode)

            while (hasMore) {
                val ordersResponse = try {
                    imwebApiService.getOrders(
                        accessToken = accessToken,
                        unitCode = unitCode,
                        page = page,
                        limit = PAGE_SIZE,
                        startWtime = startWtimeIso,
                        endWtime = endWtimeIso
                    )
                } catch (e: Exception) {
                    // 401 에러 시 토큰 재시도
                    if (e.message?.contains("401") == true && tokenRetryCount < maxTokenRetries) {
                        tokenRetryCount++
                        val retryMethod = if (tokenRetryCount == 1) "어드민 토큰 복사" else "리프레시 토큰"
                        schedulerLogService.warn("토큰 오류 발생, 재시도 $tokenRetryCount/$maxTokenRetries ($retryMethod)", siteCode)

                        val newToken = if (tokenRetryCount == 1) {
                            // 1차 재시도: 어드민 토큰 복사 (UI "토큰갱신"과 동일)
                            batchTokenService.forceCopyFromAdmin(siteCode)
                        } else {
                            // 2차 재시도: 리프레시 토큰
                            batchTokenService.forceRefreshToken(siteCode)
                        }

                        if (newToken != null) {
                            accessToken = newToken
                            schedulerLogService.info("토큰 갱신 성공, 재시도 중...", siteCode)
                            continue  // 같은 페이지 다시 시도
                        } else {
                            schedulerLogService.error("토큰 갱신 실패", siteCode)
                            throw e
                        }
                    }
                    throw e
                }

                val orderList = ordersResponse.data?.list ?: emptyList()
                val totalPages = ordersResponse.data?.totalPage ?: 1
                val totalCount = ordersResponse.data?.totalCount ?: 0

                if (page == 1) {
                    totalOrders = totalCount
                    schedulerLogService.info("API 응답: 총 ${totalCount}건 (${totalPages}페이지)", siteCode)
                }

                logger.debug("Processing {} orders (page {}/{})", orderList.size, page, totalPages)

                for (order in orderList) {
                    val orderNo = order.orderNo ?: continue

                    // 주문 시간이 startTimeKst 이후인지 확인 (정확한 필터링)
                    val orderWtime = order.wtime
                    if (orderWtime != null && isOrderBeforeStartTime(orderWtime, startTimeKst)) {
                        continue
                    }

                    // 동기화 대상 상품코드 확인
                    val firstProdNo = order.sections?.firstOrNull()
                        ?.sectionItems?.firstOrNull()
                        ?.productInfo?.prodNo

                    val isSyncTarget =
                        regionConfig.isValidProductCode(firstProdNo) || hasActiveDbMapping(siteCode, firstProdNo)

                    if (!isSyncTarget) {
                        continue
                    }

                    try {
                        processOrder(order, siteCode, unitCode, accessToken)
                        syncedCount++
                        processedOrderNos.add(orderNo)
                    val regionName = getDbRegionName(siteCode, firstProdNo)
                            ?: regionConfig.getRegionName(firstProdNo)
                            ?: "알수없음"
                        schedulerLogService.debug("주문 #${orderNo} 동기화 완료 (${regionName})", siteCode)
                    } catch (e: Exception) {
                        failedCount++
                        logger.error("Failed to sync order {}: {}", orderNo, e.message)
                        schedulerLogService.warn("주문 #${orderNo} 동기화 실패: ${e.message}", siteCode)
                    }
                }

                hasMore = page < totalPages
                page++

                // API Rate Limit 방지
                kotlinx.coroutines.delay(100)
            }

            // 결과 처리: AUTO 동기화는 항상 마지막 기록만 업데이트 (이력이 너무 많이 쌓이는 것 방지)
            val lastAutoSync = syncStatusMapper.findLastAutoSync(siteCode, "ORDERS")
            if (lastAutoSync != null) {
                // 기존 AUTO 기록이 있으면 업데이트 (성공 건수 포함)
                syncStatusMapper.updateAutoSyncWithCount(
                    lastAutoSync.id!!,
                    startDateStr,
                    endDateStr,
                    syncedCount,
                    failedCount
                )
            } else {
                // 첫 자동 동기화 시에만 새 기록 생성
                val syncStatus = SyncStatus(
                    siteCode = siteCode,
                    syncType = "ORDERS",
                    syncMode = "AUTO",
                    status = "COMPLETED",
                    syncedCount = syncedCount,
                    failedCount = failedCount,
                    startDate = startDateStr,
                    endDate = endDateStr
                )
                syncStatusMapper.insert(syncStatus)
                syncStatusMapper.complete(syncStatus.id!!, "COMPLETED", syncedCount, failedCount, null)
            }

            schedulerStatusMapper.updateLastSuccess(siteCode, SCHEDULER_TYPE)

            if (syncedCount > 0) {
                logger.info("Incremental sync completed: {} synced, {} failed", syncedCount, failedCount)
                schedulerLogService.success("동기화 완료: ${syncedCount}건 성공, ${failedCount}건 실패", siteCode)
            } else {
                logger.info("Incremental sync completed: no new orders")
                schedulerLogService.info("동기화 완료: 새 주문 없음", siteCode)
            }

            schedulerLogService.info("========== 증분 동기화 종료 ==========", siteCode)

            return IncrementalSyncResult(
                success = true,
                syncedCount = syncedCount,
                failedCount = failedCount,
                startTime = startDateStr,
                endTime = endDateStr,
                message = "동기화 완료"
            )

        } catch (e: Exception) {
            logger.error("Incremental sync failed: {}", e.message, e)
            schedulerLogService.error("증분 동기화 실패: ${e.message}", siteCode)
            schedulerStatusMapper.updateLastError(siteCode, SCHEDULER_TYPE, e.message ?: "Unknown error")
            return IncrementalSyncResult(success = false, message = e.message ?: "동기화 실패")
        }
    }

    /**
     * 주문 시간이 시작 시간 이전인지 확인
     * orderWtime: API 응답의 UTC ISO 8601 형식 (예: 2025-04-24T00:00:00.000Z)
     * startTime: KST 기준 LocalDateTime
     */
    private fun isOrderBeforeStartTime(orderWtime: String, startTime: LocalDateTime): Boolean {
        return try {
            // ISO 8601 포맷 파싱 시도 (여러 형식 지원)
            val orderTimeUtc = when {
                orderWtime.endsWith("Z") -> {
                    // 2025-04-24T00:00:00.000Z 또는 2025-04-24T00:00:00Z
                    val cleaned = orderWtime.replace("Z", "").substringBefore(".")
                    LocalDateTime.parse(cleaned, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                }
                orderWtime.contains("T") -> {
                    LocalDateTime.parse(orderWtime.substringBefore("."), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                }
                else -> {
                    LocalDateTime.parse(orderWtime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                }
            }
            // UTC -> KST 변환 (+9시간) 후 비교
            val orderTimeKst = orderTimeUtc.plusHours(9)
            orderTimeKst.isBefore(startTime)
        } catch (e: Exception) {
            logger.debug("Failed to parse orderWtime: {} - {}", orderWtime, e.message)
            false
        }
    }

    /**
     * 개별 주문 처리 (SyncService와 동일한 로직)
     */
    private suspend fun processOrder(
        order: ImwebOrder,
        siteCode: String,
        unitCode: String,
        accessToken: String
    ) {
        val orderNo = order.orderNo ?: return

        // 기존 주문 확인
        val existingOrder = syncOrderMapper.findBySiteCodeAndOrderNo(siteCode, orderNo)

        // 첫번째 결제/섹션/상품 정보
        val payment = order.payments?.firstOrNull()
        val section = order.sections?.firstOrNull()
        val firstItem = section?.sectionItems?.firstOrNull()
        val productInfo = firstItem?.productInfo
        val prodNo = productInfo?.prodNo

        // 상품 상세 정보
        var productDetail: ImwebProduct? = null
        if (prodNo != null) {
            try {
                val response = imwebApiService.getProductDetail(accessToken, unitCode, prodNo)
                productDetail = response.data
            } catch (e: Exception) {
                logger.debug("Failed to get product detail for prodNo {}: {}", prodNo, e.message)
            }
        }

        // 회원 정보
        var memberDetail: ImwebMember? = null
        val memberUid = order.memberUid
        if (!memberUid.isNullOrBlank() && order.isMember == "Y") {
            try {
                val response = imwebApiService.getMemberDetail(accessToken, unitCode, memberUid)
                memberDetail = response.data
            } catch (e: Exception) {
                logger.debug("Failed to get member detail for memberUid {}: {}", memberUid, e.message)
            }
        }

        // 소셜 로그인 타입
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

        // JSON 변환
        val optionInfoJson = productInfo?.optionInfo?.let {
            try { objectMapper.writeValueAsString(it) } catch (e: Exception) { null }
        }
        val formDataJson = order.formData?.let {
            try { objectMapper.writeValueAsString(it) } catch (e: Exception) { null }
        }
        val allProductsJson = order.sections?.flatMap { it.sectionItems ?: emptyList() }?.let {
            try { objectMapper.writeValueAsString(it) } catch (e: Exception) { null }
        }

        // 주문옵션 파싱
        val parsedOptions = parseOrderOptions(optionInfoJson)

        // 관리상태: 기존 값 유지, 없으면 초기값 결정
        val managementStatus = existingOrder?.managementStatus
            ?: determineInitialManagementStatus(payment?.paymentStatus)

        val syncOrder = SyncOrder(
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
            receiverName = section?.delivery?.receiverName,
            receiverCall = section?.delivery?.receiverCall,
            deliveryZipcode = section?.delivery?.zipcode,
            deliveryAddr1 = section?.delivery?.addr1,
            deliveryAddr2 = section?.delivery?.addr2,
            deliveryCity = section?.delivery?.city,
            deliveryState = section?.delivery?.state,
            deliveryCountry = section?.delivery?.country ?: section?.delivery?.countryName,
            deliveryMemo = section?.delivery?.memo,
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
            managementStatus = managementStatus,
            regionName = getDbRegionName(siteCode, prodNo) ?: regionConfig.getRegionName(prodNo),
            orderTime = syncService.parseUtcToKst(order.wtime),
            adminUrl = order.adminUrl
        )

        syncOrderMapper.upsert(syncOrder)

        // 카테고리 매핑
        val savedOrder = syncOrderMapper.findBySiteCodeAndOrderNo(siteCode, orderNo)
        val categories = productDetail?.categories
        if (savedOrder?.id != null && categories != null) {
            syncOrderCategoryMapper.deleteBySyncOrderId(savedOrder.id)
            for (categoryCode in categories) {
                syncOrderCategoryMapper.insert(
                    SyncOrderCategory(
                        syncOrderId = savedOrder.id,
                        categoryCode = categoryCode,
                        siteCode = siteCode
                    )
                )
            }
        }
    }

    /**
     * 결제상태에 따른 관리상태 초기값
     */
    private fun determineInitialManagementStatus(paymentStatus: String?): String {
        return if (paymentStatus != null && REFUND_PAYMENT_STATUSES.contains(paymentStatus)) {
            "환불"
        } else {
            "확인필요"
        }
    }

    /**
     * 주문옵션 파싱
     */
    private data class ParsedOrderOptions(
        val gender: String? = null,
        val birthYear: String? = null,
        val age: Int? = null,
        val job: String? = null,
        val preferredDate: String? = null
    )

    private fun parseOrderOptions(optionInfo: String?): ParsedOrderOptions {
        if (optionInfo.isNullOrBlank()) return ParsedOrderOptions()
        return try {
            val options = objectMapper.readValue(optionInfo, Map::class.java)
            var gender: String? = null
            var birthYear: String? = null
            var job: String? = null
            var preferredDate: String? = null

            options.forEach { (key, value) ->
                val keyStr = key.toString().lowercase()
                val valueStr = value?.toString() ?: ""

                if (keyStr.contains("성별") || keyStr.contains("gender")) {
                    gender = when {
                        valueStr.contains("남") || valueStr.lowercase().contains("male") -> "남"
                        valueStr.contains("여") || valueStr.lowercase().contains("female") -> "여"
                        else -> valueStr.takeIf { it.isNotBlank() }
                    }
                }

                if (keyStr.contains("출생") || keyStr.contains("생년") || keyStr.contains("년도") || keyStr.contains("birth")) {
                    val yearMatch = Regex("(19|20)\\d{2}").find(valueStr)
                    birthYear = yearMatch?.value ?: run {
                        val twoDigitMatch = Regex("^\\d{2}$").find(valueStr.trim())
                        twoDigitMatch?.value?.let { twoDigit ->
                            val num = twoDigit.toInt()
                            if (num > 30) "19$twoDigit" else "20${num.toString().padStart(2, '0')}"
                        }
                    }
                }

                if (keyStr.contains("직업") || keyStr.contains("회사") || keyStr.contains("job")) {
                    job = valueStr.takeIf { it.isNotBlank() }
                }

                if (keyStr.contains("참여") || keyStr.contains("희망") || keyStr.contains("날짜")) {
                    preferredDate = valueStr.takeIf { it.isNotBlank() }
                }
            }

            val age = birthYear?.let {
                try {
                    java.time.Year.now().value - it.toInt()
                } catch (e: Exception) { null }
            }

            ParsedOrderOptions(gender, birthYear, age, job, preferredDate)
        } catch (e: Exception) {
            ParsedOrderOptions()
        }
    }

    // ========== 스케줄러 관리 API ==========

    /**
     * 스케줄러 상태 조회
     * DB에 저장된 시간을 그대로 표시 (서버 timezone = KST 가정)
     */
    fun getSchedulerStatus(siteCode: String): SchedulerStatusResponse {
        val status = schedulerStatusMapper.findBySiteCodeAndType(siteCode, SCHEDULER_TYPE)
        val displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        return if (status != null) {
            SchedulerStatusResponse(
                isEnabled = status.isEnabled,
                lastRunAt = status.lastRunAt?.format(displayFormatter),
                lastSuccessAt = status.lastSuccessAt?.format(displayFormatter),
                lastErrorMessage = status.lastErrorMessage,
                nextRunAt = status.nextRunAt?.format(displayFormatter),
                runIntervalMinutes = status.runIntervalMinutes
            )
        } else {
            SchedulerStatusResponse(isEnabled = false)
        }
    }

    /**
     * 스케줄러 활성화/비활성화
     */
    fun setSchedulerEnabled(siteCode: String, enabled: Boolean): Boolean {
        val existing = schedulerStatusMapper.findBySiteCodeAndType(siteCode, SCHEDULER_TYPE)
        return if (existing != null) {
            schedulerStatusMapper.updateEnabled(siteCode, SCHEDULER_TYPE, enabled, existing.runIntervalMinutes) > 0
        } else {
            val status = SchedulerStatus(
                siteCode = siteCode,
                schedulerType = SCHEDULER_TYPE,
                isEnabled = enabled,
                runIntervalMinutes = DEFAULT_INTERVAL_MINUTES
            )
            schedulerStatusMapper.insert(status) > 0
        }
    }

    /**
     * 스케줄러 활성화 (간격 설정 포함)
     */
    fun setSchedulerEnabledWithInterval(siteCode: String, enabled: Boolean, intervalMinutes: Int): Boolean {
        val validInterval = intervalMinutes.coerceIn(1, 60)  // 1분 ~ 60분 범위 제한
        val existing = schedulerStatusMapper.findBySiteCodeAndType(siteCode, SCHEDULER_TYPE)
        return if (existing != null) {
            schedulerStatusMapper.updateEnabled(siteCode, SCHEDULER_TYPE, enabled, validInterval) > 0
        } else {
            val status = SchedulerStatus(
                siteCode = siteCode,
                schedulerType = SCHEDULER_TYPE,
                isEnabled = enabled,
                runIntervalMinutes = validInterval
            )
            schedulerStatusMapper.insert(status) > 0
        }
    }

    /**
     * 스케줄러 토글 (On <-> Off)
     */
    fun toggleScheduler(siteCode: String): Boolean {
        val current = schedulerStatusMapper.findBySiteCodeAndType(siteCode, SCHEDULER_TYPE)
        val newEnabled = !(current?.isEnabled ?: false)
        return setSchedulerEnabled(siteCode, newEnabled)
    }

    /**
     * 스케줄러 간격만 업데이트
     */
    fun updateSchedulerInterval(siteCode: String, intervalMinutes: Int): Boolean {
        val validInterval = intervalMinutes.coerceIn(1, 60)  // 1분 ~ 60분 범위 제한
        val existing = schedulerStatusMapper.findBySiteCodeAndType(siteCode, SCHEDULER_TYPE)
        return if (existing != null) {
            schedulerStatusMapper.updateEnabled(siteCode, SCHEDULER_TYPE, existing.isEnabled, validInterval) > 0
        } else {
            false
        }
    }
}

/**
 * 증분 동기화 결과 DTO
 */
data class IncrementalSyncResult(
    val success: Boolean,
    val syncedCount: Int = 0,
    val failedCount: Int = 0,
    val startTime: String? = null,
    val endTime: String? = null,
    val message: String
)

