package org.webnori.ordersyncoffice.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.webnori.ordersyncoffice.config.AlimtalkTemplate
import org.webnori.ordersyncoffice.config.TemplateInfo
import org.webnori.ordersyncoffice.domain.AlimtalkSendHistory
import org.webnori.ordersyncoffice.domain.SyncConfig
import org.webnori.ordersyncoffice.domain.SyncOrder
import org.webnori.ordersyncoffice.mapper.AlimtalkSendHistoryMapper
import org.webnori.ordersyncoffice.mapper.SyncConfigMapper
import reactor.core.publisher.Mono

/**
 * 알림톡 발송 서비스
 * - 루나소프트 알림톡 API 연동
 * - 상태 변경 시 자동 발송
 * - 테스트 발송 지원
 */
@Service
class AlimtalkService(
    private val alimtalkTemplate: AlimtalkTemplate,
    private val syncConfigMapper: SyncConfigMapper,
    private val alimtalkSendHistoryMapper: AlimtalkSendHistoryMapper,
    private val environment: Environment,
    webClientBuilder: WebClient.Builder
) {
    private val logger = LoggerFactory.getLogger(AlimtalkService::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val webClient = webClientBuilder.baseUrl(AlimtalkTemplate.API_HOST).build()

    companion object {
        const val CONFIG_KEY_TEST_PHONE = "alimtalk_test_phone"
    }

    /**
     * 운영 환경(prod) 여부 확인
     */
    fun isProductionEnvironment(): Boolean {
        return environment.activeProfiles.contains("prod")
    }

    /**
     * 테스트 전화번호 조회
     */
    fun getTestPhone(siteCode: String): String? {
        return syncConfigMapper.findBySiteCodeAndKey(siteCode, CONFIG_KEY_TEST_PHONE)?.configValue
    }

    /**
     * 테스트 전화번호 설정
     */
    fun setTestPhone(siteCode: String, phone: String?): Boolean {
        val existing = syncConfigMapper.findBySiteCodeAndKey(siteCode, CONFIG_KEY_TEST_PHONE)
        return if (existing != null) {
            syncConfigMapper.update(SyncConfig(
                id = existing.id,
                siteCode = siteCode,
                configKey = CONFIG_KEY_TEST_PHONE,
                configValue = phone?.replace("-", "")?.trim(),
                description = "알림톡 테스트 발송 전화번호"
            )) > 0
        } else {
            syncConfigMapper.insert(SyncConfig(
                siteCode = siteCode,
                configKey = CONFIG_KEY_TEST_PHONE,
                configValue = phone?.replace("-", "")?.trim(),
                description = "알림톡 테스트 발송 전화번호"
            )) > 0
        }
    }

    /**
     * 모든 템플릿 목록 조회
     */
    fun getAllTemplates(): List<TemplateInfo> {
        return alimtalkTemplate.getAllTemplates()
    }

    /**
     * 템플릿 정보 조회
     */
    fun getTemplateInfo(templateId: Int): TemplateInfo? {
        return alimtalkTemplate.getTemplateInfo(templateId)
    }

    /**
     * 주문 상태 변경 시 알림톡 발송
     * - prod 환경: 실제 주문자 전화번호(ordererCall)로 발송
     * - 그 외 환경(dev, local 등): 테스트 전화번호가 설정되어 있을 때만 발송
     * - 발송 대상 상태: 확정, 환불(대기자환불), 환불(참가취소,변심)
     */
    fun sendOnStatusChange(
        siteCode: String,
        order: SyncOrder,
        newStatus: String
    ): AlimtalkSendResult {
        val isProd = isProductionEnvironment()
        logger.info("Checking alimtalk send for status change: orderId={}, newStatus={}, isProd={}",
            order.id, newStatus, isProd)

        // 발송 대상 상태인지 확인
        if (!alimtalkTemplate.shouldSendAlimtalk(newStatus)) {
            logger.debug("Status {} is not a send target", newStatus)
            return AlimtalkSendResult(
                success = false,
                skipped = true,
                message = "발송 대상 상태가 아닙니다: $newStatus"
            )
        }

        // 환경에 따른 수신자 전화번호 결정
        val receiverPhone: String = if (isProd) {
            // 운영 환경: 실제 주문자 전화번호 사용
            val ordererPhone = order.ordererCall?.replace("-", "")?.trim()
            if (ordererPhone.isNullOrBlank()) {
                logger.warn("No orderer phone for order: orderId={}, orderNo={}", order.id, order.orderNo)
                return AlimtalkSendResult(
                    success = false,
                    skipped = true,
                    message = "주문자 연락처 정보가 없습니다."
                )
            }
            logger.info("Production mode: sending to actual orderer phone: {}",
                ordererPhone.take(3) + "****" + ordererPhone.takeLast(4))
            ordererPhone
        } else {
            // 개발/로컬 환경: 테스트 전화번호 사용
            val testPhone = getTestPhone(siteCode)
            if (testPhone.isNullOrBlank()) {
                logger.info("No test phone configured for siteCode: {}", siteCode)
                return AlimtalkSendResult(
                    success = false,
                    skipped = true,
                    message = "테스트 전화번호가 설정되지 않았습니다."
                )
            }
            logger.info("Test mode: sending to test phone instead of orderer")
            testPhone
        }

        // 신규 추가 상품코드(템플릿 미매핑) 체크 - 실발송 없이 경고 로그만 남김
        if (alimtalkTemplate.isUnmappedProduct(order.prodNo)) {
            logger.warn("[알림톡 발송 제한] 신규 추가 상품코드로 실발송하지 않음: orderId={}, orderNo={}, prodNo={}, prodName={}, status={}",
                order.id, order.orderNo, order.prodNo, order.prodName, newStatus)
            return AlimtalkSendResult(
                success = false,
                skipped = true,
                message = "신규 추가 상품코드(${order.prodNo})는 템플릿 미등록으로 발송 제한됨"
            )
        }

        // 템플릿 ID 결정
        val templateId = alimtalkTemplate.getTemplateId(newStatus, order.prodNo)
        if (templateId == null) {
            logger.warn("No template found for status={}, prodNo={}", newStatus, order.prodNo)
            return AlimtalkSendResult(
                success = false,
                skipped = true,
                message = "해당 상품/상태에 대한 템플릿이 없습니다."
            )
        }

        val templateInfo = alimtalkTemplate.getTemplateInfo(templateId)
            ?: return AlimtalkSendResult(
                success = false,
                skipped = true,
                message = "템플릿 정보를 찾을 수 없습니다: $templateId"
            )

        // 메시지 내용 생성 (가변요소 치환)
        val messageContent = if (templateInfo.hasVariables) {
            alimtalkTemplate.buildRefundMessage(
                templateId = templateId,
                customerName = order.ordererName ?: "고객",
                productName = order.prodName ?: "상품"
            )
        } else {
            templateInfo.messageContent
        }

        // 알림톡 발송
        return try {
            val result = sendAlimtalk(
                templateId = templateId,
                receiverPhone = receiverPhone,
                messageContent = messageContent,
                smsContent = templateInfo.smsContent,
                buttons = templateInfo.buttons.map { btn ->
                    AlimtalkButton(urlPc = btn.urlPc, urlMobile = btn.urlMobile)
                }
            )

            // 발송 이력 저장
            saveHistory(
                siteCode = siteCode,
                syncOrderId = order.id,
                orderNo = order.orderNo,
                templateId = templateId,
                templateName = templateInfo.templateName,
                receiverPhone = receiverPhone,
                receiverName = order.ordererName,
                messageContent = messageContent,
                sendType = if (isProd) "STATUS_CHANGE" else "STATUS_CHANGE_TEST",
                triggerStatus = newStatus,
                resultCode = result.code,
                resultMessage = result.message,
                isSuccess = result.success
            )

            result
        } catch (e: Exception) {
            logger.error("Failed to send alimtalk: {}", e.message, e)

            // 실패 이력 저장
            saveHistory(
                siteCode = siteCode,
                syncOrderId = order.id,
                orderNo = order.orderNo,
                templateId = templateId,
                templateName = templateInfo.templateName,
                receiverPhone = receiverPhone,
                receiverName = order.ordererName,
                messageContent = messageContent,
                sendType = if (isProd) "STATUS_CHANGE" else "STATUS_CHANGE_TEST",
                triggerStatus = newStatus,
                resultCode = -1,
                resultMessage = e.message,
                isSuccess = false
            )

            AlimtalkSendResult(
                success = false,
                code = -1,
                message = "발송 실패: ${e.message}"
            )
        }
    }

    /**
     * 테스트 알림톡 발송
     */
    fun sendTest(
        siteCode: String,
        templateId: Int,
        receiverPhone: String,
        variableValues: Map<String, String>? = null
    ): AlimtalkSendResult {
        logger.info("Sending test alimtalk: templateId={}, phone={}", templateId, receiverPhone)

        val templateInfo = alimtalkTemplate.getTemplateInfo(templateId)
            ?: return AlimtalkSendResult(
                success = false,
                message = "템플릿 정보를 찾을 수 없습니다: $templateId"
            )

        // 가변요소 치환
        var messageContent = templateInfo.messageContent
        if (templateInfo.hasVariables && variableValues != null) {
            variableValues.forEach { (key, value) ->
                messageContent = messageContent.replace(key, value)
            }
        }

        return try {
            val result = sendAlimtalk(
                templateId = templateId,
                receiverPhone = receiverPhone.replace("-", "").trim(),
                messageContent = messageContent,
                smsContent = templateInfo.smsContent,
                buttons = templateInfo.buttons.map { btn ->
                    AlimtalkButton(urlPc = btn.urlPc, urlMobile = btn.urlMobile)
                }
            )

            // 발송 이력 저장
            saveHistory(
                siteCode = siteCode,
                syncOrderId = null,
                orderNo = null,
                templateId = templateId,
                templateName = templateInfo.templateName,
                receiverPhone = receiverPhone,
                receiverName = variableValues?.get("#{NAME}"),
                messageContent = messageContent,
                sendType = "MANUAL_TEST",
                triggerStatus = null,
                resultCode = result.code,
                resultMessage = result.message,
                isSuccess = result.success
            )

            result
        } catch (e: Exception) {
            logger.error("Failed to send test alimtalk: {}", e.message, e)

            // 실패 이력 저장
            saveHistory(
                siteCode = siteCode,
                syncOrderId = null,
                orderNo = null,
                templateId = templateId,
                templateName = templateInfo.templateName,
                receiverPhone = receiverPhone,
                receiverName = variableValues?.get("#{NAME}"),
                messageContent = messageContent,
                sendType = "MANUAL_TEST",
                triggerStatus = null,
                resultCode = -1,
                resultMessage = e.message,
                isSuccess = false
            )

            AlimtalkSendResult(
                success = false,
                code = -1,
                message = "발송 실패: ${e.message}"
            )
        }
    }

    /**
     * 알림톡 API 호출
     */
    private fun sendAlimtalk(
        templateId: Int,
        receiverPhone: String,
        messageContent: String,
        smsContent: String,
        buttons: List<AlimtalkButton>
    ): AlimtalkSendResult {
        val request = AlimtalkRequest(
            userid = AlimtalkTemplate.USER_ID,
            apiKey = AlimtalkTemplate.API_KEY,
            templateId = templateId,
            messages = listOf(
                AlimtalkMessage(
                    no = "0",
                    telNum = receiverPhone,
                    msgContent = messageContent,
                    smsContent = smsContent,
                    useSms = "1",
                    btnUrl = buttons.ifEmpty { null }
                )
            )
        )

        logger.debug("Sending alimtalk request: templateId={}, phone={}", templateId, receiverPhone)

        val response = webClient.post()
            .uri(AlimtalkTemplate.API_PATH)
            .header("Content-Type", "application/json")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(AlimtalkResponse::class.java)
            .onErrorResume { e ->
                logger.error("Alimtalk API error: {}", e.message)
                Mono.just(AlimtalkResponse(code = -1, msg = AlimtalkErrorMsg(
                    userid = AlimtalkTemplate.USER_ID,
                    requestedAt = "",
                    messages = listOf(AlimtalkMessageResult(
                        no = "0",
                        telNum = receiverPhone,
                        resultCode = -1,
                        resultMsg = e.message ?: "Unknown error"
                    ))
                )))
            }
            .block()

        return if (response?.code == 0) {
            logger.info("Alimtalk sent successfully: templateId={}, phone={}", templateId, receiverPhone)
            AlimtalkSendResult(
                success = true,
                code = 0,
                message = "발송 성공"
            )
        } else {
            val errorMsg = response?.msg?.messages?.firstOrNull()?.resultMsg ?: "Unknown error"
            logger.warn("Alimtalk send failed: code={}, msg={}", response?.code, errorMsg)
            AlimtalkSendResult(
                success = false,
                code = response?.code ?: -1,
                message = errorMsg
            )
        }
    }

    /**
     * 발송 이력 저장
     */
    private fun saveHistory(
        siteCode: String,
        syncOrderId: Long?,
        orderNo: Long?,
        templateId: Int,
        templateName: String,
        receiverPhone: String,
        receiverName: String?,
        messageContent: String,
        sendType: String,
        triggerStatus: String?,
        resultCode: Int?,
        resultMessage: String?,
        isSuccess: Boolean
    ) {
        try {
            alimtalkSendHistoryMapper.insert(AlimtalkSendHistory(
                siteCode = siteCode,
                syncOrderId = syncOrderId,
                orderNo = orderNo,
                templateId = templateId,
                templateName = templateName,
                receiverPhone = receiverPhone,
                receiverName = receiverName,
                messageContent = messageContent,
                sendType = sendType,
                triggerStatus = triggerStatus,
                resultCode = resultCode,
                resultMessage = resultMessage,
                isSuccess = isSuccess
            ))
        } catch (e: Exception) {
            logger.error("Failed to save alimtalk history: {}", e.message)
        }
    }

    /**
     * 발송 이력 조회 (페이징)
     */
    fun getHistory(siteCode: String, page: Int, size: Int): List<AlimtalkSendHistory> {
        val offset = (page - 1) * size
        return alimtalkSendHistoryMapper.findBySiteCodePaged(siteCode, offset, size)
    }

    /**
     * 발송 이력 전체 개수
     */
    fun countHistory(siteCode: String): Long {
        return alimtalkSendHistoryMapper.countBySiteCode(siteCode)
    }

    /**
     * 주문별 알림톡 일괄 발송
     * - 발송 조건: 관리상태=확정 AND 알림톡 미발송
     * - prod 환경: 실제 주문자 전화번호로 발송
     * - 그 외 환경: 테스트 전화번호로 발송
     * @return 발송 결과 (성공/실패 건수)
     */
    fun sendBatchForOrders(
        siteCode: String,
        orders: List<SyncOrder>,
        updateCallback: (Long, Boolean) -> Unit  // orderId, success -> alimtalk_sent 상태 업데이트 콜백
    ): BatchSendResult {
        val isProd = isProductionEnvironment()
        logger.info("Starting batch alimtalk send for {} orders, isProd={}", orders.size, isProd)

        // 비운영 환경에서는 테스트 전화번호 필수
        val testPhone = if (!isProd) {
            val phone = getTestPhone(siteCode)
            if (phone.isNullOrBlank()) {
                logger.warn("No test phone configured for siteCode: {}", siteCode)
                return BatchSendResult(
                    totalCount = orders.size,
                    successCount = 0,
                    failedCount = 0,
                    skippedCount = orders.size,
                    message = "테스트 전화번호가 설정되지 않았습니다."
                )
            }
            phone
        } else null

        var successCount = 0
        var failedCount = 0
        var skippedCount = 0

        for (order in orders) {
            try {
                // 발송 조건 확인: 확정 상태 + 미발송
                if (order.managementStatus != "확정" || order.alimtalkSent) {
                    logger.debug("Skipping order {} - status={}, alimtalkSent={}",
                        order.id, order.managementStatus, order.alimtalkSent)
                    skippedCount++
                    continue
                }

                // 환경에 따른 수신자 전화번호 결정
                val receiverPhone: String = if (isProd) {
                    val ordererPhone = order.ordererCall?.replace("-", "")?.trim()
                    if (ordererPhone.isNullOrBlank()) {
                        logger.warn("No orderer phone for order: orderId={}, orderNo={}", order.id, order.orderNo)
                        skippedCount++
                        continue
                    }
                    ordererPhone
                } else {
                    testPhone!!  // null이면 이미 위에서 리턴됨
                }

                // 신규 추가 상품코드(템플릿 미매핑) 체크 - 실발송 없이 경고 로그만 남김
                if (alimtalkTemplate.isUnmappedProduct(order.prodNo)) {
                    logger.warn("[알림톡 일괄발송 제한] 신규 추가 상품코드로 실발송하지 않음: orderId={}, orderNo={}, prodNo={}, prodName={}",
                        order.id, order.orderNo, order.prodNo, order.prodName)
                    skippedCount++
                    continue
                }

                // 템플릿 ID 결정 (확정 상태에 대한 템플릿)
                val templateId = alimtalkTemplate.getTemplateId("확정", order.prodNo)
                if (templateId == null) {
                    logger.warn("No template found for prodNo={}", order.prodNo)
                    skippedCount++
                    continue
                }

                val templateInfo = alimtalkTemplate.getTemplateInfo(templateId)
                if (templateInfo == null) {
                    logger.warn("Template info not found: {}", templateId)
                    skippedCount++
                    continue
                }

                // 메시지 내용 생성
                val messageContent = if (templateInfo.hasVariables) {
                    alimtalkTemplate.buildRefundMessage(
                        templateId = templateId,
                        customerName = order.ordererName ?: "고객",
                        productName = order.prodName ?: "상품"
                    )
                } else {
                    templateInfo.messageContent
                }

                // 알림톡 발송
                val result = sendAlimtalk(
                    templateId = templateId,
                    receiverPhone = receiverPhone,
                    messageContent = messageContent,
                    smsContent = templateInfo.smsContent,
                    buttons = templateInfo.buttons.map { btn ->
                        AlimtalkButton(urlPc = btn.urlPc, urlMobile = btn.urlMobile)
                    }
                )

                // 발송 이력 저장
                saveHistory(
                    siteCode = siteCode,
                    syncOrderId = order.id,
                    orderNo = order.orderNo,
                    templateId = templateId,
                    templateName = templateInfo.templateName,
                    receiverPhone = receiverPhone,
                    receiverName = order.ordererName,
                    messageContent = messageContent,
                    sendType = if (isProd) "BATCH_SEND" else "BATCH_SEND_TEST",
                    triggerStatus = "확정",
                    resultCode = result.code,
                    resultMessage = result.message,
                    isSuccess = result.success
                )

                if (result.success) {
                    successCount++
                    order.id?.let { updateCallback(it, true) }
                } else {
                    failedCount++
                }

            } catch (e: Exception) {
                logger.error("Failed to send alimtalk for order {}: {}", order.id, e.message)
                failedCount++
            }
        }

        logger.info("Batch alimtalk completed: success={}, failed={}, skipped={}, isProd={}",
            successCount, failedCount, skippedCount, isProd)

        return BatchSendResult(
            totalCount = orders.size,
            successCount = successCount,
            failedCount = failedCount,
            skippedCount = skippedCount,
            message = if (successCount > 0) "일괄 발송이 완료되었습니다." else "발송된 건이 없습니다."
        )
    }
}

/**
 * 일괄 발송 결과
 */
data class BatchSendResult(
    val totalCount: Int,
    val successCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val message: String
)

/**
 * 알림톡 발송 결과
 */
data class AlimtalkSendResult(
    val success: Boolean,
    val skipped: Boolean = false,
    val code: Int? = null,
    val message: String
)

/**
 * 알림톡 API 요청 DTO
 */
data class AlimtalkRequest(
    val userid: String,
    @JsonProperty("api_key")
    val apiKey: String,
    @JsonProperty("template_id")
    val templateId: Int,
    val messages: List<AlimtalkMessage>
)

data class AlimtalkMessage(
    val no: String,
    @JsonProperty("tel_num")
    val telNum: String,
    @JsonProperty("msg_content")
    val msgContent: String,
    @JsonProperty("sms_content")
    val smsContent: String,
    @JsonProperty("use_sms")
    val useSms: String,
    @JsonProperty("btn_url")
    val btnUrl: List<AlimtalkButton>? = null
)

data class AlimtalkButton(
    @JsonProperty("url_pc")
    val urlPc: String,
    @JsonProperty("url_mobile")
    val urlMobile: String
)

/**
 * 알림톡 API 응답 DTO
 */
data class AlimtalkResponse(
    val code: Int,
    val msg: AlimtalkErrorMsg? = null
)

data class AlimtalkErrorMsg(
    val userid: String,
    @JsonProperty("requested_at")
    val requestedAt: String,
    val messages: List<AlimtalkMessageResult>
)

data class AlimtalkMessageResult(
    val no: String,
    @JsonProperty("tel_num")
    val telNum: String?,
    @JsonProperty("result_code")
    val resultCode: Int,
    @JsonProperty("result_msg")
    val resultMsg: String
)
