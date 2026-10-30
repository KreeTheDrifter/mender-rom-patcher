package io.github.kreethedrifter.mender;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.kreethedrifter.mender.patch.PatchException;
import io.github.kreethedrifter.mender.patch.Patcher;

public class MainActivity extends AppCompatActivity implements Billing.Listener {

    private static final String STATE_ROM = "rom";
    private static final String STATE_PATCH = "patch";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView romInfo, patchInfo, matchStatus, resultTitle, resultBody;
    private MaterialButton pickRom, pickPatch, patchButton;
    private LinearProgressIndicator progress;
    private MaterialCardView resultCard;

    private Uri romUri, patchUri;
    private Files.Meta romMeta, patchMeta;
    private long romCrc = -1, romSize = -1;
    private Patcher.Info patchInfoData;
    private byte[] pendingOutput;
    private String pendingNote;
    private boolean busy;

    private Ads ads;
    private Billing billing;
    private boolean adFree;
    private boolean restoreAsked;
    private MenuItem privacyChoicesItem, removeAdsItem;

    private final ActivityResultLauncher<String[]> openRom =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) setRom(uri);
            });
    private final ActivityResultLauncher<String[]> openPatch =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) setPatch(uri, true);
            });
    private final ActivityResultLauncher<String> saveOutput =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"),
                    this::onSaveTarget);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        romInfo = findViewById(R.id.romInfo);
        patchInfo = findViewById(R.id.patchInfo);
        matchStatus = findViewById(R.id.matchStatus);
        resultTitle = findViewById(R.id.resultTitle);
        resultBody = findViewById(R.id.resultBody);
        pickRom = findViewById(R.id.pickRom);
        pickPatch = findViewById(R.id.pickPatch);
        patchButton = findViewById(R.id.patchButton);
        progress = findViewById(R.id.progress);
        resultCard = findViewById(R.id.resultCard);

        pickRom.setOnClickListener(v -> launch(openRom));
        pickPatch.setOnClickListener(v -> launch(openPatch));
        patchButton.setOnClickListener(v -> startPatch());

        FrameLayout adContainer = findViewById(R.id.adContainer);
        ads = new Ads(this, adContainer);
        ads.setOnPrivacyOptionsKnown(this::updateMenu);
        billing = new Billing(this, this);
        adFree = billing.cachedAdFree();
        if (!adFree) ads.setEnabled(true);
        billing.connect();

        if (savedInstanceState != null) {
            Uri r = savedInstanceState.getParcelable(STATE_ROM);
            Uri p = savedInstanceState.getParcelable(STATE_PATCH);
            if (r != null) setRom(r);
            if (p != null) setPatch(p, false);
        } else {
            handleIncoming(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelable(STATE_ROM, romUri);
        out.putParcelable(STATE_PATCH, patchUri);
    }

    private void launch(ActivityResultLauncher<String[]> launcher) {
        try {
            launcher.launch(new String[] {"*/*"});
        } catch (ActivityNotFoundException e) {
            showError(getString(R.string.error_read));
        }
    }

    // ------------------------------------------------------------ files in

    /** "Open with" / "Share": a patch goes to step 2, anything else to step 1. */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = streamExtra(intent);
        }
        if (uri == null) return;
        final Uri u = uri;
        worker.execute(() -> {
            boolean isPatch;
            try {
                byte[] head = new byte[5];
                int n;
                try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                    n = in == null ? 0 : in.read(head);
                }
                isPatch = n >= 4 && (startsWith(head, "PATCH") || startsWith(head, "BPS1") || startsWith(head, "UPS1"));
            } catch (IOException | SecurityException e) {
                main.post(() -> showError(getString(R.string.error_read)));
                return;
            }
            main.post(() -> {
                if (isPatch) setPatch(u, true);
                else setRom(u);
                Snackbar.make(patchButton, isPatch ? R.string.received_patch : R.string.received_rom,
                        Snackbar.LENGTH_SHORT).show();
            });
        });
    }

    @SuppressWarnings("deprecation")
    private static Uri streamExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        return intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    private static boolean startsWith(byte[] b, String magic) {
        for (int i = 0; i < magic.length(); i++) if (b[i] != (byte) magic.charAt(i)) return false;
        return true;
    }

    private void setRom(Uri uri) {
        romUri = uri;
        romCrc = -1;
        hideResult();
        romInfo.setText(R.string.reading);
        pickRom.setText(R.string.change);
        updateState();
        worker.execute(() -> {
            try {
                Files.Meta meta = Files.meta(getContentResolver(), uri);
                long[] cs = Files.crcAndSize(getContentResolver(), uri);
                main.post(() -> {
                    if (!uri.equals(romUri)) return;
                    romMeta = meta;
                    romCrc = cs[0];
                    romSize = cs[1];
                    romInfo.setText(getString(R.string.file_info, meta.name, Files.sizeText(romSize),
                            Files.crcText(romCrc)));
                    updateState();
                });
            } catch (Files.TooBigException e) {
                main.post(() -> failPick(true, getString(R.string.error_too_big)));
            } catch (IOException | SecurityException e) {
                main.post(() -> failPick(true, getString(R.string.error_read)));
            }
        });
    }

    private void setPatch(Uri uri, boolean announceErrors) {
        patchUri = uri;
        patchInfoData = null;
        hideResult();
        patchInfo.setText(R.string.reading);
        pickPatch.setText(R.string.change);
        updateState();
        worker.execute(() -> {
            try {
                Files.Meta meta = Files.meta(getContentResolver(), uri);
                byte[] data = Files.read(getContentResolver(), uri, meta.size);
                Patcher.Info info = Patcher.inspect(data);
                main.post(() -> {
                    if (!uri.equals(patchUri)) return;
                    patchMeta = meta;
                    patchInfoData = info;
                    String needs = info.sourceCrc >= 0
                            ? getString(R.string.patch_needs, Files.crcText(info.sourceCrc))
                            : getString(R.string.patch_ips);
                    patchInfo.setText(getString(R.string.patch_info, meta.name, info.format.name(), needs));
                    updateState();
                });
            } catch (PatchException e) {
                main.post(() -> failPick(false, announceErrors ? getString(R.string.error_not_patch) : null));
            } catch (Files.TooBigException e) {
                main.post(() -> failPick(false, getString(R.string.error_too_big)));
            } catch (IOException | SecurityException e) {
                main.post(() -> failPick(false, getString(R.string.error_read)));
            }
        });
    }

    private void failPick(boolean rom, String message) {
        if (rom) {
            romUri = null;
            romInfo.setText(R.string.no_rom);
            pickRom.setText(R.string.pick_rom);
        } else {
            patchUri = null;
            patchInfo.setText(R.string.no_patch);
            pickPatch.setText(R.string.pick_patch);
        }
        updateState();
        if (message != null) showError(message);
    }

    /** Enables the button and explains whether the ROM fits the patch. */
    private void updateState() {
        boolean ready = romUri != null && romCrc >= 0 && patchInfoData != null;
        patchButton.setEnabled(ready && !busy);
        pickRom.setEnabled(!busy);
        pickPatch.setEnabled(!busy);
        if (!ready) {
            matchStatus.setVisibility(View.GONE);
            return;
        }
        Patcher.Info info = patchInfoData;
        int text;
        boolean good;
        if (info.sourceCrc < 0) {
            text = R.string.match_ips;
            good = true;
        } else if (romCrc == info.sourceCrc && romSize == info.sourceSize) {
            text = R.string.match_ok;
            good = true;
        } else if (info.accepts(romCrc, romSize)) {
            text = R.string.match_reverse;
            good = true;
        } else if (romSize == info.sourceSize + 512) {
            text = R.string.match_header;
            good = true;
        } else {
            text = R.string.match_bad;
            good = false;
        }
        matchStatus.setText((good ? "✓  " : "⚠  ") + getString(text));
        matchStatus.setTextColor(ContextCompat.getColor(this, good ? R.color.match_ok : R.color.match_warn));
        matchStatus.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------ patching

    private void startPatch() {
        if (busy || romUri == null || patchUri == null) return;
        setBusy(true);
        hideResult();
        final Uri rom = romUri, patch = patchUri;
        final long romKnown = romSize, patchKnown = patchMeta != null ? patchMeta.size : -1;
        worker.execute(() -> {
            try {
                byte[] romData = Files.read(getContentResolver(), rom, romKnown);
                byte[] patchData = Files.read(getContentResolver(), patch, patchKnown);
                Patcher.Result result = Patcher.apply(romData, patchData);
                main.post(() -> {
                    pendingOutput = result.output;
                    pendingNote = result.note;
                    saveOutput.launch(outputName());
                });
            } catch (PatchException e) {
                main.post(() -> {
                    setBusy(false);
                    showResult(false, getString(R.string.error_title), e.getMessage());
                });
            } catch (Files.TooBigException e) {
                main.post(() -> {
                    setBusy(false);
                    showError(getString(R.string.error_too_big));
                });
            } catch (IOException | SecurityException e) {
                main.post(() -> {
                    setBusy(false);
                    showError(getString(R.string.error_read));
                });
            } catch (OutOfMemoryError e) {
                main.post(() -> {
                    setBusy(false);
                    showError(getString(R.string.error_too_big));
                });
            }
        });
    }

    /** The patch's name with the ROM's extension: "MyHack.bps" + "Game.gba" -> "MyHack.gba". */
    private String outputName() {
        String ext = romMeta != null ? Files.extension(romMeta.name) : "";
        String base = patchMeta != null ? Files.baseName(patchMeta.name)
                : romMeta != null ? Files.baseName(romMeta.name) + " (patched)" : "patched";
        return base + ext;
    }

    private void onSaveTarget(Uri target) {
        final byte[] out = pendingOutput;
        final String note = pendingNote;
        pendingOutput = null;
        pendingNote = null;
        if (target == null || out == null) {
            setBusy(false);
            return;
        }
        worker.execute(() -> {
            try {
                Files.write(getContentResolver(), target, out);
                String name = Files.meta(getContentResolver(), target).name;
                long crc = Patcher.crc32(out);
                main.post(() -> {
                    setBusy(false);
                    String body = getString(R.string.done_body, name, Files.sizeText(out.length), Files.crcText(crc));
                    if (note != null) body += "\n\n" + note;
                    showResult(true, getString(R.string.done_title), body);
                    ads.afterPatch();
                });
            } catch (IOException | SecurityException e) {
                main.post(() -> {
                    setBusy(false);
                    showError(getString(R.string.error_write));
                });
            }
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        patchButton.setText(b ? R.string.working : R.string.patch_and_save);
        updateState();
    }

    private void showResult(boolean ok, String title, String body) {
        resultTitle.setText((ok ? "✓  " : "✗  ") + title);
        resultBody.setText(body);
        resultCard.setVisibility(View.VISIBLE);
    }

    private void hideResult() {
        resultCard.setVisibility(View.GONE);
    }

    private void showError(String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.error_title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    // ------------------------------------------------------------ menu, ads, purchase

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        privacyChoicesItem = menu.findItem(R.id.privacy_choices);
        removeAdsItem = menu.findItem(R.id.remove_ads);
        updateMenu();
        return true;
    }

    private void updateMenu() {
        if (removeAdsItem != null) removeAdsItem.setVisible(!adFree);
        if (privacyChoicesItem != null) privacyChoicesItem.setVisible(!adFree && ads.privacyOptionsRequired());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.remove_ads) {
            offerRemoveAds();
        } else if (id == R.id.restore) {
            restoreAsked = true;
            billing.refresh(true);
        } else if (id == R.id.privacy_choices) {
            ads.showPrivacyOptions();
        } else if (id == R.id.privacy_policy) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_url))));
            } catch (ActivityNotFoundException ignored) {
                // no browser
            }
        } else if (id == R.id.about) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME))
                    .setPositiveButton(R.string.ok, null)
                    .show();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void offerRemoveAds() {
        String price = billing.price();
        if (price == null) {
            if (!billing.buy()) Snackbar.make(patchButton, R.string.store_unavailable, Snackbar.LENGTH_LONG).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.remove_ads_title)
                .setMessage(getString(R.string.remove_ads_body, price))
                .setPositiveButton(R.string.buy, (d, w) -> {
                    if (!billing.buy()) {
                        Snackbar.make(patchButton, R.string.store_unavailable, Snackbar.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.not_now, null)
                .show();
    }

    @Override
    public void onAdFree(boolean adFree, boolean fromPurchase) {
        boolean wasAdFree = this.adFree;
        this.adFree = adFree;
        if (adFree != wasAdFree) ads.setEnabled(!adFree);
        updateMenu();
        if (fromPurchase) {
            Snackbar.make(patchButton, R.string.ads_removed, Snackbar.LENGTH_LONG).show();
        } else if (restoreAsked) {
            Snackbar.make(patchButton, adFree ? R.string.purchase_restored : R.string.nothing_to_restore,
                    Snackbar.LENGTH_LONG).show();
        }
        restoreAsked = false;
    }

    @Override
    public void onPending() {
        Snackbar.make(patchButton, R.string.purchase_pending, Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onUnavailable() {
        restoreAsked = false;
        Snackbar.make(patchButton, R.string.store_unavailable, Snackbar.LENGTH_LONG).show();
    }

    @Override
    protected void onPause() {
        ads.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ads.onResume();
    }

    @Override
    protected void onDestroy() {
        ads.onDestroy();
        billing.end();
        worker.shutdown();
        super.onDestroy();
    }
}
