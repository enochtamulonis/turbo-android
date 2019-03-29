package com.basecamp.turbolinks

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebView
import androidx.fragment.app.Fragment
import kotlin.random.Random

@Suppress("unused")
open class TurbolinksFragmentDelegate(fragment: TurbolinksFragment) :
        TurbolinksFragment by fragment, TurbolinksSessionCallback {

    val fragment = fragment as? Fragment ?:
        throw IllegalArgumentException("fragment must be a Fragment")

    private lateinit var location: String
    private val identifier = generateIdentifier()
    private var isInitialVisit = true
    private var isWebViewAttachedToNewDestination = false
    private var screenshot: Bitmap? = null
    private var screenshotOrientation = 0
    private var activity: TurbolinksActivity? = null
    private val turbolinksView: TurbolinksView?
        get() = onProvideTurbolinksView()
    private val turbolinksErrorPlaceholder: ViewGroup?
        get() = onProvideErrorPlaceholder()
    protected val webView: WebView?
        get() = session()?.webView

    open fun onWebViewAttached() {}

    open fun onWebViewDetached() {}

    fun onCreate(location: String) {
        this.location = location
    }

    fun onStart(activity: TurbolinksActivity) {
        this.activity = activity
        initNavigationVisit()
    }

    fun onStop() {
        this.activity = null
    }

    fun session(): TurbolinksSession? {
        return activity?.onProvideSession(fragment)
    }

    fun attachWebView(): Boolean {
        val view = turbolinksView ?: return false
        return view.attachWebView(requireNotNull(webView)).also {
            if (it) onWebViewAttached()
        }
    }

    fun detachWebView(destinationIsFinishing: Boolean, onDetached: () -> Unit) {
        val view = webView ?: return
        if (!destinationIsFinishing) {
            screenshotView()
        }

        onTitleChanged("")
        turbolinksView?.detachWebView(view)
        turbolinksView?.post { onDetached() }
        onWebViewDetached()
    }

    fun navigate(location: String, action: String = "advance") {
        activity?.navigate(location, action)
    }

    fun navigateUp() {
        activity?.navigateUp()
    }

    fun navigateBack() {
        activity?.navigateBack()
    }

    // -----------------------------------------------------------------------
    // TurbolinksSessionCallback interface
    // -----------------------------------------------------------------------

    override fun onPageStarted(location: String) {}

    override fun onPageFinished(location: String) {}

    override fun pageInvalidated() {}

    override fun visitRendered() {
        onTitleChanged(title())
        removeTransitionalViews()
    }

    override fun visitCompleted() {
        onTitleChanged(title())
        removeTransitionalViews()
    }

    override fun onReceivedError(errorCode: Int) {
        handleError(errorCode)
        removeTransitionalViews()
    }

    override fun requestFailedWithStatusCode(statusCode: Int) {
        handleError(statusCode)
        removeTransitionalViews()
    }

    override fun visitLocationStarted(location: String) {
        if (isWebViewAttachedToNewDestination) {
            showProgressView(location)
        }
    }

    override fun visitProposedToLocation(location: String, action: String,
                                         properties: PathProperties) {
        val navigated = activity?.navigate(location, action, properties)

        // In the case of a NONE presentation, reload the page with fresh data
        if (navigated == false) {
            visit(location, restoreWithCachedSnapshot = false, reload = false)
        }
    }

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------

    private fun initNavigationVisit() {
        val navigated = onGetModalResult()?.let {
            activity?.navigate(it.location, it.action)
        } ?: false

        if (!navigated) {
            initView()
            attachWebViewAndVisit()
        }
    }

    private fun initView() {
        onSetupToolbar()
        turbolinksView?.apply {
            initializePullToRefresh(this)
            showScreenshotIfAvailable(this)
            screenshot = null
            screenshotOrientation = 0
        }
    }

    private fun attachWebViewAndVisit() {
        // Attempt to attach the WebView. It may already be attached to the current instance.
        isWebViewAttachedToNewDestination = attachWebView()

        // Visit every time the Fragment is attached to the Activity
        // or started again after visiting another Activity outside
        // of the main single-Activity architecture.
        visit(location, restoreWithCachedSnapshot = !isInitialVisit, reload = false)
        isInitialVisit = false
    }

    private fun title(): String {
        return webView?.title ?: ""
    }

    private fun visit(location: String, restoreWithCachedSnapshot: Boolean, reload: Boolean) {
        val turbolinksSession = session() ?: return

        // Update the toolbar title while loading the next visit
        if (!reload) {
            onTitleChanged("")
        }

        turbolinksSession.visit(TurbolinksVisit(
                location = location,
                destinationIdentifier = identifier,
                restoreWithCachedSnapshot = restoreWithCachedSnapshot,
                reload = reload,
                callback = this
        ))
    }

    private fun screenshotView() {
        if (session()?.enableScreenshots != true) return

        turbolinksView?.let {
            screenshot = it.createScreenshot()
            screenshotOrientation = it.screenshotOrientation()
            showScreenshotIfAvailable(it)
        }
    }

    private fun showProgressView(location: String) {
        val progressView = createProgressView(location)
        turbolinksView?.addProgressView(progressView)
    }

    private fun initializePullToRefresh(turbolinksView: TurbolinksView) {
        turbolinksView.refreshLayout.apply {
            isEnabled = shouldEnablePullToRefresh()
            setOnRefreshListener {
                isWebViewAttachedToNewDestination = false
                visit(location, restoreWithCachedSnapshot = false, reload = true)
            }
        }
    }

    private fun showScreenshotIfAvailable(turbolinksView: TurbolinksView) {
        if (screenshotOrientation == turbolinksView.screenshotOrientation()) {
            screenshot?.let { turbolinksView.addScreenshot(it) }
        }
    }

    private fun removeTransitionalViews() {
        turbolinksView?.refreshLayout?.isRefreshing = false

        // TODO: This delay shouldn't be necessary, but visitRendered() is being called early.
        delay(200) {
            turbolinksView?.removeProgressView()
            turbolinksView?.removeScreenshot()
        }
    }

    private fun handleError(code: Int) {
        val errorView = createErrorView(code)

        // Make sure the underlying WebView isn't clickable.
        errorView.isClickable = true

        turbolinksErrorPlaceholder?.removeAllViews()
        turbolinksErrorPlaceholder?.addView(errorView)
    }

    private fun generateIdentifier(): Int {
        return Random.nextInt(0, 999999999)
    }
}