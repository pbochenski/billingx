package com.pixite.billingx

import android.arch.lifecycle.LiveData
import android.support.v4.app.FragmentActivity
import android.util.Log
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.SkuType
import com.pixite.android.billingx.SkuDetailsBuilder
import org.threeten.bp.Instant
import org.threeten.bp.Period
import org.threeten.bp.temporal.ChronoUnit

/**
 * BillingManager allows for direct interaction with billing state, via the Google Play Store's
 * InAppBilling APIs.
 */
class BillingManager(private val activity: FragmentActivity,
                     billingClientFactory: BillingClientFactory,
                     private val subscriptionRepo: SubscriptionRepository,
                     private val executors: AppExecutors) {

  companion object {
    const val SKU_SUBS = "com.example.subscription"
    const val SKU_PURCHASE = "com.example.purchase"
  }

  private val connectionLock: Any = Any()
  private var connecting = false
  private val onConnection: MutableList<() -> Unit> = mutableListOf()

  private val billingClient: BillingClient
  private var serviceConnected = false
  private val purchaseUpdateListener: PurchasesUpdatedListener =
      PurchasesUpdatedListener { responseCode, purchases ->
        if (responseCode.responseCode != BillingClient.BillingResponseCode.OK) {
          // TODO handle errors
          when (responseCode.responseCode) {
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
              restorePurchases()
            }
          }
          return@PurchasesUpdatedListener
        }

        if (purchases?.isNotEmpty() == true) {
          findValidSubscription(purchases) {
            subscriptionRepo.setSubscribed(it != null)
          }
        }
      }

  init {
    billingClient = billingClientFactory.createBillingClient(activity, purchaseUpdateListener)
    startServiceConnection {
      // load local purchases from the cache
      executors.networkIO.execute {
        val purchases = billingClient.queryPurchases(SkuType.SUBS)
        val validPurchase = purchases.purchasesList.find { SKU_SUBS == it.sku }
        if (validPurchase != null) {
          subscriptionRepo.setSubscribed(true)
        } else {
          restorePurchases()
        }
      }
    }
  }

  fun restorePurchases() {
    executeServiceRequest {
      billingClient.queryPurchaseHistoryAsync(SkuType.SUBS) { responseCode, purchasesList ->
        if (responseCode.responseCode != BillingClient.BillingResponseCode.OK) {
          // todo handle the error
          return@queryPurchaseHistoryAsync
        }

        findValidSubscription(purchasesList.map { Purchase(it.originalJson, it.signature) }) {
          subscriptionRepo.setSubscribed(it != null)
        }
      }
    }
  }

  private fun findValidSubscription(purchasesList: List<Purchase>, callback: (Purchase?) -> Unit) {
    if (purchasesList.isEmpty()) {
      callback(null)
      return
    }

    val validSkus = listOf(SKU_SUBS)
    val validPurchases = purchasesList.filter { validSkus.contains(it.sku) }

    // look for active subscriptions
    val activeSubscription = validPurchases.find { it.isAutoRenewing }
    if (activeSubscription != null) {
      callback(activeSubscription)
      return
    }

    // look for any other subscriptions that are still in their period
    val distinctSkus = validPurchases.map { it.sku }.distinct()
    if (distinctSkus.isEmpty()) {
      callback(null)
      return
    }

    querySkuDetails(distinctSkus) { details ->
      validPurchases.forEach { purchase ->
        val purchaseDetails = details.find { it.sku == purchase.sku }
        val purchaseTime = Instant.ofEpochMilli(purchase.purchaseTime)

        // first check if they're still in the trial period
        if (purchaseDetails?.freeTrialPeriod != null && purchaseDetails.freeTrialPeriod.isNotBlank()) {
          val trialLength = Period.parse(purchaseDetails.freeTrialPeriod)
          val trialExpiration = purchaseTime.plus(trialLength.toTotalDays(), ChronoUnit.DAYS)
          if (trialExpiration.isAfter(Instant.now())) {
            callback(purchase)
            return@querySkuDetails
          }
        } else if (purchaseDetails?.subscriptionPeriod != null && purchaseDetails.subscriptionPeriod.isNotBlank()) {
          // then check if they're in their current billing cycle
          val subsLength = Period.parse(purchaseDetails.subscriptionPeriod)
          val expiration = purchaseTime.plus(subsLength.toTotalDays(), ChronoUnit.DAYS)
          if (expiration.isAfter(Instant.now())) {
            callback(purchase)
            return@querySkuDetails
          }
        }
      }

      // no valid purchases found
      callback(null)
    }
  }

  private fun Period.toTotalDays() = this.years * 12L + this.months * 30L + this.days

  fun getPrices(vararg skus: String): LiveData<List<SkuDetails>> {
    return object : LiveData<List<SkuDetails>>() {
      override fun onActive() {
        querySkuDetails(skus.toList()) {
          postValue(it)
        }
      }
    }
  }

  fun querySkuDetails(skus: List<String>, callback: (details: List<SkuDetails>) -> Unit) {
    val params = SkuDetailsParams.newBuilder()
        .setType(SkuType.SUBS)
        .setSkusList(skus)
        .build()
    executeServiceRequest {
      billingClient.querySkuDetailsAsync(params) { responseCode, skuDetailsList ->
        if (responseCode.responseCode != BillingClient.BillingResponseCode.OK) {
          // todo deal with this
          return@querySkuDetailsAsync
        }

        callback(skuDetailsList)
      }
    }
  }

  fun initiatePurchase(sku: String) {
    executeServiceRequest {
      val params = BillingFlowParams.newBuilder()
              .setSkuDetails(SkuDetailsBuilder(sku, SkuType.SUBS, "", 0L, "", "", "").build())
          .build()
      billingClient.launchBillingFlow(activity, params)
    }
  }

  private fun startServiceConnection(onSuccess: (() -> Unit)?) {
    if (connecting) {
      synchronized(connectionLock) {
        if (connecting) {
          onSuccess?.let { onConnection.add(it) }
          return
        } else if (serviceConnected) {
          onSuccess?.invoke()
          return
        } else {
          Log.e("BillingManager", "Service connection failed.")
        }
      }
    }

    connecting = true
    billingClient.startConnection(object : BillingClientStateListener {
      override fun onBillingSetupFinished(billingResult: BillingResult) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
          serviceConnected = true
          onSuccess?.invoke()
        }

        synchronized(connectionLock) {
          val callbacks = onConnection.toList()
          onConnection.clear()
          callbacks.forEach { it.invoke() }
        }
        connecting = false
      }

      override fun onBillingServiceDisconnected() {
        serviceConnected = false
      }
    })
  }

  private fun executeServiceRequest(request: () -> Unit) {
    if (serviceConnected) {
      request()
    } else {
      startServiceConnection(request)
    }
  }

  fun destroy() {
    if (serviceConnected) {
      billingClient.endConnection()
    }
  }

}
