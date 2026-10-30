package com.vision.pos.printer

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import com.vision.pos.printer.bridge.NativePrinterBridge
import com.vision.pos.printer.bridge.PosPrinterShim
import com.vision.pos.printer.bridge.ResponsiveLayoutShim
import com.vision.pos.printer.printer.PrinterManager

/**
 * WebView host for the POS web app.
 *
 * Loads [BuildConfig.POS_URL], injects the printing bridge ([NativePrinterBridge] and
 * [PosPrinterShim]) and shows a retry screen instead of a raw WebView error page when
 * the page cannot be reached. Printing itself lives in
 * [com.vision.pos.printer.printer].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: NativePrinterBridge

    /** The loading overlay (logo + spinner); see `@id/loading` in activity_main.xml. */
    private lateinit var loading: View
    private lateinit var errorView: View
    private lateinit var errorMessage: TextView
    private lateinit var refreshButton: ImageButton

    /** True while the main page failed to load, so `onPageFinished` can't clear the error. */
    private var mainFrameFailed = false

    /** Resolves when the operator answers the runtime Bluetooth permission dialog. */
    private var pendingBluetoothPermission: CompletableDeferred<Boolean>? = null

    /** Long-press bookkeeping for the hidden maintenance menu. */
    private var touchDownAt = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchMoved = false

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pendingBluetoothPermission?.complete(result.values.all { it })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideStatusBar()

        webView = findViewById(R.id.posWebView)
        loading = findViewById(R.id.loading)
        errorView = findViewById(R.id.errorView)
        errorMessage = findViewById(R.id.errorMessage)
        findViewById<Button>(R.id.retryButton).setOnClickListener { retry() }
        refreshButton = findViewById(R.id.refreshButton)
        refreshButton.setOnClickListener { confirmReload() }

        configureWebView()

        // Resume the page the operator was on when the shell restarted: a renderer
        // death leaves it in [restoreUrl], and an Android process kill leaves it in the
        // saved instance state. Reloading BuildConfig.POS_URL instead is what used to
        // drop them back on the login screen.
        webView.loadUrl(
            savedInstanceState?.getString(STATE_URL) ?: restoreUrl ?: BuildConfig.POS_URL,
        )
        restoreUrl = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    /**
     * Remembers the current page across a process death, so a low-memory kill on a
     * cheap tablet does not send the operator back to the login screen.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) outState.putString(STATE_URL, webView.url)
    }

    override fun onDestroy() {
        // Lateinit-safe: onCreate may have thrown before these were assigned.
        if (::bridge.isInitialized) bridge.dispose()
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    /** Hides the status bar. The navigation bar stays, so back and home still work. */
    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            // The POS declares width=device-width. Let its responsive layout use
            // the actual window instead of zooming out to fit an overflowing pane.
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // The POS is served over the network, so none of these are needed.
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }

        // The POS scrolls its own lists. The WebView's overscroll glow/stretch made
        // scrolling a menu to its end look like the page had reloaded.
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        bridge = NativePrinterBridge(
            webView,
            PrinterManager(applicationContext) { requestBluetoothPermission() },
        )
        webView.addJavascriptInterface(bridge, NativePrinterBridge.BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                mainFrameFailed = false
                view.evaluateJavascript(PosPrinterShim.JS, null)
                view.evaluateJavascript(OVERSCROLL_CSS, null)
                view.evaluateJavascript(ResponsiveLayoutShim.JS, null)
                showLoading()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(PosPrinterShim.JS, null)
                view.evaluateJavascript(OVERSCROLL_CSS, null)
                view.evaluateJavascript(ResponsiveLayoutShim.JS, null)
                if (!mainFrameFailed) hideLoading()
                verifyRendered(view)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                // Keep http/https (the POS and any web flow it uses) inside the app.
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                // tel:, mailto:, market:, intent: and similar go to the system instead
                // of letting a stray link navigate the POS shell.
                openExternally(uri)
                return true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return

                // WebView reports ERROR_UNKNOWN (-1) for aborted navigations such as
                // redirects and download links. That is not an outage, so only stop
                // the spinner and leave the page alone.
                if (error.errorCode == ERROR_UNKNOWN) {
                    hideLoading()
                    return
                }

                mainFrameFailed = true
                showError()
            }

            /**
             * A server that answers but is broken (a maintenance page, or a 5xx from a
             * dead backend) is still a failure the operator has to be able to reload
             * from, and it never reaches [onReceivedError] because the response itself
             * is a valid one.
             */
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (!request.isForMainFrame) return
                if (errorResponse.statusCode >= 500) {
                    mainFrameFailed = true
                    showError()
                }
            }

            /**
             * The renderer can be killed under memory pressure, which happens on cheap
             * tablets. Returning false here kills the app, so rebuild the shell instead.
             */
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                val now = SystemClock.elapsedRealtime()
                if (now - lastRendererRestartAt > RENDERER_RESTART_WINDOW_MS) rendererRestarts = 0
                lastRendererRestartAt = now
                rendererRestarts++

                return if (rendererRestarts <= MAX_RENDERER_RESTARTS) {
                    // The dead WebView is destroyed in onDestroy().
                    restoreUrl = view.url
                    recreate()
                    true
                } else {
                    showError()
                    true
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showLoading() {
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        loading.visibility = View.VISIBLE
        refreshButton.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loading.visibility = View.GONE
    }

    private fun showError() {
        loading.visibility = View.GONE
        webView.visibility = View.GONE
        errorMessage.text = getString(R.string.error_body)
        errorView.visibility = View.VISIBLE
        // The offline screen carries its own Refresh button; two would be confusing.
        refreshButton.visibility = View.GONE
    }

    private fun retry() {
        showLoading()
        webView.reload()
    }

    /**
     * Operator-triggered reload, for picking up a POS redeploy without restarting the
     * app. Confirmed first, because a stray tap mid-sale would reload the page away
     * underneath whoever is using it.
     */
    private fun confirmReload() {
        AlertDialog.Builder(this)
            .setTitle(R.string.refresh)
            .setMessage(R.string.refresh_confirm)
            .setPositiveButton(R.string.refresh) { _, _ -> retry() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Reload is the only way out of a POS that "loaded" but renders nothing, so treat
     * an empty document as a failure. The check is deliberately strict (no text *and*
     * no child elements) because a single-page app loads as an empty root element
     * before its JavaScript mounts the real content.
     */
    private fun verifyRendered(view: WebView) {
        view.evaluateJavascript(EMPTY_DOCUMENT_PROBE) { result ->
            val parts = result?.trim('"')?.split('|').orEmpty()
            val characters = parts.getOrNull(0)?.toIntOrNull() ?: return@evaluateJavascript
            val children = parts.getOrNull(1)?.toIntOrNull() ?: return@evaluateJavascript
            if (!mainFrameFailed && characters == 0 && children == 0) {
                mainFrameFailed = true
                showError()
            }
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_title, Toast.LENGTH_SHORT).show()
        }
    }

    /** Requests the Bluetooth permissions on first use and reports whether they ended up granted. */
    private suspend fun requestBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        if (hasBluetoothPermission()) return true

        val deferred = CompletableDeferred<Boolean>()
        pendingBluetoothPermission = deferred
        withContext(Dispatchers.Main) { bluetoothPermissionLauncher.launch(BLUETOOTH_PERMISSIONS) }
        val answered = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() } ?: false
        pendingBluetoothPermission = null
        return answered && hasBluetoothPermission()
    }

    private fun hasBluetoothPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Hidden maintenance menu: long-press the top strip of the screen. The event is
     * never consumed, so the POS keeps receiving all of its normal taps. This is the
     * "reload button" without giving up any screen space to a toolbar.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownAt = ev.eventTime
                touchDownX = ev.x
                touchDownY = ev.y
                touchMoved = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> touchMoved = true // multi-touch

            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - touchDownX) > TOUCH_SLOP_PX ||
                    abs(ev.y - touchDownY) > TOUCH_SLOP_PX
                ) {
                    touchMoved = true
                }
            }

            // A cancelled gesture means the system took over, for example the
            // notification shade, and a second finger means a pinch. Neither is a
            // request for the maintenance menu, so only a stationary release counts.
            MotionEvent.ACTION_UP -> {
                val heldFor = ev.eventTime - touchDownAt
                val inTopStrip =
                    touchDownY <= resources.displayMetrics.heightPixels * TOP_STRIP_RATIO
                if (!touchMoved && inTopStrip && heldFor >= LONG_PRESS_MS) showMaintenanceMenu()
            }

            MotionEvent.ACTION_CANCEL -> touchMoved = true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showMaintenanceMenu() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setItems(
                arrayOf(
                    getString(R.string.admin_reload),
                    getString(R.string.admin_clear_cache),
                ),
            ) { _, which ->
                if (which == 1) {
                    webView.clearCache(true)
                    webView.clearHistory()
                }
                retry()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        /** Renderer restarts are throttled so a permanently crashing page can't loop us. */
        const val RENDERER_RESTART_WINDOW_MS = 60_000L
        const val MAX_RENDERER_RESTARTS = 3

        // Static on purpose: recreate() builds a new Activity, so instance counters
        // would reset on every restart and the throttle above would never trip.
        @Volatile
        private var rendererRestarts = 0

        @Volatile
        private var lastRendererRestartAt = 0L

        /** Page to resume after a renderer restart; consumed once by onCreate. */
        @Volatile
        private var restoreUrl: String? = null

        /** Reports the rendered document as "<text length>|<child element count>". */
        private const val EMPTY_DOCUMENT_PROBE =
            "(function(){try{var b=document.body;" +
                "return b?(b.innerText.trim().length+'|'+b.children.length):'0|0'}" +
                "catch(e){return '0|0'}})()"

        /** Saved-instance-state key holding the page to resume. */
        private const val STATE_URL = "posUrlState"

        /**
         * Injected on every page load. `overscroll-behavior:none` stops the document —
         * or any inner scroller such as the side menu — from handing an overscroll
         * gesture up to the WebView, which is what turns "scroll the menu to the end,
         * then slide back to the top" into a pull-to-refresh that reloads the POS.
         * Idempotent and silent: it retries until the document has a head to write to.
         */
        private const val OVERSCROLL_CSS =
            "(function(){if(window.__posOverscrollFixed){return;}try{" +
                "var s=document.createElement('style');" +
                "s.textContent='html,body{overscroll-behavior:none}';" +
                "var h=document.head||document.documentElement;if(!h){return;}" +
                "h.appendChild(s);window.__posOverscrollFixed=true;}catch(e){}})();"

        const val PERMISSION_TIMEOUT_MS = 60_000L
        const val LONG_PRESS_MS = 1_500L
        const val TOUCH_SLOP_PX = 40f
        const val TOP_STRIP_RATIO = 0.12f

        val BLUETOOTH_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
    }
}
