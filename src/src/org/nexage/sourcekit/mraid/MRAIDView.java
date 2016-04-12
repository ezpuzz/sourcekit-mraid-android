package org.nexage.sourcekit.mraid;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import org.nexage.sourcekit.mraid.internal.MRAIDHtmlProcessor;
import org.nexage.sourcekit.mraid.internal.MRAIDLog;
import org.nexage.sourcekit.mraid.internal.MRAIDLog.LOG_LEVEL;
import org.nexage.sourcekit.mraid.internal.MRAIDNativeFeatureManager;
import org.nexage.sourcekit.mraid.internal.MRAIDParser;
import org.nexage.sourcekit.mraid.properties.MRAIDOrientationProperties;
import org.nexage.sourcekit.mraid.properties.MRAIDResizeProperties;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

@SuppressLint("ViewConstructor")
public class MRAIDView extends RelativeLayout {

    // used to differentiate logging
    private static final String MRAID_LOG_TAG = "MRAIDView";

    // library version
    public static final String VERSION = "1.1.1";

    // used to define state of the MRAID advertisement
    @IntDef({STATE_LOADING, STATE_DEFAULT, STATE_EXPANDED, STATE_RESIZED, STATE_HIDDEN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface MRAIDState {
    }

    // nothing is displayed, ad is currently loading assets or making other requests
    public static final int STATE_LOADING = 0;

    // the standard display of the advertisement (banner or interstitial)
    public static final int STATE_DEFAULT = 1;

    // banner has expanded to fullscreen or ?
    public static final int STATE_EXPANDED = 2;

    // ad has been resized (orientation switch?)
    public static final int STATE_RESIZED = 3;

    // ad is currently hidden
    public static final int STATE_HIDDEN = 4;

    // default size of close region in dip
    private static final int CLOSE_REGION_SIZE = 50;

    // UI elements

    // main WebView stores ad in default state
    protected WebView webView;

    // some ads have a second part that loads independently?
    private WebView webViewPart2;

    // reference to the webview currently being presented to the user
    private WebView currentWebView;


    private final MRAIDWebChromeClient mraidWebChromeClient;
    private final MRAIDWebViewClient mraidWebViewClient;

    // layout to hold expanded webview
    private RelativeLayout expandedView;

    // layout to hold resized webview
    private RelativeLayout resizedView;

    // the close button
    private ImageButton closeRegion;

    private final Context context;

    private final String baseUrl;

    // gesture detector for capturing unwanted gestures
    private final GestureDetector gestureDetector;

    // true if this is an interstitial ad (TODO: move behavior to MRAIDInterstitial)
    private final boolean isInterstitial;

    @MRAIDState
    protected int state;

    @MRAIDState
    public int getState() {
        return state;
    }

    // not sure why we keep this separately from the actual view state?
    private boolean isViewable;

    // The only property of the MRAID expandProperties we need to keep track of
    // on the native side is the useCustomClose property.
    // The width, height, and isModal properties are not used in MRAID v2.0.
    private boolean useCustomClose;
    private final MRAIDOrientationProperties orientationProperties;
    private final MRAIDResizeProperties resizeProperties;

    private final MRAIDNativeFeatureManager nativeFeatureManager;

    // listeners
    protected MRAIDViewListener listener;
    private final MRAIDNativeFeatureListener nativeFeatureListener;

    // used for setting positions and sizes (all in pixels, not dpi)
    private final DisplayMetrics displayMetrics;
    private int contentViewTop;
    private Rect currentPosition;
    private Rect defaultPosition;

    private static class Size {
        public int width;
        public int height;
    }

    private Size maxSize;
    private Size screenSize;
    // state to help set positions and sizes
    private boolean isPageFinished;
    protected boolean isLaidOut;
    private boolean isForcingFullScreen;
    private boolean isExpandingFromDefault;
    private boolean isExpandingPart2;
    private boolean isClosing;

    // used to force full-screen mode on expand and to restore original state on close
    private View titleBar;
    private boolean isFullScreen;
    private boolean isForceNotFullScreen;
    private int origTitleBarVisibility;
    private boolean isActionBarShowing;

    // Stores the requested orientation for the Activity to which this MRAIDView belongs.
    // This is needed to restore the Activity's requested orientation in the event that
    // the view itself requires an orientation lock.
    private final int originalRequestedOrientation;

    // This is the contents of mraid.js. We keep it around in case we need to inject it
    // into webViewPart2 (2nd part of 2-part expanded ad).
    private String mraidJs;

    protected Handler handler;

    public MRAIDView(
            Context context,
            String baseUrl,
            String data,
            String[] supportedNativeFeatures,
            MRAIDViewListener listener,
            MRAIDNativeFeatureListener nativeFeatureListener,
            boolean isInterstitial) {
        super(context);

        this.context = context;
        this.baseUrl = baseUrl;
        this.isInterstitial = isInterstitial;

        state = STATE_LOADING;
        isViewable = false;
        useCustomClose = false;
        orientationProperties = new MRAIDOrientationProperties();
        resizeProperties = new MRAIDResizeProperties();
        nativeFeatureManager = new MRAIDNativeFeatureManager(context, new ArrayList<String>(Arrays.asList(supportedNativeFeatures)));

        this.listener = listener;
        this.nativeFeatureListener = nativeFeatureListener;

        displayMetrics = new DisplayMetrics();
        ((Activity) context).getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        currentPosition = new Rect();
        defaultPosition = new Rect();
        maxSize = new Size();
        screenSize = new Size();

        if (this.context instanceof Activity) {
            originalRequestedOrientation = ((Activity) context).getRequestedOrientation();
        } else {
            originalRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
        MRAIDLog.d(MRAID_LOG_TAG, "originalRequestedOrientation " + getOrientationString(originalRequestedOrientation));

        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                return true;
            }
        });

        handler = new Handler(Looper.getMainLooper());

        mraidWebChromeClient = new MRAIDWebChromeClient();
        mraidWebViewClient = new MRAIDWebViewClient();

        webView = createWebView();

        currentWebView = webView;

        injectMraidJs(webView);

        data = MRAIDHtmlProcessor.processRawHtml(data);
        webView.loadDataWithBaseURL(baseUrl, data, "text/html", "UTF-8", null);
        MRAIDLog.d("log level = " + MRAIDLog.getLoggingLevel());
        if (MRAIDLog.getLoggingLevel() == LOG_LEVEL.verbose) {
            injectJavaScript(webView, "mraid.logLevel = mraid.LogLevelEnum.DEBUG;");
        } else if (MRAIDLog.getLoggingLevel() == LOG_LEVEL.debug) {
            injectJavaScript(webView, "mraid.logLevel = mraid.LogLevelEnum.DEBUG;");
        } else if (MRAIDLog.getLoggingLevel() == LOG_LEVEL.info) {
            injectJavaScript(webView, "mraid.logLevel = mraid.LogLevelEnum.INFO;");
        } else if (MRAIDLog.getLoggingLevel() == LOG_LEVEL.warning) {
            injectJavaScript(webView, "mraid.logLevel = mraid.LogLevelEnum.WARNING;");
        } else if (MRAIDLog.getLoggingLevel() == LOG_LEVEL.error) {
            injectJavaScript(webView, "mraid.logLevel = mraid.LogLevelEnum.ERROR;");
        } else if (MRAIDLog.getLoggingLevel() == LOG_LEVEL.none) {
            injectJavaScript(webView, "mraid.logLevel = mraid.LogLevelEnum.NONE;");
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView wv = new WebView(context) {

            private static final String TAG = "MRAIDView-WebView";

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                onLayoutWebView(this, changed, left, top, right, bottom);
            }

            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                super.onConfigurationChanged(newConfig);
                MRAIDLog.d(TAG, "onConfigurationChanged " + (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT ? "portrait" : "landscape"));
                if (isInterstitial) {
                    ((Activity) context).getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                }
            }

            @Override
            protected void onVisibilityChanged(View changedView, int visibility) {
                super.onVisibilityChanged(changedView, visibility);
                MRAIDLog.d(TAG, "onVisibilityChanged " + getVisibilityString(visibility));
                if (isInterstitial) {
                    setViewable(visibility);
                }
            }

            @Override
            protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                int actualVisibility = getVisibility();
                MRAIDLog.d(TAG, "onWindowVisibilityChanged " + getVisibilityString(visibility) +
                        " (actual " + getVisibilityString(actualVisibility) + ")");
                if (isInterstitial) {
                    setViewable(actualVisibility);
                }
                if (visibility != View.VISIBLE) {
                    pauseWebView(this);
                }
            }
        };

        // changes behavior of view when bigger than window or something?
        wv.setScrollContainer(false);

        // disable the scroll bars (still allows dragging scroll but hides bars)
        wv.setVerticalScrollBarEnabled(false);
        wv.setHorizontalScrollBarEnabled(false);

        // make sure those scroll bars are gone
        wv.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);

        // i think we want to be able to focus but i dont know?
        wv.setFocusableInTouchMode(true);

        // manually delegate view focus?
        wv.setOnTouchListener(new OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_UP:
                        // isTouched = true;
                        if (!v.hasFocus()) {
                            v.requestFocus();
                        }
                        break;
                }
                return false;
            }
        });

        // definitely want javascript on
        wv.getSettings().setJavaScriptEnabled(true);

        // store things somehow ?
        wv.getSettings().setDomStorageEnabled(true);

        // not sure what this does??
        wv.getSettings().setAllowContentAccess(true);

        // we don't want to block image requests
        wv.getSettings().setBlockNetworkImage(false);

        // don't use the zoom control gestures
        wv.getSettings().setBuiltInZoomControls(false);

        // use the wide viewport i think, maybe we don't want this?
        wv.getSettings().setUseWideViewPort(true);

        // load all the images without asking
        wv.getSettings().setLoadsImagesAutomatically(true);

        // our ads often have insecure images and stuff, newer android prevents loading these by default
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            wv.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // not sure why the following says "no virtual method"
        // wv.getSettings().setOffscreenPreRaster(true);

        // no zooming!
        wv.getSettings().setSupportZoom(false);

        wv.setWebChromeClient(mraidWebChromeClient);
        wv.setWebViewClient(mraidWebViewClient);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (0 != (context.getApplicationInfo().flags &= ApplicationInfo.FLAG_DEBUGGABLE)) {
                WebView.setWebContentsDebuggingEnabled(true);
            }
        }

        return wv;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestureDetector.onTouchEvent(event)) {
            event.setAction(MotionEvent.ACTION_CANCEL);
        }
        return super.onTouchEvent(event);
    }

    public void clearView() {
        if (webView != null) {
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.loadUrl("about:blank");
        }
    }

    public void destroy() {
        if (webView != null) {
            destroyWebView(webView);
        }

        if (webViewPart2 != null) {
            destroyWebView(webViewPart2);
        }
    }

    private static void destroyWebView(WebView wv) {
        if (wv != null) {
            wv.clearHistory();
            wv.clearCache(true);
            wv.loadUrl("about:blank");
            wv.pauseTimers();
            wv.setWebChromeClient(null);
            wv.setWebViewClient(null);
            if (wv.getParent() != null) {
                ((ViewManager) wv.getParent()).removeView(wv);
            }
            wv.destroy();
        }
    }

    /**************************************************************************
     * JavaScript --> native support
     * <p/>
     * These methods are (indirectly) called by JavaScript code. They provide
     * the means for JavaScript code to talk to native code
     **************************************************************************/

    // This is the entry point to all the "actual" MRAID methods below.
    private void parseCommandUrl(String commandUrl) {
        MRAIDLog.d(MRAID_LOG_TAG, "parseCommandUrl " + commandUrl);

        MRAIDParser parser = new MRAIDParser();
        Map<String, String> commandMap = parser.parseCommandUrl(commandUrl);

        String command = commandMap.get("command");

        final String[] commandsWithNoParam = {
                "close",
                "resize",
        };

        final String[] commandsWithString = {
                "createCalendarEvent",
                "expand",
                "open",
                "playVideo",
                "storePicture",
                "useCustomClose",
        };

        final String[] commandsWithMap = {
                "setOrientationProperties",
                "setResizeProperties",
        };

        try {
            if (Arrays.asList(commandsWithNoParam).contains(command)) {
                try {
                    getClass().getDeclaredMethod(command).invoke(this);
                } catch (NoSuchMethodException e) {
                    getClass().getSuperclass().getDeclaredMethod(command).invoke(this);
                }
            } else if (Arrays.asList(commandsWithString).contains(command)) {
                String key;
                switch (command) {
                    case "createCalendarEvent":
                        key = "eventJSON";
                        break;
                    case "useCustomClose":
                        key = "useCustomClose";
                        break;
                    default:
                        key = "url";
                        break;
                }
                String val = commandMap.get(key);
                try {
                    getClass().getDeclaredMethod(command, String.class).invoke(this, val);
                } catch (NoSuchMethodException e) {
                    getClass().getSuperclass().getDeclaredMethod(command, String.class).invoke(this, val);
                }
            } else if (Arrays.asList(commandsWithMap).contains(command)) {
                try {
                    getClass().getDeclaredMethod(command, Map.class).invoke(this, commandMap);
                } catch (NoSuchMethodException e) {
                    getClass().getSuperclass().getDeclaredMethod(command, Map.class).invoke(this, commandMap);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // delegate onBackPressed behavior depending on MRAID type
    public boolean onBackPressed() {
        if (state == STATE_LOADING || state == STATE_HIDDEN) {
            return false;
        }
        close();
        return true;
    }

    ///////////////////////////////////////////////////////
    // These are methods in the MRAID API.
    ///////////////////////////////////////////////////////

    @JavascriptMRAIDCallback
    protected void close() {
        MRAIDLog.d(MRAID_LOG_TAG + "-JS callback", "close");
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (state == STATE_DEFAULT || state == STATE_EXPANDED) {
                    closeFromExpanded();
                } else if (state == STATE_RESIZED) {
                    closeFromResized();
                }
            }
        });
    }

    @JavascriptMRAIDCallback
    private void createCalendarEvent(String eventJSON) {
        MRAIDLog.d(MRAID_LOG_TAG + "-JS callback", "createCalendarEvent " + eventJSON);
        if (nativeFeatureListener != null) {
            nativeFeatureListener.mraidNativeFeatureCreateCalendarEvent(eventJSON);
        }
    }

    // Expand an ad from banner to fullscreen
    // Note: This method is also used to present an interstitial ad.
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @JavascriptMRAIDCallback
    protected void expand(String url) {
        MRAIDLog.d(MRAID_LOG_TAG + "-JS callback", "expand " + (url != null ? url : "(1-part)"));

        // 1-part expansion
        if (TextUtils.isEmpty(url)) {
            if (state == STATE_LOADING || state == STATE_DEFAULT) {
                // remove the existing webview
                if (webView.getParent() != null) {
                    ((ViewGroup) webView.getParent()).removeView(webView);
                } else {
                    removeView(webView);
                }
            } else if (state == STATE_RESIZED) {
                removeResizeView();
            }
            expandHelper(webView);
            return;
        }

        // 2-part expansion

        // First, try to get the content of the second (expanded) part of the creative.
        try {
            url = URLDecoder.decode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return;
        }

        // Check to see whether we've been given an absolute or relative URL.
        // If it's relative, prepend the base URL.
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = baseUrl + url;
        }

        final String finalUrl = url;

        // Go onto a background thread to read the content from the URL.
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String content = getStringFromUrl(finalUrl);
                if (!TextUtils.isEmpty(content)) {
                    // Get back onto the main thread to create and load a new WebView.
                    ((Activity) context).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (state == STATE_RESIZED) {
                                removeResizeView();
                                addView(webView);
                            }
                            webView.setWebChromeClient(null);
                            webView.setWebViewClient(null);
                            webViewPart2 = createWebView();
                            injectMraidJs(webViewPart2);
                            webViewPart2.loadDataWithBaseURL(baseUrl, content, "text/html", "UTF-8", null);
                            currentWebView = webViewPart2;
                            isExpandingPart2 = true;
                            expandHelper(currentWebView);
                        }
                    });
                } else {
                    MRAIDLog.e("Could not load part 2 expanded content for URL: " + finalUrl);
                }
            }
        }, "2-part-content").start();
    }

    @JavascriptMRAIDCallback
    private void open(String url) {
        try {
            url = URLDecoder.decode(url, "UTF-8");
            MRAIDLog.d(MRAID_LOG_TAG + "-JS callback", "open " + url);
            if (nativeFeatureListener != null) {
                if (url.startsWith("sms")) {
                    nativeFeatureListener.mraidNativeFeatureSendSms(url);
                } else if (url.startsWith("tel")) {
                    nativeFeatureListener.mraidNativeFeatureCallTel(url);
                } else {
                    nativeFeatureListener.mraidNativeFeatureOpenBrowser(url);
                }
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    @JavascriptMRAIDCallback
    private void playVideo(String url) {
        try {
            url = URLDecoder.decode(url, "UTF-8");
            MRAIDLog.d(MRAID_LOG_TAG + "-JS callback", "playVideo " + url);
            if (nativeFeatureListener != null) {
                nativeFeatureListener.mraidNativeFeaturePlayVideo(url);
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    @JavascriptMRAIDCallback
    private void resize() {
        MRAIDLog.d(MRAID_LOG_TAG + "-JS callback", "resize");

        // We need the cooperation of the app in order to do a resize.
        if (listener == null) {
            return;
        }
        boolean isResizeOK = listener.mraidViewResize(this,
                resizeProperties.width, resizeProperties.height, resizeProperties.offsetX, resizeProperties.offsetY);
        if (!isResizeOK) {
            return;
        }

        state = STATE_RESIZED;

        if (resizedView == null) {
            resizedView = new RelativeLayout(context);
            removeAllViews();
            resizedView.addView(webView);
            addCloseRegion(resizedView);
            FrameLayout rootView = (FrameLayout) getRootView().findViewById(android.R.id.content);
            rootView.addView(resizedView);
        }
        setCloseRegionPosition(resizedView);
        setResizedViewSize();
        setResizedViewPosition();

        handler.post(new Runnable() {
            @Override
            public void run() {
                fireStateChangeEvent();
            }
        });
    }

    @JavascriptMRAIDCallback
    protected void setOrientationProperties(Map<String, String> properties) {
        boolean allowOrientationChange = Boolean.parseBoolean(properties.get("allowOrientationChange"));
        String forceOrientation = properties.get("forceOrientation");

        MRAIDLog.d(MRAID_LOG_TAG + "-JS callback", "setOrientationProperties "
                + allowOrientationChange + " " + forceOrientation);

        orientationProperties.allowOrientationChange = allowOrientationChange;
        orientationProperties.forceOrientation = MRAIDOrientationProperties.forceOrientationFromString(forceOrientation);

        // only interstitials and expanded banners may change orientation
        if (this instanceof MRAIDInterstitial || state == STATE_EXPANDED) {
            applyOrientationProperties();
        }
    }

    @JavascriptMRAIDCallback
    private void setResizeProperties(Map<String, String> properties) {
        int width = Integer.parseInt(properties.get("width"));
        int height = Integer.parseInt(properties.get("height"));
        int offsetX = Integer.parseInt(properties.get("offsetX"));
        int offsetY = Integer.parseInt(properties.get("offsetY"));
        String customClosePosition = properties.get("customClosePosition");
        boolean allowOffscreen = Boolean.parseBoolean(properties.get("allowOffscreen"));
        MRAIDLog.d(MRAID_LOG_TAG + "-JS callback", "setResizeProperties "
                + width + " " + height + " "
                + offsetX + " " + offsetY + " "
                + customClosePosition + " " + allowOffscreen);
        resizeProperties.width = width;
        resizeProperties.height = height;
        resizeProperties.offsetX = offsetX;
        resizeProperties.offsetY = offsetY;
        resizeProperties.customClosePosition =
                MRAIDResizeProperties.customClosePositionFromString(customClosePosition);
        resizeProperties.allowOffscreen = allowOffscreen;
    }

    @JavascriptMRAIDCallback
    private void storePicture(String url) {
        try {
            url = URLDecoder.decode(url, "UTF-8");
            MRAIDLog.d(MRAID_LOG_TAG + "-JS callback", "storePicture " + url);
            if (nativeFeatureListener != null) {
                nativeFeatureListener.mraidNativeFeatureStorePicture(url);
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    @JavascriptMRAIDCallback
    private void useCustomClose(String useCustomCloseString) {
        MRAIDLog.d(MRAID_LOG_TAG + "-JS callback", "useCustomClose " + useCustomCloseString);
        boolean useCustomClose = Boolean.parseBoolean(useCustomCloseString);
        if (this.useCustomClose != useCustomClose) {
            this.useCustomClose = useCustomClose;
            if (useCustomClose) {
                removeDefaultCloseButton();
            } else {
                showDefaultCloseButton();
            }
        }
    }

    /**************************************************************************
     * JavaScript --> native support helpers
     * <p/>
     * These methods are helper methods for the ones above.
     **************************************************************************/

    private String getStringFromUrl(String url) {

        // Support second part from file system - mostly not used on real web creatives
        if (url.startsWith("file:///")) {
            return getStringFromFileUrl(url);
        }

        String content = null;
        InputStream is = null;
        try {
            HttpURLConnection conn = (HttpURLConnection) (new URL(url)).openConnection();
            int responseCode = conn.getResponseCode();
            MRAIDLog.d(MRAID_LOG_TAG, "response code " + responseCode);
            if (responseCode == HttpURLConnection.HTTP_OK) {
                MRAIDLog.d(MRAID_LOG_TAG, "getContentLength " + conn.getContentLength());
                is = conn.getInputStream();
                byte[] buf = new byte[1500];
                int count;
                StringBuilder sb = new StringBuilder();
                while ((count = is.read(buf)) != -1) {
                    String data = new String(buf, 0, count);
                    sb.append(data);
                }
                content = sb.toString();
                MRAIDLog.d(MRAID_LOG_TAG, "getStringFromUrl ok, length=" + content.length());
            }
            conn.disconnect();
        } catch (IOException e) {
            MRAIDLog.e(MRAID_LOG_TAG, "getStringFromUrl failed " + e.getLocalizedMessage());
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (IOException e) {
                // do nothing
            }
        }
        return content;
    }

    private String getStringFromFileUrl(String fileURL) {

        StringBuffer mLine = new StringBuffer("");
        String[] urlElements = fileURL.split("/");
        if (urlElements[3].equals("android_asset")) {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(context.getAssets().open(urlElements[4])));

                // do reading, usually loop until end of file reading
                String line = reader.readLine();
                mLine.append(line);
                while (line != null) {
                    line = reader.readLine();
                    mLine.append(line);
                }

                reader.close();
            } catch (IOException e) {
                MRAIDLog.e("Error fetching file: " + e.getMessage());
            }

            return mLine.toString();
        } else {
            MRAIDLog.e("Unknown location to fetch file content");
        }

        return "";
    }

    protected void showAsInterstitial() {
        expand(null);
    }

    protected void expandHelper(WebView webView) {
        applyOrientationProperties();
        forceFullScreen();

        expandedView = new RelativeLayout(context);
        expandedView.addView(webView, new RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        addCloseRegion(expandedView);
        setCloseRegionPosition(expandedView);

        ((Activity) context).addContentView(expandedView, new RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        isExpandingFromDefault = true;
    }

    private void setResizedViewSize() {
        MRAIDLog.d(MRAID_LOG_TAG, "setResizedViewSize");
        int widthInDip = resizeProperties.width;
        int heightInDip = resizeProperties.height;
        MRAIDLog.d(MRAID_LOG_TAG, "setResizedViewSize " + widthInDip + "x" + heightInDip);
        int width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthInDip, displayMetrics);
        int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, heightInDip, displayMetrics);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        resizedView.setLayoutParams(params);
    }

    private void setResizedViewPosition() {
        MRAIDLog.d(MRAID_LOG_TAG, "setResizedViewPosition");
        // resizedView could be null if it has been closed.
        if (resizedView == null) {
            return;
        }
        int widthInDip = resizeProperties.width;
        int heightInDip = resizeProperties.height;
        int offsetXInDip = resizeProperties.offsetX;
        int offsetYInDip = resizeProperties.offsetY;
        int width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthInDip, displayMetrics);
        int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, heightInDip, displayMetrics);
        int offsetX = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, offsetXInDip, displayMetrics);
        int offsetY = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, offsetYInDip, displayMetrics);
        int x = defaultPosition.left + offsetX;
        int y = defaultPosition.top + offsetY;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) resizedView.getLayoutParams();
        params.leftMargin = x;
        params.topMargin = y;
        resizedView.setLayoutParams(params);
        if (x != currentPosition.left || y != currentPosition.top || width != currentPosition.width() || height != currentPosition.height()) {
            currentPosition.left = x;
            currentPosition.top = y;
            currentPosition.right = x + width;
            currentPosition.bottom = y + height;
            setCurrentPosition();
        }
    }

    protected void closeFromExpanded() {
        if (state == STATE_EXPANDED || state == STATE_RESIZED) {
            state = STATE_DEFAULT;
        }

        isClosing = true;

        expandedView.removeAllViews();

        // get the content view for the current context
        FrameLayout rootView = (FrameLayout) ((Activity) context).findViewById(android.R.id.content);
        rootView.removeView(expandedView);
        expandedView = null;
        closeRegion = null;

        handler.post(new Runnable() {
            @Override
            public void run() {
                restoreOriginalOrientation();
                restoreOriginalScreenState();
            }
        });
        if (webViewPart2 == null) {
            // close from 1-part expansion
            addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            // close from 2-part expansion
            destroyWebView(webViewPart2);
            webView.setWebChromeClient(mraidWebChromeClient);
            webView.setWebViewClient(mraidWebViewClient);
            currentWebView = webView;
            currentWebView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        handler.post(new Runnable() {
            @Override
            public void run() {
                fireStateChangeEvent();
                if (listener != null) {
                    listener.mraidViewClose(MRAIDView.this);
                }
            }
        });
    }

    protected void closeFromResized() {
        state = STATE_DEFAULT;
        isClosing = true;
        removeResizeView();
        addView(webView);
        handler.post(new Runnable() {
            @Override
            public void run() {
                fireStateChangeEvent();
                if (listener != null) {
                    listener.mraidViewClose(MRAIDView.this);
                }
            }
        });
    }

    private void removeResizeView() {
        resizedView.removeAllViews();
        FrameLayout rootView = (FrameLayout) ((Activity) context).findViewById(android.R.id.content);
        rootView.removeView(resizedView);
        resizedView = null;
        closeRegion = null;
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void forceFullScreen() {
        MRAIDLog.d(MRAID_LOG_TAG, "forceFullScreen");
        Activity activity = (Activity) context;

        // store away the original state
        int flags = activity.getWindow().getAttributes().flags;
        isFullScreen = ((flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0);
        isForceNotFullScreen = ((flags & WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN) != 0);
        origTitleBarVisibility = -9;

        // First, see if the activity has an action bar.
        boolean hasActionBar = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            ActionBar actionBar = activity.getActionBar();
            if (actionBar != null) {
                hasActionBar = true;
                isActionBarShowing = actionBar.isShowing();
                actionBar.hide();
            }
        }

        // If not, see if the app has a title bar
        if (!hasActionBar) {
            // http://stackoverflow.com/questions/6872376/how-to-hide-the-title-bar-through-code-in-android
            titleBar = null;
            try {
                titleBar = (View) activity.findViewById(android.R.id.title).getParent();
            } catch (NullPointerException npe) {
                // do nothing
            }
            if (titleBar != null) {
                origTitleBarVisibility = titleBar.getVisibility();
                titleBar.setVisibility(View.GONE);
            }
        }

        MRAIDLog.d(MRAID_LOG_TAG, "isFullScreen " + isFullScreen);
        MRAIDLog.d(MRAID_LOG_TAG, "isForceNotFullScreen " + isForceNotFullScreen);
        MRAIDLog.d(MRAID_LOG_TAG, "isActionBarShowing " + isActionBarShowing);
        MRAIDLog.d(MRAID_LOG_TAG, "origTitleBarVisibility " + getVisibilityString(origTitleBarVisibility));

        // force fullscreen mode
        ((Activity) context).getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        ((Activity) context).getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);

        isForcingFullScreen = !isFullScreen;
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void restoreOriginalScreenState() {
        Activity activity = (Activity) context;
        if (!isFullScreen) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        if (isForceNotFullScreen) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB && isActionBarShowing) {
            ActionBar actionBar = activity.getActionBar();
            actionBar.show();
        } else if (titleBar != null) {
            titleBar.setVisibility(origTitleBarVisibility);
        }
    }

    private static String getVisibilityString(int visibility) {
        switch (visibility) {
            case View.GONE:
                return "GONE";
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.VISIBLE:
                return "VISIBLE";
            default:
                return "UNKNOWN";
        }
    }

    private void addCloseRegion(View view) {
        // The input parameter should be either expandedView or resizedView.

        closeRegion = new ImageButton(context);
        closeRegion.setBackgroundColor(Color.TRANSPARENT);
        closeRegion.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });

        // The default close button is shown only on expanded banners and interstitials,
        // but not on resized banners.
        if (view == expandedView && !useCustomClose) {
            showDefaultCloseButton();
        }

        ((ViewGroup) view).addView(closeRegion);
    }

    private void showDefaultCloseButton() {
        if (closeRegion != null) {
            Drawable closeButtonNormalDrawable = Assets.getDrawableFromBase64(getResources(), Assets.new_close);
            Drawable closeButtonPressedDrawable = Assets.getDrawableFromBase64(getResources(), Assets.new_close_pressed);

            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{-android.R.attr.state_pressed}, closeButtonNormalDrawable);
            states.addState(new int[]{android.R.attr.state_pressed}, closeButtonPressedDrawable);

            closeRegion.setImageDrawable(states);
            closeRegion.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
    }

    private void removeDefaultCloseButton() {
        if (closeRegion != null) {
            closeRegion.setImageResource(android.R.color.transparent);
        }
    }

    private void setCloseRegionPosition(View view) {
        // The input parameter should be either expandedView or resizedView.

        int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CLOSE_REGION_SIZE, displayMetrics);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(size, size);

        // The close region on expanded banners and interstitials is always in the top right corner.
        // Its position on resized banners is determined by the customClosePosition property of the
        // resizeProperties.
        if (view == expandedView) {
            params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        } else if (view == resizedView) {

            switch (resizeProperties.customClosePosition) {
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_TOP_LEFT:
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_BOTTOM_LEFT:
                    params.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
                    break;
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_TOP_CENTER:
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_CENTER:
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_BOTTOM_CENTER:
                    params.addRule(RelativeLayout.CENTER_HORIZONTAL);
                    break;
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_TOP_RIGHT:
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_BOTTOM_RIGHT:
                    params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                    break;
            }

            switch (resizeProperties.customClosePosition) {
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_TOP_LEFT:
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_TOP_CENTER:
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_TOP_RIGHT:
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                    break;
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_CENTER:
                    params.addRule(RelativeLayout.CENTER_VERTICAL);
                    break;
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_BOTTOM_LEFT:
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_BOTTOM_CENTER:
                case MRAIDResizeProperties.CUSTOM_CLOSE_POSITION_BOTTOM_RIGHT:
                    params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                    break;
            }
        }

        closeRegion.setLayoutParams(params);
    }

    /**************************************************************************
     * native --> JavaScript support
     * <p/>
     * These methods provide the means for JavaScript code to talk to native
     * code.
     **************************************************************************/

    @SuppressLint("NewApi")
    private void injectMraidJs(final WebView wv) {
        if (TextUtils.isEmpty(mraidJs)) {
            String str = Assets.mraidJS;
            byte[] mraidjsBytes = Base64.decode(str, Base64.DEFAULT);
            mraidJs = new String(mraidjsBytes);
        }

        MRAIDLog.d(MRAID_LOG_TAG, "injectMraidJs ok " + mraidJs.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            wv.loadData("<html></html>", "text/html", "UTF-8");
            wv.evaluateJavascript(mraidJs, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {

                }
            });
        } else {
            wv.loadUrl("javascript:" + mraidJs);
        }
    }

    @SuppressLint("NewApi")
    private void injectJavaScript(String js) {
        injectJavaScript(currentWebView, js);
    }

    @SuppressLint("NewApi")
    private void injectJavaScript(WebView webView, String js) {
        if (!TextUtils.isEmpty(js)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                MRAIDLog.d(MRAID_LOG_TAG, "evaluating js: " + js);
                webView.evaluateJavascript(js, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        MRAIDLog.d("Evaluated JS" + value);
                    }
                });

            } else {
                MRAIDLog.d(MRAID_LOG_TAG, "loading url: " + js);
                webView.loadUrl("javascript:" + js);
            }
        }
    }

    // convenience methods
    private void fireReadyEvent() {
        MRAIDLog.d(MRAID_LOG_TAG, "fireReadyEvent");
        injectJavaScript("mraid.fireReadyEvent();");
    }

    // We don't need to explicitly call fireSizeChangeEvent because it's taken care
    // of for us in the mraid.setCurrentPosition method in mraid.js.

    @SuppressLint("DefaultLocale")
    protected void fireStateChangeEvent() {
        MRAIDLog.d(MRAID_LOG_TAG, "fireStateChangeEvent");
        String[] stateArray = {"loading", "default", "expanded", "resized", "hidden"};
        injectJavaScript("mraid.fireStateChangeEvent('" + stateArray[state] + "');");
    }

    private void fireViewableChangeEvent() {
        MRAIDLog.d(MRAID_LOG_TAG, "fireViewableChangeEvent");
        injectJavaScript("mraid.fireViewableChangeEvent(" + isViewable + ");");
    }

    private int px2dip(int pixels) {
        return pixels * DisplayMetrics.DENSITY_DEFAULT / displayMetrics.densityDpi;
        // return pixels;
    }

    private void setCurrentPosition() {
        int x = currentPosition.left;
        int y = currentPosition.top;
        int width = currentPosition.width();
        int height = currentPosition.height();
        MRAIDLog.d(MRAID_LOG_TAG, "setCurrentPosition [" + x + "," + y + "] (" + width + "x" + height + ")");
        injectJavaScript("mraid.setCurrentPosition(" + px2dip(x) + "," + px2dip(y) + "," + px2dip(width) + "," + px2dip(height) + ");");
    }

    private void setDefaultPosition() {
        int x = defaultPosition.left;
        int y = defaultPosition.top;
        int width = defaultPosition.width();
        int height = defaultPosition.height();
        MRAIDLog.d(MRAID_LOG_TAG, "setDefaultPosition [" + x + "," + y + "] (" + width + "x" + height + ")");
        injectJavaScript("mraid.setDefaultPosition(" + px2dip(x) + "," + px2dip(y) + "," + px2dip(width) + "," + px2dip(height) + ");");
    }

    private void setMaxSize() {
        MRAIDLog.d(MRAID_LOG_TAG, "setMaxSize");
        int width = maxSize.width;
        int height = maxSize.height;
        MRAIDLog.d(MRAID_LOG_TAG, "setMaxSize " + width + "x" + height);
        injectJavaScript("mraid.setMaxSize(" + px2dip(width) + "," + px2dip(height) + ");");
    }

    private void setScreenSize() {
        MRAIDLog.d(MRAID_LOG_TAG, "setScreenSize");
        int width = screenSize.width;
        int height = screenSize.height;
        MRAIDLog.d(MRAID_LOG_TAG, "setScreenSize " + width + "x" + height);
        injectJavaScript("mraid.setScreenSize(" + px2dip(width) + "," + px2dip(height) + ");");
    }

    private void setSupportedServices() {
        MRAIDLog.d(MRAID_LOG_TAG, "setSupportedServices");
        injectJavaScript("mraid.setSupports(mraid.SUPPORTED_FEATURES.CALENDAR, " + nativeFeatureManager.isCalendarSupported() + ");");
        injectJavaScript("mraid.setSupports(mraid.SUPPORTED_FEATURES.INLINEVIDEO, " + nativeFeatureManager.isInlineVideoSupported() + ");");
        injectJavaScript("mraid.setSupports(mraid.SUPPORTED_FEATURES.SMS, " + nativeFeatureManager.isSmsSupported() + ");");
        injectJavaScript("mraid.setSupports(mraid.SUPPORTED_FEATURES.STOREPICTURE, " + nativeFeatureManager.isStorePictureSupported() + ");");
        injectJavaScript("mraid.setSupports(mraid.SUPPORTED_FEATURES.TEL, " + nativeFeatureManager.isTelSupported() + ");");
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void pauseWebView(WebView webView) {
        MRAIDLog.d(MRAID_LOG_TAG, "pauseWebView " + webView.toString());
        // Stop any video/animation that may be running in the WebView.
        // Otherwise, it will keep playing in the background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            webView.onPause();
        } else {
            webView.loadUrl("about:blank");
        }

    }

    /**************************************************************************
     * WebChromeClient and WebViewClient
     **************************************************************************/

    private class MRAIDWebChromeClient extends WebChromeClient {

        @Override
        public boolean onConsoleMessage(ConsoleMessage cm) {
            if (cm == null || cm.message() == null) {
                return false;
            }
            if (!cm.message().contains("Uncaught ReferenceError")) {
                MRAIDLog.i("JS console", cm.message()
                        + (cm.sourceId() == null ? "" : " at " + cm.sourceId())
                        + ":" + cm.lineNumber());
            }
            return true;
        }

        //		@Override
        //		public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
        //			MRAIDLog.d("JS alert", message);
        //			return handlePopups(result);
        //		}

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            MRAIDLog.d("JS confirm", message);
            return handlePopups(result);
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            MRAIDLog.d("JS prompt", message);
            return handlePopups(result);
        }

        private boolean handlePopups(JsResult result) {
            result.cancel();
            return true;
        }
    }

    private class MRAIDWebViewClient extends WebViewClient {

        @Override
        public void onPageFinished(WebView view, String url) {
            MRAIDLog.d(MRAID_LOG_TAG, "onPageFinished: " + url);
            super.onPageFinished(view, url);
            if (state == STATE_LOADING) {
                isPageFinished = true;
                injectJavaScript("mraid.setPlacementType('" + (isInterstitial ? "interstitial" : "inline") + "');");
                setSupportedServices();
                if (isLaidOut) {
                    setScreenSize();
                    setMaxSize();
                    setCurrentPosition();
                    setDefaultPosition();
                    if (isInterstitial) {
                        showAsInterstitial();
                    } else {
                        state = STATE_DEFAULT;
                        fireStateChangeEvent();
                        fireReadyEvent();
                        if (isViewable) {
                            fireViewableChangeEvent();
                        }
                    }
                }
                if (listener != null) {
                    listener.mraidViewLoaded(MRAIDView.this);
                }
            }
            if (isExpandingPart2) {
                isExpandingPart2 = false;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        injectJavaScript("mraid.setPlacementType('" + (isInterstitial ? "interstitial" : "inline") + "');");
                        setSupportedServices();
                        setScreenSize();
                        setDefaultPosition();
                        MRAIDLog.d(MRAID_LOG_TAG, "calling fireStateChangeEvent 2");
                        fireStateChangeEvent();
                        fireReadyEvent();
                        if (isViewable) {
                            fireViewableChangeEvent();
                        }
                    }
                });
            }
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            MRAIDLog.d(MRAID_LOG_TAG, "onReceivedError: " + description);
            super.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            MRAIDLog.d(MRAID_LOG_TAG, "shouldOverrideUrlLoading: " + url);
            if (url.startsWith("mraid://")) {
                parseCommandUrl(url);
                return true;
            } else {
                open(url);
                return true;
            }
        }

    }

    /**************************************************************************
     * Methods for responding to changes of size and position.
     **************************************************************************/

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        MRAIDLog.d(MRAID_LOG_TAG, "onConfigurationChanged " + (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT ? "portrait" : "landscape"));
        ((Activity) context).getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
    }

    @Override
    protected void onAttachedToWindow() {
        MRAIDLog.d(MRAID_LOG_TAG, "onAttachedToWindow");
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        MRAIDLog.d(MRAID_LOG_TAG, "onDetachedFromWindow");
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        MRAIDLog.d(MRAID_LOG_TAG, "onVisibilityChanged " + getVisibilityString(visibility));
        setViewable(visibility);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        int actualVisibility = getVisibility();
        MRAIDLog.d(MRAID_LOG_TAG, "onWindowVisibilityChanged " + getVisibilityString(visibility) +
                " (actual " + getVisibilityString(actualVisibility) + ")");
        setViewable(actualVisibility);
    }

    private void setViewable(int visibility) {
        boolean isCurrentlyViewable = (visibility == View.VISIBLE);
        if (isCurrentlyViewable != isViewable) {
            isViewable = isCurrentlyViewable;
            if (isPageFinished && isLaidOut) {
                fireViewableChangeEvent();
            }
        }
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        MRAIDLog.w(MRAID_LOG_TAG, "onLayout (" + state + ") " +
                changed + " " + left + " " + top + " " + right + " " + bottom);
        if (isForcingFullScreen) {
            MRAIDLog.d(MRAID_LOG_TAG, "onLayout ignored");
            return;
        }
        if (state == STATE_EXPANDED || state == STATE_RESIZED) {
            calculateScreenSize();
            calculateMaxSize();
        }
        if (isClosing) {
            isClosing = false;
            currentPosition = new Rect(defaultPosition);
            setCurrentPosition();
        } else {
            calculatePosition(false);
        }
        if (state == STATE_RESIZED && changed) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    setResizedViewPosition();
                }
            });
        }
        isLaidOut = true;
        if (state == STATE_LOADING && isPageFinished && !isInterstitial) {
            state = STATE_DEFAULT;
            fireStateChangeEvent();
            fireReadyEvent();
            if (isViewable) {
                fireViewableChangeEvent();
            }
        }
    }

    private void onLayoutWebView(WebView wv, boolean changed, int left, int top, int right, int bottom) {
        boolean isCurrent = (wv == currentWebView);
        MRAIDLog.w(MRAID_LOG_TAG, "onLayoutWebView " + (wv == webView ? "1 " : "2 ") + isCurrent + " (" + state + ") " +
                changed + " " + left + " " + top + " " + right + " " + bottom);
        if (!isCurrent) {
            MRAIDLog.d(MRAID_LOG_TAG, "onLayoutWebView ignored, not current");
            return;
        }

        if (state == STATE_LOADING || state == STATE_DEFAULT) {
            calculateScreenSize();
            calculateMaxSize();
        }

        // If closing from expanded state, just set currentPosition to default position in onLayout above.
        if (!isClosing) {
            calculatePosition(true);
            if (isInterstitial) {
                // For interstitials, the default position is always the current position
                if (!defaultPosition.equals(currentPosition)) {
                    defaultPosition = new Rect(currentPosition);
                    setDefaultPosition();
                }
            }
        }

        if (isExpandingFromDefault) {
            isExpandingFromDefault = false;
            if (isInterstitial) {
                state = STATE_DEFAULT;
                isLaidOut = true;
            }
            if (!isExpandingPart2) {
                MRAIDLog.d(MRAID_LOG_TAG, "calling fireStateChangeEvent 1");
                fireStateChangeEvent();
            }
            if (isInterstitial) {
                fireReadyEvent();
                if (isViewable) {
                    fireViewableChangeEvent();
                }
            }
            if (listener != null) {
                listener.mraidViewExpand(this);
            }
        }
    }

    private void calculateScreenSize() {
        int orientation = getResources().getConfiguration().orientation;
        boolean isPortrait = (orientation == Configuration.ORIENTATION_PORTRAIT);
        MRAIDLog.d(MRAID_LOG_TAG, "calculateScreenSize orientation " + (isPortrait ? "portrait" : "landscape"));
        int width = displayMetrics.widthPixels;
        int height = displayMetrics.heightPixels;
        MRAIDLog.d(MRAID_LOG_TAG, "calculateScreenSize screen size " + width + "x" + height);
        if (width != screenSize.width || height != screenSize.height) {
            screenSize.width = width;
            screenSize.height = height;
            if (isPageFinished) {
                setScreenSize();
            }
        }
    }

    private void calculateMaxSize() {
        int width, height;
        Rect frame = new Rect();
        Window window = ((Activity) context).getWindow();
        window.getDecorView().getWindowVisibleDisplayFrame(frame);
        MRAIDLog.d(MRAID_LOG_TAG, "calculateMaxSize frame [" + frame.left + "," + frame.top + "][" + frame.right + "," + frame.bottom + "] (" +
                frame.width() + "x" + frame.height() + ")");
        int statusHeight = frame.top;
        contentViewTop = window.findViewById(Window.ID_ANDROID_CONTENT).getTop();
        int titleHeight = contentViewTop - statusHeight;
        MRAIDLog.d(MRAID_LOG_TAG, "calculateMaxSize statusHeight " + statusHeight);
        MRAIDLog.d(MRAID_LOG_TAG, "calculateMaxSize titleHeight " + titleHeight);
        MRAIDLog.d(MRAID_LOG_TAG, "calculateMaxSize contentViewTop " + contentViewTop);
        width = frame.width();
        height = screenSize.height - contentViewTop;
        MRAIDLog.d(MRAID_LOG_TAG, "calculateMaxSize max size " + width + "x" + height);
        if (width != maxSize.width || height != maxSize.height) {
            maxSize.width = width;
            maxSize.height = height;
            if (isPageFinished) {
                setMaxSize();
            }
        }
    }

    private void calculatePosition(boolean isCurrentWebView) {
        int x, y, width, height;
        int[] location = new int[2];

        View view = isCurrentWebView ? currentWebView : this;
        String name = (isCurrentWebView ? "current" : "default");

        // This is the default location regardless of the state of the MRAIDView.
        view.getLocationOnScreen(location);
        x = location[0];
        y = location[1];
        MRAIDLog.d(MRAID_LOG_TAG, "calculatePosition " + name + " locationOnScreen [" + x + "," + y + "]");
        MRAIDLog.d(MRAID_LOG_TAG, "calculatePosition " + name + " contentViewTop " + contentViewTop);
        y -= contentViewTop;
        width = view.getWidth();
        height = view.getHeight();

        MRAIDLog.d(MRAID_LOG_TAG, "calculatePosition " + name + " position [" + x + "," + y + "] (" + width + "x" + height + ")");

        Rect position = isCurrentWebView ? currentPosition : defaultPosition;

        if (x != position.left || y != position.top || width != position.width() || height != position.height()) {
            if (isCurrentWebView) {
                currentPosition = new Rect(x, y, x + width, y + height);
            } else {
                defaultPosition = new Rect(x, y, x + width, y + height);
            }
            if (isPageFinished) {
                if (isCurrentWebView) {
                    setCurrentPosition();
                } else {
                    setDefaultPosition();
                }
            }
        }
    }

    /**************************************************************************
     * Methods for forcing orientation.
     **************************************************************************/

    private static String getOrientationString(int orientation) {
        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED:
                return "UNSPECIFIED";
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                return "LANDSCAPE";
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                return "PORTRAIT";
            default:
                return "UNKNOWN";
        }
    }

    protected void applyOrientationProperties() {
        MRAIDLog.d(MRAID_LOG_TAG, "applyOrientationProperties " +
                orientationProperties.allowOrientationChange + " " + orientationProperties.forceOrientationString());

        Activity activity = (Activity) context;

        int currentOrientation = getResources().getConfiguration().orientation;
        boolean isCurrentPortrait = (currentOrientation == Configuration.ORIENTATION_PORTRAIT);
        MRAIDLog.d(MRAID_LOG_TAG, "currentOrientation " + (isCurrentPortrait ? "portrait" : "landscape"));

        int orientation = originalRequestedOrientation;
        if (orientationProperties.forceOrientation == MRAIDOrientationProperties.FORCE_ORIENTATION_PORTRAIT) {
            orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else if (orientationProperties.forceOrientation == MRAIDOrientationProperties.FORCE_ORIENTATION_LANDSCAPE) {
            orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else {
            // orientationProperties.forceOrientation == MRAIDOrientationProperties.FORCE_ORIENTATION_NONE
            if (orientationProperties.allowOrientationChange) {
                orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            } else {
                // orientationProperties.allowOrientationChange == false
                // lock the current orientation
                orientation = (isCurrentPortrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        }
        activity.setRequestedOrientation(orientation);
    }

    private void restoreOriginalOrientation() {
        MRAIDLog.d(MRAID_LOG_TAG, "restoreOriginalOrientation");
        Activity activity = (Activity) context;
        int currentRequestedOrientation = activity.getRequestedOrientation();
        if (currentRequestedOrientation != originalRequestedOrientation) {
            activity.setRequestedOrientation(originalRequestedOrientation);
        }
    }
}

