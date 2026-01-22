package org.webnori.ordersyncoffice.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 지역별 상품코드 설정
 * - 동기화 대상 상품코드 목록
 * - 상품코드별 지역명 매핑
 *
 * application.yml에서 설정:
 * sync:
 *   regions:
 *     - codes: [248]
 *       name: 압구정
 *     - codes: [54]
 *       name: 역삼
 */
@Configuration
@ConfigurationProperties(prefix = "sync")
class RegionConfig {

    private val logger = LoggerFactory.getLogger(RegionConfig::class.java)

    /**
     * 지역 설정 목록
     */
    var regions: List<RegionMapping> = mutableListOf()

    /**
     * 상품코드 -> 지역명 매핑 (캐시)
     */
    private var regionMap: Map<Int, String> = emptyMap()

    /**
     * 동기화 대상 상품코드 목록 (캐시)
     */
    private var validProductCodes: Set<Int> = emptySet()

    @PostConstruct
    fun init() {
        buildCache()
        logger.info("=== Region Config Loaded ===")
        logger.info("Total regions: {}", regions.size)
        logger.info("Valid product codes: {}", validProductCodes)
        regions.forEach { region ->
            logger.info("  {} -> codes: {}", region.name, region.codes)
        }
    }

    /**
     * 캐시 빌드
     */
    private fun buildCache() {
        val mapBuilder = mutableMapOf<Int, String>()
        val codesBuilder = mutableSetOf<Int>()

        regions.forEach { region ->
            region.codes.forEach { code ->
                mapBuilder[code] = region.name
                codesBuilder.add(code)
            }
        }

        regionMap = mapBuilder.toMap()
        validProductCodes = codesBuilder.toSet()
    }

    /**
     * 상품번호로 지역명 조회
     */
    fun getRegionName(prodNo: Int?): String? {
        return prodNo?.let { regionMap[it] }
    }

    /**
     * 동기화 대상 상품코드인지 확인
     */
    fun isValidProductCode(prodNo: Int?): Boolean {
        if (validProductCodes.isEmpty()) {
            // 설정이 없으면 모든 상품 허용
            return true
        }
        return prodNo != null && validProductCodes.contains(prodNo)
    }

    /**
     * 동기화 대상 상품코드 목록 반환
     */
    fun getValidProductCodes(): Set<Int> = validProductCodes

    /**
     * 전체 지역명 목록 반환
     */
    fun getRegionNames(): List<String> {
        return regions.map { it.name }.distinct()
    }

    /**
     * 지역명으로 상품코드 목록 조회
     */
    fun getProductCodesByRegion(regionName: String): List<Int> {
        return regions.find { it.name == regionName }?.codes ?: emptyList()
    }

    /**
     * 설정 리로드 (런타임 변경 지원용)
     */
    fun reload() {
        buildCache()
        logger.info("Region config reloaded. Valid codes: {}", validProductCodes)
    }
}

/**
 * 지역 매핑 설정
 */
class RegionMapping {
    var codes: List<Int> = mutableListOf()
    var name: String = ""
}
