package com.example.share2link

import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.share2link.data.LinkRepository

object WebViewHelper {

    fun buildWebView(
        context: Context,
        index: Int,
        url: String,
        isClipboard: Boolean,
        linkId: String,
        webViewRefs: MutableMap<Int, WebView>,
        repo: LinkRepository
    ): WebView {
        return WebView(context.applicationContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Remove the WebView marker so sites treat this like a normal browser.
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            // Required for Google Sign-In and other OAuth popup windows.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    super.onPageFinished(view, pageUrl)
                    if (isClipboard && !pageUrl.isNullOrBlank() && pageUrl != "about:blank") {
                        repo.saveLastWebViewUrl(linkId, pageUrl)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    if (view == null || resultMsg == null) return false
                    val popup = WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        settings.setSupportMultipleWindows(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                v: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView?) {
                                view.removeView(window)
                            }
                        }
                    }
                    view.addView(
                        popup,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    val transport = resultMsg.obj as? WebView.WebViewTransport
                    transport?.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }

            loadUrl(url)
            tag = url
            webViewRefs[index] = this
            PopupWebViewCache.webViews[index] = this
        }
    }
}
