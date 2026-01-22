package org.webnori.ordersyncoffice.mapper

import org.apache.ibatis.annotations.*
import org.webnori.ordersyncoffice.domain.*
import java.time.LocalDateTime

@Mapper
interface AppVersionMapper {
    @Select("SELECT * FROM app_version ORDER BY id DESC LIMIT 1")
    fun getLatestVersion(): AppVersion?
}

@Mapper
interface StoreInfoMapper {

    @Select("SELECT * FROM store_info WHERE site_code = #{siteCode}")
    fun findBySiteCode(siteCode: String): StoreInfo?

    @Select("SELECT * FROM store_info WHERE is_active = true ORDER BY created_at DESC")
    fun findAllActive(): List<StoreInfo>

    @Select("SELECT * FROM store_info ORDER BY created_at DESC")
    fun findAll(): List<StoreInfo>

    @Insert("""
        INSERT INTO store_info (site_code, site_name, site_url, unit_code, admin_email,
            company_name, representative_name, business_number, phone, address, is_active)
        VALUES (#{siteCode}, #{siteName}, #{siteUrl}, #{unitCode}, #{adminEmail},
            #{companyName}, #{representativeName}, #{businessNumber}, #{phone}, #{address}, #{isActive})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(storeInfo: StoreInfo): Int

    @Update("""
        UPDATE store_info SET
            site_name = #{siteName},
            site_url = #{siteUrl},
            unit_code = #{unitCode},
            admin_email = #{adminEmail},
            company_name = #{companyName},
            representative_name = #{representativeName},
            business_number = #{businessNumber},
            phone = #{phone},
            address = #{address},
            is_active = #{isActive},
            updated_at = CURRENT_TIMESTAMP
        WHERE site_code = #{siteCode}
    """)
    fun update(storeInfo: StoreInfo): Int

    @Delete("DELETE FROM store_info WHERE site_code = #{siteCode}")
    fun deleteBySiteCode(siteCode: String): Int
}

@Mapper
interface OAuthTokenMapper {

    @Select("SELECT * FROM oauth_token WHERE site_code = #{siteCode}")
    fun findBySiteCode(siteCode: String): OAuthToken?

    @Insert("""
        INSERT INTO oauth_token (site_code, access_token, token_type, expires_in, expires_at,
            refresh_token, refresh_token_expires_at, scopes, issued_at)
        VALUES (#{siteCode}, #{accessToken}, #{tokenType}, #{expiresIn}, #{expiresAt},
            #{refreshToken}, #{refreshTokenExpiresAt}, #{scopes}, #{issuedAt})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(oAuthToken: OAuthToken): Int

    @Update("""
        UPDATE oauth_token SET
            access_token = #{accessToken},
            token_type = #{tokenType},
            expires_in = #{expiresIn},
            expires_at = #{expiresAt},
            refresh_token = #{refreshToken},
            refresh_token_expires_at = #{refreshTokenExpiresAt},
            scopes = #{scopes},
            issued_at = #{issuedAt},
            updated_at = CURRENT_TIMESTAMP
        WHERE site_code = #{siteCode}
    """)
    fun update(oAuthToken: OAuthToken): Int

    @Delete("DELETE FROM oauth_token WHERE site_code = #{siteCode}")
    fun deleteBySiteCode(siteCode: String): Int
}

/**
 * Sync Mappers - XML 매퍼와 연동
 */

@Mapper
interface SyncCategoryMapper {
    fun findBySiteCode(siteCode: String): List<SyncCategory>

    fun findBySiteCodeAndCategoryCode(siteCode: String, categoryCode: String): SyncCategory?

    fun upsert(category: SyncCategory): Int

    fun deleteAllBySiteCode(siteCode: String): Int
}

@Mapper
interface SyncOrderMapper {
    fun findBySiteCode(siteCode: String): List<SyncOrder>

    fun findBySiteCodeAndOrderNo(siteCode: String, orderNo: Long): SyncOrder?

    fun findBySiteCodePaged(siteCode: String, offset: Int, limit: Int): List<SyncOrder>

    fun countBySiteCode(siteCode: String): Long

    fun findBySiteCodeFiltered(
        siteCode: String,
        categoryCode: String?,
        paymentMethod: String?,
        startDate: String?,
        endDate: String?,
        regionName: String?,
        managementStatus: String?,
        prodNo: Int?,
        searchPhone: String?,
        searchName: String?,
        sortBy: String?,
        sortDir: String?,
        offset: Int,
        limit: Int
    ): List<SyncOrder>

    fun countBySiteCodeFiltered(
        siteCode: String,
        categoryCode: String?,
        paymentMethod: String?,
        startDate: String?,
        endDate: String?,
        regionName: String?,
        managementStatus: String?,
        prodNo: Int?,
        searchPhone: String?,
        searchName: String?
    ): Long

    fun sumPaidPriceFiltered(
        siteCode: String,
        categoryCode: String?,
        paymentMethod: String?,
        startDate: String?,
        endDate: String?,
        regionName: String?,
        managementStatus: String?,
        prodNo: Int?,
        searchPhone: String?,
        searchName: String?
    ): Long

    fun findDistinctPaymentMethods(siteCode: String): List<String>

    fun findCategoryNamesByOrderId(orderId: Long): List<String>

    fun upsert(order: SyncOrder): Int

    fun deleteAllBySiteCode(siteCode: String): Int

    fun executeSampleQuery(siteCode: String, table: String, order: String): List<Map<String, Any?>>

    fun updateManagementStatus(id: Long, managementStatus: String): Int

    fun updateManagementStatusWithRound(id: Long, managementStatus: String, carryoverRound: Int?): Int

    fun findById(id: Long): SyncOrder?

    fun updateAlimtalkSent(id: Long, alimtalkSent: Boolean): Int

    fun findByIdsForAlimtalk(ids: List<Long>): List<SyncOrder>

    // 리얼타임 체크 관련 메서드
    fun findOrdersNeedingRealtimeCheck(
        @Param("siteCode") siteCode: String,
        @Param("orderIds") orderIds: List<Long>
    ): List<SyncOrder>

    fun updatePaymentStatusAndRealtimeCheck(
        @Param("id") id: Long,
        @Param("paymentStatus") paymentStatus: String
    ): Int

    fun updateRealtimeCheckTime(@Param("id") id: Long): Int

    fun findDistinctManagementStatuses(@Param("siteCode") siteCode: String): List<String>

    // 성별 및 관리상태별 통계
    fun countByGenderAndStatus(
        @Param("siteCode") siteCode: String,
        @Param("prodNo") prodNo: Int?,
        @Param("preferredDate") preferredDate: String?,
        @Param("regionName") regionName: String?
    ): List<Map<String, Any>>

    // 참석희망날짜 목록 (DB 기반) - Imweb API 직접 호출 방식으로 대체됨
    @Deprecated("Use Imweb API: /api/sync/product-options or /api/sync/region-options")
    fun findDistinctPreferredDates(
        @Param("siteCode") siteCode: String,
        @Param("prodNo") prodNo: Int?
    ): List<String>

    // V2 필터 (키워드 + 날짜 + 상태 + 성별 + 지역명 + 결제상태 + 결제수단 + 기간검색)
    fun findBySiteCodeFilteredV2(
        @Param("siteCode") siteCode: String,
        @Param("keyword") keyword: String?,
        @Param("preferredDate") preferredDate: String?,
        @Param("managementStatus") managementStatus: String?,
        @Param("prodNo") prodNo: Int?,
        @Param("regionName") regionName: String?,
        @Param("gender") gender: String?,
        @Param("paymentStatuses") paymentStatuses: List<String>?,
        @Param("paymentMethods") paymentMethods: List<String>?,
        @Param("startDate") startDate: String?,
        @Param("endDate") endDate: String?,
        @Param("sortBy") sortBy: String?,
        @Param("sortDir") sortDir: String?,
        @Param("offset") offset: Int,
        @Param("limit") limit: Int
    ): List<SyncOrder>

    fun countBySiteCodeFilteredV2(
        @Param("siteCode") siteCode: String,
        @Param("keyword") keyword: String?,
        @Param("preferredDate") preferredDate: String?,
        @Param("managementStatus") managementStatus: String?,
        @Param("prodNo") prodNo: Int?,
        @Param("regionName") regionName: String?,
        @Param("gender") gender: String?,
        @Param("paymentStatuses") paymentStatuses: List<String>?,
        @Param("paymentMethods") paymentMethods: List<String>?,
        @Param("startDate") startDate: String?,
        @Param("endDate") endDate: String?
    ): Long

    // 참여희망날짜 업데이트 (order_event_date_dt도 함께 갱신)
    fun updateOptPreferredDate(
        @Param("id") id: Long,
        @Param("optPreferredDate") optPreferredDate: String,
        @Param("orderEventDateDt") orderEventDateDt: LocalDateTime?
    ): Int

    // ===== 대시보드 통계 =====

    // 전체 주문 건수
    fun countAllOrders(@Param("siteCode") siteCode: String): Long

    // 확정 주문 건수
    fun countConfirmedOrders(@Param("siteCode") siteCode: String): Long

    // 지역별 주문 건수
    fun countByRegion(@Param("siteCode") siteCode: String): List<Map<String, Any>>

    // 성별 주문 건수
    fun countByGender(@Param("siteCode") siteCode: String): List<Map<String, Any>>

    // 최근 주문 목록 (대시보드용)
    fun findRecentOrders(
        @Param("siteCode") siteCode: String,
        @Param("limit") limit: Int
    ): List<SyncOrder>

    // 주문자 정보 수정 (이름, 출생년도, 직업, 연락처, 프로필, 프리미엄)
    fun updateOrdererInfo(
        @Param("id") id: Long,
        @Param("ordererName") ordererName: String?,
        @Param("optBirthYear") optBirthYear: String?,
        @Param("optJob") optJob: String?,
        @Param("ordererCall") ordererCall: String?,
        @Param("optProfile") optProfile: String?,
        @Param("optPremium") optPremium: String?
    ): Int

    // 수동 주문 생성을 위한 다음 주문번호 조회 (음수 시퀀스)
    fun getNextManualOrderNo(@Param("siteCode") siteCode: String): Long

    // 수동 주문 생성
    fun insertManualOrder(order: SyncOrder): Int

    // 상품코드별 상품명 조회 (첫 번째 레코드의 prod_name 반환)
    fun findProductNameByProdNo(
        @Param("siteCode") siteCode: String,
        @Param("prodNo") prodNo: Int
    ): String?

    // 수동 추가 주문 삭제 (order_status가 NULL인 경우만 삭제 가능)
    fun deleteManualOrder(
        @Param("id") id: Long,
        @Param("siteCode") siteCode: String
    ): Int

    // 대시보드 집계: 일자별/지역별 성별 통계
    fun getDashboardAggregation(
        @Param("siteCode") siteCode: String,
        @Param("weeks") weeks: Int
    ): List<Map<String, Any>>

    // 매출 합계 조회 (필터 없을 시, 결제완료+확정 상태만)
    fun getSalesTotal(@Param("siteCode") siteCode: String): Map<String, Any>?

    // 매출 합계 조회 (필터 적용)
    fun getSalesTotalFiltered(
        @Param("siteCode") siteCode: String,
        @Param("keyword") keyword: String?,
        @Param("preferredDate") preferredDate: String?,
        @Param("managementStatus") managementStatus: String?,
        @Param("prodNo") prodNo: Int?,
        @Param("regionName") regionName: String?,
        @Param("paymentStatuses") paymentStatuses: List<String>?,
        @Param("paymentMethods") paymentMethods: List<String>?,
        @Param("startDate") startDate: String?,
        @Param("endDate") endDate: String?
    ): Map<String, Any>?

    // =================================================================
    // 전체주문 페이지(tables-all) 전용 메서드
    // =================================================================

    /**
     * 전체주문 페이지용 매출 합계 조회
     * - 결제완료 합계(PAYMENT_COMPLETE)
     * - 환불완료 합계(REFUND_COMPLETE)
     */
    fun getSalesTotalAll(
        @Param("siteCode") siteCode: String,
        @Param("keyword") keyword: String?,
        @Param("startDate") startDate: String?,
        @Param("endDate") endDate: String?,
        @Param("paymentMethods") paymentMethods: List<String>?
    ): Map<String, Any>?

    /**
     * 전체주문 페이지용 주문 조회 (성별 분리 없음)
     * - paymentStatusFilter: null(전체), PAID(결제=확인필요), REFUND(환불완료)
     */
    fun findBySiteCodeFilteredAll(
        @Param("siteCode") siteCode: String,
        @Param("keyword") keyword: String?,
        @Param("startDate") startDate: String?,
        @Param("endDate") endDate: String?,
        @Param("paymentStatusFilter") paymentStatusFilter: String?,
        @Param("paymentMethods") paymentMethods: List<String>?,
        @Param("sortBy") sortBy: String?,
        @Param("sortDir") sortDir: String?,
        @Param("offset") offset: Int,
        @Param("limit") limit: Int
    ): List<SyncOrder>

    /**
     * 전체주문 페이지용 카운트 조회
     */
    fun countBySiteCodeFilteredAll(
        @Param("siteCode") siteCode: String,
        @Param("keyword") keyword: String?,
        @Param("startDate") startDate: String?,
        @Param("endDate") endDate: String?,
        @Param("paymentStatusFilter") paymentStatusFilter: String?,
        @Param("paymentMethods") paymentMethods: List<String>?
    ): Long
}

@Mapper
interface SyncOrderCategoryMapper {
    fun findBySyncOrderId(syncOrderId: Long): List<SyncOrderCategory>

    fun insert(mapping: SyncOrderCategory): Int

    fun deleteBySyncOrderId(syncOrderId: Long): Int

    fun deleteAllBySiteCode(siteCode: String): Int
}

@Mapper
interface SyncStatusMapper {
    fun findBySiteCode(siteCode: String): List<SyncStatus>

    fun findBySiteCodePaged(siteCode: String, offset: Int, limit: Int): List<SyncStatus>

    fun countBySiteCode(siteCode: String): Long

    fun findLatestBySiteCodeAndType(siteCode: String, syncType: String): SyncStatus?

    fun findRunningBySiteCode(siteCode: String): SyncStatus?

    fun insert(status: SyncStatus): Int

    fun updateStatus(id: Long, status: String, syncedCount: Int, failedCount: Int): Int

    fun complete(id: Long, status: String, syncedCount: Int, failedCount: Int, errorMessage: String?): Int

    /**
     * 5분 이상 경과한 RUNNING 상태를 FAILED로 변경
     * @return 업데이트된 row 수
     */
    fun failStaleRunning(siteCode: String, minutesThreshold: Int): Int

    fun deleteAllBySiteCode(siteCode: String): Int

    /**
     * 마지막 완료된 동기화의 종료일시 조회 (자동 동기화 기준 시점용)
     * @return end_date (KST 문자열, 예: "2025-12-21 03:07:37")
     * 완료시간(completed_at)이 아닌 동기화 기간의 끝(end_date)을 사용해야 주문 누락 방지
     */
    fun findLastSyncEndDate(siteCode: String, syncType: String): String?

    /**
     * 자동 동기화 마지막 실행 기록 조회
     */
    fun findLastAutoSync(siteCode: String, syncType: String): SyncStatus?

    /**
     * 자동 동기화 마지막 실행 기록 업데이트 (0건일 때 기존 기록 갱신)
     */
    fun updateAutoSyncLastRun(id: Long, startDate: String, endDate: String): Int

    /**
     * 자동 동기화 마지막 실행 기록 업데이트 (동기화 건수 포함)
     * - AUTO 동기화는 새 이력을 쌓지 않고 마지막 기록만 업데이트
     */
    fun updateAutoSyncWithCount(
        id: Long,
        startDate: String,
        endDate: String,
        syncedCount: Int,
        failedCount: Int
    ): Int
}

@Mapper
interface SyncOrderStatusHistoryMapper {
    fun insert(history: SyncOrderStatusHistory): Int

    fun findBySyncOrderId(syncOrderId: Long): List<SyncOrderStatusHistory>

    fun findBySyncOrderIdLimit(syncOrderId: Long, limit: Int): List<SyncOrderStatusHistory>

    fun deleteAllBySiteCode(siteCode: String): Int

    fun deleteBySyncOrderId(syncOrderId: Long): Int
}

/**
 * 배치/스케줄러용 OAuth 토큰 매퍼
 */
@Mapper
interface OAuthTokenBatchMapper {

    @Select("SELECT * FROM oauth_token_batch WHERE site_code = #{siteCode}")
    fun findBySiteCode(siteCode: String): OAuthTokenBatch?

    @Insert("""
        INSERT INTO oauth_token_batch (site_code, access_token, token_type, expires_in, expires_at,
            refresh_token, refresh_token_expires_at, scopes, issued_at)
        VALUES (#{siteCode}, #{accessToken}, #{tokenType}, #{expiresIn}, #{expiresAt},
            #{refreshToken}, #{refreshTokenExpiresAt}, #{scopes}, #{issuedAt})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(token: OAuthTokenBatch): Int

    @Update("""
        UPDATE oauth_token_batch SET
            access_token = #{accessToken},
            token_type = #{tokenType},
            expires_in = #{expiresIn},
            expires_at = #{expiresAt},
            refresh_token = #{refreshToken},
            refresh_token_expires_at = #{refreshTokenExpiresAt},
            scopes = #{scopes},
            issued_at = #{issuedAt},
            updated_at = CURRENT_TIMESTAMP
        WHERE site_code = #{siteCode}
    """)
    fun update(token: OAuthTokenBatch): Int

    @Delete("DELETE FROM oauth_token_batch WHERE site_code = #{siteCode}")
    fun deleteBySiteCode(siteCode: String): Int
}

/**
 * 스케줄러 상태 매퍼
 */
@Mapper
interface SchedulerStatusMapper {

    @Select("SELECT * FROM scheduler_status WHERE site_code = #{siteCode} AND scheduler_type = #{schedulerType}")
    fun findBySiteCodeAndType(siteCode: String, schedulerType: String): SchedulerStatus?

    @Select("SELECT * FROM scheduler_status WHERE is_enabled = true")
    fun findAllEnabled(): List<SchedulerStatus>

    @Insert("""
        INSERT INTO scheduler_status (site_code, scheduler_type, is_enabled, run_interval_minutes, next_run_at)
        VALUES (#{siteCode}, #{schedulerType}, #{isEnabled}, #{runIntervalMinutes}, IF(#{isEnabled}, CURRENT_TIMESTAMP, NULL))
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(status: SchedulerStatus): Int

    @Update("""
        UPDATE scheduler_status SET
            is_enabled = #{isEnabled},
            run_interval_minutes = #{runIntervalMinutes},
            next_run_at = IF(#{isEnabled}, CURRENT_TIMESTAMP, NULL),
            updated_at = CURRENT_TIMESTAMP
        WHERE site_code = #{siteCode} AND scheduler_type = #{schedulerType}
    """)
    fun updateEnabled(siteCode: String, schedulerType: String, isEnabled: Boolean, runIntervalMinutes: Int): Int

    @Update("""
        UPDATE scheduler_status SET
            last_run_at = CURRENT_TIMESTAMP,
            next_run_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL run_interval_minutes MINUTE),
            updated_at = CURRENT_TIMESTAMP
        WHERE site_code = #{siteCode} AND scheduler_type = #{schedulerType}
    """)
    fun updateLastRun(siteCode: String, schedulerType: String): Int

    @Update("""
        UPDATE scheduler_status SET
            last_run_at = CURRENT_TIMESTAMP,
            last_success_at = CURRENT_TIMESTAMP,
            last_error_message = NULL,
            next_run_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL run_interval_minutes MINUTE),
            updated_at = CURRENT_TIMESTAMP
        WHERE site_code = #{siteCode} AND scheduler_type = #{schedulerType}
    """)
    fun updateLastSuccess(siteCode: String, schedulerType: String): Int

    @Update("""
        UPDATE scheduler_status SET
            last_run_at = CURRENT_TIMESTAMP,
            last_error_message = #{errorMessage},
            next_run_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL run_interval_minutes MINUTE),
            updated_at = CURRENT_TIMESTAMP
        WHERE site_code = #{siteCode} AND scheduler_type = #{schedulerType}
    """)
    fun updateLastError(siteCode: String, schedulerType: String, errorMessage: String): Int

    @Delete("DELETE FROM scheduler_status WHERE site_code = #{siteCode}")
    fun deleteBySiteCode(siteCode: String): Int
}

/**
 * 공통 설정 매퍼 (알림톡 테스트 전화번호 등)
 */
@Mapper
interface SyncConfigMapper {

    @Select("SELECT * FROM sync_config WHERE site_code = #{siteCode} AND config_key = #{configKey}")
    fun findBySiteCodeAndKey(siteCode: String, configKey: String): SyncConfig?

    @Select("SELECT * FROM sync_config WHERE site_code = #{siteCode}")
    fun findBySiteCode(siteCode: String): List<SyncConfig>

    @Insert("""
        INSERT INTO sync_config (site_code, config_key, config_value, description)
        VALUES (#{siteCode}, #{configKey}, #{configValue}, #{description})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(config: SyncConfig): Int

    @Update("""
        UPDATE sync_config SET
            config_value = #{configValue},
            description = #{description},
            updated_at = CURRENT_TIMESTAMP
        WHERE id = #{id}
    """)
    fun update(config: SyncConfig): Int

    @Delete("DELETE FROM sync_config WHERE site_code = #{siteCode} AND config_key = #{configKey}")
    fun deleteBySiteCodeAndKey(siteCode: String, configKey: String): Int
}

/**
 * 알림톡 발송 이력 매퍼
 */
@Mapper
interface AlimtalkSendHistoryMapper {

    @Insert("""
        INSERT INTO alimtalk_send_history (
            site_code, sync_order_id, order_no, template_id, template_name,
            receiver_phone, receiver_name, message_content, send_type, trigger_status,
            result_code, result_message, is_success
        ) VALUES (
            #{siteCode}, #{syncOrderId}, #{orderNo}, #{templateId}, #{templateName},
            #{receiverPhone}, #{receiverName}, #{messageContent}, #{sendType}, #{triggerStatus},
            #{resultCode}, #{resultMessage}, #{isSuccess}
        )
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(history: AlimtalkSendHistory): Int

    @Select("""
        SELECT * FROM alimtalk_send_history
        WHERE site_code = #{siteCode}
        ORDER BY sent_at DESC
        LIMIT #{limit} OFFSET #{offset}
    """)
    fun findBySiteCodePaged(siteCode: String, offset: Int, limit: Int): List<AlimtalkSendHistory>

    @Select("SELECT COUNT(*) FROM alimtalk_send_history WHERE site_code = #{siteCode}")
    fun countBySiteCode(siteCode: String): Long

    @Select("""
        SELECT * FROM alimtalk_send_history
        WHERE sync_order_id = #{syncOrderId}
        ORDER BY sent_at DESC
    """)
    fun findBySyncOrderId(syncOrderId: Long): List<AlimtalkSendHistory>

    @Delete("DELETE FROM alimtalk_send_history WHERE site_code = #{siteCode}")
    fun deleteBySiteCode(siteCode: String): Int
}
