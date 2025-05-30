package com.multiplatform.webview.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.multiplatform.webview.cookie.CookieManager
import com.multiplatform.webview.cookie.WebViewCookieManager
import com.multiplatform.webview.setting.WebSettings
import com.multiplatform.webview.util.KLogger
import com.multiplatform.webview.util.getPlatform
import com.multiplatform.webview.util.isZero

/**
 * Created By Kevin Zou On 2023/9/5
 */

/**
 * A state holder to hold the state for the WebView. In most cases this will be remembered
 * using the rememberWebViewState(uri) function.
 */
class WebViewState(
    webContent: WebContent,
) {
    /**
     * The last loaded url. This is updated when a new page is loaded.
     */
    var lastLoadedUrl: String? by mutableStateOf(null)
        internal set

    /**
     *  The content being loaded by the WebView
     */
    var content: WebContent by mutableStateOf(webContent)

    /**
     * Whether the WebView is currently [LoadingState.Loading] data in its main frame (along with
     * progress) or the data loading has [LoadingState.Finished]. See [LoadingState]
     */
    var loadingState: LoadingState by mutableStateOf(LoadingState.Initializing)
        internal set

    /**
     * Whether the webview is currently loading data in its main frame
     */
    val isLoading: Boolean
        get() = loadingState !is LoadingState.Finished

    /**
     * The title received from the loaded content of the current page
     */
    var pageTitle: String? by mutableStateOf(null)
        internal set

    /**
     * A list for errors captured in the last load. Reset when a new page is loaded.
     * Errors could be from any resource (iframe, image, etc.), not just for the main page.
     * To filter for only main frame errors, use [WebViewError.isFromMainFrame].
     */
    val errorsForCurrentRequest: SnapshotStateList<WebViewError> = mutableStateListOf()

    /**
     * Custom Settings for WebView.
     */
    val webSettings: WebSettings by mutableStateOf(WebSettings())

    /**
     * Whether the WebView should capture back presses and navigate back.
     * We need access to this in the state saver. An internal DisposableEffect or AndroidView
     * onDestroy is called after the state saver and so can't be used.
     */
    internal var webView by mutableStateOf<IWebView?>(null)

    /**
     * The native web view instance. On Android, this is an instance of [android.webkit.WebView].
     * On iOS, this is an instance of [WKWebView]. On desktop, this is an instance of [KCEFBrowser].
     */
    val nativeWebView get() = webView?.webView ?: error("WebView is not initialized")

    /**
     * The saved view state from when the view was destroyed last. To restore state,
     * use the navigator and only call loadUrl if the bundle is null.
     * See WebViewSaveStateSample.
     */
    var viewState: WebViewBundle? = null
        internal set

    var scrollOffset: Pair<Int, Int> = 0 to 0
        internal set

    /**
     * CookieManager for WebView.
     * Exposes access to the cookie manager for webView
     */
    val cookieManager: CookieManager by mutableStateOf(WebViewCookieManager())
}

/**
 * Creates a WebView state that is remembered across Compositions.
 *
 * @param url The url to load in the WebView
 * @param additionalHttpHeaders Optional, additional HTTP headers that are passed to [AccompanistWebView.loadUrl].
 *                              Note that these headers are used for all subsequent requests of the WebView.
 */
@Composable
fun rememberWebViewState(
    url: String,
    additionalHttpHeaders: Map<String, String> = emptyMap(),
    extraSettings: WebSettings.() -> Unit = {},
): WebViewState =
// Rather than using .apply {} here we will recreate the state, this prevents
    // a recomposition loop when the webview updates the url itself.
    remember {
        WebViewState(
            WebContent.Url(
                url = url,
                additionalHttpHeaders = additionalHttpHeaders,
            ),
        )
    }.apply {
        this.content =
            WebContent.Url(
                url = url,
                additionalHttpHeaders = additionalHttpHeaders,
            )
        extraSettings(this.webSettings)
    }

/**
 * Creates a WebView state that is remembered across Compositions and saved
 * across activity recreation.
 * When using saved state, you cannot change the URL via recomposition. The only way to load
 * a URL is via a WebViewNavigator.
 *
 * @param data The uri to load in the WebView
 * @sample com.google.accompanist.sample.webview.WebViewSaveStateSample
 */
@Composable
fun rememberSaveableWebViewState(
    url: String,
    additionalHttpHeaders: Map<String, String> = emptyMap(),
): WebViewState =
    if (getPlatform().isDesktop()) {
        rememberWebViewState(url, additionalHttpHeaders)
    } else {
        rememberSaveable(saver = WebStateSaver) {
            WebViewState(WebContent.NavigatorOnly)
        }
    }

val WebStateSaver: Saver<WebViewState, Any> =
    run {
        val pageTitleKey = "pagetitle"
        val lastLoadedUrlKey = "lastloaded"
        val stateBundleKey = "bundle"
        val scrollOffsetKey = "scrollOffset"

        mapSaver(
            save = {
                val viewState = it.webView?.saveState()
                KLogger.info {
                    "WebViewStateSaver Save: ${it.pageTitle}, ${it.lastLoadedUrl}, ${it.webView?.scrollOffset()}, $viewState"
                }
                mapOf(
                    pageTitleKey to it.pageTitle,
                    lastLoadedUrlKey to it.lastLoadedUrl,
                    stateBundleKey to viewState,
                    scrollOffsetKey to it.webView?.scrollOffset(),
                )
            },
            restore = {
                KLogger.info {
                    "WebViewStateSaver Restore: ${it[pageTitleKey]}, ${it[lastLoadedUrlKey]}, ${it["scrollOffset"]}, ${it[stateBundleKey]}"
                }
                val scrollOffset = it[scrollOffsetKey] as Pair<Int, Int>? ?: (0 to 0)
                val bundle = it[stateBundleKey] as WebViewBundle?
                WebViewState(WebContent.NavigatorOnly).apply {
                    this.pageTitle = it[pageTitleKey] as String?
                    this.lastLoadedUrl = it[lastLoadedUrlKey] as String?
                    bundle?.let { this.viewState = it }
                    if (!scrollOffset.isZero()) {
                        this.scrollOffset = scrollOffset
                    }
                }
            },
        )
    }

/**
 * Creates a WebView state that is remembered across Compositions.
 *
 * @param data The uri to load in the WebView
 * @param baseUrl The URL to use as the page's base URL.
 * @param encoding The encoding of the data in the string.
 * @param mimeType The MIME type of the data in the string.
 * @param historyUrl The history URL for the loaded HTML. Leave null to use about:blank.
 */
@Composable
fun rememberWebViewStateWithHTMLData(
    data: String,
    baseUrl: String? = null,
    encoding: String = "utf-8",
    mimeType: String? = null,
    historyUrl: String? = null,
): WebViewState =
    remember {
        WebViewState(WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl))
    }.apply {
        this.content =
            WebContent.Data(
                data,
                baseUrl,
                encoding,
                mimeType,
                historyUrl,
            )
    }

/**
 * Creates a WebView state for HTML file loading that is remembered across Compositions.
 *
 * @param fileName The file path or URI string to load in the WebView. The exact format and
 *                 interpretation of this string (e.g., relative path, absolute URI)
 *                 depend on the value of the [readType] parameter.
 * @param readType Specifies the method and source location for loading the HTML file.
 *                 This parameter is of type [WebViewFileReadType] and dictates how the
 *                 [fileName] should be treated by the underlying platform-specific WebView
 *                 implementation.
 *
 *                 Possible values for `readType` and their implications for `fileName`:
 *                 - **`WebViewFileReadType.ASSET_RESOURCES` (Default)**:
 *                     - **Android**: Expects `fileName` to be a path relative to the
 *                       Android `assets` folder (e.g., "index.html" or "www/index.html").
 *                       The WebView will typically load this using a "file:///android_asset/"-based URL.
 *                     - **iOS**: Expects `fileName` to be a path relative to the main
 *                       bundle's resources, often within a specific assets or resources directory
 *                       conventionally used (e.g., "assets/index.html" or a path resolved via
 *                       `NSBundle.mainBundle.pathForResource`). The `loadHtmlFile` implementation
 *                       constructs an appropriate `file:///` URL to this bundled resource.
 *                     - **Desktop (JAR)**: Expects `fileName` to be a path relative to a
 *                       predefined root within the JAR's classpath, typically an "assets"
 *                       folder (e.g., "index.html" would be loaded from "/assets/index.html"
 *                       within the JAR).
 *
 *                 - **`WebViewFileReadType.COMPOSE_RESOURCE_FILES`**:
 *                     - **All Platforms**: Expects `fileName` to be the URI string
 *                       obtained from `org.jetbrains.compose.resources.Res.getUri("files/your_file.html")`.
 *                       This is the recommended approach for platform-agnostic file access
 *                       using Compose Multiplatform resources.
 *                       - **Android**: `Res.getUri()` typically returns a "file:///android_asset/composeResources/files/..." URI.
 *                       - **iOS**: `Res.getUri()` typically returns an absolute "file:///..." URI pointing to the resource within the app bundle.
 *                       - **Desktop (JAR)**: `Res.getUri()` typically returns a "jar:file:///path/to/your.jar!/composeResources/files/..." URI.
 *                       The platform-specific `loadHtmlFile` implementations are responsible for correctly parsing these URIs.
 *
 *                 The default value is `WebViewFileReadType.ASSET_RESOURCES`.
 *
 * @see WebViewFileReadType
 * @see WebContent.File
 */
@Composable
fun rememberWebViewStateWithHTMLFile(
    fileName: String,
    readType: WebViewFileReadType,
): WebViewState =
    remember {
        WebViewState(WebContent.File(fileName, readType))
    }.apply {
        this.content = WebContent.File(fileName, readType)
    }

@Composable
@Deprecated(
    "Use the overloaded " +
        "rememberWebViewStateWithHTMLFile(fileName: String, readType: WebViewFileReadType) " +
        "instead. Pass readType = WebViewFileReadType.ASSET_RESOURCES explicitly to make " +
        "the loading source clear.",
)
fun rememberWebViewStateWithHTMLFile(fileName: String): WebViewState =
    remember {
        WebViewState(WebContent.File(fileName, WebViewFileReadType.ASSET_RESOURCES))
    }.apply {
        this.content = WebContent.File(fileName, WebViewFileReadType.ASSET_RESOURCES)
    }
