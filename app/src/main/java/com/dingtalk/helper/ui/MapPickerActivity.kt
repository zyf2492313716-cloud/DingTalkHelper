package com.dingtalk.helper.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dingtalk.helper.R
import com.dingtalk.helper.utils.ConfigManager

/**
 * 地图选点 Activity
 * 使用 WebView 加载 OpenStreetMap，用户点击选择位置后返回坐标
 */
class MapPickerActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 简单使用 WebView 作为内容视图
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // C7 修复：关闭文件访问，防止潜在的安全风险
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
        }

        setContentView(webView)

        // 添加 JavaScript 接口
        webView.addJavascriptInterface(MapInterface(), "Android")

        // 加载地图 HTML
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 传递当前配置的坐标作为初始位置
                try {
                    val lat = ConfigManager.getLatitude()
                    val lng = ConfigManager.getLongitude()
                    view?.evaluateJavascript(
                        "setInitialPosition($lat, $lng)",
                        null
                    )
                } catch (_: Exception) {
                    // ConfigManager 可能未初始化，使用默认坐标
                }
            }
        }

        webView.loadUrl("file:///android_asset/map.html")
    }

    /**
     * C7 修复：清理 WebView 资源，防止内存泄漏
     */
    override fun onDestroy() {
        webView.apply {
            stopLoading()
            loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * JavaScript 接口 - 接收地图选择的坐标
     */
    inner class MapInterface {

        @JavascriptInterface
        fun onLocationSelected(lat: Double, lng: Double, address: String) {
            runOnUiThread {
                val intent = Intent().apply {
                    putExtra(EXTRA_LATITUDE, lat)
                    putExtra(EXTRA_LONGITUDE, lng)
                    putExtra(EXTRA_ADDRESS, address)
                }
                setResult(RESULT_OK, intent)
                Toast.makeText(
                    this@MapPickerActivity,
                    "已选择: ${String.format("%.6f", lat)}, ${String.format("%.6f", lng)}",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ADDRESS = "address"
        const val REQUEST_CODE_MAP_PICKER = 1001
    }
}
