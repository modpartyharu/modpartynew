package org.webnori.ordersyncoffice.mapper

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface ProductRegionMappingMapper {

    /**
     * 사이트의 활성화된 모든 매핑 조회
     */
    fun findActiveBySiteCode(
        @Param("siteCode") siteCode: String
    ): List<Map<String, Any?>>

    /**
     * 특정 상품의 활성 매핑 조회
     */
    fun findActiveBySiteCodeAndProdNo(
        @Param("siteCode") siteCode: String,
        @Param("prodNo") prodNo: Int
    ): Map<String, Any?>?

    /**
     * 업서트 (존재하면 업데이트, 없으면 삽입)
     * @return 영향받은 행 수
     */
    fun upsert(
        @Param("siteCode") siteCode: String,
        @Param("prodNo") prodNo: Int,
        @Param("regionName") regionName: String
    ): Int

    /**
     * 비활성화
     * @return 영향받은 행 수
     */
    fun deactivate(
        @Param("siteCode") siteCode: String,
        @Param("prodNo") prodNo: Int
    ): Int
}
