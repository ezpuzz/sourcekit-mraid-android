package org.nexage.sourcekit.mraid;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.ViewGroup;
import android.webkit.WebView;

/**
 * Created by emorypetermann on 3/22/16.
 */

@SuppressLint("ViewConstructor")
public class MRAIDBanner extends MRAIDView {

    private final static String TAG = "MRAIDBanner";

    public MRAIDBanner(
            Context context,
            String baseUrl,
            String data,
            String[] supportedNativeFeatures,
            MRAIDViewListener viewListener,
            MRAIDNativeFeatureListener nativeFeatureListener
    ) {
        super(context, baseUrl, data, supportedNativeFeatures, viewListener, nativeFeatureListener, false);
        webView.setBackgroundColor(Color.TRANSPARENT);
        addView(webView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    public boolean onBackPressed() {
        return state != STATE_DEFAULT && super.onBackPressed();
    }

    @Override
    protected void close() {
        if (state == STATE_LOADING || state == STATE_DEFAULT || state == STATE_HIDDEN) {
            return;
        }
        super.close();
    }

    @Override
    protected void expand(String url) {
        // The only time it is valid to call expand on a banner ad is
        // when the ad is currently in either default or resized state.
        if (state != STATE_DEFAULT && state != STATE_RESIZED) {
            return;
        }

        super.expand(url);
    }

    @Override
    protected void expandHelper(WebView webView) {
        state = STATE_EXPANDED;
        super.expandHelper(webView);
        this.fireStateChangeEvent();
    }
}
