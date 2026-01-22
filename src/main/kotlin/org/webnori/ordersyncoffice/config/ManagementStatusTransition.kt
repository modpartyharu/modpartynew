package org.webnori.ordersyncoffice.config

import org.springframework.stereotype.Component

/**
 * 관리상태 자동 전이 규칙 공통 코드
 * - 상품동기화, 결제상태 실시간 체크 등에서 동일한 전이 규칙 사용
 */
@Component
class ManagementStatusTransition {

    companion object {
        // 결제상태 상수
        const val PAYMENT_COMPLETE = "PAYMENT_COMPLETE"
        const val PAYMENT_PENDING = "PAYMENT_PENDING"
        const val PAYMENT_PREPARATION = "PAYMENT_PREPARATION"
        const val PAYMENT_OVERDUE = "PAYMENT_OVERDUE"

        // 환불 관련 결제상태
        val REFUND_PAYMENT_STATUSES = setOf(
            "REFUND_PROCESSING",
            "PARTIAL_REFUND_COMPLETE",
            "REFUND_COMPLETE"
        )

        // 환불 관련 주문 섹션 상태 (orderSectionStatus)
        val REFUND_SECTION_STATUSES = setOf(
            "RETURN_COMPLETE",      // 반품완료
            "CANCEL_COMPLETE"       // 취소완료
        )

        // 관리상태 상수
        const val STATUS_PAYMENT_WAITING = "결제대기중"
        const val STATUS_CHECK_REQUIRED = "확인필요"
        const val STATUS_CONFIRMED = "확정"
        const val STATUS_WAITING = "대기"
        const val STATUS_CARRIED_OVER = "이월"
        const val STATUS_NO_SHOW = "불참"
        const val STATUS_REFUND = "환불"
    }

    /**
     * 결제상태 및 주문섹션상태에 따른 관리상태 초기값 결정
     * - 환불처리중/환불완료 (결제상태): 환불
     * - 반품완료/취소완료 (주문섹션상태): 환불
     * - 결제완료: 확인필요
     * - 결제대기 등 결제 전 단계: 결제대기중
     */
    fun determineInitialManagementStatus(paymentStatus: String?, orderSectionStatus: String? = null): String {
        return when {
            // 환불 관련 결제상태 체크
            paymentStatus != null && REFUND_PAYMENT_STATUSES.contains(paymentStatus) -> STATUS_REFUND
            // 환불 관련 주문섹션상태 체크 (반품완료, 취소완료)
            orderSectionStatus != null && REFUND_SECTION_STATUSES.contains(orderSectionStatus) -> STATUS_REFUND
            // 결제완료
            paymentStatus == PAYMENT_COMPLETE -> STATUS_CHECK_REQUIRED
            else -> STATUS_PAYMENT_WAITING
        }
    }

    /**
     * 결제상태 변경에 따른 관리상태 자동 업데이트 여부 결정
     * - 기존 관리상태가 '결제대기중'이고 새로운 결제상태가 '결제완료'면 '확인필요'로 변경
     * - 환불 상태로 변경된 경우 '환불'로 변경
     * @return 변경이 필요하면 새 관리상태, 아니면 null
     */
    fun determineStatusTransition(
        currentManagementStatus: String?,
        newPaymentStatus: String?
    ): String? {
        // 결제대기중 상태에서 결제완료로 변경되면 확인필요로 변경
        if (currentManagementStatus == STATUS_PAYMENT_WAITING && newPaymentStatus == PAYMENT_COMPLETE) {
            return STATUS_CHECK_REQUIRED
        }

        // 환불 상태로 변경되면 관리상태도 환불로 변경
        if (newPaymentStatus != null && REFUND_PAYMENT_STATUSES.contains(newPaymentStatus)) {
            // 이미 세부 환불 상태인 경우는 유지
            if (currentManagementStatus?.startsWith(STATUS_REFUND) == true) {
                return null
            }
            return STATUS_REFUND
        }

        return null
    }

    /**
     * 관리상태 전이가 유효한지 확인
     * - 환불 관련 상태(환불, 환불(대기자환불), 환불(참가취소,변심))는 자동감지에 의해서만 전환
     * - 일반 상태 -> 환불 관련 상태로 수동 전이 불가
     * - 환불 상태 -> 세부 환불 상태로만 전이 가능
     * - 세부 환불 상태 -> 일반 상태로 복귀 가능
     */
    fun isValidManualTransition(current: String, next: String): Boolean {
        // 환불 관련 상태 집합
        val refundStatuses = setOf(STATUS_REFUND, "환불(대기자환불)", "환불(참가취소,변심)")
        val refundSubStatuses = setOf("환불(대기자환불)", "환불(참가취소,변심)")
        // 일반 상태 집합
        val normalStatuses = setOf(STATUS_CHECK_REQUIRED, STATUS_CONFIRMED, STATUS_WAITING, STATUS_CARRIED_OVER, STATUS_NO_SHOW, STATUS_PAYMENT_WAITING)

        return when (current) {
            STATUS_REFUND -> next in refundSubStatuses // 환불에서는 세부 환불로만 전이
            "환불(대기자환불)" -> next in normalStatuses // 세부 환불에서 일반 상태로 복귀 가능
            "환불(참가취소,변심)" -> next in normalStatuses // 세부 환불에서 일반 상태로 복귀 가능
            else -> next !in refundStatuses // 일반 상태에서 환불 관련 상태로 전이 불가
        }
    }

    /**
     * 알림톡 발송 가능 여부 확인
     * - 관리상태가 '확정'이고 알림톡 미발송인 경우
     */
    fun canSendAlimtalk(managementStatus: String?, alimtalkSent: Boolean): Boolean {
        return managementStatus == STATUS_CONFIRMED && !alimtalkSent
    }
}
