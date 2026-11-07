package io.github.kreethedrifter.mender;

import android.app.Activity;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ads: asks for consent where the law requires it (Google's UMP form), then
 * shows a banner at the bottom and, at most every few minutes, a full-screen
 * ad after a successful patch. Nothing is shown once ads are removed.
 */
final class Ads {

    private static final long INTERSTITIAL_GAP_MS = 3 * 60 * 1000;

    private final Activity activity;
    private final FrameLayout container;
    private final ConsentInformation consent;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private AdView banner;
    private InterstitialAd interstitial;
    private boolean enabled = true;
    private long lastInterstitial = 0;
    private Runnable onPrivacyOptionsKnown;
    private java.util.function.Consumer<String> statusListener = msg -> { };

    /** Debug builds show this text on screen, to see why ads do or don't appear. */
    void setStatusListener(java.util.function.Consumer<String> l) {
        statusListener = l;
    }

    private void status(String msg) {
        activity.runOnUiThread(() -> statusListener.accept(msg));
    }

    Ads(Activity activity, FrameLayout container) {
        this.activity = activity;
        this.container = container;
        this.consent = UserMessagingPlatform.getConsentInformation(activity);
    }

    /** Called once ads-free status is known (from Play). */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            container.setVisibility(View.GONE);
            container.removeAllViews();
            if (banner != null) {
                banner.destroy();
                banner = null;
            }
            interstitial = null;
            return;
        }
        gatherConsentAndStart();
    }

    /** Runs when we know whether a "Privacy choices" menu entry is required. */
    void setOnPrivacyOptionsKnown(Runnable r) {
        onPrivacyOptionsKnown = r;
    }

    boolean privacyOptionsRequired() {
        return consent.getPrivacyOptionsRequirementStatus()
                == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
    }

    void showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity, formError -> {
            if (consent.canRequestAds()) start();
        });
    }

    private void gatherConsentAndStart() {
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        consent.requestConsentInfoUpdate(activity, params,
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, formError -> {
                    if (onPrivacyOptionsKnown != null) onPrivacyOptionsKnown.run();
                    if (formError != null) status("Consent form error: " + formError.getMessage());
                    if (consent.canRequestAds()) start();
                    else status("Ads off: consent not given (status " + consent.getConsentStatus() + ")");
                }),
                requestError -> {
                    status("Consent check failed: " + requestError.getMessage());
                    // offline or similar: use consent gathered on an earlier run, if any
                    if (consent.canRequestAds()) start();
                });
        // consent from a previous session lets ads start right away
        if (consent.canRequestAds()) start();
    }

    private void start() {
        if (!enabled) return;
        status("Loading ads…");
        if (started.getAndSet(true)) {
            loadBanner();
            return;
        }
        new Thread(() -> {
            MobileAds.initialize(activity, status -> { });
            activity.runOnUiThread(this::loadBanner);
        }).start();
    }

    private void loadBanner() {
        if (!enabled || activity.isFinishing() || activity.isDestroyed()) return;
        if (banner == null) {
            banner = new AdView(activity);
            banner.setAdUnitId(BuildConfig.BANNER_ID);
            banner.setAdSize(bannerSize());
            banner.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    status("Banner ad loaded");
                    if (enabled) container.setVisibility(View.VISIBLE);
                }

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError error) {
                    status("Banner failed: code " + error.getCode() + ", " + error.getMessage());
                    container.setVisibility(View.GONE);
                }
            });
            container.removeAllViews();
            container.addView(banner);
            banner.loadAd(new AdRequest.Builder().build());
        }
        loadInterstitial();
    }

    @SuppressWarnings("deprecation")
    private AdSize bannerSize() {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int widthDp = (int) (dm.widthPixels / dm.density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp);
    }

    private void loadInterstitial() {
        if (!enabled || interstitial != null) return;
        InterstitialAd.load(activity, BuildConfig.INTERSTITIAL_ID, new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        interstitial = ad;
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        interstitial = null;
                    }
                });
    }

    /** After a successful patch: maybe show a full-screen ad. */
    void afterPatch() {
        if (!enabled || interstitial == null) return;
        long now = SystemClock.elapsedRealtime();
        if (lastInterstitial != 0 && now - lastInterstitial < INTERSTITIAL_GAP_MS) return;
        lastInterstitial = now;
        InterstitialAd ad = interstitial;
        interstitial = null;
        ad.show(activity);
        loadInterstitial();
    }

    void onPause() {
        if (banner != null) banner.pause();
    }

    void onResume() {
        if (banner != null) banner.resume();
    }

    void onDestroy() {
        if (banner != null) banner.destroy();
    }
}
