package com.chaiandtapri.counter

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

data class SyncCatalogueSnapshot(val shopId: String, val serverRevision: Long, val products: List<TapriProduct>)

/** Downloads only the authenticated shop's validated catalogue snapshot, never its raw event journal. */
class SyncCatalogueClient(private val transport: SyncHttpTransport) {
    fun fetch(endpointBase: String, identity: SyncEnvelopeIdentity, bearerToken: String): SyncCatalogueSnapshot {
        require(identity.businessId.isUuid() && identity.shopId.isUuid() && identity.deviceId.isUuid()) { "Sync shop identity is invalid." }
        require(bearerToken.isNotBlank()) { "An authenticated sync session is required." }
        val base = endpointBase.trim().trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull()
            ?: throw IllegalArgumentException("Enter a valid HTTPS sync server URL.")
        require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Sync requires an HTTPS server URL without embedded credentials, query, or fragment."
        }
        val endpoint = "$base/v1/sync/catalogue/${URLEncoder.encode(identity.shopId, Charsets.UTF_8.name())}"
        val response = transport.get(endpoint, bearerToken)
        if (response.statusCode !in 200..299) throw SyncRequestException(response.statusCode)
        return parse(response.body, identity.shopId)
    }

    private fun parse(body: String, expectedShopId: String): SyncCatalogueSnapshot = try {
        val root = JSONObject(body)
        if (root.getString("shopId") != expectedShopId) throw SyncProtocolException("Catalogue response shop did not match the active shop.")
        val revision = root.getLong("serverRevision")
        if (revision < 0) throw SyncProtocolException("Catalogue response revision was invalid.")
        val array = root.getJSONArray("catalogue")
        if (array.length() > MAX_PRODUCTS || root.getInt("catalogueCount") != array.length()) throw SyncProtocolException("Catalogue response count was invalid.")
        val products = buildList {
            val ids = mutableSetOf<String>()
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                if (item.keys().asSequence().toSet() != PRODUCT_KEYS) throw SyncProtocolException("Catalogue response contained an unsupported field.")
                val id = item.getString("productId")
                val name = item.getString("name")
                val category = item.getString("category")
                val price = item.getLong("unitPricePaise")
                val unit = item.getString("unitLabel")
                val purchaseUnit = item.getString("purchaseUnitLabel")
                val factor = item.getLong("baseUnitsPerPurchaseUnit")
                val aliases = item.getJSONArray("aliases")
                if (!id.matches(PRODUCT_ID) || !ids.add(id) || name.length !in 2..60 || name.trim() != name || name.any(Char::isISOControl) ||
                    category.length !in 2..40 || category.trim() != category || category.any(Char::isISOControl) || price !in 0L..10_000_000L ||
                    factor !in 1L..100_000L || aliases.length() > 50 || !unit.matches(UNIT_LABEL) || !purchaseUnit.matches(UNIT_LABEL)) {
                    throw SyncProtocolException("Catalogue response product was invalid.")
                }
                if (item.getBoolean("active") && item.getBoolean("priceConfirmed") && price == 0L) {
                    throw SyncProtocolException("Active catalogue product has an unpriced zero value.")
                }
                val aliasValues = buildList {
                    repeat(aliases.length()) { aliasIndex ->
                        val alias = aliases.getString(aliasIndex)
                        if (alias.length > 40 || alias.trim() != alias || alias.any(Char::isISOControl)) throw SyncProtocolException("Catalogue alias was invalid.")
                        add(alias)
                    }
                }
                val scanCode = if (item.isNull("scanCode")) null else item.getString("scanCode")
                val reorderThreshold = if (item.isNull("reorderThreshold")) null else item.getLong("reorderThreshold").also {
                    if (it !in 1L..100_000L) throw SyncProtocolException("Catalogue reorder threshold was invalid.")
                }.toInt()
                if (scanCode != null && (scanCode.isBlank() || scanCode.length > 128 || scanCode.any(Char::isISOControl))) {
                    throw SyncProtocolException("Catalogue scan code was invalid.")
                }
                add(TapriProduct(
                    productId = id, name = name, category = category, unitPricePaise = price.toInt(),
                    active = item.getBoolean("active"), favorite = item.getBoolean("favorite"),
                    priceConfirmed = item.getBoolean("priceConfirmed"), aliases = aliasValues,
                    reorderThreshold = reorderThreshold, scanCode = scanCode, unitLabel = unit, purchaseUnitLabel = purchaseUnit,
                    baseUnitsPerPurchaseUnit = factor.toInt()
                ))
            }
        }
        SyncCatalogueSnapshot(expectedShopId, revision, products)
    } catch (error: SyncProtocolException) {
        throw error
    } catch (error: Exception) {
        throw SyncProtocolException("Catalogue response was malformed.", error)
    }

    private fun String.isUuid() = runCatching { UUID.fromString(this).toString().equals(this, ignoreCase = true) }.getOrDefault(false)

    companion object {
        private const val MAX_PRODUCTS = 5000
        private val PRODUCT_ID = Regex("[a-z][a-z0-9_]{1,39}")
        private val UNIT_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9 _-]{0,29}")
        private val PRODUCT_KEYS = setOf("productId", "name", "category", "unitPricePaise", "active", "favorite", "priceConfirmed", "aliases", "scanCode", "reorderThreshold", "unitLabel", "purchaseUnitLabel", "baseUnitsPerPurchaseUnit")
    }
}
