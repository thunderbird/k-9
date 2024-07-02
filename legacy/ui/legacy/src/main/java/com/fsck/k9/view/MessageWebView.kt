package com.fsck.k9.view

import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.util.TypedValue
import android.webkit.WebSettings.LayoutAlgorithm
import android.webkit.WebSettings.RenderPriority
import android.webkit.WebView
import com.fsck.k9.mailstore.AttachmentResolver
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class MessageWebView : WebView, KoinComponent {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    private val webViewClientFactory: WebViewClientFactory by inject()

    fun blockNetworkData(shouldBlockNetworkData: Boolean) {
        // Images with content: URIs will not be blocked, nor will network images that are already in the WebView cache.
        try {
            settings.blockNetworkLoads = shouldBlockNetworkData
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to unblock network loads. Missing INTERNET permission?")
        }
    }

    fun configure(config: WebViewConfig) {
        isVerticalScrollBarEnabled = true
        setVerticalScrollbarOverlay(true)
        scrollBarStyle = SCROLLBARS_INSIDE_OVERLAY
        isLongClickable = true

        if (config.useDarkMode) {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)
            val backgroundColor = typedValue.data
            setBackgroundColor(backgroundColor)
        }

        with(settings) {
            setSupportZoom(true)
            builtInZoomControls = true
            useWideViewPort = true

            if (config.autoFitWidth) {
                loadWithOverviewMode = true
            }

            disableDisplayZoomControls()

            javaScriptEnabled = false
            loadsImagesAutomatically = true
            setRenderPriority(RenderPriority.HIGH)

            // TODO: Review alternatives. NARROW_COLUMNS is deprecated on KITKAT
            layoutAlgorithm = LayoutAlgorithm.NARROW_COLUMNS

            overScrollMode = OVER_SCROLL_NEVER

            textZoom = config.textZoom
        }

        // Disable network images by default. This is overridden by preferences.
        blockNetworkData(true)
    }

    private fun disableDisplayZoomControls() {
        val packageManager = context.packageManager
        val supportsMultiTouch = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_FAKETOUCH_MULTITOUCH_DISTINCT)

        settings.displayZoomControls = !supportsMultiTouch
    }

    fun displayHtmlContentWithInlineAttachments(
        htmlText: String,
        attachmentResolver: AttachmentResolver?,
        onPageFinishedListener: OnPageFinishedListener?,
    ) {
        setWebViewClient(attachmentResolver, onPageFinishedListener)
        setHtmlContent(htmlText)
    }

    private fun setWebViewClient(
        attachmentResolver: AttachmentResolver?,
        onPageFinishedListener: OnPageFinishedListener?,
    ) {
        val webViewClient = webViewClientFactory.create(attachmentResolver, onPageFinishedListener)
        setWebViewClient(webViewClient)
    }

    private fun setHtmlContent(htmlText: String) {
        loadDataWithBaseURL("about:blank", htmlText, "text/html", "utf-8", null)
        resumeTimers()
    }

    fun interface OnPageFinishedListener {
        fun onPageFinished()
    }
}
