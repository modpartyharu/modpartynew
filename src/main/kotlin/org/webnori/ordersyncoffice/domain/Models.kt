package org.webnori.ordersyncoffice.domain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDateTime

/**
 * App Version
 */
data class AppVersion(
    val id: Long? = null,
    val version: String,
    val description: String? = null,
    val createdAt: LocalDateTime? = null
)

/**
 * Store Information (멀티 스토어 지원)
 */
data class StoreInfo(
    val id: Long? = null,
    val siteCode: String,
    val siteName: String? = null,
    val siteUrl: String? = null,
    val unitCode: String? = null,
    val adminEmail: String? = null,
    val companyName: String? = null,
    val representativeName: String? = null,
    val businessNumber: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val isActive: Boolean = true,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)

/**
 * OAuth Token Information (스토어별 토큰 관리)
 */
data class OAuthToken(
    val id: Long? = null,
    val siteCode: String,
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Int? = null,
    val expiresAt: LocalDateTime? = null,
    val refreshToken: String? = null,
    val refreshTokenExpiresAt: LocalDateTime? = null,
    val scopes: String? = null,
    val issuedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)

/**
 * Imweb API Response Models
 */

// Token Response (Imweb API는 wrapper 형태로 응답: {statusCode, data: {...}})
data class ImwebTokenResponse(
    val statusCode: Int? = null,
    val data: ImwebTokenData? = null,
    // 기존 필드도 유지 (직접 응답 시)
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val scope: List<String>? = null
)

data class ImwebTokenData(
    val accessToken: String = "",
    val refreshToken: String? = null,
    val scope: List<String>? = null  // scope는 배열로 반환됨
)

// Site Info Response (Imweb API: {statusCode, data: {...}})
data class ImwebSiteInfoResponse(
    val statusCode: Int = 0,
    val data: ImwebSiteInfo? = null
)

data class ImwebSiteInfo(
    val siteCode: String? = null,
    val firstOrderTime: String? = null,
    val ownerUid: String? = null,
    val unitList: List<ImwebUnitSummary>? = null  // unitList는 객체 배열
)

// Site Info 응답에 포함된 Unit 요약 정보
data class ImwebUnitSummary(
    val unitCode: String? = null,
    val currency: String? = null,
    val name: String? = null
)

// Unit Info Response
data class ImwebUnitInfoResponse(
    val statusCode: Int = 0,
    val data: ImwebUnitInfo? = null
)

data class ImwebUnitInfo(
    val siteCode: String? = null,
    val unitCode: String? = null,
    val langCode: String? = null,
    val currency: String? = null,
    val currencyFormat: String? = null,
    val name: String? = null,
    val isDefault: String? = null,
    val companyName: String? = null,
    val presidentName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address1: String? = null,
    val address2: String? = null,
    val companyRegistrationNo: String? = null,
    val primaryDomain: String? = null
)

// Order Response (Imweb API: camelCase)
data class ImwebOrderListResponse(
    val statusCode: Int = 0,
    val data: ImwebOrderListData? = null
)

// 주문 상세 응답 (단건)
data class ImwebOrderDetailResponse(
    val statusCode: Int = 0,
    val data: ImwebOrder? = null
)

data class ImwebOrderListData(
    val totalCount: Int? = null,
    val totalPage: Int? = null,
    val currentPage: Int? = null,
    val pageSize: Int? = null,
    val list: List<ImwebOrder>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebOrder(
    val orderNo: Long? = null,
    val saleChannel: String? = null,
    val channelOrderNo: String? = null,
    val isMember: String? = null,
    val isSubscription: String? = null,
    val isGift: String? = null,
    val memberCode: String? = null,
    val memberUid: String? = null,
    val orderType: String? = null,
    val orderStatus: String? = null,
    val currency: String? = null,
    val totalPrice: Long? = null,
    val totalPaymentPrice: Long? = null,
    val totalDeliveryPrice: Long? = null,
    val totalDiscountPrice: Long? = null,
    val ordererName: String? = null,
    val ordererEmail: String? = null,
    val ordererCall: String? = null,
    val wtime: String? = null,
    val adminUrl: String? = null,
    val country: String? = null,
    val device: String? = null,
    val payments: List<ImwebPayment>? = null,
    val sections: List<ImwebOrderSection>? = null,
    val formData: List<ImwebFormData>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebOrderSection(
    val orderSectionNo: String? = null,
    val orderSectionCode: String? = null,
    val orderSectionStatus: String? = null,
    val deliveryType: String? = null,
    val sectionItems: List<ImwebSectionItem>? = null,
    val delivery: ImwebDelivery? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebSectionItem(
    val orderSectionItemNo: String? = null,
    val orderItemCode: String? = null,
    val qty: Int? = null,
    val productInfo: ImwebProductInfo? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebProductInfo(
    val prodNo: Int? = null,
    val prodName: String? = null,
    val baseItemPrice: Long? = null,
    val itemPrice: Long? = null,
    val optionInfo: Map<String, String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebDelivery(
    val receiverName: String? = null,
    val receiverCall: String? = null,
    val zipcode: String? = null,
    val addr1: String? = null,
    val addr2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val countryName: String? = null,
    val memo: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebFormData(
    val formConfigCode: String? = null,
    val inputType: String? = null,
    val isRequire: String? = null,
    val title: String? = null,
    val description: String? = null,
    val value: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebPayment(
    val paymentNo: String? = null,
    val pgName: String? = null,
    val isCancel: String? = null,
    val method: String? = null,
    val paymentStatus: String? = null,
    val paidPrice: Long? = null,
    val taxFreePrice: Long? = null,
    val paymentCompleteTime: String? = null
)

// Product Response (Imweb API: camelCase)
data class ImwebProductListResponse(
    val statusCode: Int = 0,
    val data: ImwebProductListData? = null
)

// Product Detail Response (single product)
data class ImwebProductDetailResponse(
    val statusCode: Int = 0,
    val data: ImwebProduct? = null
)

data class ImwebProductListData(
    val totalCount: Int? = null,
    val totalPage: Int? = null,
    val currentPage: Int? = null,
    val pageSize: Int? = null,
    val list: List<ImwebProduct>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebProduct(
    val prodNo: Int? = null,
    val siteCode: String? = null,
    val unitCode: String? = null,
    val prodCode: String? = null,
    val categories: List<String>? = null,
    val showcases: List<String>? = null,
    val name: String? = null,
    val price: Int? = null,
    val priceOrg: Int? = null,
    val priceTax: String? = null,
    val prodStatus: String? = null,
    val prodType: String? = null,
    val simpleContent: String? = null,
    val stockUse: String? = null,
    val stockNoOption: Int? = null,
    val addTime: String? = null,
    val editTime: String? = null,
    val productImages: List<String>? = null,
    val seoTitle: String? = null,
    val seoDescription: String? = null,
    // 추가 필드 (소개팅 앱용)
    val eventWords: String? = null,
    val brand: String? = null,
    val reviewCount: Int? = null,
    val isBadgeNew: String? = null,
    val isBadgeBest: String? = null,
    val isBadgeMd: String? = null,
    val isBadgeHot: String? = null,
    val maximumPurchaseQuantity: Int? = null
)

// Category Response (Imweb API: /products/shop-categories)
data class ImwebCategoryListResponse(
    val statusCode: Int = 0,
    val data: List<ImwebCategory>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebCategory(
    val categoryCode: String? = null,
    val name: String? = null,
    val children: List<ImwebCategory>? = null
)

// 상품 옵션 Response (Imweb API: /products/{prodNo}/options)
data class ImwebProductOptionListResponse(
    val statusCode: Int = 0,
    val data: ImwebProductOptionListData? = null
)

data class ImwebProductOptionListData(
    val totalCount: Int? = null,
    val totalPage: Int? = null,
    val currentPage: Int? = null,
    val pageSize: Int? = null,
    val list: List<ImwebProductOption>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebProductOption(
    val type: String? = null,               // default (선택형) or input (입력형)
    val optionCode: String? = null,
    val name: String? = null,               // 옵션명 (참여희망날짜, 성별 등)
    val optionValueList: List<ImwebProductOptionValue>? = null,
    val isRequire: String? = null           // Y or N
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebProductOptionValue(
    val optionValueCode: String? = null,
    val optionValueName: String? = null,    // 옵션값 (12월 24일(수) - 일반 티켓 등)
    val color: String? = null,
    val imageUrl: String? = null
)

// Member Detail Response (회원 상세 조회)
data class ImwebMemberDetailResponse(
    val statusCode: Int = 0,
    val data: ImwebMember? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebMember(
    val memberCode: String? = null,
    val siteCode: String? = null,
    val unitCode: String? = null,
    val uid: String? = null,
    val name: String? = null,
    val email: String? = null,
    val callnum: String? = null,
    val gender: String? = null,  // M or F
    val homePage: String? = null,
    val birth: String? = null,  // YYYY-MM-DD format
    val address: String? = null,
    val addressDetail: String? = null,
    val addressCountry: String? = null,
    val postCode: String? = null,
    val smsAgree: String? = null,
    val emailAgree: String? = null,
    val thirdPartyAgree: String? = null,
    val joinTime: String? = null,
    val recommendCode: String? = null,
    val recommendTargetCode: String? = null,
    val point: Int? = null,
    val grade: String? = null,
    val group: List<String>? = null,
    val coupon: List<String>? = null,
    val socialLogin: ImwebSocialLogin? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImwebSocialLogin(
    val googleId: String? = null,
    val facebookId: String? = null,
    val kakaoId: String? = null,
    val naverId: String? = null,
    val appleId: String? = null,
    val lineId: String? = null
)

/**
 * Sync Domain Models
 */

// 동기화된 카테고리
data class SyncCategory(
    val id: Long? = null,
    val siteCode: String,
    val categoryCode: String,
    val name: String? = null,
    val parentCode: String? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)

// 동기화된 주문 통합 정보
data class SyncOrder(
    val id: Long? = null,
    val siteCode: String,
    val unitCode: String? = null,

    // 주문 기본 정보
    val orderNo: Long,
    val orderStatus: String? = null,
    val orderType: String? = null,
    val saleChannel: String? = null,
    val device: String? = null,
    val country: String? = null,
    val currency: String? = null,

    // 금액 정보
    val totalPrice: Long = 0,
    val totalPaymentPrice: Long = 0,
    val totalDeliveryPrice: Long = 0,
    val totalDiscountPrice: Long = 0,

    // 주문자 기본 정보
    val ordererName: String? = null,
    val ordererEmail: String? = null,
    val ordererCall: String? = null,
    val isMember: String? = null,
    val memberCode: String? = null,
    val memberUid: String? = null,

    // 회원 상세 정보
    val memberGender: String? = null,
    val memberBirth: String? = null,
    val memberJoinTime: String? = null,
    val memberPoint: Int? = null,
    val memberGrade: String? = null,
    val memberSocialLogin: String? = null,
    val memberSmsAgree: String? = null,
    val memberEmailAgree: String? = null,

    // 결제 정보
    val paymentNo: String? = null,
    val paymentStatus: String? = null,
    val paymentMethod: String? = null,
    val pgName: String? = null,
    val paidPrice: Long = 0,
    val paymentCompleteTime: String? = null,

    // 배송 정보
    val receiverName: String? = null,
    val receiverCall: String? = null,
    val deliveryZipcode: String? = null,
    val deliveryAddr1: String? = null,
    val deliveryAddr2: String? = null,
    val deliveryCity: String? = null,
    val deliveryState: String? = null,
    val deliveryCountry: String? = null,
    val deliveryMemo: String? = null,

    // 주문 섹션 상태
    val orderSectionStatus: String? = null,
    val deliveryType: String? = null,

    // 상품 정보
    val prodNo: Int? = null,
    val prodName: String? = null,
    val prodCode: String? = null,
    val prodStatus: String? = null,
    val prodType: String? = null,
    val itemPrice: Long = 0,
    val itemQty: Int = 1,
    val optionInfo: String? = null,  // JSON

    // 상품 상세 정보
    val prodBrand: String? = null,
    val prodEventWords: String? = null,
    val prodReviewCount: Int = 0,
    val prodIsBadgeBest: String? = null,
    val prodIsBadgeHot: String? = null,
    val prodIsBadgeNew: String? = null,
    val prodSimpleContent: String? = null,
    val prodImageUrl: String? = null,

    // 폼 데이터
    val formData: String? = null,  // JSON
    val allProducts: String? = null,  // JSON

    // 주문옵션 정보 (optionInfo에서 파싱)
    val optGender: String? = null,        // 성별: M/F
    val optBirthYear: String? = null,     // 출생년도: 1989, 1995 등
    val optAge: Int? = null,              // 나이 (현재년도 - 출생년도)
    val optJob: String? = null,           // 직업_회사
    val optPreferredDate: String? = null, // 참여희망날짜
    val orderEventDateDt: LocalDateTime? = null, // 참여날짜 DateTime (정렬용)

    // 관리상태 및 지역명
    val managementStatus: String? = "확인필요", // 관리상태: 확인필요/확정/대기/이월/불참/환불(대기자환불)/환불(참가취소,변심)
    val carryoverRound: Int? = null,           // 이월 회차 (1~5), 이월 상태일 때만 사용
    val regionName: String? = null,            // 지역명: 상품코드 기반

    // 주문 일시
    val orderTime: LocalDateTime? = null,  // KST로 저장
    val adminUrl: String? = null,

    // 동기화 정보
    val syncedAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,

    // 알림톡 발송 정보 (DB 컬럼 순서에 맞춤)
    val alimtalkSent: Boolean = false,           // 알림톡 발송여부
    val alimtalkSentAt: LocalDateTime? = null,   // 알림톡 발송일시

    // 리얼타임 체크 정보
    val lastRealtimeCheck: LocalDateTime? = null, // 마지막 리얼타임 결제상태 체크 시간

    // 추가 주문옵션 필드
    val optPremium: String? = null,              // 프리미엄 인증 여부: "인증" 또는 null(미인증)
    val optProfile: String? = null,              // 프로필 URL (http/https 링크)
    val eventDate: String? = null,               // 진행날짜 (이벤트일)

    // 수동 추가 주문 플래그
    val isManualOrder: Boolean? = false          // 수동 추가 주문 여부 (DB 마이그레이션 전 null 허용)
) {
    // MyBatis용 빈 생성자 (autoMapping="false" 사용 시 필요)
    @Suppress("unused")
    constructor() : this(siteCode = "", orderNo = 0L)

    // null-safe 수동 주문 여부 체크
    fun isManual(): Boolean = isManualOrder == true
}

// 주문-카테고리 매핑
data class SyncOrderCategory(
    val id: Long? = null,
    val syncOrderId: Long,
    val categoryCode: String,
    val siteCode: String,
    val createdAt: LocalDateTime? = null
)

// 동기화 상태
data class SyncStatus(
    val id: Long? = null,
    val siteCode: String,
    val syncType: String,  // ORDERS, CATEGORIES
    val syncMode: String = "MANUAL",  // MANUAL: 수동, AUTO: 자동
    val status: String,    // RUNNING, COMPLETED, FAILED
    val totalCount: Int = 0,
    val syncedCount: Int = 0,
    val failedCount: Int = 0,
    val startDate: String? = null,
    val endDate: String? = null,
    val errorMessage: String? = null,
    val startedAt: LocalDateTime? = null,
    val completedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime? = null
)

// 페이징 응답 DTO
data class PagedSyncOrders(
    val content: List<SyncOrder>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)

// 주문 표시용 DTO (카테고리명 포함)
data class SyncOrderView(
    val order: SyncOrder,
    val categoryNames: List<String> = emptyList(),
    // optionInfo에서 파싱한 성별/출생년도/나이
    val ordererGender: String? = null,
    val ordererBirthYear: String? = null,
    val ordererAge: Int? = null
) {
    val categoryNamesJoined: String
        get() = categoryNames.joinToString(", ")

    // 성별(나이) 형식으로 표시
    val genderWithAge: String
        get() {
            val genderStr = when (ordererGender) {
                "M" -> "남"
                "F" -> "여"
                else -> ordererGender ?: "-"
            }
            return if (ordererAge != null) "$genderStr($ordererAge)" else genderStr
        }

    // 알림톡 일괄 발송 체크박스 활성화 여부 (확정 + 미발송)
    val canSendAlimtalk: Boolean
        get() = order.managementStatus == "확정" && !order.alimtalkSent
}

// 필터링된 페이징 응답 DTO
data class PagedSyncOrderViews(
    val content: List<SyncOrderView>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val totalPaidPrice: Long = 0
)

// 필터 조건 DTO
data class SyncOrderFilter(
    val categoryCode: String? = null,
    val paymentMethod: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val regionName: String? = null,
    val managementStatus: String? = null,
    val prodNo: Int? = null,           // 상품코드 필터 (메뉴별 고정 필터)
    val searchPhone: String? = null,   // 휴대폰 검색 (ordererCall 또는 receiverCall)
    val searchName: String? = null,    // 이름 검색 (ordererName 또는 receiverName)
    val sortBy: String? = "orderNo",  // orderNo, orderTime, syncedAt
    val sortDir: String? = "DESC"      // ASC, DESC
)

/**
 * SSE 동기화 진행상황 이벤트 DTO
 * status: IN_PROGRESS, COMPLETED, FAILED
 */
data class SyncProgressEvent(
    val status: String,
    val message: String,
    val progress: Int,
    val result: SyncProgressResult? = null
)

data class SyncProgressResult(
    val newRecords: Int = 0,
    val updatedRecords: Int = 0,
    val totalRecords: Int = 0,
    val failedRecords: Int = 0
)

/**
 * 동기화 이력 뷰 DTO (KST 시간 포맷팅 포함)
 */
data class SyncStatusView(
    val id: Long?,
    val siteCode: String,
    val syncType: String,
    val syncMode: String,
    val status: String,
    val totalCount: Int,
    val syncedCount: Int,
    val failedCount: Int,
    val startDate: String?,
    val endDate: String?,
    val errorMessage: String?,
    val startedAt: String?,   // KST 포맷 문자열
    val completedAt: String?  // KST 포맷 문자열
)

/**
 * 페이징된 동기화 이력 (KST 시간 포맷팅 포함)
 */
data class PagedSyncStatusView(
    val content: List<SyncStatusView>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)

/**
 * 페이징된 동기화 이력 (Raw - 내부용)
 */
data class PagedSyncStatus(
    val content: List<SyncStatus>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)

/**
 * 관리상태 변경 이력
 */
data class SyncOrderStatusHistory(
    val id: Long? = null,
    val syncOrderId: Long,
    val siteCode: String,
    val previousStatus: String? = null,
    val newStatus: String,
    val carryoverRound: Int? = null,  // 이월 회차 (1~5)
    val changedBy: String = "admin",
    val changedAt: LocalDateTime? = null
)

/**
 * 관리상태 변경 요청 DTO
 */
data class StatusChangeRequest(
    val orderId: Long,
    val newStatus: String,
    val carryoverRound: Int? = null  // 이월 상태일 때만 사용
)

/**
 * 관리상태 변경 응답 DTO
 */
data class StatusChangeResponse(
    val success: Boolean,
    val orderId: Long,
    val newStatus: String,
    val carryoverRound: Int? = null,
    val message: String
)

/**
 * 배치/스케줄러용 OAuth 토큰 (어드민 토큰과 분리)
 */
data class OAuthTokenBatch(
    val id: Long? = null,
    val siteCode: String,
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Int? = null,
    val expiresAt: LocalDateTime? = null,
    val refreshToken: String? = null,
    val refreshTokenExpiresAt: LocalDateTime? = null,
    val scopes: String? = null,
    val issuedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)

/**
 * 스케줄러 상태 관리
 */
data class SchedulerStatus(
    val id: Long? = null,
    val siteCode: String,
    val schedulerType: String,  // ORDER_SYNC
    val isEnabled: Boolean = false,
    val lastRunAt: LocalDateTime? = null,
    val lastSuccessAt: LocalDateTime? = null,
    val lastErrorMessage: String? = null,
    val nextRunAt: LocalDateTime? = null,
    val runIntervalMinutes: Int = 10,  // 기본 10분
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)

/**
 * 스케줄러 상태 응답 DTO
 */
data class SchedulerStatusResponse(
    val isEnabled: Boolean,
    val lastRunAt: String? = null,
    val lastSuccessAt: String? = null,
    val lastErrorMessage: String? = null,
    val nextRunAt: String? = null,
    val runIntervalMinutes: Int = 10  // 기본 10분
)

/**
 * 공통 설정 (사이트별)
 * - 알림톡 테스트 전화번호 등 저장
 */
data class SyncConfig(
    val id: Long? = null,
    val siteCode: String,
    val configKey: String,
    val configValue: String? = null,
    val description: String? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)

/**
 * 알림톡 발송 이력
 */
data class AlimtalkSendHistory(
    val id: Long? = null,
    val siteCode: String,
    val syncOrderId: Long? = null,
    val orderNo: Long? = null,
    val templateId: Int,
    val templateName: String? = null,
    val receiverPhone: String,
    val receiverName: String? = null,
    val messageContent: String? = null,
    val sendType: String = "STATUS_CHANGE",  // STATUS_CHANGE, MANUAL_TEST
    val triggerStatus: String? = null,       // 발송 트리거한 상태
    val resultCode: Int? = null,
    val resultMessage: String? = null,
    val isSuccess: Boolean = false,
    val sentAt: LocalDateTime? = null
)

/**
 * 참석 통계 DTO (성별/상태별 집계)
 */
data class AttendanceStats(
    val total: Int = 0,                  // 총 인원
    val maleConfirmed: Int = 0,          // 남성 확정
    val maleWaiting: Int = 0,            // 남성 대기
    val femaleConfirmed: Int = 0,        // 여성 확정
    val femaleWaiting: Int = 0,          // 여성 대기
    val maleTotal: Int = 0,              // 남성 합계
    val femaleTotal: Int = 0             // 여성 합계
)

/**
 * V2 필터 조건 DTO (단순화된 필터)
 */
data class SyncOrderFilterV2(
    val keyword: String? = null,           // 이름/직업/출생년도 키워드 검색
    val preferredDate: String? = null,     // 참석희망날짜 필터
    val managementStatus: String? = null,  // 관리상태 필터
    val prodNo: Int? = null,               // 상품코드 필터
    val regionName: String? = null,        // 지역명 필터 (광주, 대구 등)
    val gender: String? = null,            // 성별 필터 (M/F)
    val paymentStatuses: List<String>? = null,  // 결제상태 필터 (복수)
    val paymentMethods: List<String>? = null,   // 결제수단 필터 (CARD, KAKAOPAY, VIRTUAL, BANKTRANSFER)
    val sortBy: String? = "orderTime",     // 정렬 기준
    val sortDir: String? = "DESC",         // 정렬 방향
    val startDate: String? = null,         // 기간 검색 시작일 (order_event_date_dt)
    val endDate: String? = null            // 기간 검색 종료일 (order_event_date_dt)
) {
    companion object {
        // 기본 결제상태 필터 (주문관리 페이지용)
        val DEFAULT_PAYMENT_STATUSES = listOf(
            "PAYMENT_COMPLETE",       // 결제완료
            "PARTIAL_REFUND_COMPLETE", // 부분환불완료
            "MANUAL_ORDER"            // 수동추가
        )
    }
}

/**
 * V2 페이징 응답 DTO (성별 분리 포함)
 */
data class PagedSyncOrderViewsV2(
    val maleOrders: List<SyncOrderView>,      // 남성 리스트
    val femaleOrders: List<SyncOrderView>,    // 여성 리스트
    val stats: AttendanceStats,               // 참석 통계
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)

/**
 * 전체주문 통계 페이지 전용 필터 (tables-all)
 * - 결제상태(전체), 결제, 환불 필터
 */
data class SyncOrderFilterAll(
    val keyword: String? = null,               // 이름/직업/출생년도 키워드 검색
    val startDate: String? = null,             // 기간 검색 시작일 (order_event_date_dt)
    val endDate: String? = null,               // 기간 검색 종료일 (order_event_date_dt)
    val paymentStatusFilter: String? = null,   // 결제상태 필터: null(전체), PAID(결제), REFUND(환불)
    val paymentMethodType: String? = null,     // 결제유형 필터: PG, VIRTUAL
    val sortBy: String? = "orderTime",         // 정렬 기준
    val sortDir: String? = "DESC"              // 정렬 방향
) {
    companion object {
        // 전체주문 페이지 기본 결제상태 (전체: 결제완료, 환불완료 모두)
        val ALL_PAYMENT_STATUSES = listOf(
            "PAYMENT_COMPLETE",       // 결제완료
            "PARTIAL_REFUND_COMPLETE", // 부분환불완료
            "MANUAL_ORDER",           // 수동추가
            "REFUND_COMPLETE"         // 환불완료
        )

        // 결제 필터: 확인필요 상태만
        const val PAID_MANAGEMENT_STATUS = "확인필요"

        // 환불 필터: 환불완료만
        val REFUND_PAYMENT_STATUS = "REFUND_COMPLETE"
    }
}

/**
 * 전체주문 페이지용 매출 합계 DTO
 */
data class SalesTotalAll(
    val paidTotal: Long = 0,      // 결제 완료 합계 (PAYMENT_COMPLETE)
    val refundTotal: Long = 0     // 환불 완료 합계 (REFUND_COMPLETE)
)

/**
 * 전체주문 페이지용 페이징 응답 DTO (성별 분리 없음)
 */
data class PagedSyncOrderViewsAll(
    val orders: List<SyncOrderView>,          // 전체 리스트 (최신순)
    val salesTotal: SalesTotalAll,            // 매출 합계
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)
