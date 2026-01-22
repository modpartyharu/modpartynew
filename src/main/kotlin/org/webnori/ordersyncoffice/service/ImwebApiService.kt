package org.webnori.ordersyncoffice.service

import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.webnori.ordersyncoffice.domain.*
import java.util.*

@Service
class ImwebApiService(
    private val webClient: WebClient,
    @Value("\${imweb.client-id}") private val clientId: String,
    @Value("\${imweb.client-secret}") private val clientSecret: String,
    @Value("\${imweb.redirect-uri}") private val redirectUri: String,
    @Value("\${imweb.api-base-url}") private val apiBaseUrl: String
) {
    private val logger = LoggerFactory.getLogger(ImwebApiService::class.java)

    companion object {
        // 필요한 scope 정의: site-info:write는 필수, 추가로 주문/상품/회원 조회
        const val REQUIRED_SCOPES = "site-info:write order:read product:read member-info:read"
    }

    /**
     * CSRF 방지를 위한 state 토큰 생성
     */
    fun generateStateToken(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * OAuth 인증 URL 생성
     * Imweb API는 camelCase 파라미터명 사용: clientId, redirectUri, responseType
     * forceLogin: true면 prompt=login 추가하여 강제 로그인 요청
     */
    fun buildAuthorizeUrl(siteCode: String, state: String, forceLogin: Boolean = true): String {
        val encodedRedirectUri = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        val encodedScope = java.net.URLEncoder.encode(REQUIRED_SCOPES, "UTF-8")

        var url = "$apiBaseUrl/oauth2/authorize" +
                "?clientId=$clientId" +
                "&redirectUri=$encodedRedirectUri" +
                "&responseType=code" +
                "&scope=$encodedScope" +
                "&state=$state" +
                "&siteCode=$siteCode"

        // 강제 로그인 요청 - 기존 인증 상태 무시하고 항상 로그인 화면 표시
        if (forceLogin) {
            url += "&prompt=login"
        }

        logger.info("=== Build Authorize URL ===")
        logger.info("Site Code: {}", siteCode)
        logger.info("Redirect URI: {}", redirectUri)
        logger.info("Scopes: {}", REQUIRED_SCOPES)
        logger.info("Force Login: {}", forceLogin)
        logger.info("Generated URL: {}", url)

        return url
    }

    /**
     * Authorization Code를 Access Token으로 교환
     * Imweb API: Content-Type: application/x-www-form-urlencoded with camelCase keys
     */
    suspend fun exchangeCodeForToken(code: String): ImwebTokenResponse {
        logger.info("=== Exchange Code for Token ===")
        logger.info("Authorization Code: {}", code)
        logger.info("Redirect URI: {}", redirectUri)

        logger.info("Token Request - clientId: {}, redirectUri: {}", clientId, redirectUri)

        val response = webClient.post()
            .uri("$apiBaseUrl/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("clientId", clientId)
                .with("clientSecret", clientSecret)
                .with("redirectUri", redirectUri)
                .with("code", code)
                .with("grantType", "authorization_code"))
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .doOnNext { body ->
                        logger.info("Token API Raw Response: {}", body)
                    }
                    .flatMap { body ->
                        if (clientResponse.statusCode().isError) {
                            reactor.core.publisher.Mono.error(RuntimeException("Token API failed: ${clientResponse.statusCode()} - $body"))
                        } else {
                            try {
                                val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                                val tokenResponse = objectMapper.readValue(body, ImwebTokenResponse::class.java)
                                reactor.core.publisher.Mono.just(tokenResponse)
                            } catch (e: Exception) {
                                logger.error("Failed to parse token response: {}", e.message)
                                reactor.core.publisher.Mono.error(RuntimeException("JSON decoding error: ${e.message}"))
                            }
                        }
                    }
            }
            .awaitSingle()

        logger.info("Token Response received")
        logger.info("Status Code: {}", response.statusCode)
        val tokenData = response.data
        if (tokenData != null) {
            logger.info("Access Token: {}...", tokenData.accessToken.take(20))
            logger.info("Scopes: {}", tokenData.scope)
        }

        return response
    }

    /**
     * Refresh Token을 사용하여 새 Access Token 발급
     * Imweb API: Content-Type: application/x-www-form-urlencoded with camelCase keys
     */
    suspend fun refreshToken(refreshTokenValue: String): ImwebTokenResponse {
        logger.info("=== Refresh Token ===")

        val response = webClient.post()
            .uri("$apiBaseUrl/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("clientId", clientId)
                .with("clientSecret", clientSecret)
                .with("refreshToken", refreshTokenValue)
                .with("grantType", "refresh_token"))
            .retrieve()
            .awaitBody<ImwebTokenResponse>()

        logger.info("Token refreshed successfully")
        return response
    }

    /**
     * 사이트 정보 조회
     */
    suspend fun getSiteInfo(accessToken: String): ImwebSiteInfoResponse {
        logger.info("=== Get Site Info ===")

        // 먼저 raw response 확인
        val rawResponse = webClient.get()
            .uri("$apiBaseUrl/site-info")
            .header("Authorization", "Bearer $accessToken")
            .retrieve()
            .awaitBody<String>()

        logger.info("Site Info Raw Response: {}", rawResponse)

        val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val response = objectMapper.readValue(rawResponse, ImwebSiteInfoResponse::class.java)

        logger.info("Site Info Response: statusCode={}", response.statusCode)
        response.data?.let {
            logger.info("Site Code: {}", it.siteCode)
            logger.info("Owner UID: {}", it.ownerUid)
            logger.info("Unit List: {}", it.unitList)
        }

        return response
    }

    /**
     * 유닛 정보 조회
     */
    suspend fun getUnitInfo(accessToken: String, unitCode: String): ImwebUnitInfoResponse {
        logger.info("=== Get Unit Info ===")
        logger.info("Unit Code: {}", unitCode)

        return webClient.get()
            .uri("$apiBaseUrl/site-info/unit/$unitCode")
            .header("Authorization", "Bearer $accessToken")
            .retrieve()
            .awaitBody<ImwebUnitInfoResponse>()
    }

    /**
     * 주문 목록 조회
     * Imweb API: page, limit, unitCode (camelCase)
     * orderSectionStatus: PRODUCT_PREPARATION, DELIVERY_PREPARATION, DELIVERY_ING, DELIVERY_COMPLETE,
     *                     CANCEL_REQUEST, CANCEL_COMPLETE, RETURN_REQUEST, RETURN_ING, RETURN_COMPLETE,
     *                     EXCHANGE_REQUEST, EXCHANGE_ING, EXCHANGE_COMPLETE
     * paymentStatus: PAYMENT_PREPARATION, PAYMENT_OVERDUE, PAYMENT_COMPLETE, PARTIAL_REFUND_COMPLETE,
     *                REFUND_COMPLETE, PAYMENT_FAILED, PAYMENT_EXIT, CANCELLED_BEFORE_DEPOSIT,
     *                REFUND_PROCESSING, REFUND_FAILED
     * paymentMethod: BANKTRANSFER, CARD, VIRTUAL, TRANSFER, PHONE, FREE, KAKAOPAY, NAVERPAY,
     *                TOSSPAY, SUBSCRIPTION, PAYCO, SAMSUNGPAY, APPLEPAY, etc.
     */
    suspend fun getOrders(
        accessToken: String,
        unitCode: String,
        page: Int = 1,
        limit: Int = 10,
        startWtime: String? = null,
        endWtime: String? = null,
        startCancelRequestTime: String? = null,
        endCancelRequestTime: String? = null,
        orderSectionStatus: String? = null,
        paymentStatus: String? = null,
        paymentMethod: String? = null,
        country: String? = null
    ): ImwebOrderListResponse {
        logger.info("=== Get Orders ===")
        logger.info("Page: {}, Limit: {}, UnitCode: {}", page, limit, unitCode)

        val uriBuilder = StringBuilder("$apiBaseUrl/orders?page=$page&limit=$limit&unitCode=$unitCode")
        if (!startWtime.isNullOrBlank()) {
            uriBuilder.append("&startWtime=$startWtime")
        }
        if (!endWtime.isNullOrBlank()) {
            uriBuilder.append("&endWtime=$endWtime")
        }
        if (!startCancelRequestTime.isNullOrBlank()) {
            uriBuilder.append("&startCancelRequestTime=$startCancelRequestTime")
        }
        if (!endCancelRequestTime.isNullOrBlank()) {
            uriBuilder.append("&endCancelRequestTime=$endCancelRequestTime")
        }
        if (!orderSectionStatus.isNullOrBlank()) {
            uriBuilder.append("&orderSectionStatus=$orderSectionStatus")
        }
        if (!paymentStatus.isNullOrBlank()) {
            uriBuilder.append("&paymentStatus=$paymentStatus")
        }
        if (!paymentMethod.isNullOrBlank()) {
            uriBuilder.append("&paymentMethod=$paymentMethod")
        }
        if (!country.isNullOrBlank()) {
            uriBuilder.append("&country=$country")
        }

        logger.info("Orders Request URL: {}", uriBuilder.toString())

        val rawResponse = webClient.get()
            .uri(uriBuilder.toString())
            .header("Authorization", "Bearer $accessToken")
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .doOnNext { body ->
                        logger.info("Orders API Status: {}", clientResponse.statusCode())
                        logger.info("Orders API Raw Response: {}", body.take(1000))
                    }
                    .flatMap { body ->
                        if (clientResponse.statusCode().isError) {
                            reactor.core.publisher.Mono.error(RuntimeException("Orders API failed: ${clientResponse.statusCode()} - $body"))
                        } else {
                            reactor.core.publisher.Mono.just(body)
                        }
                    }
            }
            .awaitSingle()

        logger.info("Orders Raw Response: {}", rawResponse.take(500))

        val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val response = objectMapper.readValue(rawResponse, ImwebOrderListResponse::class.java)

        logger.info("Orders Response: statusCode={}", response.statusCode)
        response.data?.let {
            logger.info("Total Count: {}", it.totalCount)
            logger.info("Current Page: {}", it.currentPage)
        }

        return response
    }

    /**
     * 주문 상세 조회 (단건)
     * Imweb API: GET /orders/{orderNo}?unitCode={unitCode}
     */
    suspend fun getOrderDetail(
        accessToken: String,
        unitCode: String,
        orderNo: String
    ): ImwebOrderDetailResponse {
        logger.info("=== Get Order Detail ===")
        logger.info("OrderNo: {}, UnitCode: {}", orderNo, unitCode)

        val uri = "$apiBaseUrl/orders/$orderNo?unitCode=$unitCode"

        val rawResponse = webClient.get()
            .uri(uri)
            .header("Authorization", "Bearer $accessToken")
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .doOnNext { body ->
                        logger.debug("Order Detail API Status: {}", clientResponse.statusCode())
                        logger.debug("Order Detail API Raw Response: {}", body.take(500))
                    }
                    .flatMap { body ->
                        if (clientResponse.statusCode().isError) {
                            reactor.core.publisher.Mono.error(RuntimeException("Order Detail API failed: ${clientResponse.statusCode()} - $body"))
                        } else {
                            reactor.core.publisher.Mono.just(body)
                        }
                    }
            }
            .awaitSingle()

        val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        return objectMapper.readValue(rawResponse, ImwebOrderDetailResponse::class.java)
    }

    /**
     * 상품 목록 조회
     * Imweb API: page, limit, unitCode (필수)
     */
    suspend fun getProducts(
        accessToken: String,
        unitCode: String,
        page: Int = 1,
        limit: Int = 10,
        prodStatus: String? = null
    ): ImwebProductListResponse {
        logger.info("=== Get Products ===")
        logger.info("Page: {}, Limit: {}, UnitCode: {}", page, limit, unitCode)

        val uriBuilder = StringBuilder("$apiBaseUrl/products?page=$page&limit=$limit&unitCode=$unitCode")
        if (!prodStatus.isNullOrBlank()) {
            uriBuilder.append("&prodStatus=$prodStatus")
        }

        logger.info("Products Request URL: {}", uriBuilder.toString())

        val rawResponse = webClient.get()
            .uri(uriBuilder.toString())
            .header("Authorization", "Bearer $accessToken")
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .doOnNext { body ->
                        logger.info("Products API Status: {}", clientResponse.statusCode())
                        logger.info("Products API Raw Response: {}", body.take(1000))
                    }
                    .flatMap { body ->
                        if (clientResponse.statusCode().isError) {
                            reactor.core.publisher.Mono.error(RuntimeException("Products API failed: ${clientResponse.statusCode()} - $body"))
                        } else {
                            reactor.core.publisher.Mono.just(body)
                        }
                    }
            }
            .awaitSingle()

        logger.info("Products Raw Response: {}", rawResponse.take(500))

        val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val response = objectMapper.readValue(rawResponse, ImwebProductListResponse::class.java)

        logger.info("Products Response: statusCode={}", response.statusCode)
        response.data?.let {
            logger.info("Total Count: {}", it.totalCount)
            logger.info("Current Page: {}", it.currentPage)
        }

        return response
    }

    /**
     * 특정 상품 상세 조회
     */
    suspend fun getProduct(accessToken: String, prodNo: String): ImwebProductListResponse {
        logger.info("=== Get Product Detail ===")
        logger.info("Product No: {}", prodNo)

        return webClient.get()
            .uri("$apiBaseUrl/products/$prodNo")
            .header("Authorization", "Bearer $accessToken")
            .retrieve()
            .awaitBody<ImwebProductListResponse>()
    }

    /**
     * 상품 상세 조회 (unitCode 포함)
     */
    suspend fun getProductDetail(accessToken: String, unitCode: String, prodNo: Int): ImwebProductDetailResponse {
        logger.info("=== Get Product Detail ===")
        logger.info("Product No: {}, UnitCode: {}", prodNo, unitCode)

        val rawResponse = webClient.get()
            .uri("$apiBaseUrl/products/$prodNo?unitCode=$unitCode")
            .header("Authorization", "Bearer $accessToken")
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .doOnNext { body ->
                        logger.info("Product Detail API Status: {}", clientResponse.statusCode())
                        logger.info("Product Detail Raw Response: {}", body.take(500))
                    }
                    .flatMap { body ->
                        if (clientResponse.statusCode().isError) {
                            reactor.core.publisher.Mono.error(RuntimeException("Product Detail API failed: ${clientResponse.statusCode()} - $body"))
                        } else {
                            reactor.core.publisher.Mono.just(body)
                        }
                    }
            }
            .awaitSingle()

        val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        return objectMapper.readValue(rawResponse, ImwebProductDetailResponse::class.java)
    }

    /**
     * 회원 상세 조회
     * memberUid: 회원의 고유 식별자 (이메일 등)
     */
    suspend fun getMemberDetail(accessToken: String, unitCode: String, memberUid: String): ImwebMemberDetailResponse {
        logger.info("=== Get Member Detail ===")
        logger.info("MemberUid: {}, UnitCode: {}", memberUid, unitCode)

        val encodedMemberUid = java.net.URLEncoder.encode(memberUid, "UTF-8")

        val rawResponse = webClient.get()
            .uri("$apiBaseUrl/member-info/members/$encodedMemberUid?unitCode=$unitCode")
            .header("Authorization", "Bearer $accessToken")
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .doOnNext { body ->
                        logger.info("Member Detail API Status: {}", clientResponse.statusCode())
                        logger.info("Member Detail Raw Response: {}", body.take(500))
                    }
                    .flatMap { body ->
                        if (clientResponse.statusCode().isError) {
                            reactor.core.publisher.Mono.error(RuntimeException("Member Detail API failed: ${clientResponse.statusCode()} - $body"))
                        } else {
                            reactor.core.publisher.Mono.just(body)
                        }
                    }
            }
            .awaitSingle()

        val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        return objectMapper.readValue(rawResponse, ImwebMemberDetailResponse::class.java)
    }

    /**
     * 상품 옵션 목록 조회
     * API: GET /products/{prodNo}/options?page=1&limit=100&unitCode={unitCode}
     */
    suspend fun getProductOptions(
        accessToken: String,
        unitCode: String,
        prodNo: Int,
        page: Int = 1,
        limit: Int = 100
    ): ImwebProductOptionListResponse {
        logger.info("=== Get Product Options ===")
        logger.info("ProdNo: {}, UnitCode: {}", prodNo, unitCode)

        val uri = "$apiBaseUrl/products/$prodNo/options?page=$page&limit=$limit&unitCode=$unitCode"

        val rawResponse = webClient.get()
            .uri(uri)
            .header("Authorization", "Bearer $accessToken")
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .doOnNext { body ->
                        logger.info("Product Options API Status: {}", clientResponse.statusCode())
                        logger.info("Product Options Raw Response: {}", body.take(500))
                    }
                    .flatMap { body ->
                        if (clientResponse.statusCode().isError) {
                            reactor.core.publisher.Mono.error(RuntimeException("Product Options API failed: ${clientResponse.statusCode()} - $body"))
                        } else {
                            reactor.core.publisher.Mono.just(body)
                        }
                    }
            }
            .awaitSingle()

        val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        return objectMapper.readValue(rawResponse, ImwebProductOptionListResponse::class.java)
    }

    /**
     * 상품 카테고리 목록 조회
     */
    suspend fun getCategories(accessToken: String, unitCode: String): ImwebCategoryListResponse {
        logger.info("=== Get Categories ===")
        logger.info("UnitCode: {}", unitCode)

        val rawResponse = webClient.get()
            .uri("$apiBaseUrl/products/shop-categories?unitCode=$unitCode")
            .header("Authorization", "Bearer $accessToken")
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .doOnNext { body ->
                        logger.info("Categories API Status: {}", clientResponse.statusCode())
                        logger.info("Categories Raw Response: {}", body.take(500))
                    }
                    .flatMap { body ->
                        if (clientResponse.statusCode().isError) {
                            reactor.core.publisher.Mono.error(RuntimeException("Categories API failed: ${clientResponse.statusCode()} - $body"))
                        } else {
                            reactor.core.publisher.Mono.just(body)
                        }
                    }
            }
            .awaitSingle()

        val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        return objectMapper.readValue(rawResponse, ImwebCategoryListResponse::class.java)
    }
}
