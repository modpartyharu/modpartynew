package org.webnori.ordersyncoffice.repository

import org.springframework.stereotype.Repository
import org.webnori.ordersyncoffice.domain.AppVersion
import org.webnori.ordersyncoffice.domain.OAuthToken
import org.webnori.ordersyncoffice.domain.StoreInfo
import org.webnori.ordersyncoffice.mapper.AppVersionMapper
import org.webnori.ordersyncoffice.mapper.OAuthTokenMapper
import org.webnori.ordersyncoffice.mapper.StoreInfoMapper

@Repository
class AppVersionRepository(private val mapper: AppVersionMapper) {
    fun getLatestVersion(): AppVersion? = mapper.getLatestVersion()
}

@Repository
class StoreInfoRepository(private val mapper: StoreInfoMapper) {

    fun findBySiteCode(siteCode: String): StoreInfo? = mapper.findBySiteCode(siteCode)

    fun findAllActive(): List<StoreInfo> = mapper.findAllActive()

    fun findAll(): List<StoreInfo> = mapper.findAll()

    fun save(storeInfo: StoreInfo): StoreInfo {
        val existing = mapper.findBySiteCode(storeInfo.siteCode)
        return if (existing != null) {
            mapper.update(storeInfo)
            storeInfo.copy(id = existing.id)
        } else {
            mapper.insert(storeInfo)
            storeInfo
        }
    }

    fun delete(siteCode: String): Int = mapper.deleteBySiteCode(siteCode)
}

@Repository
class OAuthTokenRepository(private val mapper: OAuthTokenMapper) {

    fun findBySiteCode(siteCode: String): OAuthToken? = mapper.findBySiteCode(siteCode)

    fun save(oAuthToken: OAuthToken): OAuthToken {
        val existing = mapper.findBySiteCode(oAuthToken.siteCode)
        return if (existing != null) {
            mapper.update(oAuthToken)
            oAuthToken.copy(id = existing.id)
        } else {
            mapper.insert(oAuthToken)
            oAuthToken
        }
    }

    fun delete(siteCode: String): Int = mapper.deleteBySiteCode(siteCode)
}
