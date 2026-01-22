package org.webnori.ordersyncoffice.util

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 날짜 변환 유틸리티
 * - 참여희망날짜 문자열을 DateTime으로 변환
 * - 연말/연초 전환 고려
 */
object DateUtils {

    private val monthDayPattern = "(\\d+)월\\s*(\\d+)일".toRegex()  // 공백 허용
    private val monthDaySpacePattern = "(\\d+)월\\s+(\\d+)일".toRegex()  // 공백 있는 경우 검출용

    /**
     * 참여희망날짜 문자열 정규화 (공백 제거)
     * - "1월 1일(금)" -> "1월1일(금)"
     * - "12월 31일(화)" -> "12월31일(화)"
     * - 공백이 없으면 원본 반환
     *
     * @param preferredDate 원본 참여희망날짜 문자열
     * @return 정규화된 문자열 (공백 제거)
     */
    fun normalizePreferredDate(preferredDate: String?): String? {
        if (preferredDate.isNullOrBlank()) return preferredDate
        // "1월 1일" 패턴의 공백 제거 -> "1월1일"
        return preferredDate.replace(monthDaySpacePattern) { match ->
            "${match.groupValues[1]}월${match.groupValues[2]}일"
        }
    }

    /**
     * 참여희망날짜가 기준일로부터 지정된 개월 수 이내인지 확인
     * - 과거 또는 미래 지정 개월 이상 차이나는 날짜는 false 반환
     *
     * @param preferredDate 참여희망날짜 문자열
     * @param months 허용 개월 수 (기본값: 3개월)
     * @param baseDate 기준 날짜 (기본값: 현재 날짜)
     * @return 범위 내이면 true, 범위 밖이면 false
     */
    fun isWithinMonths(
        preferredDate: String?,
        months: Long = 3,
        baseDate: LocalDate = LocalDate.now()
    ): Boolean {
        if (preferredDate.isNullOrBlank()) return false

        val parsedDate = parsePreferredDateToDateTime(preferredDate, baseDate) ?: return false
        val targetDate = parsedDate.toLocalDate()

        val pastLimit = baseDate.minusMonths(months)
        val futureLimit = baseDate.plusMonths(months)

        return !targetDate.isBefore(pastLimit) && !targetDate.isAfter(futureLimit)
    }

    /**
     * 참여희망날짜 목록에서 지정 개월 수 이내의 날짜만 필터링
     *
     * @param dates 참여희망날짜 문자열 목록
     * @param months 허용 개월 수 (기본값: 3개월)
     * @param baseDate 기준 날짜 (기본값: 현재 날짜)
     * @return 필터링된 날짜 목록
     */
    fun filterDatesWithinMonths(
        dates: Collection<String>,
        months: Long = 3,
        baseDate: LocalDate = LocalDate.now()
    ): List<String> {
        return dates.filter { isWithinMonths(it, months, baseDate) }
    }

    /**
     * 참여희망날짜 문자열을 LocalDateTime으로 변환
     * - "12월27일 (토)" -> LocalDateTime
     * - 연말(10~12월)에는 1~3월을 다음 해로 취급
     *
     * @param preferredDate 참여희망날짜 문자열 (예: "12월27일 (토)")
     * @param baseDate 기준 날짜 (기본값: 현재 날짜)
     * @return 변환된 LocalDateTime, 변환 실패 시 null
     */
    fun parsePreferredDateToDateTime(
        preferredDate: String?,
        baseDate: LocalDate = LocalDate.now()
    ): LocalDateTime? {
        if (preferredDate.isNullOrBlank()) return null

        val match = monthDayPattern.find(preferredDate) ?: return null

        val month = match.groupValues[1].toIntOrNull() ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null

        // 유효성 검사
        if (month < 1 || month > 12 || day < 1 || day > 31) return null

        // 연도 결정: 연말/연초 경계 처리
        val currentMonth = baseDate.monthValue
        val year = when {
            // 연말(10~12월)에 1~3월 날짜 → 다음 해
            currentMonth >= 10 && month <= 3 -> baseDate.year + 1
            // 연초(1~3월)에 10~12월 날짜 → 이전 해
            currentMonth <= 3 && month >= 10 -> baseDate.year - 1
            // 그 외 → 현재 해
            else -> baseDate.year
        }

        return try {
            LocalDateTime.of(year, month, day, 0, 0)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 참여희망날짜 목록을 오름차순 정렬 (가까운 날짜가 위로)
     * - 연말/연초 전환 고려
     *
     * @param dates 참여희망날짜 문자열 목록
     * @param baseDate 기준 날짜 (기본값: 현재 날짜)
     * @return 정렬된 날짜 목록
     */
    fun sortPreferredDates(
        dates: Collection<String>,
        baseDate: LocalDate = LocalDate.now()
    ): List<String> {
        val currentMonth = baseDate.monthValue

        return dates.sortedWith { a, b ->
            val matchA = monthDayPattern.find(a)
            val matchB = monthDayPattern.find(b)

            if (matchA != null && matchB != null) {
                val monthA = matchA.groupValues[1].toIntOrNull() ?: 0
                val dayA = matchA.groupValues[2].toIntOrNull() ?: 0
                val monthB = matchB.groupValues[1].toIntOrNull() ?: 0
                val dayB = matchB.groupValues[2].toIntOrNull() ?: 0

                // 연말/연초 경계 처리하여 정렬
                val adjustedMonthA = when {
                    currentMonth >= 10 && monthA <= 3 -> monthA + 12  // 연말에 1~3월 → 다음 해
                    currentMonth <= 3 && monthA >= 10 -> monthA - 12  // 연초에 10~12월 → 이전 해
                    else -> monthA
                }
                val adjustedMonthB = when {
                    currentMonth >= 10 && monthB <= 3 -> monthB + 12
                    currentMonth <= 3 && monthB >= 10 -> monthB - 12
                    else -> monthB
                }

                if (adjustedMonthA != adjustedMonthB) {
                    adjustedMonthA - adjustedMonthB
                } else {
                    dayA - dayB
                }
            } else {
                a.compareTo(b)
            }
        }
    }
}
