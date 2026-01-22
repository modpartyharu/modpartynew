package org.webnori.ordersyncoffice.config

import org.springframework.stereotype.Component

/**
 * 알림톡 템플릿 정의
 * - 상품코드 -> 템플릿코드 매핑
 * - 템플릿별 메시지 내용 및 버튼 정보
 */
@Component
class AlimtalkTemplate {

    companion object {
        // 루나소프트 API 정보
        const val API_HOST = "https://jupiter.lunasoft.co.kr"
        const val API_PATH = "/api/AlimTalk/message/send"
        const val USER_ID = "dlentwotjr"
        const val API_KEY = "XGZH75ZXJLHJZ9A4KKSY2C7BRZME1BNYLJIWTHU3"

        // 신규 추가 상품코드 (템플릿 미매핑 - 알림톡 발송 제한)
        // 이 상품코드들은 동기화는 되지만 알림톡 템플릿이 아직 없어 발송하지 않고 로그만 남김
        val UNMAPPED_PRODUCT_CODES: Set<Int> = setOf(
            265,  // 압구정 (신규 추가)
            266,  // 이태원 (신규 추가)
            267,  // 부산 (신규 추가)
            268,  // 대구 (신규 추가)
            270,  // 일산 (신규 추가)
            271   // 일산 (신규 추가)
        )

        // 상품코드 -> 템플릿코드 매핑
        val PRODUCT_TEMPLATE_MAP: Map<Int, Int> = mapOf(
            248 to 50035,   // 압구정 - 식스나잇 (매주 금요일)
            54 to 50046,    // 역삼 - 시그널쿡 (매주 일요일)
            164 to 50036,   // 이태원 - 원사이즈 (토요일)
            186 to 50037,   // 청담 - 보타닉
            190 to 50044,   // 광주 (알베르)
            168 to 50044,   // 광주 (알베르)
            264 to 50064,   // 일산 (벨라시타점 스타라이트)
            167 to 50042,   // 대구 (배디스트)
            193 to 50042,   // 대구 (배디스트)
            189 to 50040,   // 대전 (스페인261)
            166 to 50040,   // 대전 (스페인261)
            194 to 50043,   // 부산 (피크라운지)
            209 to 50043,   // 부산 (피크라운지)
            198 to 50039,   // 수원 (카페도화 연무대점)
            218 to 50039,   // 수원 (카페도화 연무대점)
            217 to 50045,   // 울산 (카페 웰빈)
            231 to 50038,   // 인천 (그리너리하우스)
            183 to 50038,   // 인천 (그리너리하우스)
            182 to 50041,   // 천안 (마리노)
            196 to 50041    // 천안 (마리노)
        )

        // 환불 템플릿 코드
        const val TEMPLATE_REFUND_A = 50062  // 환불(대기자환불)
        const val TEMPLATE_REFUND_B = 50063  // 환불(참가취소,변심)

        // 발송 대상 상태
        val SEND_STATUS_MAP: Map<String, Int?> = mapOf(
            "확정" to null,                    // 상품코드별 템플릿 사용
            "환불(대기자환불)" to TEMPLATE_REFUND_A,
            "환불(참가취소,변심)" to TEMPLATE_REFUND_B
        )
    }

    /**
     * 상태 변경에 따른 발송 여부 확인
     */
    fun shouldSendAlimtalk(managementStatus: String): Boolean {
        return SEND_STATUS_MAP.containsKey(managementStatus)
    }

    /**
     * 신규 추가 상품코드(템플릿 미매핑) 여부 확인
     */
    fun isUnmappedProduct(prodNo: Int?): Boolean {
        return prodNo != null && UNMAPPED_PRODUCT_CODES.contains(prodNo)
    }

    /**
     * 상태 변경에 따른 템플릿 ID 조회
     * @param managementStatus 관리상태
     * @param prodNo 상품코드 (확정 상태일 때 필요)
     * @return 템플릿 ID (null이면 발송 대상 아님)
     */
    fun getTemplateId(managementStatus: String, prodNo: Int?): Int? {
        return when (managementStatus) {
            "확정" -> prodNo?.let { PRODUCT_TEMPLATE_MAP[it] }
            "환불(대기자환불)" -> TEMPLATE_REFUND_A
            "환불(참가취소,변심)" -> TEMPLATE_REFUND_B
            else -> null
        }
    }

    /**
     * 템플릿 정보 조회
     */
    fun getTemplateInfo(templateId: Int): TemplateInfo? {
        return TEMPLATES[templateId]
    }

    /**
     * 모든 템플릿 목록 조회
     */
    fun getAllTemplates(): List<TemplateInfo> {
        return TEMPLATES.values.toList()
    }

    /**
     * 환불 메시지 생성 (가변요소 치환)
     */
    fun buildRefundMessage(templateId: Int, customerName: String, productName: String): String {
        val template = TEMPLATES[templateId] ?: return ""
        return template.messageContent
            .replace("#{NAME}", customerName)
            .replace("#{PRODUCT}", productName)
    }
}

/**
 * 템플릿 정보
 */
data class TemplateInfo(
    val templateId: Int,
    val templateName: String,
    val regionName: String,
    val messageContent: String,
    val smsContent: String,
    val buttons: List<TemplateButton> = emptyList(),
    val hasVariables: Boolean = false,
    val variables: List<String> = emptyList()
)

/**
 * 템플릿 버튼 정보
 */
data class TemplateButton(
    val urlPc: String,
    val urlMobile: String
)

/**
 * 템플릿 상세 정보 정의
 */
private val TEMPLATES: Map<Int, TemplateInfo> = mapOf(
    // 압구정 - 식스나잇
    50035 to TemplateInfo(
        templateId = 50035,
        templateName = "압구정 - 식스나잇",
        regionName = "압구정",
        messageContent = """[알림] 모드파티 참가자 필수공지

안녕하세요, 고객님 모드파티입니다!
참석하실 파티 안내문 보내드립니다 :)

[와인파티 안내문]
※ 일시: (금) 오후 7:30 ~ 9:30
※ 장소: 압구정 식스나잇

※JS가든 옆입니다! 입간판 SIX NIGHT 잘 보시고 들어오세요!
※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.
※ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매)

공지사항 꼭 확인하시고 참석해 주세요!
▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

※ 파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있습니다
   10분 전에 도착하셔서 만남의 기회를 꼭 잡으세요! 섬세하게 준비하겠습니다!
※ 혹시 문의사항 있으신가요? 문자로 이 메시지를 받으신 경우,
   아래 링크 접속 후 말씀 남겨주셔야 고객센터로 메시지가 전달됩니다! 감사합니다!""",
        smsContent = "[모드파티] 압구정 식스나잇 파티 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://naver.me/xeAfl4wW", "http://naver.me/xeAfl4wW"),
            TemplateButton("http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4", "http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4"),
            TemplateButton("http://chta.lk/ChatL1nk", "http://chta.lk/ChatL1nk")
        )
    ),

    // 역삼 - 시그널쿡 (버튼 없음)
    50046 to TemplateInfo(
        templateId = 50046,
        templateName = "역삼 - 시그널쿡",
        regionName = "역삼",
        messageContent = """모드파티 시그널쿡 참가 안내

● 모임 일정: 일요일 14:00~17:00

● 장소: 모드라운지 역삼점 (서울 강남구 테헤란로22길 11 B1층)
역삼역 인근으로 대중교통 이용을 권장드리며,
주차는 선착순으로 가능하나 주류가 제공되는 모임인 점 감안하셔서
대중 교통을 이용해 주세요! (역삼역 바로 인근)

※ 준비 시간 있습니다! 행사 15분 전까지 도착 부탁드립니다.
※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.

● 장소: 모드라운지 역삼점 (서울 강남구 테헤란로22길 11 B1층)
역삼역 인근으로 대중교통 이용을 권장드리며, 주차는 선착순으로 가능하나 주류가 제공되는 모임인 점 참고해주세요.

● 프로그램
1부 - 쉐프의 요리 강의(30분)
2부 - 조별 요리 체험(1시간)
3부 - 만찬 및 자리 로테이션(1시간 30분)

● 준비물: 따뜻한 마음가짐
요리도구, 앞치마 등은 현장에 준비되어 있습니다.

※ 복장은 상대방에 대한 예의입니다. 파티에 어울리는 단정한 복장으로 참석해주세요. (츄리닝 등은 지양)
※ 본 모임은 전문 요리 클래스보다는, 타 참가자와 요리를 통해 자연스럽게 어울리는 소셜 파티입니다. 열린 마음으로 참여 부탁드립니다.
※ 최대한 비슷한 연령대끼리 조를 구성합니다. 침묵보다는 대화를 중심으로 즐겨주세요.
※ 부득이하게 지각 또는 불참 시, 반드시 사전 연락 부탁드립니다. 조 편성에 매우 중요한 요소이며, 대기자가 많은 행사입니다.
※ 장소는 대관 인기 공간으로 자유로운 분위기입니다. 사진도 자유롭게 남겨가세요.
※ 행사 시간상 모든 분과의 로테이션은 어렵습니다. 못다 한 인연은 2차 뒤풀이에서 이어주세요. (현장 수요 조사 후 진행 예정)
https://chta.lk/ChatL1nk""",
        smsContent = "[모드파티] 역삼 시그널쿡 참가 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = emptyList()
    ),

    // 이태원 - 원사이즈
    50036 to TemplateInfo(
        templateId = 50036,
        templateName = "이태원 - 원사이즈",
        regionName = "이태원",
        messageContent = """[알림] 모드파티 참가자 필수공지

안녕하세요, 고객님 모드파티입니다!
참석하실 파티 안내문 보내드립니다 :)

[와인파티 안내문]
※ 일시: (토) 오후 7:00 ~ 9:00
※ 장소: 이태원 원사이즈


※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.
※ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매)

공지사항 꼭 확인하시고 참석해 주세요!
▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

※ 파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있습니다
   10분 전에 도착하셔서 만남의 기회를 꼭 잡으세요! 섬세하게 준비하겠습니다!
※ 혹시 문의사항 있으신가요? 문자로 이 메시지를 받으신 경우,
   아래 링크 접속 후 말씀 남겨주셔야 고객센터로 메시지가 전달됩니다! 감사합니다!""",
        smsContent = "[모드파티] 이태원 원사이즈 파티 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://naver.me/G1w73T3L", "http://naver.me/G1w73T3L"),
            TemplateButton("http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4", "http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4"),
            TemplateButton("http://chta.lk/ChatL1nk", "http://chta.lk/ChatL1nk")
        )
    ),

    // 청담 - 보타닉
    50037 to TemplateInfo(
        templateId = 50037,
        templateName = "청담 - 보타닉",
        regionName = "청담",
        messageContent = """[알림] 모드파티 참가자 필수공지

안녕하세요, 고객님 모드파티입니다!
참석하실 파티 안내문 보내드립니다 :)

[와인파티 안내문]
※ 일시: (토) 오후 6:00 ~ 8:00
※ 장소: 청담 보타닉


※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.
※ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매)

공지사항 꼭 확인하시고 참석해 주세요!
▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

※ 파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있습니다
   10분 전에 도착하셔서 만남의 기회를 꼭 잡으세요! 섬세하게 준비하겠습니다!
※ 혹시 문의사항 있으신가요? 문자로 이 메시지를 받으신 경우,
   아래 링크 접속 후 말씀 남겨주셔야 고객센터로 메시지가 전달됩니다! 감사합니다!""",
        smsContent = "[모드파티] 청담 보타닉 파티 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://naver.me/IDFUWk1H", "http://naver.me/IDFUWk1H"),
            TemplateButton("http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4", "http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4"),
            TemplateButton("http://chta.lk/ChatL1nk", "http://chta.lk/ChatL1nk")
        )
    ),

    // 인천 - 그리너리하우스
    50038 to TemplateInfo(
        templateId = 50038,
        templateName = "인천 - 그리너리하우스",
        regionName = "인천",
        messageContent = """[알림] 모드파티 참가자 필수공지

안녕하세요, 고객님 모드파티입니다!
참석하실 파티 안내문 보내드립니다 :)

[와인파티 안내문]
※ 일시: (토) 오후 7:00 ~ 9:00
※ 장소: 그리너리하우스


※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.
※ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매 )

공지사항 꼭 확인하시고 참석해 주세요!
▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

※ 파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있습니다
   10분 전에 도착하셔서 만남의 기회를 꼭 잡으세요! 섬세하게 준비하겠습니다!
※ 혹시 문의사항 있으신가요? 문자로 이 메시지를 받으신 경우,
   아래 링크 접속 후 말씀 남겨주셔야 고객센터로 메시지가 전달됩니다! 감사합니다!""",
        smsContent = "[모드파티] 인천 그리너리하우스 파티 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://naver.me/GTnh4FU5", "http://naver.me/GTnh4FU5"),
            TemplateButton("http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4", "http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4"),
            TemplateButton("http://chta.lk/ChatL1nk", "http://chta.lk/ChatL1nk")
        )
    ),

    // 수원 - 카페도화 연무대점
    50039 to TemplateInfo(
        templateId = 50039,
        templateName = "수원 - 카페도화 연무대점",
        regionName = "수원",
        messageContent = """[알림] 모드파티 참가자 필수공지

안녕하세요, 고객님 모드파티입니다!
참석하실 파티 안내문 보내드립니다 :)

[와인파티 안내문]
※ 일시: (토) 19:00~21:00
※ 장소: 카페도화 연무대점(본점으로 가시면 안됩니다!!)

※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.
※ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매)

공지사항 꼭 확인하시고 참석해 주세요!
▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

※ 파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있습니다.
10분 전에 도착하셔서 만남의 기회를 꼭 잡으세요! 섬세하게 준비하겠습니다!
※ 혹시 문의사항 있으신가요? 문자로 이 메시지를 받으신 경우,
아래 링크 접속 후 말씀 남겨주셔야 고객센터로 메시지가 전달됩니다!
감사합니다!""",
        smsContent = "[모드파티] 수원 카페도화 연무대점 파티 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://naver.me/5LHs8ILt", "http://naver.me/5LHs8ILt"),
            TemplateButton("http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4", "http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4"),
            TemplateButton("http://chta.lk/ChatL1nk", "http://chta.lk/ChatL1nk")
        )
    ),

    // 대전 - 스페인261
    50040 to TemplateInfo(
        templateId = 50040,
        templateName = "대전 - 스페인261",
        regionName = "대전",
        messageContent = """[알림] 모드파티 참가자 필수공지

안녕하세요, 고객님 모드파티입니다!
참석하실 파티 안내문 보내드립니다 :)

[와인파티 안내문]
※ 일시: (토) 15:30 ~ 17:30
※ 장소: 봉명동 스페인261

※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.
※ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매)

공지사항 꼭 확인하시고 참석해 주세요!
▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

※지각 누적 시 모드파티 영구 참가 제한됩니다.
    파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있습니다.
   10분 전에 도착하셔서 만남의 기회를 꼭 잡으세요! 섬세하게 준비하겠습니다!
※ 혹시 문의사항 있으신가요? 문자로 이 메시지를 받으신 경우,
    아래 링크 접속 후 말씀 남겨주셔야 고객센터로 메시지가 전달됩니다!
감사합니다""",
        smsContent = "[모드파티] 대전 스페인261 파티 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://naver.me/5xjpAMol", "http://naver.me/5xjpAMol"),
            TemplateButton("http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4", "http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4"),
            TemplateButton("http://chta.lk/ChatL1nk", "http://chta.lk/ChatL1nk")
        )
    ),

    // 천안 - 마리노
    50041 to TemplateInfo(
        templateId = 50041,
        templateName = "천안 - 마리노",
        regionName = "천안",
        messageContent = """[알림] 모드파티 참가자 필수공지

안녕하세요, 고객님 모드파티입니다!
참석하실 파티 안내문 보내드립니다 :)

[와인파티 안내문]
※ 일시: (토) 17:00 ~ 19:00
※ 장소: 두정동 마리노 8층

※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.
※ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매))
공지사항 꼭 확인하시고 참석해 주세요!
▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

※지각 누적 시 모드파티 영구 참가 제한됩니다.
    파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있습니다.
   10분 전에 도착하셔서 만남의 기회를 꼭 잡으세요! 섬세하게 준비하겠습니다!
※ 혹시 문의사항 있으신가요? 문자로 이 메시지를 받으신 경우,
    아래 링크 접속 후 말씀 남겨주셔야 고객센터로 메시지가 전달됩니다!
감사합니다""",
        smsContent = "[모드파티] 천안 마리노 파티 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://naver.me/xwwU5du3", "http://naver.me/xwwU5du3"),
            TemplateButton("http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4", "http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4"),
            TemplateButton("http://chta.lk/ChatL1nk", "http://chta.lk/ChatL1nk")
        )
    ),

    // 대구 - 배디스트
    50042 to TemplateInfo(
        templateId = 50042,
        templateName = "대구 - 배디스트",
        regionName = "대구",
        messageContent = """[알림] 모드파티 참가자 필수공지

안녕하세요, 고객님 모드파티입니다!
참석하실 파티 안내문 보내드립니다 :)

[와인파티안내문]
※ 일시: (토) 19:30 ~ 21:30
※ 장소: 동성로 배디스트

※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.

※ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매))
공지사항 꼭 확인하시고 참석해 주세요!
▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있습니다.
10분 전에 도착하셔서 만남의 기회를 꼭 잡으세요! 섬세하게 준비하겠습니다!
※ 혹시 문의사항 있으신가요? 문자로 이 메시지를 받으신 경우,
아래 링크 접속 후 말씀 남겨주셔야 고객센터로 메시지가 전달됩니다.
감사합니다!""",
        smsContent = "[모드파티] 대구 배디스트 파티 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://naver.me/xs8ikK50", "http://naver.me/xs8ikK50"),
            TemplateButton("http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4", "http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4"),
            TemplateButton("http://chta.lk/ChatL1nk", "http://chta.lk/ChatL1nk")
        )
    ),

    // 부산 - 피크라운지
    50043 to TemplateInfo(
        templateId = 50043,
        templateName = "부산 - 피크라운지",
        regionName = "부산",
        messageContent = """[알림] 모드파티 참가자 필수공지

안녕하세요, 고객님 모드파티입니다!
내일 참석하실 파티 안내문 보내드립니다 :)

[부산 와인파티]
※ 일시: (토) 18:00 ~ 20:00
※ 장소: 해운대 피크라운지

※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.
※ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매)

아래의 공지사항 꼭 확인하시고 참석해 주세요!
▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있습니다.
10분 전에 도착하셔서 만남의 기회를 꼭 잡으세요! 섬세하게 준비하겠습니다!
※ 혹시 문의사항 있으신가요? 문자로 이 메시지를 받으신 경우,
   아래 링크 접속 후 말씀 남겨주셔야 고객센터로 메시지가 전달됩니다!
감사합니다!""",
        smsContent = "[모드파티] 부산 피크라운지 파티 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://naver.me/FfeOl39m", "http://naver.me/FfeOl39m"),
            TemplateButton("http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4", "http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4"),
            TemplateButton("http://chta.lk/ChatL1nk", "http://chta.lk/ChatL1nk")
        )
    ),

    // 광주 - 알베르
    50044 to TemplateInfo(
        templateId = 50044,
        templateName = "광주 - 알베르",
        regionName = "광주",
        messageContent = """[알림] 모드파티 참가자 필수공지

안녕하세요, 고객님 모드파티입니다!
참석하실 파티 안내문 보내드립니다 :)

[와인파티 안내문]
※ 일시: (토) 19:00 ~ 21:00
※ 장소: 상무지구 알베르


※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.
※ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매)

공지사항 꼭 확인하시고 참석해 주세요!
▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

※지각 누적 시 모드파티 영구 참가 제한됩니다.
    파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있습니다.
   10분 전에 도착하셔서 만남의 기회를 꼭 잡으세요! 섬세하게 준비하겠습니다!
※ 혹시 문의사항 있으신가요? 문자로 이 메시지를 받으신 경우,
    아래 링크 접속 후 말씀 남겨주셔야 고객센터로 메시지가 전달됩니다!
감사합니다""",
        smsContent = "[모드파티] 광주 알베르 파티 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://naver.me/5Rh0GLjM", "http://naver.me/5Rh0GLjM"),
            TemplateButton("http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4", "http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4"),
            TemplateButton("http://chta.lk/ChatL1nk", "http://chta.lk/ChatL1nk")
        )
    ),

    // 울산 - 카페 웰빈
    50045 to TemplateInfo(
        templateId = 50045,
        templateName = "울산 - 카페 웰빈",
        regionName = "울산",
        messageContent = """[알림] 모드파티 참가자 필수공지

안녕하세요, 고객님 모드파티입니다!
참석하실 파티 안내문 보내드립니다 :)

[와인파티 안내문]
※ 일시: (토) 17:30 ~ 19:30
※ 장소: 삼산동 카페 웰빈

※ 본인 확인 절차 후, 입장이 가능합니다!
※ 지각 누적 시 모드파티 영구 참가 제한됩니다.
※ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매)

공지사항 꼭 확인하시고 참석해 주세요!
▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

※지각 누적 시 모드파티 영구 참가 제한됩니다.
    파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있습니다.
   10분 전에 도착하셔서 만남의 기회를 꼭 잡으세요! 섬세하게 준비하겠습니다!
※ 혹시 문의사항 있으신가요? 문자로 이 메시지를 받으신 경우,
    아래 링크 접속 후 말씀 남겨주셔야 고객센터로 메시지가 전달됩니다!
감사합니다""",
        smsContent = "[모드파티] 울산 카페 웰빈 파티 안내문입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://naver.me/x67yMU3f", "http://naver.me/x67yMU3f"),
            TemplateButton("http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4", "http://www.modparty.co.kr/submit_profile_v1?utm_source=notion&utm_medium=referrer&utm_campaign=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_content=%EB%85%B8%EC%85%98_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C_%EC%99%80%EC%9D%B8%EB%AA%A8%EC%9E%84&utm_term=%EA%B2%B0%EC%A0%9C%ED%9A%8C%EC%9B%90_%ED%95%84%EC%88%98%EA%B0%80%EC%9D%B4%EB%93%9C%EC%95%88%EB%82%B4"),
            TemplateButton("http://chta.lk/ChatL1nk", "http://chta.lk/ChatL1nk")
        )
    ),

    // 일산 - 벨라시타점 스타라이트
    50064 to TemplateInfo(
        templateId = 50064,
        templateName = "일산 - 벨라시타점 스타라이트",
        regionName = "일산",
        messageContent = """안녕하세요, 고객님 모드파티입니다!
참석하실 파티 안내문 보내드립니다 :)

[와인파티 안내문]
✔ 일시: (토) 17:00 ~ 19:00
✔ 장소: 벨라시타점 스타라이트
https://naver.me/xv3Deo4s

✓ 본인 확인 절차 후, 입장이 가능합니다!
✓ 본 행사는 와인 파티로 논알콜은 제공되지 않습니다.(업체 별도 구매)

공지사항 꼭 확인하시고 참석해 주세요!

▼공지사항: 파티를 즐기는 tip▼
https://cafe.naver.com/s279999/5692

※지각 누적 시 참가 패널티가 적용되어 이후에 참가가 제한될 수 있어요!
    파티에 늦으시면 괜찮은 이성을 만날 기회를 놓치실 수 있으니 10분 전에 도착하셔서 좋은 첫인상과 만남의 기회를 꼭 잡으세요 :)
※ 혹시 문의사항 있으신가요? 아래 링크 접속 후 말씀 남겨주시면 고객센터로 연결됩니다.
modparty.channel.io""",
        smsContent = "[모드파티] 안녕하세요, 모드파티입니다! 참석하실 파티 안내문입니다. [와인파티 안내문] 일시: (토) 17:00~19:00 / 장소: 벨라시타점 스타라이트 https://naver.me/xv3Deo4s / 본인 확인 후 입장 가능합니다. 본 행사는 와인 파티로 논알콜은 제공되지 않습니다. 공지사항: https://cafe.naver.com/s279999/5692 / 지각 시 참가 패널티가 적용됩니다. 10분 전 도착 부탁드립니다. 문의: modparty.channel.io",
        buttons = listOf(
            TemplateButton("https://naver.me/xv3Deo4s", "https://naver.me/xv3Deo4s"),
            TemplateButton("https://cafe.naver.com/s279999/5692", "https://cafe.naver.com/s279999/5692"),
            TemplateButton("https://modparty.channel.io", "https://modparty.channel.io")
        )
    ),

    // 환불A - 대기자환불 (가변요소 포함)
    50062 to TemplateInfo(
        templateId = 50062,
        templateName = "환불A - 대기자환불",
        regionName = "환불",
        messageContent = """안녕하세요 #{NAME} 모드파티입니다.

요청하신 #{PRODUCT} 환불 신청이 정상적으로 완료되었습니다.

실제 반영까지 카드사 영업일 기준 3~5일 정도소요될 수 있는 점 양해 부탁드리겠습니다.

아쉬운 마음을 조금이나마 덜어드리고자, 다음 파티 신청 시 활용하실 수 있는 [보상 쿠폰]을 전달드립니다.

쿠폰은 발행일로부터 1개월간 사용 가능하며, 이후 자동 소멸되는 점 참고해주세요!

불편을 드려 다시 한번 진심으로 사과드리며, 다음번 파티 참여를 원하실 때는 빠르게 참가 확정 도와드리도록 끝까지 책임지고 노력하겠습니다 🙏

감사합니다.""",
        smsContent = "[모드파티] 환불 안내입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://modparty.co.kr/?coupon=DC06E6BD1F20C", "http://modparty.co.kr/?coupon=DC06E6BD1F20C")
        ),
        hasVariables = true,
        variables = listOf("#{NAME}", "#{PRODUCT}")
    ),

    // 환불B - 참가취소,변심 (가변요소 포함)
    50063 to TemplateInfo(
        templateId = 50063,
        templateName = "환불B - 참가취소,변심",
        regionName = "환불",
        messageContent = """안녕하세요 #{NAME} 모드파티입니다.

요청하신 #{PRODUCT} 환불 신청이 정상적으로 완료되었습니다.

실제 반영까지 카드사 영업일 기준 3~5일 정도소요될 수 있는 점 양해 부탁드리겠습니다.

아쉬운 마음을 조금이나마 덜어드리고자, 다음 파티 신청 시 활용하실 수 있는 [보상 쿠폰]을 전달드립니다.

쿠폰은 발행일로부터 1개월간 사용 가능하며, 이후 자동 소멸되는 점 참고해주세요!

불편을 드려 다시 한번 진심으로 사과드리며, 다음번 파티 참여를 원하실 때는 빠르게 참가 확정 도와드리도록 끝까지 책임지고 노력하겠습니다 🙏

감사합니다.""",
        smsContent = "[모드파티] 환불 안내입니다. 자세한 내용은 카카오톡을 확인해주세요.",
        buttons = listOf(
            TemplateButton("http://modparty.co.kr/?coupon=65EA686AEA16E", "http://modparty.co.kr/?coupon=65EA686AEA16E")
        ),
        hasVariables = true,
        variables = listOf("#{NAME}", "#{PRODUCT}")
    )
)
