package io.github.kreethedrifter.mender;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
import java.util.List;

/**
 * The one-time "remove ads" purchase (product ID {@value #PRODUCT_ID}, created in
 * Play Console under Monetize > Products > One-time products).
 */
final class Billing {

    static final String PRODUCT_ID = "remove_ads";
    private static final String PREFS = "billing";
    private static final String KEY_AD_FREE = "ad_free";

    interface Listener {
        /** Ad-free status known or changed. fromPurchase = the user just bought it. */
        void onAdFree(boolean adFree, boolean fromPurchase);

        void onPending();

        void onUnavailable();
    }

    private final Activity activity;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final BillingClient client;
    private ProductDetails product;

    Billing(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.client = BillingClient.newBuilder(activity)
                .setListener(this::onPurchasesUpdated)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
    }

    /** Last known status, for showing the right thing before Play answers. */
    boolean cachedAdFree() {
        return prefs.getBoolean(KEY_AD_FREE, false);
    }

    void connect() {
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    // Play not available (e.g. no Google account): keep the cached status
                    post(() -> listener.onAdFree(cachedAdFree(), false));
                    return;
                }
                refresh(false);
                loadProduct();
            }

            @Override
            public void onBillingServiceDisconnected() {
                // the client reconnects by itself (enableAutoServiceReconnection)
            }
        });
    }

    /** Ask Play which purchases this account owns. */
    void refresh(boolean userAsked) {
        if (!client.isReady()) {
            if (userAsked) post(listener::onUnavailable);
            return;
        }
        client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (result, purchases) -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        if (userAsked) post(listener::onUnavailable);
                        return;
                    }
                    boolean owned = handle(purchases);
                    setAdFree(owned, false);
                });
    }

    private void loadProduct() {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()))
                .build();
        client.queryProductDetailsAsync(params, (result, details) -> {
            if (details == null) return;
            List<ProductDetails> list = details.getProductDetailsList();
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null && !list.isEmpty()) {
                product = list.get(0);
            }
        });
    }

    /** Price text like "$1.99", or null if Play hasn't said yet. */
    @Nullable
    String price() {
        if (product == null || product.getOneTimePurchaseOfferDetails() == null) return null;
        return product.getOneTimePurchaseOfferDetails().getFormattedPrice();
    }

    /** Opens Google Play's purchase sheet. Returns false if the product isn't available yet. */
    boolean buy() {
        if (!client.isReady() || product == null) {
            if (client.isReady()) loadProduct();
            return false;
        }
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
                .build();
        client.launchBillingFlow(activity, params);
        return true;
    }

    private void onPurchasesUpdated(BillingResult result, @Nullable List<Purchase> purchases) {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            if (handle(purchases)) setAdFree(true, true);
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            refresh(false);
        }
    }

    /** Acknowledges new purchases. Returns whether "remove ads" is owned (paid). */
    private boolean handle(List<Purchase> purchases) {
        boolean owned = false;
        for (Purchase p : purchases) {
            if (!p.getProducts().contains(PRODUCT_ID)) continue;
            if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                owned = true;
                if (!p.isAcknowledged()) {
                    client.acknowledgePurchase(
                            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(),
                            r -> { });
                }
            } else if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                post(listener::onPending);
            }
        }
        return owned;
    }

    private void setAdFree(boolean adFree, boolean fromPurchase) {
        prefs.edit().putBoolean(KEY_AD_FREE, adFree).apply();
        post(() -> listener.onAdFree(adFree, fromPurchase));
    }

    private void post(Runnable r) {
        activity.runOnUiThread(r);
    }

    void end() {
        client.endConnection();
    }
}
