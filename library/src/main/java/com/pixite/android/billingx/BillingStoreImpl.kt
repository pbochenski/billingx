package com.pixite.android.billingx

import android.content.SharedPreferences
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.SkuType
import com.android.billingclient.api.Purchase.PurchasesResult
import org.json.JSONArray
import org.json.JSONObject

class BillingStoreImpl(private val prefs: SharedPreferences) : BillingStore(){

  companion object {
    internal const val KEY_PURCHASES = "dbc_purchases"
    internal const val KEY_SKU_DETAILS = "dbc_sku_details"
  }

  override fun getSkuDetails(params: SkuDetailsParams): List<SkuDetails> {
    return prefs.getString(KEY_SKU_DETAILS, "[]").toSkuDetailsList()
        .filter { it.sku in params.skusList && it.type == params.skuType }
  }

  override fun getPurchases(@SkuType skuType: String): PurchasesResult {
    return InternalPurchasesResult(BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
        prefs.getString(KEY_PURCHASES, "[]").toPurchaseList()
            .filter { it.signature.endsWith(skuType) })
  }

  override fun getPurchaseByToken(purchaseToken: String): Purchase? {
    return prefs.getString(KEY_PURCHASES, "[]").toPurchaseList()
        .firstOrNull { it.purchaseToken == purchaseToken }
  }

  override fun addProduct(skuDetails: SkuDetails): BillingStore {
    val allDetails = JSONArray(prefs.getString(KEY_SKU_DETAILS, "[]"))
    allDetails.put(skuDetails.toJSONObject())
    prefs.edit().putString(KEY_SKU_DETAILS, allDetails.toString()) .apply()
    return this
  }

  override fun removeProduct(sku: String): BillingStore {
    val allDetails = prefs.getString(KEY_SKU_DETAILS, "[]").toSkuDetailsList()
    val filtered = allDetails.filter { it.sku != sku }
    val json = JSONArray()
    filtered.forEach { json.put(it.toJSONObject()) }
    prefs.edit().putString(KEY_SKU_DETAILS, json.toString()).apply()
    return this
  }

  override fun clearProducts(): BillingStore {
    prefs.edit().remove(KEY_SKU_DETAILS).apply()
    return this
  }

  override fun addPurchase(purchase: Purchase): BillingStore {
    val allPurchases = JSONArray(prefs.getString(KEY_PURCHASES, "[]"))
    allPurchases.put(purchase.toJSONObject())
    prefs.edit().putString(KEY_PURCHASES, allPurchases.toString()) .apply()
    return this
  }

  override fun removePurchase(purchaseToken: String): BillingStore {
    val allPurchases = prefs.getString(KEY_PURCHASES, "[]").toPurchaseList()
    val filtered = allPurchases.filter { it.purchaseToken != purchaseToken }
    val json = JSONArray()
    filtered.forEach { json.put(it.toJSONObject()) }
    prefs.edit().putString(KEY_PURCHASES, json.toString()).apply()
    return this
  }

  override fun clearPurchases(): BillingStore {
    prefs.edit().remove(KEY_PURCHASES).apply()
    return this
  }

  private fun Purchase.toJSONObject(): JSONObject =
      JSONObject().put("purchase", JSONObject(originalJson)).put("signature", signature)

  private fun JSONObject.toPurchase(): Purchase =
      Purchase(this.getJSONObject("purchase").toString(), this.getString("signature"))

  private fun SkuDetails.toJSONObject(): JSONObject = JSONObject(originalJson)

  private fun JSONObject.toSkuDetails(): SkuDetails = SkuDetails(toString())

  private fun String.toPurchaseList(): List<Purchase> {
    val list = mutableListOf<Purchase>()
    val array = JSONArray(this)
    (0 until array.length()).mapTo(list) { array.getJSONObject(it).toPurchase() }
    return list
  }

  private fun String.toSkuDetailsList(): List<SkuDetails> {
    val list = mutableListOf<SkuDetails>()
    val array = JSONArray(this)
    (0 until array.length()).mapTo(list) { array.getJSONObject(it).toSkuDetails() }
    return list
  }

}