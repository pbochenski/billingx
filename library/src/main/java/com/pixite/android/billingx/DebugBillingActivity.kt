package com.pixite.android.billingx

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.support.v4.content.LocalBroadcastManager
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.SkuType
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.SkuDetails
import com.android.billingclient.api.SkuDetailsParams
import com.android.billingclient.util.BillingHelper
import java.util.Date

class DebugBillingActivity : AppCompatActivity() {

  companion object {
    internal const val RESPONSE_INTENT_ACTION = "proxy_activity_response_intent_action"
    internal const val RESPONSE_CODE = "response_code_key"
    internal const val RESPONSE_BUNDLE = "response_bundle_key"
    internal const val RESPONSE_INAPP_PURCHASE_DATA = "INAPP_PURCHASE_DATA"
    internal const val REQUEST_SKU_TYPE = "request_sku_type"
    internal const val REQUEST_SKU = "request_sku"
    private const val REQUEST_CODE = 100
  }

  private lateinit var title: TextView
  private lateinit var description: TextView
  private lateinit var price: TextView
  private lateinit var buyButton: Button

  private lateinit var prefs: SharedPreferences
  @SkuType private lateinit var skuType: String
  private lateinit var item: SkuDetails
  private lateinit var itemJson: String

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_debug_billing)

    prefs = getSharedPreferences("dbx", MODE_PRIVATE)

    title = findViewById(R.id.title)
    description = findViewById(R.id.description)
    price = findViewById(R.id.price)
    buyButton = findViewById(R.id.buy)

    val sku = intent.getStringExtra(REQUEST_SKU)
    skuType = intent.getStringExtra(REQUEST_SKU_TYPE)
    val items = BillingStore.defaultStore(this)
        .getSkuDetails(SkuDetailsParams.newBuilder()
            .setType(skuType)
            .setSkusList(listOf(sku))
            .build())
        .associate { it.sku to it }
    val it = items[sku]
    if (it == null) {
      Log.e("DBX", "Unknown $skuType sku: $sku")
      finish()
      return
    }
    item = it
    title.text = item.title
    description.text = item.description
    price.text = item.price

    // TODO get the response code from a spinner with options
    buyButton.setOnClickListener {
      broadcastResult(BillingClient.BillingResponseCode.OK, buildResultBundle(item.toPurchaseData(this, skuType)))
      finish()
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    window.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
  }

  override fun onBackPressed() {
    broadcastUserCanceled()
    super.onBackPressed()
  }

  override fun onTouchEvent(event: MotionEvent?): Boolean {
    if (event?.action == MotionEvent.ACTION_OUTSIDE) {
      broadcastUserCanceled()
      finish()
      return true
    }
    return super.onTouchEvent(event)
  }

  private fun broadcastUserCanceled() {
    broadcastResult(BillingClient.BillingResponseCode.USER_CANCELED, Bundle())
  }

  private fun buildResultBundle(purchase: Purchase): Bundle {
    return Bundle().apply {
      putInt(BillingHelper.RESPONSE_CODE, BillingClient.BillingResponseCode.OK)
      putStringArrayList(BillingHelper.RESPONSE_INAPP_PURCHASE_DATA_LIST, arrayListOf(purchase.originalJson))
      putStringArrayList(BillingHelper.RESPONSE_INAPP_SIGNATURE_LIST, arrayListOf(purchase.signature))
    }
  }

  private fun SkuDetails.toPurchaseData(context: Context, @SkuType skuType: String): Purchase {
    val json = """{"orderId":"$sku..0","packageName":"${context.packageName}","productId":
      |"$sku","autoRenewing":true,"purchaseTime":"${Date().time}","purchaseToken":
      |"0987654321"}""".trimMargin()
    return Purchase(json, "debug-signature-$sku-$skuType")
  }

  private fun broadcastResult(responseCode: Int, resultBundle: Bundle) {
    val intent = Intent(RESPONSE_INTENT_ACTION)
    intent.putExtra(RESPONSE_CODE, responseCode)
    intent.putExtra(RESPONSE_BUNDLE, resultBundle)
    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
  }
}