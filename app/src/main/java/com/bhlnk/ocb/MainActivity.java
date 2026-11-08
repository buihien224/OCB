package com.bhlnk.ocb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.IUpdateEngineStable;
import android.os.IUpdateEngineStableCallback;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * OCB — local OTA installer with optional custom OTA certificate.
 * Two tabs (Root / Local Update), two transports (Shizuku / ADB-over-cable).
 */
public class MainActivity extends Activity {

    private static final String TAG = "VSOCB";
    private static final int REQUEST_CODE        = 1001;
    private static final int REQUEST_PICK_FILE   = 1002;
    private static final int REQUEST_SAVE_JSON   = 1003;
    private static final int REQUEST_PICK_CERT   = 1004;
    private static final int REQUEST_PICK_TIMER  = 1005;

    // ── Settings ──────────────────────────────────────────────────────────
    private static final int CERT_STOCK  = 1;
    private static final int CERT_CUSTOM = 2;
    private int    certMode       = CERT_STOCK;
    private String customCertB64  = null; // base64 of custom otacerts.zip
    private String customTimerPath = null; // absolute path of custom timer binary
    private android.app.AlertDialog settingsDlg;

    private String pendingDeviceInfoJson = null;

    // ── Persistent log store (survives app restart, cleared on boot to new build) ──
    private static final String LOG_PREFS      = "vsocb_log";
    private static final String LOG_KEY        = "lines";
    private static final String LOG_FINGERPRINT = "fingerprint";
    private static final int    LOG_MAX        = 300;

    private static final class LogLine {
        final long   time;
        final String text;
        LogLine(long time, String text) { this.time = time; this.text = text; }
    }

    private final List<LogLine> logLines = new ArrayList<>();

    private void persistLog() {
        StringBuilder sb = new StringBuilder();
        synchronized (logLines) {
            for (LogLine l : logLines) sb.append(l.time).append('\t').append(l.text).append('\n');
        }
        getSharedPreferences(LOG_PREFS, MODE_PRIVATE).edit()
                .putString(LOG_KEY, sb.toString()).apply();
    }

    private void restoreLog() {
        android.content.SharedPreferences p = getSharedPreferences(LOG_PREFS, MODE_PRIVATE);
        String fp = android.os.Build.FINGERPRINT;
        if (!fp.equals(p.getString(LOG_FINGERPRINT, fp))) {
            p.edit().clear().apply(); // new build — start fresh
        }
        p.edit().putString(LOG_FINGERPRINT, fp).apply();
        for (String row : p.getString(LOG_KEY, "").split("\n")) {
            int tab = row.indexOf('\t');
            if (tab < 1) continue;
            try {
                long t = Long.parseLong(row.substring(0, tab));
                String txt = row.substring(tab + 1);
                logLines.add(new LogLine(t, txt));
            } catch (NumberFormatException ignored) {}
        }
    }

    // Dynamic palette — updated by applyMonetColors() from wallpaper hue + system dark/light mode
    private static int BG      = 0xFF0A0A0A;
    private static int CARD    = 0xFF1E1E1E;
    private static int LINE    = 0xFF2C2C2C;
    private static int TEXT    = 0xFFE6E1E5;
    private static int MUTED   = 0xFF938F99;
    private static int PRIMARY = 0xFFD0BCFF;
    private static final int GREEN      = 0xFF81C784;
    private static final int GREEN_DARK = 0xFF1B5E20;
    private static final int RED        = 0xFFEF9A9A;
    private static final int AMBER      = 0xFFFFCC80;
    private static final int PURPLE     = 0xFFD0BCFF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private boolean dotBlinking = false;
    private final Runnable dotBlink = new Runnable() {
        private boolean vis = true;
        @Override public void run() {
            if (!dotBlinking || statusDot == null) return;
            vis = !vis;
            statusDot.setAlpha(vis ? 1f : 0.15f);
            ui.postDelayed(this, 550);
        }
    };

    private IUserService service;
    private boolean bound;
    private boolean binding;
    private boolean pendingExploit; // run exploit automatically once Shizuku connects
    private boolean rootOpen;
    private String rootUid = "uid=0";

    // Root tab widgets
    private android.app.ProgressDialog copyProgressDialog;

    private TextView statusTitle;
    private TextView statusSubtitle;
    private View statusDot;

    // Root info rows (dynamic)
    private TextView infoSelinux;
    private TextView infoRoSecure;
    private TextView infoSlot;
    private TextView infoBoot;

    private String selinux = "-";
    private String seccomp = "-";
    private String roSecure = "-";
    private String slot = "-";
    private String bootloader = "-";

    // Update tab widgets
    private LinearLayout rootPage;
    private LinearLayout updatePage;
    private TextView tabRoot;
    private TextView tabUpdate;
    private LinearLayout fileList;
    private TextView selectedLabel;
    private LinearLayout romInfoBlock;
    private String selectedOta = "";
    private Uri selectedOtaUri = null;
    private boolean isPartialOta = false;

    private TextView logView;
    private ScrollView logScroll;
    private android.widget.ProgressBar otaProgressBar; // in-button progress
    private TextView otaProgressText;                  // unused — kept for compat
    private FrameLayout    installBtnFrame;
    private GradientDrawable installBg;
    private TextView    installBtnLabel;
    private android.widget.ProgressBar installBtnBar;
    private TextView    romPickBtn;
    private LinearLayout exploitBtnView;
    private TextView     exploitLabel;
    private TextView segUpdate;
    private TextView segDowngrade;
    private boolean forceDowngradeMode = false;
    private boolean userPickedMode = false; // true once user manually taps UPDATE or DOWNGRADE
    private TextView btnCancelOta;
    private TextView btnInstall;
    private android.widget.CheckBox chkAutoFormat; // unused; kept for binary compat
    private FrameLayout rootFrame;
    private FrameLayout logOverlay;
    private LinearLayout topCard;
    private FrameLayout logCardView;
    private boolean logExpanded = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IUserService.Stub.asInterface(binder);
            bound = true;
            binding = false;
            addLog("user service connected");
            refreshDetails();
            maybeReattachOta();
            // Bring app to foreground in case user is in pairing/setup screen
            try {
                Intent bring = new Intent(MainActivity.this, MainActivity.class);
                bring.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(bring);
            } catch (Throwable ignored) {}
            // Resume pending exploit the user triggered before Shizuku was ready
            if (pendingExploit) {
                pendingExploit = false;
                ui.postDelayed(() -> runRoot(), 400);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            binding = false;
            addLog("user service disconnected");
            refreshDetails();
        }
    };

    // Shizuku (including Shizuku Next) can publish its binder after the app has
    // already been opened. These listeners make the connection self-healing.
    private final Shizuku.OnBinderReceivedListener shizukuBinderListener = () ->
            ui.post(() -> {
                addLog("Shizuku service available");
                showShizuku();
            });

    private final Shizuku.OnBinderDeadListener shizukuBinderDeadListener = () ->
            ui.post(() -> {
                service = null;
                bound = false;
                binding = false;
                addLog("Shizuku service stopped");
                refreshDetails();
            });

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> ui.post(() -> {
                if (requestCode != REQUEST_CODE) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    addLog("Shizuku permission granted");
                    bindService();
                } else {
                    addLog("Shizuku permission denied");
                    refreshDetails();
                }
            });

    private static final String PREFS_CONSENT  = "vsocb_consent";
    private static final String KEY_AGREED     = "agreed_v1";
    private static final String PREFS_ROM      = "vsocb_rom";
    private static final String KEY_ROM_PATH   = "rom_path";
    private static final String KEY_ROM_URI    = "rom_uri";

    private void applyMonetColors() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        float[] hsv = new float[3];
        // Default fallback palette
        if (dark) {
            BG = 0xFF0A0A0A; CARD = 0xFF1A1A1A; LINE = 0xFF2C2C2C;
            TEXT = 0xFFE6E1E5; MUTED = 0xFF938F99; PRIMARY = 0xFFD0BCFF;
        } else {
            BG = 0xFFF6F0FF; CARD = 0xFFFFFFFF; LINE = 0xFFCAC4D0;
            TEXT = 0xFF1C1B1F; MUTED = 0xFF79747E; PRIMARY = 0xFF6750A4;
        }

        if (Build.VERSION.SDK_INT < 31) return;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(this);
            WallpaperColors wc = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (wc == null) return;

            Color.colorToHSV(wc.getPrimaryColor().toArgb(), hsv);

            if (dark) {
                hsv[1] = 0.08f; hsv[2] = 0.05f;
                BG = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
                hsv[1] = 0.10f; hsv[2] = 0.13f;
                CARD = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
                hsv[1] = 0.12f; hsv[2] = 0.20f;
                LINE = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
                hsv[1] = 0.08f; hsv[2] = 0.90f;
                TEXT = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
                hsv[1] = 0.15f; hsv[2] = 0.58f;
                MUTED = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
                hsv[1] = 0.35f; hsv[2] = 0.85f;
                PRIMARY = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
            } else {
                hsv[1] = 0.06f; hsv[2] = 0.97f;
                BG = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
                hsv[1] = 0.00f; hsv[2] = 1.00f;
                CARD = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
                hsv[1] = 0.10f; hsv[2] = 0.82f;
                LINE = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
                hsv[1] = 0.08f; hsv[2] = 0.12f;
                TEXT = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
                hsv[1] = 0.12f; hsv[2] = 0.48f;
                MUTED = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
                hsv[1] = 0.55f; hsv[2] = 0.65f;
                PRIMARY = 0xFF000000 | (Color.HSVToColor(hsv) & 0x00FFFFFF);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener);
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener);
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);

        applyMonetColors();

        rootFrame = new FrameLayout(this);
        applyRootBackground();
        rootFrame.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        // Build pages once; they are reattached on orientation change
        tabRoot   = tab("Root",         () -> showTab(0));
        tabUpdate = tab("Local Update", () -> showTab(1));
        rootPage   = buildRootPage();
        updatePage = buildUpdatePage();
        updatePage.setVisibility(View.GONE);
        topCard    = buildTopCard();
        logCardView = buildLogCard();

        // Overlay for fullscreen log — always on top, hidden by default
        logOverlay = new FrameLayout(this);
        logOverlay.setBackgroundColor(BG);
        applyRootBackground();
        logOverlay.setVisibility(View.GONE);
        logOverlay.setPadding(dp(12), dp(6), dp(12), dp(12));

        applyLayout();
        setContentView(rootFrame);

        boolean alreadyAgreed = getSharedPreferences(PREFS_CONSENT, MODE_PRIVATE)
                .getBoolean(KEY_AGREED, false);
        if (alreadyAgreed) {
            startApp();
        } else {
            showConsentDialog();
        }
    }

    private void showConsentDialog() {
        // Outer card container with padding
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(16), dp(20), dp(8));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(CARD);
        cardBg.setCornerRadius(dp(16));
        card.setBackground(cardBg);

        // Title
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("VSOCB\nImportant Notice");
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(TEXT);
        title.setPadding(0, 0, 0, dp(12));
        card.addView(title);

        // Divider
        android.view.View div = new android.view.View(this);
        div.setBackgroundColor(LINE);
        card.addView(div, new LinearLayout.LayoutParams(-1, 1));

        // Scrollable content
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setPadding(0, dp(12), 0, dp(4));
        android.widget.TextView msg = new android.widget.TextView(this);
        msg.setPadding(0, 0, 0, 0);
        msg.setLineSpacing(dp(3), 1f);
        msg.setTextColor(TEXT);
        msg.setTextSize(13);
        msg.setText(
            "This application supports custom ROM installation " +
            "via the device's official OTA mechanism.\n\n" +

            "HOW IT WORKS\n" +
            "Requires root (uid=0) or ADB to execute privileged commands.\n" +
            "Overwrites the OTA verification certificate (otacerts) in the system partition, " +
            "allowing the device to accept OTA packages signed with a custom key.\n" +
            "Once the certificate is replaced, you can sideload your own ROM directly " +
            "from the device without a PC.\n\n" +

            "RISKS\n" +
            "Writing to the system partition may brick the device if the process is interrupted.\n" +
            "Replacing the OTA certificate may disable the manufacturer's official OTA update mechanism.\n" +
            "This app operates at a low system level.\nThe user assumes full responsibility.\n\n" +

            "By tapping \"I Agree\" you confirm that you have read and understood the risks above " +
            "and voluntarily allow the app to perform these operations on your device."
        );
        sv.addView(msg);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svLp.topMargin = dp(4);
        card.addView(sv, svLp);

        // Divider above buttons
        android.view.View div2 = new android.view.View(this);
        div2.setBackgroundColor(LINE);
        LinearLayout.LayoutParams div2Lp = new LinearLayout.LayoutParams(-1, 1);
        div2Lp.topMargin = dp(8);
        card.addView(div2, div2Lp);

        // Button row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(8), 0, dp(4));

        android.widget.Button btnDecline = new android.widget.Button(this);
        btnDecline.setText("Decline & Exit");
        btnDecline.setTextColor(MUTED);
        btnDecline.setBackground(null);
        btnDecline.setTextSize(13);
        LinearLayout.LayoutParams decLp = new LinearLayout.LayoutParams(0, -2, 1f);
        btnRow.addView(btnDecline, decLp);

        android.widget.Button btnAgree = new android.widget.Button(this);
        btnAgree.setText("I Agree");
        btnAgree.setTextColor(PRIMARY);
        btnAgree.setBackground(null);
        btnAgree.setTextSize(13);
        btnAgree.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams agrLp = new LinearLayout.LayoutParams(0, -2, 1f);
        btnRow.addView(btnAgree, agrLp);

        card.addView(btnRow);

        android.app.AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(card)
            .setCancelable(false)
            .create();

        btnDecline.setOnClickListener(v -> { dialog.dismiss(); finish(); });
        btnAgree.setOnClickListener(v -> {
            getSharedPreferences(PREFS_CONSENT, MODE_PRIVATE)
                .edit().putBoolean(KEY_AGREED, true).apply();
            dialog.dismiss();
            startApp();
        });

        dialog.show();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
    }

    private void restoreRomSelection() {
        android.content.SharedPreferences p = getSharedPreferences(PREFS_ROM, MODE_PRIVATE);
        String path = p.getString(KEY_ROM_PATH, null);
        String uriStr = p.getString(KEY_ROM_URI, null);
        if (path == null || uriStr == null) return;
        if (!new java.io.File(path).exists()) {
            // File đã bị xóa — xóa cache
            p.edit().remove(KEY_ROM_PATH).remove(KEY_ROM_URI).apply();
            return;
        }
        Uri uri = Uri.parse(uriStr);
        selectedOta = path;
        selectedOtaUri = uri;
        // Parse ROM info lại trên background thread
        new Thread(() -> showRomInfo(path)).start();
    }

    private void startApp() {
        showTab(0);
        refreshDetails();
        restoreRomSelection();

        // Restore persisted log entries before adding "VSOCB ready"
        restoreLog();
        synchronized (logLines) {
            for (LogLine l : logLines) appendLogSpan(l.time, l.text);
        }
        if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));

        addLog("VSOCB ready");
        // Restore isPartialOta so re-attach after app restart shows correct button
        isPartialOta = getPreferences(MODE_PRIVATE).getBoolean("isPartialOta", false);
        // Restore settings
        android.content.SharedPreferences sp = getPreferences(MODE_PRIVATE);
        certMode        = sp.getInt("certMode", CERT_STOCK);
        customCertB64   = sp.getString("customCertB64", null);
        customTimerPath = sp.getString("customTimerPath", null);
        if (safePing() && safePermission() == 0) {
            bindService();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyMonetColors();
        applyLayout();
    }

    private LinearLayout buildTopCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        FrameLayout tabArea = new FrameLayout(this);
        tabArea.addView(rootPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabArea.addView(updatePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.view.GestureDetector swipe = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    private static final int SWIPE_MIN_DIST = 80;
                    private static final int SWIPE_MAX_OFF = 150;
                    private static final int SWIPE_MIN_VEL = 100;
                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                          float vX, float vY) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dy) > SWIPE_MAX_OFF) return false;
                        if (Math.abs(dx) > SWIPE_MIN_DIST && Math.abs(vX) > SWIPE_MIN_VEL) {
                            if (dx < 0) showTab(1); else showTab(0);
                            return true;
                        }
                        return false;
                    }
                });
        tabArea.setOnTouchListener((v, ev) -> {
            swipe.onTouchEvent(ev);
            return false; // don't consume — children still get clicks
        });

        card.addView(tabArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return card;
    }

    private void applyLayout() {
        boolean land = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        // Detach both cards before reparenting
        if (topCard.getParent() != null)
            ((ViewGroup) topCard.getParent()).removeView(topCard);
        if (logCardView.getParent() != null)
            ((ViewGroup) logCardView.getParent()).removeView(logCardView);
        if (logOverlay != null && logOverlay.getParent() != null)
            ((ViewGroup) logOverlay.getParent()).removeView(logOverlay);
        rootFrame.removeAllViews();

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        main.setPadding(dp(12), dp(6), dp(12), dp(12));

        if (land) {
            // Landscape: left column = tabs + content card, right column = log
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);

            LinearLayout tabRow = buildTabRow();
            LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
            trLp.bottomMargin = dp(8);
            left.addView(tabRow, trLp);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, 0, 1f);
            left.addView(topCard, cardLp);

            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -1, 1f);
            leftLp.rightMargin = dp(10);
            main.addView(left, leftLp);

            // Right column: log card full height
            LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(0, -1, 1f);
            logCardView.setLayoutParams(logLp);
            main.addView(logCardView, logLp);

        } else {
            // Portrait: tabs → content card → log card stacked vertically
            LinearLayout tabRow = buildTabRow();
            LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
            trLp.bottomMargin = dp(8);
            main.addView(tabRow, trLp);

            LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(-1, 0, 1f);
            topLp.bottomMargin = dp(10);
            main.addView(topCard, topLp);

            LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, 0, 1f);
            logCardView.setLayoutParams(logLp);
            main.addView(logCardView, logLp);
        }

        rootFrame.addView(main, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (logOverlay != null)
            rootFrame.addView(logOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout buildTabRow() {
        // Remove tabs from any prior parent before adding
        if (tabRoot.getParent() != null)
            ((ViewGroup) tabRoot.getParent()).removeView(tabRoot);
        if (tabUpdate.getParent() != null)
            ((ViewGroup) tabUpdate.getParent()).removeView(tabUpdate);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lpLeft = rowParams();
        lpLeft.rightMargin = dp(6);
        row.addView(tabRoot, lpLeft);
        row.addView(tabUpdate, rowParams());
        return row;
    }

    // ---------------------------------------------------------------- pages

    private LinearLayout buildRootPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(16));

        // ── Top row: settings button ───────────────────────────────────
        LinearLayout rootTopRow = new LinearLayout(this);
        rootTopRow.setOrientation(LinearLayout.HORIZONTAL);
        rootTopRow.setGravity(Gravity.CENTER_VERTICAL);
        rootTopRow.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));

        android.widget.ImageView settingsBtn = new android.widget.ImageView(this);
        settingsBtn.setImageResource(getResources().getIdentifier(
                "ic_settings", "drawable", getPackageName()));
        settingsBtn.setColorFilter(TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
        GradientDrawable settingsBg = new GradientDrawable();
        settingsBg.setColor(LINE); settingsBg.setCornerRadius(dp(14));
        settingsBtn.setBackground(settingsBg);
        int sp = dp(8);
        settingsBtn.setPadding(sp, sp, sp, sp);
        settingsBtn.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        rootTopRow.addView(settingsBtn, settingsLp);

        page.addView(rootTopRow, new LinearLayout.LayoutParams(-1, -2));

        // ── Device info (top, fills spare space) ──────────────────────
        LinearLayout infoBlock = new LinearLayout(this);
        infoBlock.setOrientation(LinearLayout.VERTICAL);

        infoRow(infoBlock, "Device",
                android.os.Build.MODEL + " / " + android.os.Build.PRODUCT);
        infoRow(infoBlock, "Android",
                android.os.Build.VERSION.RELEASE + " (SDK " + android.os.Build.VERSION.SDK_INT + ")");
        infoRow(infoBlock, "Kernel",
                System.getProperty("os.version", "?"));
        infoSelinux  = infoRow(infoBlock, "SELinux", "…");
        infoSlot     = infoRow(infoBlock, "Slot", "…");
        infoBoot     = infoRow(infoBlock, "Bootloader", "…");

        ScrollView infoScroll = new ScrollView(this);
        infoScroll.setVerticalScrollBarEnabled(false);
        infoScroll.addView(infoBlock);
        page.addView(infoScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ── Exploit button with embedded status ────────────────────────
        LinearLayout exploitBtn = new LinearLayout(this);
        exploitBtn.setOrientation(LinearLayout.VERTICAL);
        exploitBtn.setGravity(Gravity.CENTER_HORIZONTAL);
        exploitBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable exploitBg = new GradientDrawable();
        exploitBg.setColor(PRIMARY);
        exploitBg.setCornerRadius(dp(14));
        exploitBtn.setBackground(exploitBg);
        exploitBtn.setOnClickListener(v -> {
            if (transportReady()) confirmRoot();
            else { pendingExploit = false; promptConnect(); }
        });
        exploitBtnView = exploitBtn;

        // status row inside button
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(-2, -2);
        srLp.bottomMargin = dp(8);

        statusDot = new View(this);
        statusDot.setBackground(dot(blendColor(PRIMARY, 0x88000000)));
        statusRow.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));

        statusTitle = new TextView(this);
        statusTitle.setTextColor(MUTED);
        statusTitle.setTextSize(12);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusTitle.setPadding(dp(8), 0, 0, 0);
        statusSubtitle = new TextView(this); // unused but kept to avoid NPE in setStatus
        statusSubtitle.setVisibility(View.GONE);
        statusRow.addView(statusTitle, new LinearLayout.LayoutParams(-2, -2));
        exploitBtn.addView(statusRow, srLp);

        // divider
        View div = new View(this);
        div.setBackgroundColor(0x33ffffff);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, dp(1));
        divLp.bottomMargin = dp(10);
        exploitBtn.addView(div, divLp);

        // "Exploit" label
        exploitLabel = new TextView(this);
        exploitLabel.setText(transportReady() ? "Exploit" : "Shizuku");
        exploitLabel.setTextColor(BG);
        exploitLabel.setTextSize(15);
        exploitLabel.setTypeface(Typeface.DEFAULT_BOLD);
        exploitLabel.setGravity(Gravity.CENTER);
        exploitBtn.addView(exploitLabel, new LinearLayout.LayoutParams(-1, -2));

        page.addView(exploitBtn, new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    /** One-line key/value row, returns the value TextView. */
    private TextView infoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
        rLp.bottomMargin = dp(4);

        TextView key = new TextView(this);
        key.setText(label);
        key.setTextColor(MUTED);
        key.setTextSize(12);
        row.addView(key, new LinearLayout.LayoutParams(-2, -2));

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(TEXT);
        val.setTextSize(12);
        val.setPadding(dp(8), 0, 0, 0);
        val.setSingleLine(true);
        row.addView(val, new LinearLayout.LayoutParams(0, -2, 1f));

        parent.addView(row, rLp);
        return val;
    }

    private LinearLayout buildUpdatePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(10), dp(14), dp(14));

        // ── Top row: selected label + ROM button + Cert button ──────────
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        // spacer pushes ROM button to the right
        topRow.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));

        TextView[] infoBtnRef = new TextView[1];
        TextView deviceInfoBtn = button("Info", () -> {
            infoBtnRef[0].setEnabled(false);
            infoBtnRef[0].setText("…");
            showDeviceInfoJson(infoBtnRef[0]);
        });
        infoBtnRef[0] = deviceInfoBtn;
        deviceInfoBtn.setPadding(dp(14), dp(6), dp(14), dp(6));
        topRow.addView(deviceInfoBtn, new LinearLayout.LayoutParams(-2, -2));

        View infoRomSpacer = new View(this);
        topRow.addView(infoRomSpacer, new LinearLayout.LayoutParams(dp(6), 0));

        romPickBtn = button("ROM", () -> openFilePicker());
        romPickBtn.setPadding(dp(14), dp(6), dp(14), dp(6));
        topRow.addView(romPickBtn, new LinearLayout.LayoutParams(-2, -2));

LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
        trLp.bottomMargin = dp(10);
        page.addView(topRow, trLp);

        // ── ROM info block (grows to fill middle) ─────────────────────
        romInfoBlock = new LinearLayout(this);
        romInfoBlock.setOrientation(LinearLayout.VERTICAL);
        romInfoBlock.setGravity(Gravity.TOP);

        FrameLayout romCenter = new FrameLayout(this);
        TextView romPlaceholder = new TextView(this);
        romPlaceholder.setText("Select an OTA file to view details");
        romPlaceholder.setTextColor(MUTED);
        romPlaceholder.setTextSize(12);
        romPlaceholder.setGravity(Gravity.CENTER);
        romCenter.addView(romPlaceholder, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        romInfoBlock.addView(romCenter, new LinearLayout.LayoutParams(-1, 0, 1f));

        ScrollView romScroll = new ScrollView(this);
        romScroll.setVerticalScrollBarEnabled(false);
        romScroll.setFillViewport(true);
        romScroll.addView(romInfoBlock, new ViewGroup.LayoutParams(-1, -1));
        page.addView(romScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ── Install button with embedded progress ─────────────────────────
        installBtnFrame = new FrameLayout(this);
        installBg = new GradientDrawable();
        installBg.setColor(PRIMARY);
        installBg.setCornerRadius(dp(14));
        installBtnFrame.setBackground(installBg);
        installBtnFrame.setClipToOutline(true);
        LinearLayout.LayoutParams installFLp = new LinearLayout.LayoutParams(-1, -2);
        installBtnFrame.setLayoutParams(installFLp);

        // Progress fill: transparent track + clipped dark overlay — no gray track visible at 0%
        installBtnBar = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        installBtnBar.setMax(1000);
        installBtnBar.setProgress(0);
        android.graphics.drawable.LayerDrawable pbLd = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),
                    new android.graphics.drawable.ClipDrawable(
                        new android.graphics.drawable.ColorDrawable(blendColor(PRIMARY, 0x55000000)),
                        Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL)
                });
        pbLd.setId(0, android.R.id.background);
        pbLd.setId(1, android.R.id.progress);
        installBtnBar.setProgressDrawable(pbLd);
        FrameLayout.LayoutParams barFlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        installBtnFrame.addView(installBtnBar, barFlp);

        // Label on top
        installBtnLabel = new TextView(this);
        installBtnLabel.setText("Install");
        installBtnLabel.setTextColor(BG);
        installBtnLabel.setGravity(Gravity.CENTER);
        installBtnLabel.setTextSize(15);
        installBtnLabel.setTypeface(Typeface.DEFAULT_BOLD);
        installBtnLabel.setPadding(dp(12), dp(14), dp(12), dp(14));
        installBtnFrame.addView(installBtnLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        installBtnFrame.setOnClickListener(v -> bypassSelected());
        // wire old ref so existing code that sets btnInstall.setText() still works
        btnInstall = installBtnLabel;

        // Cancel button — same height as INSTALL, 1/5 width, hidden until OTA running
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(PRIMARY);
        cancelBg.setCornerRadius(dp(14));
        btnCancelOta = new TextView(this);
        btnCancelOta.setText("✕");
        btnCancelOta.setTextColor(BG);
        btnCancelOta.setGravity(Gravity.CENTER);
        btnCancelOta.setTextSize(15);
        btnCancelOta.setTypeface(Typeface.DEFAULT_BOLD);
        btnCancelOta.setPadding(dp(12), dp(14), dp(12), dp(14));
        btnCancelOta.setBackground(cancelBg);
        btnCancelOta.setVisibility(View.GONE);
        btnCancelOta.setOnClickListener(v -> cancelOta());

        // Row: [INSTALL 4/5] [gap 8dp] [CANCEL 1/5], same height via MATCH_PARENT
        LinearLayout installRow = new LinearLayout(this);
        installRow.setOrientation(LinearLayout.HORIZONTAL);
        installRow.setBaselineAligned(false);
        LinearLayout.LayoutParams installFrLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4f);
        LinearLayout.LayoutParams cancelLp    = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        cancelLp.leftMargin = dp(8);
        installRow.addView(installBtnFrame, installFrLp);
        installRow.addView(btnCancelOta, cancelLp);

        // Segmented toggle: UPDATE | DOWNGRADE — same style as Shizuku/ADB
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);

        segUpdate    = segment("UPDATE",    () -> {});
        segDowngrade = segment("DOWNGRADE", () -> {});

        Runnable refreshMode = () -> {
            segUpdate.setBackground(segmentBg(!forceDowngradeMode));
            segUpdate.setTextColor(!forceDowngradeMode ? BG : TEXT);
            segDowngrade.setBackground(segmentBg(forceDowngradeMode));
            segDowngrade.setTextColor(forceDowngradeMode ? BG : TEXT);
        };
        refreshMode.run();

        segUpdate.setOnClickListener(v -> { forceDowngradeMode = false; userPickedMode = true; refreshMode.run(); });
        segDowngrade.setOnClickListener(v -> { forceDowngradeMode = true; userPickedMode = true; refreshMode.run(); });

        modeRow.addView(segUpdate,    rowBtn(true));
        modeRow.addView(segDowngrade, rowBtn(false));
        LinearLayout.LayoutParams modeRowLp = new LinearLayout.LayoutParams(-1, -2);
        modeRowLp.bottomMargin = dp(8);
        page.addView(modeRow, modeRowLp);
        page.addView(installRow, new LinearLayout.LayoutParams(-1, -2));


        // keep fileList for background population (not visible in UI)
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileList.setVisibility(View.GONE);
        page.addView(fileList);

        return page;
    }

    /**
     * Parse ROM info for the selected OTA. Does file/zip I/O (reading the
     * central directory of a multi-GB OTA zip can take a while) — call from a
     * background thread; all view updates are posted to the UI thread.
     */
    private void showRomInfo(String path) {
        if (romInfoBlock == null) return;
        if (path == null || path.isEmpty()) {
            ui.post(() -> {
                romInfoBlock.removeAllViews();
                romInfoBlock.setGravity(Gravity.TOP);
                FrameLayout c = new FrameLayout(this);
                TextView ph = new TextView(this);
                ph.setText("Select an OTA file to view details");
                ph.setTextColor(MUTED);
                ph.setTextSize(12);
                ph.setGravity(Gravity.CENTER);
                c.addView(ph, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                romInfoBlock.addView(c, new LinearLayout.LayoutParams(-1, 0, 1f));
            });
            return;
        }
        final java.util.List<String[]> rows = new java.util.ArrayList<>();
        java.io.File f = new java.io.File(path);
        rows.add(new String[]{"File", f.getName()});
        long sz = f.length();
        rows.add(new String[]{"Size", sz > 1024L*1024*1024
                ? String.format("%.2f GB", sz / 1073741824.0)
                : String.format("%.1f MB", sz / 1048576.0)});
        if (!path.toLowerCase().endsWith(".zip")) {
            isDowngrade = false;
            ui.post(() -> renderRomRows(rows));
            return;
        }
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(path)) {

            // ── payload_properties.txt ─────────────────────────────────
            java.util.zip.ZipEntry pp = zf.getEntry("payload_properties.txt");
            if (pp != null) {
                java.io.InputStream in = zf.getInputStream(pp);
                byte[] buf = new byte[8192];
                int n = in.read(buf);
                String props = new String(buf, 0, Math.max(n, 0), "UTF-8");
                java.util.Map<String,String> pm = new java.util.LinkedHashMap<>();
                for (String line : props.split("\n")) {
                    line = line.trim();
                    int eq = line.indexOf('=');
                    if (eq > 0) pm.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
                String[][] fields = {
                    {"POST_BUILD_VERSION_INCREMENTAL", "Version"},
                    {"POST_OTA_VERSION",               "OTA version"},
                    {"ota_target_version",             "Target version"},
                    {"POST_BUILD_TIMESTAMP",           "Build time"},
                    {"SECURITY_PATCH_LEVEL",           "Security patch"},
                    {"POST_BUILD_FINGERPRINT",         "Fingerprint"},
                    {"oplus_separate_soft",            "Separate soft"},
                };
                for (String[] field : fields) {
                    String v = pm.get(field[0]);
                    if (v != null && !v.isEmpty()) rows.add(new String[]{field[1], v});
                }
            }

            // ── payload.bin size ───────────────────────────────────────
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry e = entries.nextElement();
                if (e.getName().endsWith("payload.bin") && e.getSize() > 0) {
                    rows.add(new String[]{"Payload",
                            String.format("%.1f MB", e.getSize() / 1048576.0)});
                    break;
                }
            }

            // ── OTA metadata (META-INF/com/android/metadata) ──────────
            java.util.zip.ZipEntry meta = zf.getEntry("META-INF/com/android/metadata");
            if (meta != null) {
                java.io.InputStream in = zf.getInputStream(meta);
                byte[] buf = new byte[4096];
                int n = in.read(buf);
                String raw = new String(buf, 0, Math.max(n, 0), "UTF-8");
                java.util.Map<String,String> mm = new java.util.LinkedHashMap<>();
                for (String line : raw.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) mm.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
                String[][] metaFields = {
                    {"pre-build-incremental",  "Pre-build"},
                    {"post-build-incremental", "Post-build"},
                    {"pre-device",             "Pre-device"},
                    {"post-timestamp",         "Post-timestamp"},
                    {"ota-streaming-property-files", null},  // skip (too long)
                };
                for (String[] field : metaFields) {
                    if (field[1] == null) continue;
                    String v = mm.get(field[0]);
                    if (v != null && !v.isEmpty()) rows.add(new String[]{field[1], v});
                }
            }

            // ── Wipe marker detection ──────────────────────────────────
            boolean wipeRequired = zf.getEntry("META-INF/com/oplus/wipe_data") != null;
            if (!wipeRequired) {
                // also check metadata field
                java.util.zip.ZipEntry meta2 = zf.getEntry("META-INF/com/android/metadata");
                if (meta2 != null) {
                    java.io.InputStream in2 = zf.getInputStream(meta2);
                    byte[] buf2 = new byte[4096];
                    int n2 = in2.read(buf2);
                    wipeRequired = new String(buf2, 0, Math.max(n2, 0), "UTF-8")
                            .contains("wipe-after-install=true");
                }
            }
            if (wipeRequired) {
                rows.add(new String[]{"Wipe", "Format data required"});
                final boolean wr = true;
                ui.post(() -> autoFormatData = wr);
            }

            // ── Downgrade detection ────────────────────────────────────
            // post-timestamp from metadata vs device Build.TIME (ms → s)
            boolean downgrade = false;
            java.util.zip.ZipEntry meta3 = zf.getEntry("META-INF/com/android/metadata");
            if (meta3 != null) {
                java.io.InputStream in3 = zf.getInputStream(meta3);
                byte[] buf3 = new byte[4096];
                int n3 = in3.read(buf3);
                String raw3 = new String(buf3, 0, Math.max(n3, 0), "UTF-8");
                for (String line : raw3.split("\n")) {
                    if (line.startsWith("post-timestamp=")) {
                        try {
                            long otaTs = Long.parseLong(line.substring("post-timestamp=".length()).trim());
                            long deviceTs = android.os.Build.TIME / 1000L;
                            downgrade = otaTs < deviceTs;
                        } catch (NumberFormatException ignored2) {}
                        break;
                    }
                }
            }
            final boolean dg = downgrade;
            isDowngrade = dg;
            if (dg) {
                rows.add(new String[]{"Type",
                        "DOWNGRADE\nwill use --allow_older_timestamps"});
            }
            // Auto-set toggle only if user hasn't manually chosen yet
            if (!userPickedMode) {
                ui.post(() -> {
                    forceDowngradeMode = dg;
                    if (segUpdate != null && segDowngrade != null) {
                        segUpdate.setBackground(segmentBg(!dg));
                        segUpdate.setTextColor(!dg ? BG : TEXT);
                        segDowngrade.setBackground(segmentBg(dg));
                        segDowngrade.setTextColor(dg ? BG : TEXT);
                    }
                });
            }

            // ── OTA signature detection (custom certificate) ───────────
            // Compare the OTA's META-INF/com/android/otacert with the
            // user-supplied cert, when one is loaded.
            boolean customSigned = false;
            try {
                byte[] customCertBytes = firstEntryBytes(customCertB64);
                java.util.zip.ZipEntry otaCertEntry = zf.getEntry("META-INF/com/android/otacert");
                if (otaCertEntry != null && customCertBytes != null) {
                    try (java.io.InputStream oci = zf.getInputStream(otaCertEntry)) {
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] tmp = new byte[4096];
                        int r;
                        while ((r = oci.read(tmp)) >= 0) bos.write(tmp, 0, r);
                        customSigned = java.util.Arrays.equals(customCertBytes, bos.toByteArray());
                    }
                }
            } catch (Throwable ignored2) {}
            rows.add(new String[]{"Signed by", customSigned ? "Custom" : "Official"});

        } catch (Throwable ignored) {}
        ui.post(() -> renderRomRows(rows));
    }

    /** Tìm banner cert đang hiển thị trong romInfoBlock và refresh status line. */
    /** UI-thread render of parsed rows into the ROM info block. */
    private void renderRomRows(java.util.List<String[]> rows) {
        romInfoBlock.removeAllViews();
        romInfoBlock.setGravity(Gravity.TOP);
        for (String[] r : rows) romInfoRow(r[0], r[1]);
    }

    /** Persist wipe intent for this OTA path in SharedPreferences + .wipe sidecar file. */
    private void saveWipeFlag(String otaPath, boolean wipe) {
        if (otaPath == null || otaPath.isEmpty()) return;
        android.content.SharedPreferences.Editor ed =
                getSharedPreferences("ota_flags", MODE_PRIVATE).edit();
        String key = "wipe:" + otaPath;
        if (wipe) ed.putBoolean(key, true); else ed.remove(key);
        ed.apply();
        // also write/remove sidecar file for portability
        java.io.File sidecar = new java.io.File(otaPath + ".wipe");
        try {
            if (wipe) {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(sidecar)) {
                    fos.write("wipe-after-install=true\n".getBytes());
                }
            } else {
                sidecar.delete();
            }
        } catch (Throwable ignored) {}
    }

    /** Check wipe flag: sidecar file OR SharedPreferences OR zip metadata. */
    private boolean readWipeFlag(String otaPath) {
        if (otaPath == null || otaPath.isEmpty()) return false;
        // sidecar file
        if (new java.io.File(otaPath + ".wipe").exists()) return true;
        // SharedPreferences
        if (getSharedPreferences("ota_flags", MODE_PRIVATE)
                .getBoolean("wipe:" + otaPath, false)) return true;
        return false;
    }

    private void romInfoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(3);

        TextView key = new TextView(this);
        key.setText(label);
        key.setTextColor(MUTED);
        key.setTextSize(11);
        key.setMinWidth(dp(88));
        row.addView(key, new LinearLayout.LayoutParams(-2, -2));

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(TEXT);
        val.setTextSize(11);
        val.setPadding(dp(6), 0, 0, 0);
        val.setSingleLine(true);
        row.addView(val, new LinearLayout.LayoutParams(0, -2, 1f));

        romInfoBlock.addView(row, lp);
    }

    private FrameLayout buildLogCard() {
        // Use FrameLayout as outer container so the expand button can float top-right
        FrameLayout frame = new FrameLayout(this);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        wrapper.setBackground(bg);
        frame.addView(wrapper, new FrameLayout.LayoutParams(-1, -1));

        // Floating expand button — top-right corner with Material Symbol path icons
        final String PATH_EXPAND   = "M5,19L5,13L7,13L7,17L11,17L11,19L5,19ZM17,11L17,7L13,7L13,5L19,5L19,11L17,11Z";
        final String PATH_COLLAPSE = "M11,13L11,19L9,19L9,15L5,15L5,13L11,13ZM15,5L15,9L19,9L19,11L13,11L13,5L15,5Z";
        android.widget.ImageButton expandBtn = new android.widget.ImageButton(this);
        expandBtn.setImageDrawable(pathIcon(PATH_EXPAND, TEXT));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0x44FFFFFF);
        btnBg.setCornerRadius(dp(10));
        expandBtn.setBackground(btnBg);
        expandBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        expandBtn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        expandBtn.setClickable(true);
        expandBtn.setFocusable(true);
        expandBtn.setTag(new String[]{PATH_EXPAND, PATH_COLLAPSE});
        expandBtn.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                toggleLogExpand(expandBtn);
            }
            return true;
        });
        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(dp(32), dp(32),
                Gravity.TOP | Gravity.END);
        btnLp.topMargin = dp(8);
        btnLp.rightMargin = dp(8);
        frame.addView(expandBtn, btnLp);

        // No separate progress row — progress lives inside the Install button

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setVerticalScrollBarEnabled(false);
        android.widget.HorizontalScrollView hScroll = new android.widget.HorizontalScrollView(this);
        hScroll.setFillViewport(true);
        hScroll.setHorizontalScrollBarEnabled(false);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFFc0c0c0);
        logView.setTextIsSelectable(false);
        logView.setPadding(dp(4), dp(0), dp(16), dp(8));
        logView.setSingleLine(false);
        logView.setHorizontallyScrolling(true);
        hScroll.addView(logView, new ViewGroup.LayoutParams(-2, -2));
        logScroll.addView(hScroll, new ViewGroup.LayoutParams(-1, -2));
        wrapper.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return frame;
    }

    private void toggleLogExpand(View expandBtn) {
        logExpanded = !logExpanded;
        if (expandBtn instanceof android.widget.ImageButton && expandBtn.getTag() instanceof String[]) {
            String[] paths = (String[]) expandBtn.getTag();
            ((android.widget.ImageButton) expandBtn).setImageDrawable(
                    pathIcon(logExpanded ? paths[1] : paths[0], TEXT));
        }
        logCardView.animate().cancel();
        logOverlay.animate().cancel();

        android.view.animation.DecelerateInterpolator decel =
                new android.view.animation.DecelerateInterpolator(2f);
        android.view.animation.AccelerateInterpolator accel =
                new android.view.animation.AccelerateInterpolator(2f);

        ViewGroup parent = (ViewGroup) logCardView.getParent();
        if (logExpanded) {
            // move logCardView into fullscreen overlay, slide up from bottom
            if (parent != null) parent.removeView(logCardView);
            logOverlay.addView(logCardView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            logOverlay.setVisibility(View.VISIBLE);
            // start below screen, slide to y=0
            logCardView.setTranslationY(logOverlay.getHeight() > 0
                    ? logOverlay.getHeight() : 1800f);
            logCardView.animate()
                    .translationY(0f)
                    .setDuration(260)
                    .setInterpolator(decel)
                    .start();
            if (logScroll != null)
                logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        } else {
            // slide down out of view, then swap back
            logCardView.animate()
                    .translationY(logOverlay.getHeight() > 0
                            ? logOverlay.getHeight() : 1800f)
                    .setDuration(220)
                    .setInterpolator(accel)
                    .withEndAction(() -> {
                        logCardView.setTranslationY(0f);
                        logOverlay.setVisibility(View.GONE);
                        logOverlay.removeAllViews();
                        logOverlay.post(() -> {
                            reattachLogCard();
                            if (logScroll != null)
                                logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
                        });
                    }).start();
        }
    }

    private void reattachLogCard() {
        applyLayout();
    }

    private int currentTab = -1;

    private void showTab(int index) {
        if (index == currentTab) return;
        currentTab = index;

        LinearLayout inPage  = index == 0 ? rootPage  : updatePage;
        LinearLayout outPage = index == 0 ? updatePage : rootPage;

        float slideIn = index == 1 ? 60f : -60f;
        android.view.animation.Interpolator decel = new android.view.animation.DecelerateInterpolator(2f);

        outPage.animate().cancel();
        inPage.animate().cancel();

        inPage.setAlpha(0f);
        inPage.setTranslationX(slideIn);
        inPage.setVisibility(View.VISIBLE);

        // Sync both animators to the same duration so they finish together
        inPage.animate()
                .alpha(1f).translationX(0f)
                .setDuration(200)
                .setInterpolator(decel)
                .start();

        outPage.animate()
                .alpha(0f).translationX(-slideIn)
                .setDuration(200)
                .setInterpolator(decel)
                .withEndAction(() -> {
                    outPage.setVisibility(View.GONE);
                    outPage.setAlpha(1f);
                    outPage.setTranslationX(0f);
                })
                .start();

        styleTabActive(tabRoot, index == 0);
        styleTabActive(tabUpdate, index == 1);
        if (index == 1) refreshFiles();
    }

    // --------------------------------------------------------- OTA widgets

    private TextView segment(String label, Runnable action) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(13);
        v.setPadding(dp(8), dp(9), dp(8), dp(9));
        v.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView button(String label, Runnable action) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextColor(TEXT);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(13);
        v.setPadding(dp(12), dp(11), dp(12), dp(11));
        GradientDrawable d = new GradientDrawable();
        d.setColor(LINE);
        d.setCornerRadius(dp(14));
        v.setBackground(d);
        v.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    /** LayoutParams for a button placed in a horizontal row (weight + gap). */
    private LinearLayout.LayoutParams rowBtn(boolean gapRight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.topMargin = dp(10);
        if (gapRight) {
            p.rightMargin = dp(8);
        }
        return p;
    }

    private TextView primary(String label, Runnable action) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextColor(BG); // text on primary bg
        v.setGravity(Gravity.CENTER);
        v.setTextSize(15);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(12), dp(14), dp(12), dp(14));
        GradientDrawable d = new GradientDrawable();
        d.setColor(PRIMARY);
        d.setCornerRadius(dp(16));
        v.setBackground(d);
        v.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView iconButton(String glyph, Runnable action) {
        TextView v = new TextView(this);
        v.setText(glyph);
        v.setTextColor(PRIMARY);
        v.setTextSize(20);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(4), dp(10), dp(4));
        v.setOnClickListener(x -> action.run());
        return v;
    }

    private TextView tab(String label, Runnable action) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextColor(MUTED);
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(8), dp(10), dp(8));
        v.setOnClickListener(x -> action.run());
        return v;
    }

    private void styleTabActive(TextView tab, boolean active) {
        tab.setTextColor(active ? TEXT : MUTED);
        tab.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        GradientDrawable d = new GradientDrawable();
        d.setColor(active ? LINE : adjustAlpha(LINE, 0.35f));
        d.setCornerRadius(dp(12));
        tab.setBackground(d);
    }

    private GradientDrawable dot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(1000);
        return d;
    }

    // ------------------------------------------------------------ transport

    private GradientDrawable segmentBg(boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(active ? PRIMARY : LINE);
        d.setCornerRadius(dp(14));
        return d;
    }

    private boolean transportReady() {
        if (rootDaemonReady()) return true;
        return service != null && bound;
    }

    // ------------------------------------------------------------- state

    private void refreshDetails() {
        if (transportReady()) {
            new Thread(() -> {
                try {
                    String info = tInfo();
                    boolean root = rootDaemonReady() || suReady();
                    ui.post(() -> {
                        parseInfo(info);
                        rootOpen = root;
                        applyDetails();
                    });
                } catch (Throwable ignored) {}
            }).start();
        }
        applyDetails();
    }

    private String tInfo() {
        // Root daemon pipe — highest priority, gives real root info
        if (rootDaemonReady()) {
            String uid = sh("id").trim();
            String sel = sh("getenforce").trim();
            String sec = sh("grep -m1 Seccomp /proc/self/status | awk '{print $2}'").trim();
            String ros = sh("getprop ro.secure").trim();
            String sl  = sh("getprop ro.boot.slot_suffix").trim();
            String vbs = sh("getprop ro.boot.verifiedbootstate").trim();
            String fll = sh("getprop ro.boot.flash.locked").trim();
            String bl  = !vbs.isEmpty() ? vbs : (!fll.isEmpty() ? (fll.equals("0") ? "unlocked" : "locked") : "?");
            return "uid=" + uid + "\nselinux=" + sel + "\nseccomp=" + sec
                    + "\nrosecure=" + ros + "\nslot=" + sl + "\nbootloader=" + bl + "\n";
        }
        try {
            String base = service.info();
            String uid = service.execShell("id").trim();
            String ros = service.execShell("getprop ro.secure").trim();
            String sl  = service.execShell("getprop ro.boot.slot_suffix").trim();
            String vbs = service.execShell("getprop ro.boot.verifiedbootstate").trim();
            String fll = service.execShell("getprop ro.boot.flash.locked").trim();
            String bl  = !vbs.isEmpty() ? vbs : (!fll.isEmpty() ? (fll.equals("0") ? "unlocked" : "locked") : "?");
            return "uid=" + uid + "\n" + base + "rosecure=" + ros + "\nslot=" + sl + "\nbootloader=" + bl + "\n";
        } catch (Throwable t) {
            return "selinux=?\n";
        }
    }

    private void parseInfo(String raw) {
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "uid":        rootUid    = value; break;
                case "selinux":    selinux    = value; break;
                case "seccomp":    seccomp    = value; break;
                case "rosecure":   roSecure   = value; break;
                case "slot":       slot       = value; break;
                case "bootloader": bootloader = value; break;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 31) {
            applyMonetColors();
            rootFrame.setBackgroundColor(BG);
            if (logOverlay != null) logOverlay.setBackgroundColor(BG);
        if (rootFrame != null) applyRootBackground();
        }
        refreshDetails();
        maybeReattachOta();
    }

    /**
     * Re-attach to a running OTA if one is in progress and we are not already tailing.
     * Safe to call multiple times — no-ops if tailRunning or Shizuku not yet bound.
     * Also triggered from onServiceConnected so it fires as soon as Shizuku binds.
     */
    private void maybeReattachOta() {
        if (tailRunning) return;
        new Thread(() -> {
            // Wait up to 30s for either transport or Shizuku to become available.
            // transportReady() only returns true for the CURRENT transport mode (ADB or
            // Shizuku), but binder re-attach only needs Shizuku — check both.
            for (int i = 0; i < 30 && !tailRunning; i++) {
                if (transportReady() || (service != null && bound)) break;
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
            }
            if (tailRunning) return;
            synchronized (otaLock) {
                if (tailRunning) return;
            }

            // ── Binder re-attach (Shizuku bound) ─────────────────────────────
            // Works regardless of transportMode — Shizuku may be bound even when
            // the UI is set to ADB mode.
            if (service != null && bound) {
                tryBinderReattach();
                return;
            }

            // ── Shell re-attach (apply.log file) ─────────────────────────────
            if (!transportReady()) return;
            String log = sh("cat /data/local/tmp/apply.log 2>/dev/null");
            String rc  = sh("cat /data/local/tmp/apply.rc 2>/dev/null").trim();
            String ageSec = sh(
                    "f=/data/local/tmp/apply.log; [ -f \"$f\" ] && "
                    + "echo $(( $(date +%s) - $(stat -c %Y \"$f\" 2>/dev/null || echo 0) )) "
                    + "|| echo 99999").trim();
            boolean logExists = !log.trim().isEmpty()
                    && !log.equals("not connected")
                    && !log.startsWith("bridge error");
            boolean logRecent;
            try { logRecent = Integer.parseInt(ageSec) < 600; }
            catch (NumberFormatException e) { logRecent = false; }
            boolean rcEmpty = rc.isEmpty()
                    || rc.equals("not connected")
                    || rc.startsWith("bridge error");
            boolean ueRunning = false;
            if (!logExists) {
                String pid = sh("pidof update_engine_client 2>/dev/null").trim();
                ueRunning = pid.matches("\\d+(\\s+\\d+)*");
            }
            if ((logExists && logRecent && rcEmpty) || ueRunning) {
                addLog("OTA in progress — re-attaching log...");
                tailApplyLog();
            }
        }, "reattach-check").start();
    }

    /**
     * Re-attach to an ongoing OTA session via IUpdateEngineStable.bind().
     * Uses a 3-second probe: bind callback, wait for first non-IDLE status.
     * If nothing arrives → engine is IDLE → abort silently.
     */
    private void tryBinderReattach() {
        try {
            final java.util.concurrent.CountDownLatch probe = new java.util.concurrent.CountDownLatch(1);
            final int[] firstStatus = {-1};

            IUpdateCallback probeCb = new IUpdateCallback.Stub() {
                @Override public void onStatus(int status, int pct) {
                    firstStatus[0] = status;
                    probe.countDown();
                }
                @Override public void onComplete(int errorCode) { probe.countDown(); }
            };
            String err = service.rebindCallback(probeCb);
            // service not found / dead = update_engine not running = no OTA → silent
            if (!err.isEmpty()) {
                boolean expected = err.contains("not found")
                        || err.contains("DEAD_OBJECT")
                        || err.contains("DeadObjectException");
                if (!expected) addLog("[warn] binder probe: " + err);
                return;
            }

            probe.await(3, java.util.concurrent.TimeUnit.SECONDS);
            // 0 = IDLE, -1 = no callback → engine not doing anything
            if (firstStatus[0] <= 0) {
                service.rebindCallback(null);
                return;
            }

            // status 6 = UPDATED_NEED_REBOOT — already done, just show success UI
            if (firstStatus[0] == 6) {
                service.rebindCallback(null);
                synchronized (otaLock) { otaResultClaimed = false; }
                otaEpoch++;
                tailRunning = true;
                onOtaSuccess(otaEpoch);
                return;
            }

            // Engine is active mid-install — replace probe with real callback
            synchronized (otaLock) {
                if (tailRunning) { service.rebindCallback(null); return; }
                otaResultClaimed = false;
            }
            otaEpoch++;
            tailRunning = true;
            final int epoch = otaEpoch;

            ui.post(() -> {
                addLog("OTA in progress — re-attaching via binder...");
                if (installBtnBar   != null) installBtnBar.setProgress(0);
                if (installBtnLabel != null) installBtnLabel.setText("DOWNLOADING: 0%");
                if (btnCancelOta    != null) btnCancelOta.setVisibility(View.VISIBLE);
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });

            IUpdateCallback realCb = new IUpdateCallback.Stub() {
                @Override
                public void onStatus(int status, int pct) {
                    if (epoch != otaEpoch) return;
                    if (status == 6) { onOtaSuccess(epoch); return; }
                    if (status <= 0) return;
                    String stage = ueStatusLabel(status);
                    ui.post(() -> {
                        if (installBtnBar   != null) installBtnBar.setProgress(pct * 10);
                        if (installBtnLabel != null) installBtnLabel.setText(stage.toUpperCase() + ": " + pct + "%");
                        if (pct % 10 == 0)  addLog(stage + ": " + pct + "%");
                    });
                }
                @Override
                public void onComplete(int errorCode) {
                    if (epoch != otaEpoch) return;
                    if (errorCode == 0) onOtaSuccess(epoch);
                    else onOtaFailure(epoch, "update_engine error " + errorCode + otaErrorHint(errorCode));
                }
            };
            err = service.rebindCallback(realCb);
            if (!err.isEmpty()) {
                tailRunning = false;
                addLog("[warn] binder reattach: " + err);
            }
        } catch (Throwable t) {
            tailRunning = false;
            addLog("[warn] tryBinderReattach: " + t);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(shizukuBinderListener);
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener);
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        if (bound || binding) {
            try {
                Shizuku.unbindUserService(serviceArgs(), connection, true);
            } catch (Throwable ignored) {
            }
            bound = false;
            binding = false;
        }
    }

    private void applyDetails() {
        boolean ready = transportReady();
        boolean hasSu = rootDaemonReady();
        if (rootOpen) {
            String sel = selinux.isEmpty() || "-".equals(selinux) ? "" : " · SELinux: " + selinux;
            setStatus("Root", rootUid + sel, GREEN);
        } else if (hasSu) {
            setStatus("Su ready", "pipe daemon", GREEN);
        } else if (ready) {
            setStatus("Connected", "", BG);
        } else {
            setStatus("Not connected", "", blendColor(PRIMARY, 0x88000000));
        }

        if (infoSelinux != null) {
            boolean enf = "Enforcing".equalsIgnoreCase(selinux);
            infoSelinux.setText(selinux);
            infoSelinux.setTextColor(enf ? RED : GREEN);
        }
        if (infoSlot != null) {
            infoSlot.setText(slot.isEmpty() ? "—" : slot);
            infoSlot.setTextColor(TEXT);
        }
        if (infoBoot != null) {
            boolean unlocked = bootloader.equalsIgnoreCase("orange")
                    || bootloader.equalsIgnoreCase("unlocked");
            infoBoot.setText(unlocked ? "Unlocked" : "Locked");
            infoBoot.setTextColor(unlocked ? GREEN : TEXT);
        }

        // Exploit button label mirrors connection state
        if (exploitLabel != null)
            exploitLabel.setText(ready ? "Exploit" : "Shizuku");

        // Disable install button when Shizuku not connected and OTA not running
        if (installBtnFrame != null && installBg != null && !tailRunning) {
            boolean connected = ready;
            installBg.setColor(connected ? PRIMARY : LINE);
            installBtnFrame.setClickable(connected);
            if (installBtnLabel != null)
                installBtnLabel.setTextColor(connected ? BG : MUTED);
        }
    }

    private void setStatus(String title, String sub, int dotColor) {
        if (statusTitle != null) {
            statusTitle.setText(title);
            statusTitle.setTextColor(BG);
        }
        if (statusSubtitle != null) statusSubtitle.setText(sub);
        if (statusDot != null) {
            statusDot.setBackground(dot(dotColor));
            statusDot.setAlpha(1f);
        }
        boolean notConn = "Not connected".equals(title);
        if (notConn && !dotBlinking) {
            dotBlinking = true;
            ui.post(dotBlink);
        } else if (!notConn && dotBlinking) {
            dotBlinking = false;
            ui.removeCallbacks(dotBlink);
            if (statusDot != null) statusDot.setAlpha(1f);
        }
    }

    private boolean safePing() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private void showShizuku() {
        if (!safePing()) {
            refreshDetails();
            return;
        }
        if (safePermission() != PackageManager.PERMISSION_GRANTED) {
            try {
                if (!Shizuku.shouldShowRequestPermissionRationale()) {
                    Shizuku.requestPermission(REQUEST_CODE);
                    addLog("Shizuku permission requested");
                } else {
                    addLog("Shizuku permission was denied; grant it in Shizuku Next");
                }
            } catch (Throwable t) {
                addLog("permission request failed: " + t);
            }
        } else if (!bound && !binding) {
            bindService();
        }
    }

    private int safePermission() {
        try {
            return Shizuku.checkSelfPermission();
        } catch (Throwable t) {
            return -1;
        }
    }

    // --------------------------------------------------------------- root

    private void prepare() {
        if (!transportReady()) {
            promptConnect();
            return;
        }
        if (service == null) {
            bindService();
            toast("Connecting to Shizuku...");
            return;
        }
        toast("Preparing...");
        new Thread(() -> {
            try {
                final String result = service.install(getPackageCodePath());
                addLog(result);
                ui.post(() -> toast(result));
            } catch (Throwable t) {
                addLog("prepare failed: " + t);
            }
        }).start();
    }

    private void promptConnect() {
        onShizukuNotConnected();
    }

    /** Shizuku chưa chạy khi cần transport. Integrated build override để mở pairing tutorial. */
    private void onShizukuNotConnected() {
        showShizuku();
    }

    enum AlertType { INFO, WARN, ERROR }

    private void showAlert(String title, String message, AlertType type) {
        if (isFinishing()) return;
        ui.post(() -> {
            showThemedDialog(title, message, "OK", null, null, null);
        });
    }

    private void showThemedDialog(String title, String message,
                                   String posLabel, Runnable posAction,
                                   String negLabel, Runnable negAction) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(16));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(CARD);
        cardBg.setCornerRadius(dp(16));
        card.setBackground(cardBg);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(TEXT);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setPadding(0, 0, 0, dp(10));
        card.addView(tvTitle);

        TextView tvMsg = new TextView(this);
        tvMsg.setText(message);
        tvMsg.setTextColor(TEXT);
        tvMsg.setTextSize(14);
        tvMsg.setPadding(0, 0, 0, dp(16));
        card.addView(tvMsg);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        android.app.AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(card).setCancelable(false).create();

        if (negLabel != null) {
            TextView btnNeg = new TextView(this);
            btnNeg.setText(negLabel);
            btnNeg.setTextColor(MUTED);
            btnNeg.setTextSize(14);
            btnNeg.setTypeface(Typeface.DEFAULT_BOLD);
            btnNeg.setPadding(dp(16), dp(10), dp(16), dp(10));
            btnNeg.setOnClickListener(v -> { dialog.dismiss(); if (negAction != null) negAction.run(); });
            btnRow.addView(btnNeg);
        }

        TextView btnPos = new TextView(this);
        btnPos.setText(posLabel);
        btnPos.setTextColor(PRIMARY);
        btnPos.setTextSize(14);
        btnPos.setTypeface(Typeface.DEFAULT_BOLD);
        btnPos.setPadding(dp(16), dp(10), dp(0), dp(10));
        btnPos.setOnClickListener(v -> { dialog.dismiss(); if (posAction != null) posAction.run(); });
        btnRow.addView(btnPos);

        card.addView(btnRow);
        dialog.show();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void confirmRoot() {
        if (rootDaemonReady()) {
            showThemedDialog("Su available",
                    "su_server is already running (uid=0).\nNo need to run exploit again.",
                    "OK", null, null, null);
            return;
        }
        showThemedDialog("Get root",
                "Gains uid=0 and sets SELinux to Permissive so OTA can be installed.",
                "Run", () -> {
                    if (!transportReady()) {
                        pendingExploit = true;
                        promptConnect();
                        return;
                    }
                    runRoot();
                }, "Cancel", null);
    }

    private void runRoot() {
        if (!transportReady()) {
            promptConnect();
            return;
        }
        new Thread(() -> {
            try {
                ensureTools();
                addLog("probing su...");
                if (suReady()) {
                    rootOpen = true;
                    parseInfo(tInfo());
                    addLog("su works — no exploit needed");
                    showAlert("Root ready", "su uid=0 available. Tap Install to continue.", AlertType.INFO);
                    ui.post(this::applyDetails);
                    return;
                }
                addLog("starting exploit...");
                boolean success = false;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    String parkedPid = timerPid();
                    if (!parkedPid.isEmpty()) {
                        // A parked timer owns a freed kernel page; killing it
                        // (or reaping it) corrupts the slab and panics this
                        // kernel. Only a reboot frees it safely.
                        addLog("timer parked (pid " + parkedPid + ") — reboot required");
                        showAlert("Reboot required",
                                "A timer process is still parked. Reboot the device and try again.",
                                AlertType.ERROR);
                        return;
                    }
                    if (attempt > 1) addLog("attempt " + attempt + "/3...");
                    success = ensureRootSession();
                    if (success) break;
                    addLog("attempt " + attempt + " failed — " + (attempt < 3 ? "retrying..." : "no more attempts"));
                    if (attempt < 3) Thread.sleep(2000);
                }
                rootOpen = success;
                if (success) {
                    parseInfo(tInfo());
                    addLog("Root success");
                    addLog("Ready to Install OTA");
                    // Auto-start Shizuku server so binder OTA path is available
                    // without requiring a standalone Shizuku app installation.
                    startShizukuServer();
                    showAlert("Root ready", "uid=0 + SELinux Permissive. Tap Install to continue.", AlertType.INFO);
                }
                ui.post(this::applyDetails);
                if (!success) {
                    addLog("exploit failed after 3 attempts");
                    showAlert("Exploit failed",
                            "Failed after 3 attempts. Reboot and try again.",
                            AlertType.ERROR);
                }
            } catch (Throwable t) {
                addLog("root failed: " + t);
                showAlert("Exploit error",
                        t.getMessage() != null ? t.getMessage() : t.toString(),
                        AlertType.ERROR);
            }
        }).start();
    }

    /**
     * Start timer with given args in background, clearing timer.log first.
     * Uses a subshell so the adb shell session exits immediately and
     * the bridge socket is freed for subsequent poll calls.
     *
     * Refuses to start when another timer is already alive: concurrent
     * exploit runs are the main cause of parked processes / high load.
     */
    private boolean spawnBackground(String args) {
        String running = timerPid();
        if (!running.isEmpty()) {
            addLog("timer already running (pid " + running + ") — skipping");
            return false;
        }
        sh("rm -f /data/local/tmp/timer.log");
        try { service.runDetached(args.isEmpty() ? "shell" : args); }
        catch (Throwable ignored) {}
        return true;
    }

    /** PIDs of live timer processes (empty when none). */
    private String timerPid() {
        try {
            String name = customTimerPath != null ? "timer_custom"
                    : (service != null ? service.timerName() : "timer_GKI");
            String out = sh("pidof '" + name + "' 2>/dev/null").trim();
            if (out.startsWith("not connected") || out.startsWith("error")) return "";
            return out;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Poll timer.log every 500 ms, streaming new lines to the log view.
     * Returns true when any success marker appears, false on failure marker or timeout.
     */
    private boolean pollLog(long timeoutMs, String[] success, String[] fail)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        int lastLen = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            String log = tReadLog();
            if (log.length() > lastLen) {
                String chunk = log.substring(lastLen);
                lastLen = log.length();
                for (String line : chunk.split("\n", -1)) {
                    String l = line.trim();
                    if (!l.isEmpty()) addLogRaw(l);
                }
            }
            for (String s : success) if (log.contains(s)) return true;
            for (String f : fail)    if (log.contains(f)) return false;
        }
        return false;
    }

    // su wrapper script embedded as base64 — written directly by installSuScript() so
    // the root context never needs to read from the app's credential-encrypted data dir.
    private static final String SU_WRAPPER_B64 =
        "IyEvc3lzdGVtL2Jpbi9zaAppZiBbICIkMSIgPSAiLWMiIF0gJiYgWyAtbiAiJDIiIF07IHRoZW4K"
        + "ICBwcmludGYgJyVzXG4nICIkMiIgfCBuYyAtVSAvZGF0YS9sb2NhbC90bXAvc3Uuc29jawplbHNl"
        + "CiAgcHJpbnRmICclc1xuJyAiJHsxOi1pZH0iIHwgbmMgLVUgL2RhdGEvbG9jYWwvdG1wL3N1LnNv"
        + "Y2sKZmkK";

    /**
     * Always re-install the device-specific timer binary.
     */
    private void ensureTools() {
        if (customTimerPath != null) {
            addLog("installing custom timer: " + new java.io.File(customTimerPath).getName());
            try {
                String res = service.execShell(
                        "rm -f /data/local/tmp/timer /data/local/tmp/timer_*"
                        + " && cp '" + customTimerPath + "' /data/local/tmp/timer_custom"
                        + " && chmod 755 /data/local/tmp/timer_custom"
                        + " && echo OK");
                addLog("custom timer install: " + res.trim());
            } catch (Throwable t) {
                addLog("custom timer install failed: " + t);
            }
            return;
        }
        addLog("installing timer...");
        try {
            addLog(service.install(getPackageCodePath()));
        } catch (Throwable t) {
            addLog("install failed: " + t);
        }
    }

    /** Expected on-device size of the timer binary, derived from the APK asset. -1 if unknown. */
    private long timerExpectedSize() {
        try {
            String model = sh("getprop ro.product.name").trim();
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(getPackageCodePath());
            java.util.zip.ZipEntry e = !model.isEmpty()
                    ? zf.getEntry("assets/timer_" + model) : null;
            if (e == null) e = zf.getEntry("assets/timer_GKI");
            if (e == null) e = zf.getEntry("assets/timer");
            long size = e != null ? e.getSize() : -1;
            zf.close();
            return size;
        } catch (Throwable t) {
            return -1;
        }
    }

    private String tReadLog() {
        try {
            return service.readLog();
        } catch (Throwable t) {
            return "";
        }
    }

    private void reboot() {
        showThemedDialog("Reboot device", "Reboot the device now?",
                "Reboot", () -> new Thread(() -> {
                    addLog("rebooting...");
                    powerReboot("");
                }).start(),
                "Cancel", null);
    }

    // ------------------------------------------------------------ update

    private void refreshFiles() {
        if (!transportReady()) {
            addLog("Shizuku not connected");
            return;
        }
        new Thread(() -> {
            try {
                final String list = service.listOtaFiles();
                ui.post(() -> {
                    fileList.removeAllViews();
                    boolean any = false;
                    for (String line : list.split("\n")) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("ls:")) {
                            continue;
                        }
                        any = true;
                        fileList.addView(fileRow(line));
                    }
                    if (!any) {
                        fileList.addView(fileRow("(no .bin/.zip in /sdcard/Download)"));
                    }
                });
            } catch (Throwable t) {
                addLog("list failed: " + t);
            }
        }).start();
    }

    private TextView fileRow(String path) {
        TextView row = new TextView(this);
        row.setText(path);
        row.setTextColor(TEXT);
        row.setTextSize(12);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setOnClickListener(v -> {
            selectedOta = path;
            // format data is mandatory when changing ROM
            new Thread(() -> {
                autoFormatData = true;
                showRomInfo(path);
            }).start();
        });
        return row;
    }

    private static final String SU_PIPE_IN  = "/data/local/tmp/su_in";
    private static final String SU_PIPE_OUT = "/data/local/tmp/su_out";
    private static final String SU_SOCK     = "/data/local/tmp/su.sock";

    private volatile boolean _daemonReady = false;
    private volatile long _daemonChecked = 0;

    /** Fast check for UI thread — returns cached result. */
    private boolean rootDaemonReady() {
        if (!new java.io.File(SU_SOCK).exists()) return false;
        long now = System.currentTimeMillis();
        if (now - _daemonChecked > 5000) pingDaemonAsync();
        return _daemonReady;
    }

    /**
     * Start the integrated Shizuku server as root so the binder OTA path is available
     * without a standalone Shizuku app.  Called after the exploit gives us root.
     * Runs async; on success calls bindService() so service/bound become non-null.
     */
    private void startShizukuServer() {
        if (safePing()) {
            addLog("Shizuku server already running");
            if (!bound) ui.post(this::bindService);
            return;
        }
        new Thread(() -> {
            try {
                // Extract rish_shizuku.dex from assets into app private dir
                java.io.File dexOut = new java.io.File(getFilesDir(), "rish_shizuku.dex");
                if (!dexOut.exists() || dexOut.length() < 1_000_000) {
                    try (java.io.InputStream is = getAssets().open("rish_shizuku.dex");
                         java.io.FileOutputStream fo = new java.io.FileOutputStream(dexOut)) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
                    }
                    dexOut.setReadable(true, false);
                }

                // Copy to /data/local/tmp/ so app_process (shell context) can read it
                String tmpDex = "/data/local/tmp/rish_shizuku.dex";
                shLocal("cp '" + dexOut.getAbsolutePath() + "' " + tmpDex
                        + " && chmod 644 " + tmpDex);

                // Launch Shizuku server via app_process (matches starter.cpp SERVER_CLASS_PATH)
                shLocal("CLASSPATH=" + tmpDex
                        + " nohup app_process"
                        + " -Djava.class.path=" + tmpDex
                        + " -Xcompiler-option --debuggable"
                        + " -Xcompiler-option --compiler-filter=speed"
                        + " /system/bin --nice-name=shizuku_server"
                        + " rikka.shizuku.server.ShizukuService"
                        + " </dev/null >/data/local/tmp/shizuku.log 2>&1 &");

                addLog("starting Shizuku server...");

                // Wait up to 30s for binder to register
                for (int i = 0; i < 30; i++) {
                    Thread.sleep(1000);
                    if (safePing()) {
                        addLog("Shizuku server ready — binder path enabled");
                        ui.post(this::showShizuku);
                        return;
                    }
                }
                addLog("[warn] Shizuku server did not register in 30s — binder path unavailable");
            } catch (Throwable t) {
                addLog("[warn] startShizukuServer: " + t);
            }
        }, "shizuku-starter").start();
    }

    /** Ping su_server socket on a background thread and update _daemonReady. */
    private void pingDaemonAsync() {
        _daemonChecked = System.currentTimeMillis();
        new Thread(() -> {
            String nonce = "__PING_" + System.currentTimeMillis() + "__";
            String out = suSock("echo " + nonce, 1500);
            boolean alive = out != null && out.contains(nonce);
            boolean prev = _daemonReady;
            _daemonReady = alive;
            _daemonChecked = System.currentTimeMillis();
            if (prev != alive) {
                if (alive) ui.post(this::refreshDetails);
                else ui.post(this::applyDetails);
            }
        }).start();
    }

    // ------------------------------------------------------------ bypass

    private static final String SU_CMD = "/data/local/tmp/su_cmd";
    private static final String SU_RES = "/data/local/tmp/su_res";

    /** Send cmd to su_server Unix socket, return output. timeoutMs=0 means default 10s. */
    private String suSock(String cmd, int timeoutMs) {
        try {
            android.net.LocalSocket sock = new android.net.LocalSocket();
            sock.connect(new android.net.LocalSocketAddress(
                    SU_SOCK, android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            sock.setSoTimeout(timeoutMs > 0 ? timeoutMs : 10000);
            java.io.OutputStream out = sock.getOutputStream();
            out.write((cmd + "\n").getBytes("UTF-8"));
            out.flush();
            sock.shutdownOutput();
            byte[] buf = sock.getInputStream().readAllBytes();
            sock.close();
            return new String(buf, "UTF-8");
        } catch (Exception e) { return null; }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Run cmd via su_server socket (after exploit, no ADB needed). */
    private String shLocal(String cmd) {
        if (rootDaemonReady()) {
            // update_engine_client needs binder; u:r:kernel:s0 can't use binder even in
            // permissive mode — switch to shell context first
            String sockCmd = cmd.contains("update_engine_client")
                    ? "runcon u:r:shell:s0 sh -c " + shellQuote(cmd)
                    : cmd;
            String r = suSock(sockCmd, 10000);
            if (r != null) return r;
        }
        // Fallback: try system su
        String[] suCandidates = { "su" };
        for (String su : suCandidates) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{ su, "-c", cmd });
                byte[] buf = p.getInputStream().readAllBytes();
                byte[] err = p.getErrorStream().readAllBytes();
                p.waitFor();
                String out = new String(buf) + new String(err);
                if (!out.contains("Permission denied") && !out.isEmpty()) return out;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Run cmd directly via /system/bin/sh without root or ADB (works for shell-level commands). */
    private String shDirect(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{ "/system/bin/sh", "-c", cmd });
            byte[] out = p.getInputStream().readAllBytes();
            byte[] err = p.getErrorStream().readAllBytes();
            p.waitFor();
            return new String(out) + new String(err);
        } catch (Exception e) {
            return "";
        }
    }

    private String sh(String cmd) {
        if (rootDaemonReady()) {
            String r = shLocal(cmd);
            if (r != null) return r;
        }
        if (service == null || !bound) return "not connected";
        try { return service.execShell(cmd); } catch (Throwable t) { return "error: " + t; }
    }

    /** Persistent su binary: minisu extracted from APK assets, mounted on a suid-allowed tmpfs. */
    private static final String SU_HOST    = "/tmp/su_host";
    private static final String SU_BIN     = "/system/bin/su";
    // MINISU_TMP is set at runtime to getFilesDir()/minisu (app-writable)
    private String minisuTmp() {
        return getFilesDir().getAbsolutePath() + "/minisu";
    }

    /**
     * Extract minisu from APK assets to app's private files dir so the root
     * script can copy it to the suid-capable mount point.
     */
    private void extractMiniSu() {
        for (String asset : new String[]{"minisu", "su_server", "su_wrapper.sh"}) {
            try {
                java.io.File out = new java.io.File(getFilesDir(), asset);
                if (!out.exists() || out.length() == 0) {
                    try (java.io.InputStream is = getAssets().open(asset);
                         java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
                    }
                    out.setExecutable(true, false);
                    out.setReadable(true, false);
                    addLog(asset + " extracted");
                }
            } catch (Exception e) {
                addLog("[warn] extract " + asset + ": " + e.getMessage());
            }
        }
    }

    /** Script run as root after exploit to mount a suid-capable tmpfs and install minisu. */
    private String installSuScript() {
        String appSrc = minisuTmp();
        // world-readable so root context can read it
        new java.io.File(appSrc).setReadable(true, false);
        // Prefer /data/local/tmp/minisu (shell-writable, no SELinux app_data restriction)
        // Fall back to app private dir if available
        String pubSrc = "/data/local/tmp/minisu";
        // After exploit: uid=0, SELinux Permissive.
        // Start a persistent root sh daemon reading commands from a named pipe.
        // App writes cmds to SU_PIPE_IN, reads output from SU_PIPE_OUT.
        String pipeIn  = "/data/local/tmp/su_in";
        String pipeOut = "/data/local/tmp/su_out";
        // Use regular files (not FIFOs) so reads never block:
        // App writes cmd to su_cmd, daemon runs it, writes result + __DONE__ to su_res, removes su_cmd
        String suCmd = "/data/local/tmp/su_cmd";
        String suRes = "/data/local/tmp/su_res";
        String suSock = "/data/local/tmp/su.sock";
        String suServer = "/data/local/tmp/su_server";
        return "setenforce 0 2>/dev/null; true\n"
            + "chmod 777 /data/local/tmp\n"
            + "touch " + pipeIn + " " + pipeOut + "\n"
            + "chmod 666 " + pipeIn + " " + pipeOut + "\n"
            // Push su_server from app private dir if not present
            + "if [ ! -f " + suServer + " ]; then\n"
            + "  cp '" + getFilesDir().getAbsolutePath() + "/su_server' " + suServer + " 2>/dev/null || true\n"
            + "fi\n"
            + "chmod 755 " + suServer + " 2>/dev/null; true\n"
            // Start socket server (daemonises itself)
            + "pkill -f su_server 2>/dev/null; true\n"
            + "rm -f " + suSock + "\n"
            + suServer + "\n"
            + "sleep 0.3\n"
            // Write su wrapper directly from embedded base64 — avoids reading from
            // app's credential-encrypted data dir in u:r:kernel:s0 context (cp would
            // silently produce null bytes there).
            + "printf '%s' '" + SU_WRAPPER_B64 + "' | base64 -d > /data/local/tmp/su\n"
            + "chmod 755 /data/local/tmp/su\n"
            + "echo SU_INSTALLED\n";
    }

    /** Cheap probe: does the system su binary give uid 0? No exploit is started. */
    private boolean suReady() {
        // Fast path: su.sock daemon is alive — that IS root access.
        if (rootDaemonReady()) return true;
        // /data/local/tmp/su wrapper connects to su.sock from shell context (Shizuku/ADB).
        // The app's Java socket is rejected by su_server's peer-cred check, but
        // routing through the shell-side nc wrapper works fine.
        String localSu = "/data/local/tmp/su";
        if (new java.io.File(localSu).exists()) {
            String r = sh("timeout 4 " + localSu + " -c id 2>/dev/null");
            if (r != null && r.contains("uid=0")) return true;
        }
        // Fallback: system su (Magisk, KernelSU binary, etc.)
        return sh("timeout 4 su -c id 2>/dev/null").contains("uid=0");
    }

    /**
     * One-shot root bring-up via timer shell mode.
     * Runs a test command as uid=0 to verify the exploit works.
     *
     * <p>Cert is planted in EVERY exit path that returns true so the
     * device is always in the correct state immediately after root is
     * established — not deferred to the install flow.
     */
    private boolean ensureRootSession() throws InterruptedException {
        if (suReady()) {
            addLog("root already established — planting cert");
            boolean ok = ensureCertsPlanted();
            if (ok) addLog("Exploit SUCCESS — cert planted");
            else     addLog("Exploit FAILED — root OK but cert plant failed");
            return ok;
        }
        String pid = timerPid();
        if (!pid.isEmpty()) {
            String tlog = sh("cat /data/local/tmp/timer.log 2>/dev/null");
            if (tlog.contains("ROOT_SHELL_OK") || tlog.contains("__ROOTCMD_END__")) {
                addLog("timer parked (pid " + pid + "), root already established — planting cert");
                boolean ok = ensureCertsPlanted();
                if (ok) addLog("Exploit SUCCESS — cert planted");
                else     addLog("Exploit FAILED — root OK but cert plant failed");
                return ok;
            }
            addLog("timer already running (pid " + pid + ") — skipping");
            return false;
        }
        addLog("exploit: uid=0 + selinux patch + cert...");
        extractMiniSu();
        String certPlant = buildCertPlantScript();
        String out = rootCmd("id\n" + PREP_SCRIPT + installSuScript() + HIDE_CERTS_SCRIPT + certPlant);
        if (out != null) addLogRaw(out.trim());
        boolean rootOk = out != null && out.contains("uid=0");
        boolean certOk = out != null && out.contains("CERTS_PLANTED");
        if (rootOk && certOk)  addLog("Exploit SUCCESS — uid=0, cert planted");
        else if (rootOk)       addLog("Exploit FAILED — uid=0 but CERTS_PLANTED missing");
        else                   addLog("Exploit FAILED — uid=0 not found in output");
        return rootOk && certOk;
    }

    /** Decode a base64 otacerts.zip and return its first file entry, or null. */
    private static byte[] firstEntryBytes(String b64) throws java.io.IOException {
        if (b64 == null) return null;
        byte[] rawZip = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(rawZip))) {
            java.util.zip.ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (!ze.isDirectory()) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] tmp = new byte[4096];
                    int r;
                    while ((r = zis.read(tmp)) >= 0) bos.write(tmp, 0, r);
                    return bos.toByteArray();
                }
            }
        }
        return null;
    }

    /**
     * Build the shell fragment that writes the correct otacerts.zip after
     * HIDE_CERTS_SCRIPT has already mounted a tmpfs over /my_engineering/etc/security.
     * Returns the script string (always ends with "echo CERTS_PLANTED").
     */
    private String buildCertPlantScript() {
        if (certMode != CERT_CUSTOM || customCertB64 == null) {
            // Stock mode — never plant anything; use the device's existing certs.
            return "echo CERTS_PLANTED\n";
        }
        String b64 = customCertB64;
        return "printf '%s' '" + b64 + "'"
                + " | base64 -d > /my_engineering/etc/security/otacerts.zip"
                + " && chmod 644 /my_engineering/etc/security/otacerts.zip"
                + " && echo CERTS_PLANTED\n";
    }

    /**
     * Ensure the correct otacerts.zip is visible to update_engine before the
     * payload is applied.
     *
     * <p>Must be called right before {@link #beginOta} in every install path,
     * including when SELinux was already Permissive and
     * {@link #ensureRootSession} was skipped — that path previously left the
     * device with the OPPO certs untouched, causing ErrorCode 26
     * (kDownloadMetadataSignatureMismatch) on custom-signed payloads.
     *
     * @return true when certs are confirmed planted (or untouched for stock mode)
     */
    private boolean ensureCertsPlanted() {
        // Stock mode (or custom mode without a loaded cert): don't touch certs.
        if (certMode != CERT_CUSTOM || customCertB64 == null) {
            addLog("[cert] stock mode: skipping cert plant");
            return true;
        }
        // Custom cert: mount tmpfs over /my_engineering/etc/security then plant.
        final String script = HIDE_CERTS_SCRIPT + buildCertPlantScript();

        // rootDaemonReady() uses an async-updated cache (_daemonReady).  If this
        // method is called before the async ping has completed, _daemonReady may
        // still be false even though su.sock is alive — causing rootCmd() to fall
        // through to the system "su" / minisu paths that have no root access.
        // Fix: do a synchronous ping directly against the socket first so the
        // cache is warm before rootCmd() checks it.
        if (!_daemonReady && new java.io.File(SU_SOCK).exists()) {
            String nonce = "__PING_CERT_" + System.currentTimeMillis() + "__";
            String pr = suSock("echo " + nonce, 3000);
            if (pr != null && pr.contains(nonce)) {
                _daemonReady = true;
                _daemonChecked = System.currentTimeMillis();
                addLog("ensureCertsPlanted: daemon ping OK (cache warmed)");
            } else {
                addLog("ensureCertsPlanted: su.sock found but ping failed — will try other paths");
            }
        }

        String out = rootCmd(script);
        if (out == null) {
            addLog("ensureCertsPlanted: rootCmd returned null — no root?");
            return false;
        }
        addLogRaw(out.trim());
        boolean planted = out.contains("CERTS_PLANTED");
        if (!planted) addLog("ensureCertsPlanted: CERTS_PLANTED not found in output");
        return planted;
    }

    /**
     * Poll device state (not just the log): success is `su -c id` returning
     * uid 0. The KernelSU module hides itself from a shell's /proc/modules,
     * so that file is not a usable signal here.
     */
    private boolean pollRoot(long timeoutMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        int lastLen = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
            String log = tReadLog();
            if (log.length() > lastLen) {
                String chunk = log.substring(lastLen);
                lastLen = log.length();
                for (String line : chunk.split("\n", -1)) {
                    String l = line.trim();
                    if (!l.isEmpty()) addLogRaw(l);
                }
            }
            if (suReady()) {
                return true;
            }
            if (log.contains("no spray sockets")
                    || log.contains("no successful plant")
                    || log.contains("child task not leaked")
                    || log.contains("fake cred write failed")
                    || log.contains("no KASLR slide")
                    || log.contains("selinux write failed")
                    || log.contains("panic_on_warn write failed")
                    || log.contains("fake uid fix failed")) {
                return false;
            }
        }
        return false;
    }

    /**
     * Run a script as uid=0. Prefers KernelSU's su (one persistent root
     * capability, no extra exploit). Falls back to the exploit's `timer shell`
     * only when no timer process is alive, so a deliberately parked fake-cred
     * page is never disturbed by a second exploit run.
     */
    private String rootCmd(String script) {
        // Fastest path: su_server already running from a previous exploit
        if (rootDaemonReady()) {
            String r = suSock("echo __ROOTCMD_BEGIN__\n" + script + "\necho __ROOTCMD_END__", 120000);
            if (r != null && r.contains("__ROOTCMD_BEGIN__")) return cleanRootOut(r);
        }
        String wrapped = "echo __ROOTCMD_BEGIN__\n" + script
                + "\necho __ROOTCMD_END__\n";
        String b64 = Base64.encodeToString(
                wrapped.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String inner = "echo " + b64 + " | base64 -d | /system/bin/sh";
        // 1. /data/local/tmp/su wrapper — routes via nc to su_server socket from
        //    shell context (Shizuku/ADB). The app's Java LocalSocket is rejected by
        //    su_server's peer-cred check (untrusted_app uid), but shell context can
        //    connect fine. This is the primary post-exploit path.
        String localSu = "/data/local/tmp/su";
        if (new java.io.File(localSu).exists()) {
            String out = sh("timeout 120 " + localSu + " -c \"" + inner + "\" 2>&1");
            if (out != null && out.contains("__ROOTCMD_BEGIN__")) {
                return cleanRootOut(out);
            }
        }
        // 2. Persistent minisu (mounted on suid-capable tmpfs after first exploit)
        String out = sh("timeout 120 " + SU_BIN + " -c \"" + inner + "\" 2>&1");
        if (out.contains("__ROOTCMD_BEGIN__")) {
            return cleanRootOut(out);
        }
        // 3. System su (if available)
        out = sh("timeout 120 su -c \"" + inner + "\" 2>&1");
        if (out.contains("__ROOTCMD_BEGIN__")) {
            return cleanRootOut(out);
        }
        // 3. Timer fake: exploit gives uid=0 child that runs our ksu_stage.sh
        //    Include cert plant directly in ksu_stage.sh so cert is written
        //    in the timer's uid=0 context — independent of su_server ping success.
        if (timerPid().isEmpty()) {
            String stageScript = installSuScript() + HIDE_CERTS_SCRIPT + buildCertPlantScript();
            String b64Stage = android.util.Base64.encodeToString(
                    stageScript.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP);
            sh("echo " + b64Stage + " | base64 -d > /data/local/tmp/ksu_stage.sh"
                    + " && chmod 755 /data/local/tmp/ksu_stage.sh");
            addLog("su unavailable — using timer fake (su_server setup + cert plant)");
            sh("rm -f /data/local/tmp/timer.log");
            try { service.runDetached("fake"); } catch (Throwable t) { addLog("spawn failed: " + t); }
            try {
                pollLog(120000,
                        new String[]{"CERTS_PLANTED"},
                        new String[]{"no spray sockets", "no successful plant",
                                "fake cred write failed", "no KASLR slide",
                                "fake uid fix failed", "child task not leaked",
                                "panic_on_warn write failed", "selinux write failed"});
            } catch (InterruptedException ignored) {}
            // Force fresh daemon check after exploit — wait up to 5s for socket
            _daemonReady = false;
            _daemonChecked = 0;
            for (int i = 0; i < 10; i++) {
                if (new java.io.File(SU_SOCK).exists()) {
                    String nonce = "__PING__";
                    String pr = suSock("echo " + nonce, 1500);
                    if (pr != null && pr.contains(nonce)) {
                        _daemonReady = true;
                        _daemonChecked = System.currentTimeMillis();
                        break;
                    }
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored2) {}
            }
            if (_daemonReady) {
                ui.post(this::refreshDetails);
                String r = suSock("echo __ROOTCMD_BEGIN__\n" + script + "\necho __ROOTCMD_END__", 120000);
                if (r != null && r.contains("__ROOTCMD_BEGIN__")) return cleanRootOut(r);
            }
            addLog("timer fake finished; su_server not ready yet");
            return cleanRootOut(tReadLog());
        }
        return out;
    }

    private String cleanRootOut(String out) {
        return out.replace("__ROOTCMD_BEGIN__\n", "")
                .replace("__ROOTCMD_END__", "")
                .trim();
    }

    /** True when update_engine can no longer see otacerts.zip. */
    private boolean otacertsGone() {
        // -e follows symlinks: exits 1 (false) when file missing OR symlink is dangling
        return sh("test -e /system/etc/security/otacerts.zip; echo rc=$?")
                .contains("rc=1");
    }

    /** Permissive + ro.secure=0. Does not touch otacerts. */
    private static final String PREP_SCRIPT =
            "export PATH=/data/local/tmp:$PATH\n"
            + "setenforce 0 2>&1 || echo '[warn] setenforce failed'\n"
            + "F=''\n"
            + "for f in /dev/__properties__/*; do\n"
            + "  grep -aq ro.secure \"$f\" 2>/dev/null && { F=\"$f\"; break; }\n"
            + "done\n"
            + "if [ -n \"$F\" ]; then\n"
            + "  chmod 644 \"$F\" 2>/dev/null\n"
            + "  O=$(grep -abo -m1 ro.secure \"$F\" | head -1 | cut -d: -f1)\n"
            + "  [ -n \"$O\" ] && printf 0 | dd of=\"$F\" bs=1 seek=$((O-92))"
            + " conv=notrunc 2>/dev/null\n"
            + "fi\n"
            + "echo \"RO_SECURE=$(getprop ro.secure) ENF=$(getenforce)\"\n";

    /** Restore the OEM certs hidden by timer's ksu_stage.sh tmpfs mount. */
    private static final String RESTORE_CERTS_SCRIPT =
            "for i in 1 2 3; do\n"
            + "  umount /my_engineering/etc/security 2>/dev/null\n"
            + "  umount -l /my_engineering/etc/security 2>/dev/null\n"
            + "done\n"
            + "if [ -s /system/etc/security/otacerts.zip ]; then\n"
            + "  echo OTACERTS_OK\n"
            + "else echo OTACERTS_MISSING; fi\n";

    /**
     * Hide the OEM certs and expose a writable dir so the app can plant its
     * own otacerts.zip when a custom certificate is selected.
     */
    private static final String HIDE_CERTS_SCRIPT =
            "mkdir -p /dev/__sec_bak\n"
            + "rm -rf /dev/__sec_bak/*\n"
            + "cp -a /my_engineering/etc/security/. /dev/__sec_bak/ 2>/dev/null\n"
            + "rm -f /dev/__sec_bak/otacerts.zip /dev/__sec_bak/specialcerts.zip"
            + " /dev/__sec_bak/specialcerts_256.zip 2>/dev/null\n"
            + "mount -t tmpfs tmpfs /my_engineering/etc/security 2>/dev/null && "
            + "cp -a /dev/__sec_bak/. /my_engineering/etc/security/ 2>/dev/null && "
            + "echo OTACERTS_MOUNT_OK || echo OTACERTS_MOUNT_SKIP\n";

    /**
     * Rewrite the OPPO compatibility headers so their checks pass:
     *   - oplus_update_engine_verify_disable=1
     *   - oplus_separate_soft = device ro.separate.soft
     *   - ota_target_version  = package string with the device's version code
     *     (the 3rd underscore field of the value, not of the whole line —
     *      the key name itself contains underscores).
     */
    private static final String HEADER_SCRIPT =
            "cd /data/local/tmp || exit 1\n"
            + "V=$(getprop ro.build.version.ota)\n"
            + "S=$(getprop ro.separate.soft)\n"
            + "NEW=$(echo \"$V\" | cut -d_ -f3)\n"
            + "OLD=$(grep '^ota_target_version=' payload_properties.txt"
            + " | cut -d= -f2-)\n"
            + "P1=$(echo \"$OLD\" | cut -d_ -f1)\n"
            + "P2=$(echo \"$OLD\" | cut -d_ -f2)\n"
            + "P4=$(echo \"$OLD\" | cut -d_ -f4-)\n"
            + "if [ -n \"$NEW\" ] && [ -n \"$P2\" ] && [ -n \"$P4\" ]; then\n"
            + "  LINE=\"ota_target_version=${P1}_${P2}_${NEW}_${P4}\"\n"
            + "  sed -i \"s|^ota_target_version=.*|$LINE|\" payload_properties.txt\n"
            + "fi\n"
            + "sed -i \"s|^oplus_update_engine_verify_disable=.*|"
            + "oplus_update_engine_verify_disable=1|\" payload_properties.txt\n"
            + "sed -i \"s|^oplus_separate_soft=.*|"
            + "oplus_separate_soft=$S|\" payload_properties.txt\n"
            + "grep -q '^oplus_update_engine_verify_disable='"
            + " payload_properties.txt ||"
            + " echo 'oplus_update_engine_verify_disable=1'"
            + " >> payload_properties.txt\n"
            + "grep -q '^oplus_separate_soft=' payload_properties.txt ||"
            + " echo \"oplus_separate_soft=$S\" >> payload_properties.txt\n"
            // OPPO separates slot switch from payload apply; ensure standard fallback also commits slot
            + "grep -q '^SWITCH_SLOT_ON_REBOOT=' payload_properties.txt ||"
            + " echo 'SWITCH_SLOT_ON_REBOOT=1' >> payload_properties.txt\n"
            // Mandatory format data: update_engine schedules the powerwash/BCB
            // wipe itself and writes the forward-merge indicator, so recovery
            // merges snapshots then wipes data without reverting the slot.
            + "sed -i \"s|^POWERWASH=.*|POWERWASH=1|\" payload_properties.txt\n"
            + "grep -q '^POWERWASH=' payload_properties.txt ||"
            + " echo 'POWERWASH=1' >> payload_properties.txt\n"
            + "cat payload_properties.txt\n";

    private void bypassSelected() {
        if (forceDowngradeMode) {
            showThemedDialog("Downgrade OTA",
                    "This will permanently wipe all user data after reboot. Continue?",
                    "Wipe & Install", () -> {
                        if (!checkSelection()) return;
                        startSelectedInstall();
                    }, "Cancel", null);
            return;
        }
        if (!checkSelection()) return;
        startSelectedInstall();
    }

    /** Common tail of both install paths. */
    private void startSelectedInstall() {
        if (!transportReady()) { promptConnect(); return; }
        if (tailRunning) { offerResetAndInstall(); return; }
        new Thread(this::runBypass).start();
    }

    /** Called when INSTALL is pressed but tailRunning=true — offer cancel-and-retry. */
    private void offerResetAndInstall() {
        showThemedDialog("OTA in progress",
                "An install is already running.\nCancel it and start a new install?",
                "Cancel & Restart", () -> {
                    new Thread(() -> {
                        sh("update_engine_client --cancel 2>/dev/null");
                        onOtaFailure(otaEpoch, "restarted by user");
                        ui.post(() -> new Thread(this::runBypass).start());
                    }).start();
                }, "Keep running", null);
    }

    /**
     * Read payload.bin's data offset and size from the zip's local file header.
     * The first entry in OTA zips is always payload.bin (stored, not compressed).
     * Returns {offset, compressedSize} or null on failure.
     */
    private long[] readPayloadInfo(String zipPath) {
        // Try direct path first; fall back to URI-based FD for scoped-storage paths
        try {
            new java.io.RandomAccessFile(zipPath, "r").close();
        } catch (Throwable t) {
            if (selectedOtaUri != null) {
                return readPayloadInfoFromUri(selectedOtaUri);
            }
            return null;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(zipPath, "r")) {
            byte[] hdr = new byte[30];
            raf.readFully(hdr);
            // Check PK local file signature
            if (hdr[0] != 'P' || hdr[1] != 'K' || hdr[2] != 3 || hdr[3] != 4) return null;
            long usize32 = ((hdr[22]&0xFFL))|((hdr[23]&0xFFL)<<8)|((hdr[24]&0xFFL)<<16)|((hdr[25]&0xFFL)<<24);
            long compSize = ((hdr[18]&0xFFL))|((hdr[19]&0xFFL)<<8)|((hdr[20]&0xFFL)<<16)|((hdr[21]&0xFFL)<<24);
            int fnLen = (hdr[26]&0xFF)|((hdr[27]&0xFF)<<8);
            int exLen = (hdr[28]&0xFF)|((hdr[29]&0xFF)<<8);
            byte[] fn = new byte[fnLen];
            raf.readFully(fn);
            if (!"payload.bin".equals(new String(fn, "UTF-8"))) return null;

            // Payload > 4 GiB: the 32-bit size fields are 0xFFFFFFFF and the
            // real sizes live in the ZIP64 extra field (id 0x0001), in the
            // order original(uncompressed) size then compressed size.
            if (compSize == 0xFFFFFFFFL && exLen > 0) {
                byte[] extra = new byte[exLen];
                raf.readFully(extra);
                int i = 0;
                while (i + 4 <= extra.length) {
                    int id = (extra[i]&0xFF) | ((extra[i+1]&0xFF)<<8);
                    int sz = (extra[i+2]&0xFF) | ((extra[i+3]&0xFF)<<8);
                    int p = i + 4;
                    if (p + sz > extra.length) break;
                    if (id == 0x0001) {
                        if (usize32 == 0xFFFFFFFFL) p += 8; // skip uncompressed size
                        if (p + 8 <= i + 4 + sz) {
                            long v = 0;
                            for (int b = 7; b >= 0; b--) v = (v << 8) | (extra[p+b] & 0xFFL);
                            compSize = v;
                        }
                        break;
                    }
                    i = p + sz;
                }
            }
            long offset = 30L + fnLen + exLen;
            return new long[]{offset, compSize};
        } catch (Throwable t) {
            return null;
        }
    }

    private void runBypass() {
        try {
            // Guard early so a second Install tap cannot race past startSelectedInstall()'s
            // tailRunning check while this thread is still setting up (which can take seconds).
            tailRunning = true;

            addStatus("Checking tools...");
            ensureTools();

            addStatus("Reading OTA...");
            long[] payInfo = null;
            String propsTxt = null;
            if (selectedOta.toLowerCase().endsWith(".zip")) {
                payInfo = readPayloadInfo(selectedOta);
                propsTxt = readZipEntry(selectedOta, selectedOtaUri, "payload_properties.txt");
            }
            if (payInfo == null) {
                tailRunning = false;
                showAlert("Invalid file", "Cannot read payload offset from zip.", AlertType.ERROR);
                return;
            }
            addLog("payload offset=" + payInfo[0] + " size=" + payInfo[1]);
            if (propsTxt == null || propsTxt.isEmpty()) {
                addLog("payload_properties.txt missing or empty");
                tailRunning = false;
                showAlert("Invalid file", "Missing payload_properties.txt in zip.", AlertType.ERROR);
                return;
            }

            long offset = payInfo[0];
            long size   = payInfo[1];

            // Respect user's toggle selection — do NOT auto-override here
            // (auto-detect already ran when the zip was loaded and set the toggle then)
            boolean needDowngrade = forceDowngradeMode;
            addLog("mode: " + (needDowngrade ? "DOWNGRADE" : "UPDATE"));
            boolean hasPowerwash = propsTxt.contains("POWERWASH=1");
            String deviceSpl = needDowngrade
                    ? sh("getprop ro.build.version.security_patch 2>/dev/null").trim() : "";
            String romSpl = "";
            if (needDowngrade) {
                for (String line : propsTxt.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("security_patch=")) { romSpl = t.substring("security_patch=".length()).trim(); break; }
                }
            }
            boolean splDowngrade = !deviceSpl.isEmpty() && !romSpl.isEmpty()
                    && romSpl.compareTo(deviceSpl) < 0;
            if (needDowngrade && splDowngrade)
                addLog("SPL downgrade: ROM=" + romSpl + " < device=" + deviceSpl);

            // User selection is final: DOWNGRADE adds flags, UPDATE strips them regardless
            isPartialOta = !needDowngrade;

            StringBuilder sb = new StringBuilder();
            for (String line : propsTxt.split("\n")) {
                line = line.trim();
                if (line.startsWith("POWERWASH=")) continue;
                if (line.startsWith("ota-downgrade=")) continue;
                if (line.startsWith("SPL_DOWNGRADE=")) continue;
                if (!line.isEmpty()) sb.append(line).append("\\n");
            }
            if (!propsTxt.contains("oplus_update_engine_verify_disable"))
                sb.append("oplus_update_engine_verify_disable=1\\n");
            if (!propsTxt.contains("SKIP_HASH_CHECKS"))
                sb.append("SKIP_HASH_CHECKS=1\\n");
            if (!propsTxt.contains("SWITCH_SLOT_ON_REBOOT"))
                sb.append("SWITCH_SLOT_ON_REBOOT=1\\n");
            if (needDowngrade) {
                sb.append("POWERWASH=1\\n");
                sb.append("ota-downgrade=yes\\n");
                sb.append("SPL_DOWNGRADE=1\\n");
            }
            String propsEsc = sb.toString().replaceAll("\\\\n$", "");
            // String[] form for the binder path (Shizuku → update_engine_stable)
            String[] headersArr = java.util.Arrays.stream(propsEsc.replace("\\n", "\n").split("\n"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
            addLog("headers to update_engine:\n" + propsEsc.replace("\\n", "\n").trim());

            // Ensure SELinux=Permissive before calling update_engine_client so that
            // the slot-switch binder call (SWITCH_SLOT_ON_REBOOT) is not blocked.
            // If still Enforcing at this point the slot switch silently fails and
            // the bootloader reverts to the old slot on next reboot.
            String seState = sh("getenforce 2>/dev/null").trim();
            if (!"Permissive".equalsIgnoreCase(seState)) {
                addStatus("SELinux Enforcing — patching before install...");
                addLog("preparing root for install...");
                android.app.ProgressDialog certDlg = new android.app.ProgressDialog(this);
                certDlg.setMessage("Preparing install\nPlease wait...");
                certDlg.setCancelable(false);
                certDlg.setIndeterminate(true);
                ui.post(certDlg::show);
                boolean patched;
                try {
                    patched = ensureRootSession();
                } finally {
                    ui.post(certDlg::dismiss);
                }
                seState = sh("getenforce 2>/dev/null").trim();
                if (!"Permissive".equalsIgnoreCase(seState)) {
                    tailRunning = false;
                    showAlert("SELinux still Enforcing",
                            "SELinux could not be set to Permissive — OTA may revert on reboot.\nRun Exploit first, then retry Install.",
                            AlertType.WARN);
                    return;
                }
            }

            // Verify engine is IDLE before submitting — use binder (reliable, no shell race).
            int engSt = -1;
            try { engSt = service.getEngineStatus(); } catch (Throwable ignored) {}
            addLog("engine status before install: " + engSt);
            if (engSt != 0) {  // 0 = IDLE
                addStatus("Cancelling previous session...");
                try {
                    service.cancelUpdate();
                } catch (Throwable t2) {
                    sh("update_engine_client --cancel 2>/dev/null");
                }
                // Poll until IDLE — max 5 s (10 × 500 ms)
                for (int i = 0; i < 10; i++) {
                    Thread.sleep(500);
                    try { engSt = service.getEngineStatus(); } catch (Throwable ignored) {}
                    if (engSt == 0) break;
                }
                if (engSt != 0) {
                    // Last resort: force resetStatus
                    try { service.resetStatus(); } catch (Throwable ignored) {}
                    Thread.sleep(500);
                    try { engSt = service.getEngineStatus(); } catch (Throwable ignored) {}
                }
                addLog("engine after reset: " + engSt);
                if (engSt != 0 && engSt != -1) {
                    tailRunning = false;
                    showAlert("Engine busy",
                            "update_engine is still running (status " + engSt + "). "
                            + "Wait a moment and try again.", AlertType.ERROR);
                    return;
                }
            }

            beginOta(isPartialOta);

            addStatus("Sending payload...");

            // ── Binder path (Shizuku → update_engine_stable) ──────────────────────────
            // Direct IUpdateEngineStable.applyPayloadFd() call.
            // Callbacks (onStatus / onComplete) fire directly into this process — no file,
            // no polling, no cleanup step needed.
            if (startUpdateBinder(selectedOta, offset, size, headersArr)) {
                addStatus("Installing (binder)...");
                // tailRunning stays true; onComplete callback calls onOtaSuccess/Failure
                return;
            }

            // ── Shell fallback (Shizuku shell transport) ──────────────────────────────
            addLog("binder path unavailable — falling back to update_engine_client shell");
            sh("printf '" + propsEsc + "' > /data/local/tmp/_ue_props.txt");
            sh("setprop oplus_update_engine_verify_disable 1");
            sh("setprop persist.oplus.update_engine_verify_disable 1");

            String ueCmd = "runcon u:r:shell:s0 update_engine_client --update"
                    + " --payload='file://" + selectedOta + "'"
                    + " --offset=" + offset
                    + " --size=" + size
                    + " --headers=\"$(cat /data/local/tmp/_ue_props.txt)\""
                    + (ueSupportsSkipHash() ? " --skip_hash_checks=true" : "")
                    + " --follow 2>&1";

            // Shizuku shell: nohup launch then poll apply.log
            sh("rm -f /data/local/tmp/apply.log /data/local/tmp/apply.rc");
            String script =
                    "#!/system/bin/sh\n"
                    + "printf '" + propsEsc + "' > /data/local/tmp/_ue_props.txt\n"
                    + "setprop oplus_update_engine_verify_disable 1\n"
                    + "setprop persist.oplus.update_engine_verify_disable 1\n"
                    + ueCmd.replace(" 2>&1", "")
                    + " > /data/local/tmp/apply.log 2>&1\n"
                    + "echo $? > /data/local/tmp/apply.rc\n";
            String b64 = android.util.Base64.encodeToString(
                    script.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP);
            sh("echo " + b64 + " | base64 -d > /data/local/tmp/_ue_launch.sh && chmod 755 /data/local/tmp/_ue_launch.sh");
            sh("nohup /data/local/tmp/_ue_launch.sh </dev/null >/dev/null 2>&1 &");

            Thread.sleep(2000);
            String earlyLog = sh("cat /data/local/tmp/apply.log 2>/dev/null").trim();
            if (!earlyLog.isEmpty()) {
                String earlyErr = firstOtaErrorLine(earlyLog);
                if (!earlyErr.isEmpty()) {
                    onOtaFailure(otaEpoch, earlyErr);
                    return;
                }
                addStatus("Installing...");
            }
            tailApplyLog();

        } catch (Throwable t) {
            onOtaFailure(otaEpoch,
                    "Failed to start update_engine: "
                            + (t.getMessage() != null ? t.getMessage() : t.toString()));
        }
    }

    /** Add a clean status line (no timestamp prefix clutter for status messages). */
    private void addStatus(String text) {
        addLog("> " + text);
    }

    /**
     * Start a new OTA run: invalidates any previous log tail/callback, puts the
     * UI in the "installing" state and clears the one-shot result guard.
     */
    private void beginOta(boolean partial) {
        synchronized (otaLock) {
            otaResultClaimed = false;
        }
        otaEpoch++;
        isPartialOta = partial;
        getPreferences(MODE_PRIVATE).edit().putBoolean("isPartialOta", partial).apply();
        ui.post(() -> getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        ui.post(() -> {
            if (installBtnBar != null) installBtnBar.setProgress(0);
            if (installBtnLabel != null) installBtnLabel.setText("STARTING...");
            if (btnCancelOta != null) btnCancelOta.setVisibility(View.VISIBLE);
            if (installBtnFrame != null) {
                GradientDrawable d = new GradientDrawable();
                d.setColor(blendColor(PRIMARY, 0x55000000));
                d.setCornerRadius(dp(14));
                installBtnFrame.setBackground(d);
                installBtnFrame.setClipToOutline(true);
            }
        });
    }

    private void cancelOta() {
        showThemedDialog("Cancel update",
                "Stop the ongoing OTA. The device stays on the current slot.",
                "Cancel update", () -> {
                    // Force-reset state immediately — bypass claimOtaResult so the UI
                    // always resets even if onComplete(48) already claimed the result.
                    otaCancelledByUser = true;
                    tailRunning = false;
                    otaEpoch++;   // invalidate any in-flight callbacks
                    addLog("OTA cancelled by user");
                    resetOtaState();
                    // Fire-and-forget: tell update_engine to stop
                    new Thread(() -> {
                        try { service.cancelUpdate(); } catch (Throwable ignored) {}
                    }, "ota-cancel").start();
                }, "Keep installing", null);
    }

    /** Exactly one of success/failure may be handled for a given run. */
    private boolean claimOtaResult(int epoch) {
        synchronized (otaLock) {
            if (epoch != otaEpoch || otaResultClaimed) return false;
            otaResultClaimed = true;
            return true;
        }
    }

    private void onOtaSuccess(int epoch) {
        if (!claimOtaResult(epoch)) return;
        tailRunning = false;
        otaEpoch++; // stop the log tail and any stale binder callback
        ui.post(() -> getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        ui.post(() -> {
            if (installBtnBar   != null) installBtnBar.setProgress(1000);
            if (installBtnLabel != null) installBtnLabel.setText("INSTALLING: 100%");
            if (btnCancelOta    != null) btnCancelOta.setVisibility(View.GONE);
            addLog("OTA installed successfully");
            if (!isPartialOta)
                addLog("data wipe required — update_engine scheduled powerwash");
            if (btnInstall != null) {
                btnInstall.setText(isPartialOta ? "REBOOT" : "REBOOT + WIPE DATA");
                btnInstall.setOnClickListener(v ->
                        new Thread(MainActivity.this::rebootAfterUpdate).start());
            }
        });
    }

    /**
     * Handle an OTA failure: show the reason in a dialog and restore the UI so
     * the user can select the file again and retry without restarting the app.
     */
    private void onOtaFailure(int epoch, String detail) {
        if (!claimOtaResult(epoch)) return;
        tailRunning = false;
        otaEpoch++;
        ui.post(() -> getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        String reason = detail == null ? "" : detail.trim();
        boolean userCancelled = reason.equals("cancelled by user")
                || otaCancelledByUser
                || reason.contains("error 48")    // kUserCancelled on OPPO/Realme
                || reason.contains("error 20");   // kUserCancelled alternate code
        otaCancelledByUser = false;
        if (reason.isEmpty()) reason = "update_engine returned an unknown error.";
        reason = shorten(reason, 900);
        final String msg = reason;
        if (userCancelled) {
            addLog("OTA cancelled by user");
            ui.post(() -> { resetOtaState(); });
        } else {
            addLog("OTA failed: " + msg);
            ui.post(() -> {
                resetOtaState();
                showAlert("OTA failed", msg + "\n\nState reset. Select the file and tap Install to retry.", AlertType.ERROR);
            });
        }
    }

    /** Restore the update tab to its idle state (UI thread). */
    private void resetOtaState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(this::resetOtaState);
            return;
        }
        tailRunning = false;
        isPartialOta = false;
        getPreferences(MODE_PRIVATE).edit().remove("isPartialOta").apply();
        synchronized (otaLock) {
            otaResultClaimed = true;
        }
        if (installBtnBar != null) installBtnBar.setProgress(0);
        if (installBtnLabel != null) installBtnLabel.setText("Install");
        if (btnCancelOta != null) btnCancelOta.setVisibility(View.GONE);
        if (installBtnFrame != null) {
            boolean connected = transportReady();
            if (installBg != null) {
                installBg.setColor(connected ? PRIMARY : LINE);
            } else {
                installBg = new GradientDrawable();
                installBg.setColor(connected ? PRIMARY : LINE);
                installBg.setCornerRadius(dp(14));
                installBtnFrame.setBackground(installBg);
            }
            installBtnFrame.setClipToOutline(true);
            installBtnFrame.setClickable(connected);
        }
        if (btnInstall != null) {
            boolean connected = transportReady();
            btnInstall.setText("Install");
            btnInstall.setTextColor(connected ? BG : MUTED);
            btnInstall.setOnClickListener(connected ? v -> bypassSelected() : null);
        }
        // Drop the stable binder callback so a late completion cannot resurrect
        // the failed run.
        if (ueEngine != null && ueCb != null) {
            try { ueEngine.unbind(ueCb); } catch (Throwable ignored) {}
        }
        ueEngine = null;
        ueCb = null;
        // Remove the run markers so the next attempt starts from a clean slate.
        new Thread(() ->
                sh("rm -f /data/local/tmp/apply.log /data/local/tmp/apply.rc")).start();
    }

    private static String shorten(String text, int max) {
        if (text == null) return "";
        text = text.trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** update_engine terminal success marker (checked before the error patterns).
     *  Do NOT match a bare "ErrorCode::kSuccess": update_engine logs that for
     *  every finished action (DownloadAction, InstallPlanAction, ...), long
     *  before the whole update is done. */
    private static boolean isOtaSuccessMarker(String line) {
        return line.contains("UPDATED_NEED_REBOOT")
                || line.contains("Update successfully applied")
                || line.contains("onPayloadApplicationComplete(ErrorCode::kSuccess");
    }

    /** update_engine / update_engine_client failure line. */
    private static boolean isOtaErrorMarker(String line) {
        if (isOtaSuccessMarker(line)) return false;
        if (line.contains("ErrorCode::kSuccess")) return false; // per-action success
        if (line.contains("Failed to OpenArchive")) return false; // harmless post-update check
        if (line.contains("Read merge_stats file failed")) return false; // non-fatal: no prior merge stats
        // A second client hitting this means the first client's download is already running — not a failure.
        if (line.contains("Already processing an update")) return false;
        return line.contains("ERROR:")
                || line.contains("ErrorCode::")
                || line.contains("errorCode=")
                || line.contains("error_code=")
                || line.contains("Unable to apply payload")
                || line.contains("Payload verification failed")
                || line.contains("applyPayload failed");
    }

    private static String firstOtaErrorLine(String log) {
        for (String line : log.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && isOtaErrorMarker(t)) return t;
        }
        return "";
    }

    /** Human-readable label for update_engine UpdateStatus codes. */
    private static String ueStatusLabel(int status) {
        switch (status) {
            case 1:  return "Checking";        // CHECKING_FOR_UPDATE
            case 2:  return "Preparing";       // UPDATE_AVAILABLE
            case 3:  return "Downloading";     // DOWNLOADING
            case 4:  return "Verifying";       // VERIFYING_UPDATE
            case 5:  return "Finalizing";      // FINALIZING
            case 6:  return "Done";            // UPDATED_NEED_REBOOT
            case 7:  return "Error";           // REPORTING_ERROR_EVENT
            case 8:  return "Rolling back";    // ATTEMPTING_ROLLBACK
            case 9:  return "Disabled";        // DISABLED
            case 10: return "Waiting";         // NEED_PERMISSION_TO_UPDATE
            case 11: return "Cleaning up";     // CLEANUP_PREVIOUS_UPDATE
            default: return "Installing";
        }
    }

    /** Friendly hint for the numeric update_engine ErrorCode. */
    private static String otaErrorHint(int code) {
        switch (code) {
            case 1:  return "\ngeneral update_engine error";
            case 2:  return "\nfilesystem check failed";
            case 3:  return "\npayload download error";
            case 4:  return "\npayload timestamp older than current build";
            case 5:  return "\nupdated but new slot not active";
            case 8:  return "\npayload verification failed";
            case 9:  return "\ninvalid payload public key";
            case 10: return "\npayload signature mismatch";
            case 11: return "\npayload hash mismatch";
            case 12: return "\nmetadata hash mismatch";
            case 13: return "\ninvalid metadata signature (wrong cert?)";
            case 14: return "\npayload size mismatch";
            case 15: return "\npayload download failed";
            case 16: return "\npartition write error";
            case 17: return "\ninvalid payload operation";
            case 20: return "\ncancelled by update_engine";
            case 48: return "\ncancelled by update_engine";
            case 26: return "\npayload does not match device";
            default: return "";
        }
    }

    private volatile boolean tailRunning = false;
    private volatile boolean autoFormatData = true;
    private volatile boolean isDowngrade = false;
    private volatile boolean otaCancelledByUser = false;


    // OTA run state: otaEpoch invalidates stale log tails / binder callbacks,
    // otaResultClaimed makes sure success/failure is handled exactly once.
    private volatile int otaEpoch = 0;
    private volatile boolean otaResultClaimed = false;
    private final Object otaLock = new Object();

    // Direct update_engine_stable binder (available after SELinux=Permissive)
    private IUpdateEngineStable ueEngine;
    private IUpdateEngineStableCallback ueCb;
    private ParcelFileDescriptor activePfd;

    /** Bind to update_engine_stable directly via reflection (requires SELinux=Permissive). */
    private boolean bindEngine() {
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "update_engine_stable");
            if (b == null) { addLog("update_engine service not found"); return false; }
            IUpdateEngineStable engine = IUpdateEngineStable.Stub.asInterface(b);
            // drop any callback left over from a previous run so progress
            // isn't reported twice
            if (ueEngine != null && ueCb != null) {
                try { ueEngine.unbind(ueCb); } catch (Throwable ignored) {}
            }
            ueEngine = engine;
            final int epoch = otaEpoch;
            ueCb = new IUpdateEngineStableCallback.Stub() {
                @Override
                public void onStatusUpdate(int status, float fraction) {
                    if (epoch != otaEpoch) return;
                    if (status == 6) { onOtaSuccess(epoch); return; }
                    int pct = Math.round(fraction * 100);
                    String stage = status == 5 ? "Finalizing" : status == 4 ? "Verifying" : status == 3 ? "Downloading" : "Installing";
                    ui.post(() -> {
                        if (installBtnBar != null) installBtnBar.setProgress(pct * 10);
                        if (installBtnLabel != null) installBtnLabel.setText(stage.toUpperCase() + ": " + pct + "%");
                    });
                }
                @Override
                public void onPayloadApplicationComplete(int errorCode) {
                    // Safe to close fd now that engine is done
                    if (activePfd != null) {
                        try { activePfd.close(); } catch (Exception ignored) {}
                        activePfd = null;
                    }
                    if (errorCode == 0) {
                        onOtaSuccess(epoch);
                    } else {
                        onOtaFailure(epoch, "update_engine error code " + errorCode
                                + otaErrorHint(errorCode));
                    }
                }
            };
            ueEngine.bind(ueCb);
            addLog("bound to update_engine_stable");
            return true;
        } catch (Throwable t) {
            addLog("bind failed: " + t);
            ueEngine = null;
            return false;
        }
    }

    /**
     * Submit payload to update_engine via the Shizuku UserService binder.
     * Callbacks fire directly into this process — no file, no polling.
     * Returns true when the payload was submitted successfully.
     */
    private boolean startUpdateBinder(String payloadPath, long payloadOffset, long payloadSize,
                                       String[] headers) {
        if (service == null || !bound) return false;
        final int epoch = otaEpoch;
        try {
            IUpdateCallback cb = new IUpdateCallback.Stub() {
                @Override
                public void onStatus(int status, int pct) {
                    if (epoch != otaEpoch) return;
                    if (status == 6) { onOtaSuccess(epoch); return; }
                    if (status <= 0) return;
                    String stage = ueStatusLabel(status);
                    ui.post(() -> {
                        if (installBtnBar  != null) installBtnBar.setProgress(pct * 10);
                        if (installBtnLabel != null) installBtnLabel.setText(stage.toUpperCase() + ": " + pct + "%");
                        if (pct % 10 == 0) addLog(stage + ": " + pct + "%");
                    });
                }
                @Override
                public void onComplete(int errorCode) {
                    if (epoch != otaEpoch) return;
                    if (errorCode == 0) onOtaSuccess(epoch);
                    else onOtaFailure(epoch, "update_engine error " + errorCode + otaErrorHint(errorCode));
                }
            };
            String err = service.startUpdate(payloadPath, payloadOffset, payloadSize, headers, cb);
            if (!err.isEmpty()) { addLog("startUpdate: " + err); return false; }
            addLog("payload submitted via binder (Shizuku → update_engine_stable)");
            return true;
        } catch (Throwable t) {
            addLog("startUpdateBinder: " + t);
            return false;
        }
    }

    /** Apply payload directly via the bound engine (no shell, no bridge). */
    private boolean applyDirect(String payloadPath, String[] headers) {
        if (ueEngine == null && !bindEngine()) return false;
        try {
            if (activePfd != null) {
                try { activePfd.close(); } catch (Exception ignored) {}
                activePfd = null;
            }
            activePfd = ParcelFileDescriptor.open(
                    new File(payloadPath), ParcelFileDescriptor.MODE_READ_ONLY);
            long offset = 0, size = 0;
            for (String h : headers) {
                if (h.startsWith("FILE_SIZE=")) {
                    try { size = Long.parseLong(h.substring(10).trim()); } catch (Exception ignored) {}
                }
            }
            ueEngine.applyPayloadFd(activePfd, offset, size, headers);
            // Do NOT close activePfd here — engine reads async; closed in onPayloadApplicationComplete
            addLog("applyPayloadFd sent");
            return true;
        } catch (Throwable t) {
            addLog("applyDirect failed: " + t);
            ueEngine = null;
            return false;
        }
    }

    /**
     * Parse a raw update_engine logcat line into a short human-readable message.
     * Returns null to skip the line entirely.
     */
    private String formatUeLine(String raw, java.util.regex.Pattern pPct) {
        // Progress: "Completed 146/6196 operations (2%), ... overall progress 2%"
        java.util.regex.Matcher m = pPct.matcher(raw);
        if (m.find() && raw.contains("Completed")) {
            java.util.regex.Matcher ops = java.util.regex.Pattern
                    .compile("Completed (\\d+)/(\\d+) operations").matcher(raw);
            if (ops.find()) {
                return ops.group(1) + "/" + ops.group(2) + " ops " + m.group(1) + "%";
            }
        }
        if (raw.contains("Resuming after") && raw.contains("operations")) {
            java.util.regex.Matcher ops = java.util.regex.Pattern
                    .compile("Resuming after (\\d+)/(\\d+) operations \\((\\d+)%\\)").matcher(raw);
            if (ops.find())
                return "resuming from " + ops.group(1) + "/" + ops.group(2)
                        + " ops (" + ops.group(3) + "%)";
        }
        // Partition start: "VABC Partition Writer for `system`"
        java.util.regex.Matcher part = java.util.regex.Pattern
                .compile("Partition Writer for `([^`]+)`").matcher(raw);
        if (part.find()) return "partition: " + part.group(1);

        // Partition resume: "Resuming update on partition `vendor` op index 296"
        java.util.regex.Matcher resume = java.util.regex.Pattern
                .compile("Resuming update on partition `([^`]+)` op index (\\d+)").matcher(raw);
        if (resume.find()) return "resuming partition " + resume.group(1) + " op#" + resume.group(2);

        // onStatusUpdate from update_engine_client: extract fraction progress
        if (raw.contains("onStatusUpdate(")) {
            java.util.regex.Matcher su = java.util.regex.Pattern
                    .compile("onStatusUpdate\\(([^,]+),\\s*([0-9.e+\\-]+)\\)").matcher(raw);
            String lastStatus = null; String lastFrac = null;
            while (su.find()) { lastStatus = su.group(1).trim(); lastFrac = su.group(2); }
            if (lastStatus != null) {
                try {
                    double frac = Double.parseDouble(lastFrac);
                    int pct = (int) Math.round(frac * 100);
                    if (lastStatus.contains("DOWNLOADING"))
                        return null;
                    if (lastStatus.contains("FINALIZING"))
                        return null;
                } catch (Exception ignored) {}
                if (lastStatus.contains("UPDATED_NEED_REBOOT")) return "install done — reboot required";
                if (lastStatus.contains("IDLE") || lastStatus.contains("CLEANUP")) return null;
            }
        }

        // Done / reboot
        if (raw.contains("UPDATED_NEED_REBOOT") || raw.contains("Update successfully applied")
                || raw.contains("onPayloadApplicationComplete(ErrorCode::kSuccess"))
            return "install done — reboot required";

        // Errors (non-success)
        if (raw.contains("ERROR:") || raw.contains("error_code=") || raw.contains("ErrorCode")) {
            if (raw.contains("kSuccess")) return null; // already handled above
            if (raw.contains("Failed to OpenArchive")) return null; // harmless post-OTA check
            if (raw.contains("Read merge_stats file failed")) return null; // non-fatal: no prior merge stats
            int idx = raw.indexOf("ERROR:");
            if (idx < 0) idx = raw.indexOf("ErrorCode");
            if (idx < 0) idx = raw.indexOf("error_code");
            return "error: " + raw.substring(idx);
        }

        // PreparePartitions done
        if (raw.contains("PreparePartitionsForUpdate done"))
            return "PreparePartitions done";

        // Starting to apply
        if (raw.contains("Starting to apply update payload"))
            return "applying payload...";

        // Snapshot created
        if (raw.contains("Successfully created snapshot for")) {
            java.util.regex.Matcher snap = java.util.regex.Pattern
                    .compile("snapshot for (\\S+)").matcher(raw);
            if (snap.find()) return "snapshot created: " + snap.group(1);
        }

        return null; // skip everything else
    }

    private void tailApplyLog() {
        if (tailRunning) return;
        // Wait up to 30s for transport to become ready (covers slow Shizuku/ADB bind)
        for (int i = 0; i < 30 && !transportReady(); i++) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
        }
        if (!transportReady()) {
            addLog("re-attach failed: transport not ready after 30s");
            return;
        }
        // Restore isPartialOta from the props file actually sent to update_engine.
        String sentProps = sh("cat /data/local/tmp/_ue_props.txt 2>/dev/null");
        if (!sentProps.contains("not connected") && !sentProps.startsWith("bridge error")) {
            isPartialOta = !sentProps.contains("POWERWASH=1");
        }
        // Re-attach: open a fresh epoch so claimOtaResult() can fire
        synchronized (otaLock) {
            otaResultClaimed = false;
        }
        otaEpoch++;
        tailRunning = true;
        ui.post(() -> getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        final int epoch = otaEpoch;
        final String rcMarker = "__VSOCB_OTA_RC__";
        final boolean reattachPartial = isPartialOta;
        ui.post(() -> {
            if (installBtnBar != null) installBtnBar.setProgress(0);
            if (installBtnLabel != null) installBtnLabel.setText("STARTING...");
            if (btnCancelOta != null) btnCancelOta.setVisibility(View.VISIBLE);
            addLog("mode: " + (reattachPartial ? "UPDATE" : "DOWNGRADE"));
        });
        new Thread(() -> {
            int fileSeen = 0;
            // Seed lastLogTime to NOW so first logcat poll uses -T and skips old history
            String lastLogTime = new java.text.SimpleDateFormat(
                    "MM-dd HH:mm:ss.mmm", java.util.Locale.US)
                    .format(new java.util.Date());
            long deadline = System.currentTimeMillis() + 90L * 60 * 1000;
            boolean success = false;
            boolean failed = false;
            boolean timedOut = false;
            String errorDetail = "";
            // Regex patterns for progress
            java.util.regex.Pattern pBinder     = java.util.regex.Pattern.compile("progress=(\\d+(?:\\.\\d+)?)%");
            java.util.regex.Pattern pShell      = java.util.regex.Pattern.compile("operations \\((\\d+)%\\)");
            // update_engine_client --follow: "onStatusUpdate(STATUS (N), 0.136)" — fraction 0–1
            java.util.regex.Pattern pStatusFrac = java.util.regex.Pattern.compile(
                    "onStatusUpdate\\([^,]+,\\s*([0-9.e+\\-]+)\\)");
            try {
                while (tailRunning && epoch == otaEpoch
                        && System.currentTimeMillis() < deadline) {
                    if (!transportReady()) {
                        // Transport may still be connecting — retry up to 15s before giving up
                        boolean recovered = false;
                        for (int r = 0; r < 15; r++) {
                            try { Thread.sleep(1000); } catch (InterruptedException ignored2) {}
                            if (transportReady()) { recovered = true; break; }
                        }
                        if (!recovered) {
                            String lcCheck = sh("logcat -v time -s update_engine,update_engine_client -d 2>/dev/null");
                            String earlyErr = firstOtaErrorLine(lcCheck);
                            errorDetail = !earlyErr.isEmpty() ? earlyErr
                                    : "Lost connection to device (transport not ready).";
                            failed = true;
                            break;
                        }
                    }
                    // Primary: poll apply.log + apply.rc in one round-trip.
                    String raw = sh("cat /data/local/tmp/apply.log 2>/dev/null;"
                            + " echo; echo " + rcMarker + ";"
                            + " cat /data/local/tmp/apply.rc 2>/dev/null");
                    if (raw.equals("not connected") || raw.startsWith("bridge error")) {
                        // Before giving up, check logcat for a real update_engine error
                        String lcCheck = sh("logcat -v time -s update_engine,update_engine_client -d 2>/dev/null");
                        String earlyErr = firstOtaErrorLine(lcCheck);
                        if (!earlyErr.isEmpty()) {
                            errorDetail = earlyErr;
                        } else {
                            errorDetail = "Lost connection to device: " + raw.trim();
                        }
                        failed = true;
                        break;
                    }
                    String rc = "";
                    int rcIdx = raw.lastIndexOf(rcMarker);
                    if (rcIdx >= 0) {
                        rc = raw.substring(rcIdx + rcMarker.length()).trim();
                        raw = raw.substring(0, rcIdx);
                    }
                    boolean gotLines = false;
                    if (!raw.trim().isEmpty()) {
                        String[] lines = raw.split("\n", -1);
                        for (int i = fileSeen; i < lines.length; i++) {
                            String t = lines[i].trim();
                            if (t.isEmpty()) continue;
                            fileSeen = i + 1;
                            gotLines = true;

                            // Parse % — try all three formats
                            int pct = -1;
                            java.util.regex.Matcher m = pBinder.matcher(t);
                            if (m.find()) {
                                try { pct = (int) Float.parseFloat(m.group(1)); } catch (Exception e2) {}
                            }
                            if (pct < 0) {
                                m = pShell.matcher(t);
                                if (m.find()) {
                                    try { pct = Integer.parseInt(m.group(1)); } catch (Exception e2) {}
                                }
                            }
                            // onStatusUpdate(STATUS (N), fraction) — fraction is 0.0–1.0
                            if (pct < 0) {
                                java.util.regex.Matcher mSU = pStatusFrac.matcher(t);
                                while (mSU.find()) {
                                    try { pct = (int) Math.round(Double.parseDouble(mSU.group(1)) * 100); }
                                    catch (Exception ignored2) {}
                                }
                            }

                            // Update progress bar even when the line has no log message
                            if (pct >= 0) {
                                final int pctF2 = pct;
                                ui.post(() -> {
                                    if (installBtnBar   != null) installBtnBar.setProgress(pctF2 * 10);
                                    if (installBtnLabel != null) installBtnLabel.setText("INSTALLING: " + pctF2 + "%");
                                });
                            }

                            String formatted = formatUeLine(t, pBinder);
                            if (formatted == null) continue;

                            final int pctF = pct;
                            final String msgF = formatted;
                            ui.post(() -> {
                                if (pctF >= 0) {
                                    if (installBtnBar   != null) installBtnBar.setProgress(pctF * 10);
                                    if (installBtnLabel != null) installBtnLabel.setText("INSTALLING: " + pctF + "%");
                                }
                                addLog(msgF);
                            });

                            if (isOtaSuccessMarker(t)) {
                                success = true;
                                break;
                            }
                            if (isOtaErrorMarker(t)) {
                                errorDetail = t;
                                failed = true;
                                break;
                            }
                        }
                    }
                    if (success || failed) break;

                    // apply.rc appears only after update_engine_client exits.
                    if (!rc.isEmpty()) {
                        if ("0".equals(rc)) {
                            success = true;
                        } else {
                            failed = true;
                            if (errorDetail.isEmpty())
                                errorDetail = "update_engine_client exited with code " + rc;
                        }
                        break;
                    }

                    // Fallback: if apply.log empty/stale, tail logcat for update_engine
                    if (!gotLines) {
                        String cmd = lastLogTime.isEmpty()
                                ? "logcat -v time -s update_engine,update_engine_client -d 2>/dev/null"
                                : "logcat -v time -s update_engine,update_engine_client -d -T '" + lastLogTime + "' 2>/dev/null";
                        String lcRaw = sh(cmd);
                        String[] lcLines = lcRaw.split("\n");
                        // logcat -T is inclusive — last line of previous poll repeats; skip it
                        int lcStart = (!lastLogTime.isEmpty() && lcLines.length > 0) ? 1 : 0;
                        for (int li = lcStart; li < lcLines.length; li++) {
                            String t = lcLines[li].trim();
                            if (t.isEmpty() || t.startsWith("-----")) continue;
                            if (t.contains("fstab") || t.contains("solvit") || t.contains("stream not")) continue;
                            String[] parts = t.split("\\s+", 3);
                            if (parts.length >= 2) lastLogTime = parts[0] + " " + parts[1];

                            // Check terminal markers before the formatter drops
                            // verbose/unknown lines, so an error is never missed.
                            if (isOtaSuccessMarker(t)) {
                                final String okLine = t;
                                ui.post(() -> addLog("install done — reboot required"));
                                success = true;
                                break;
                            }
                            if (isOtaErrorMarker(t)) {
                                final String errLine = t;
                                ui.post(() -> addLog("error: " + errLine));
                                errorDetail = t;
                                failed = true;
                                break;
                            }

                            // extract % and stage for progress bar first (before skip)
                            int pct = -1;
                            String stageLabel = "INSTALLING";
                            java.util.regex.Matcher m = pShell.matcher(t);
                            if (m.find()) {
                                try { pct = Integer.parseInt(m.group(1)); } catch (Exception e2) {}
                            }
                            if (t.contains("onStatusUpdate(")) {
                                java.util.regex.Matcher su = java.util.regex.Pattern
                                        .compile("onStatusUpdate\\(([^,]+),\\s*([0-9.e+\\-]+)\\)").matcher(t);
                                if (su.find()) {
                                    String st = su.group(1).trim();
                                    if (pct < 0) try { pct = (int) Math.round(Double.parseDouble(su.group(2)) * 100); } catch (Exception e2) {}
                                    if (st.contains("FINALIZING")) stageLabel = "FINALIZING";
                                    else if (st.contains("VERIFYING")) stageLabel = "VERIFYING";
                                }
                            }
                            final int pctF = pct;
                            final String stageLabelF = stageLabel;

                            // Parse and format — only log meaningful events
                            String msg = formatUeLine(t, pShell);
                            final String msgF = msg;
                            ui.post(() -> {
                                if (pctF >= 0) {
                                    if (installBtnBar != null) installBtnBar.setProgress(pctF * 10);
                                    if (installBtnLabel != null) installBtnLabel.setText(stageLabelF + ": " + pctF + "%");
                                }
                                if (msgF != null) addLog(msgF);
                            });
                        }
                    }

                    if (success || failed) break;
                    Thread.sleep(2000);
                }
                if (!success && !failed && epoch == otaEpoch
                        && System.currentTimeMillis() >= deadline) {
                    timedOut = true;
                }
            } catch (Throwable ignored) {
            } finally {
                tailRunning = false;
                ui.post(() -> addLog("log tail ended"));
            }
            if (epoch != otaEpoch) return; // superseded by a newer run or a reset
            if (success) {
                onOtaSuccess(epoch);
            } else if (failed) {
                onOtaFailure(epoch, errorDetail);
            } else if (timedOut && claimOtaResult(epoch)) {
                tailRunning = false;
                otaEpoch++;
                ui.post(() -> {
                    resetOtaState();
                    showAlert("OTA timed out",
                            "90 min with no signal from update_engine. State reset.\nCheck the log — do not reinstall if OTA is still running.",
                            AlertType.WARN);
                });
            }
        }).start();
    }

    /** Red full-width button for destructive actions. */
    private TextView danger(String label, Runnable action) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextColor(0xFFffdede);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(15);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(12), dp(14), dp(12), dp(14));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF4a2026);
        d.setCornerRadius(dp(16));
        v.setBackground(d);
        v.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * Reboot after a {@code POWERWASH=1} update. update_engine already wrote
     * the BCB wipe command and the forward-merge indicator, so recovery
     * merges the snapshots, wipes /data, and then boots the new slot.
     */
    /** Reboot through PowerManager (shell/Shizuku has REBOOT permission).
     *  `rootCmd("reboot")` is unreliable here: this device has no `su` binary
     *  and the timer/ksud fallback can silently no-op, leaving the update
     *  "waiting to reboot". */
    private void powerReboot(String mode) {
        String svcCmd = mode == null || mode.isEmpty()
                ? "svc power reboot" : "svc power reboot " + mode;
        // 1. Shizuku shell — fastest path
        String r = sh(svcCmd + " 2>&1");
        String t = r == null ? "" : r.trim();
        if (!t.isEmpty()) addLog("reboot: " + t);
        // 2. Shizuku UserService fallback (plain reboot only)
        if (mode == null || mode.isEmpty()) {
            try { if (service != null) service.rebootDevice(); } catch (Throwable ignored) {}
        }
    }

    private void rebootAfterUpdate() {
        if (transportReady()) sh("sync");
        addLog("rebooting...");
        powerReboot("");
    }

    /**
     * Format /data when switching ROM. Two paths, both keep the bootloader
     * locked: reboot into recovery and pick "Wipe data", or arm the BCB so
     * recovery runs {@code --wipe_data} by itself.
     */
    private void formatData() {
        if (!transportReady()) {
            promptConnect();
            return;
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(16));
        GradientDrawable cardBg2 = new GradientDrawable();
        cardBg2.setColor(CARD); cardBg2.setCornerRadius(dp(16));
        card.setBackground(cardBg2);

        TextView tv2Title = new TextView(this);
        tv2Title.setText("Format data");
        tv2Title.setTextColor(TEXT); tv2Title.setTextSize(16);
        tv2Title.setTypeface(Typeface.DEFAULT_BOLD); tv2Title.setPadding(0,0,0,dp(10));
        card.addView(tv2Title);

        TextView tv2Msg = new TextView(this);
        tv2Msg.setText("Wipe /data only AFTER the new ROM has booted at least once.\n\n"
                + "Reboot to recovery — manually select Wipe data.\n"
                + "Auto (BCB) — arms the wipe in misc and reboots.\n\n"
                + "Do NOT wipe before install — the slot switch may revert.");
        tv2Msg.setTextColor(TEXT); tv2Msg.setTextSize(14); tv2Msg.setPadding(0,0,0,dp(16));
        card.addView(tv2Msg);

        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        btnRow2.setGravity(Gravity.END);

        android.app.AlertDialog dlg2 = new AlertDialog.Builder(this).setView(card).setCancelable(true).create();

        TextView btnCancel2 = new TextView(this);
        btnCancel2.setText("Cancel"); btnCancel2.setTextColor(MUTED);
        btnCancel2.setTextSize(14); btnCancel2.setTypeface(Typeface.DEFAULT_BOLD);
        btnCancel2.setPadding(dp(12),dp(10),dp(12),dp(10));
        btnCancel2.setOnClickListener(v -> dlg2.dismiss());

        TextView btnBcb = new TextView(this);
        btnBcb.setText("Auto (BCB)"); btnBcb.setTextColor(PRIMARY);
        btnBcb.setTextSize(14); btnBcb.setTypeface(Typeface.DEFAULT_BOLD);
        btnBcb.setPadding(dp(12),dp(10),dp(12),dp(10));
        btnBcb.setOnClickListener(v -> { dlg2.dismiss(); armBcbWipe(); });

        TextView btnRec = new TextView(this);
        btnRec.setText("Reboot to recovery"); btnRec.setTextColor(PRIMARY);
        btnRec.setTextSize(14); btnRec.setTypeface(Typeface.DEFAULT_BOLD);
        btnRec.setPadding(dp(12),dp(10),dp(0),dp(10));
        btnRec.setOnClickListener(v -> { dlg2.dismiss(); new Thread(() -> {
            addLog("reboot to recovery: " + sh("svc power reboot recovery 2>&1"));
        }).start(); });

        btnRow2.addView(btnCancel2); btnRow2.addView(btnBcb); btnRow2.addView(btnRec);
        card.addView(btnRow2);
        dlg2.show();
        if (dlg2.getWindow() != null)
            dlg2.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    }

    /** Write "boot-recovery" + "--wipe_data" into misc (first 2 KiB only). */
    /** Commit OPPO slot switch then reboot. Falls back to plain reboot if binder fails. */
    private void switchSlotAndReboot() {
        addLog("calling --oplus_switch_slot...");
        String r = rootCmd("update_engine_client --oplus_switch_slot 2>&1; echo SW_RC=$?");
        addLogRaw(r.trim());
        if (r.contains("FAILED_TRANSACTION") || r.contains("error") || r.contains("Error")) {
            addLog("binder blocked — trying --switch_slot=true...");
            r = rootCmd("update_engine_client --switch_slot=true 2>&1; echo SW2_RC=$?");
            addLogRaw(r.trim());
        }
        addLog("rebooting...");
        powerReboot("");
    }

    private void armBcbWipe() {
        new Thread(() -> {
            String script = "set -e\n"
                    + "dd if=/dev/block/by-name/misc of=/data/local/tmp/misc.bak "
                    + "bs=4096 count=1 2>/dev/null\n"
                    // clear command[32] + status[32] + recovery[768] so stale
                    // bytes cannot corrupt the BCB command string
                    + "dd if=/dev/zero of=/dev/block/by-name/misc "
                    + "bs=1 count=832 conv=notrunc 2>/dev/null\n"
                    + "printf 'boot-recovery' | dd of=/dev/block/by-name/misc "
                    + "bs=1 seek=0 conv=notrunc 2>/dev/null\n"
                    + "printf 'recovery\\n--wipe_data\\n' | dd of=/dev/block/by-name/misc "
                    + "bs=1 seek=64 conv=notrunc 2>/dev/null\n"
                    + "sync\n"
                    + "echo BCB_OK\n";
            String out = rootCmd(script);
            addLog("[wipe] " + out);
            if (out.contains("BCB_OK")) {
                // reboot recovery so bootloader commits the A/B slot switch
                // before entering recovery for wipe
                powerReboot("recovery");
            } else {
                addLog("[wipe] BCB failed — use Reboot to recovery");
            }
        }).start();
    }

    private volatile boolean ueOlderChecked = false;
    private volatile boolean ueOlderSupported = false;
    private volatile boolean ueSkipHashChecked = false;
    private volatile boolean ueSkipHashSupported = false;

    private boolean ueSupportsSkipHash() {
        if (!ueSkipHashChecked) {
            ueSkipHashSupported = sh("update_engine_client --help 2>&1")
                    .contains("--skip_hash_checks");
            ueSkipHashChecked = true;
        }
        return ueSkipHashSupported;
    }

    /**
     * Ask update_engine to validate the payload metadata against the certs it
     * can currently see. Non-destructive: this never writes a partition, it
     * only catches kDownloadMetadataSignatureVerificationError up front.
     */
    private String verifyPayload() {
        return sh("cd /data/local/tmp && "
                + "MS=$(grep '^METADATA_SIZE=' payload_properties.txt | cut -d= -f2) && "
                + "B=$(dd if=payload.bin bs=1 skip=20 count=4 2>/dev/null | od -An -tu1) && "
                + "set -- $B && "
                + "SS=$(( $1 * 16777216 + $2 * 65536 + $3 * 256 + $4 )) && "
                + "head -c $((MS+SS)) payload.bin > payload.meta && "
                + "update_engine_client --verify"
                + " --metadata=/data/local/tmp/payload.meta 2>&1 | tail -3")
                .trim();
    }

    /** Some update_engine_client builds do not know --allow_older_timestamps. */
    private boolean ueSupportsOlderTimestamps() {
        if (!ueOlderChecked) {
            ueOlderSupported = sh("update_engine_client --help 2>&1")
                    .contains("--allow_older_timestamps");
            ueOlderChecked = true;
        }
        return ueOlderSupported;
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private boolean checkSelection() {
        if (selectedOta == null || selectedOta.isEmpty()
                || selectedOta.startsWith("(no")) {
            toast("Select a file first");
            return false;
        }
        if (selectedOta.indexOf('\'') >= 0) {
            // the path is embedded in single-quoted shell commands during install
            toast("File name contains ' (quote)\nRename it first");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------ plumbing

    private void bindService() {
        if (bound || binding) return;
        try {
            binding = true;
            Shizuku.bindUserService(serviceArgs(), connection);
            addLog("binding user service...");
        } catch (Throwable t) {
            binding = false;
            addLog("bind failed: " + t);
        }
    }

    private Shizuku.UserServiceArgs serviceArgs() {
        return new Shizuku.UserServiceArgs(
                new ComponentName(getPackageName(), UserService.class.getName()))
                .daemon(false)
                .processNameSuffix("timer")
                .debuggable(false)
                .version(5);
    }

    // ------------------------------------------------------------ helpers

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** Creates a tinted drawable from an SVG path string (viewport 24×24). */
    private android.graphics.drawable.Drawable pathIcon(String svgPath, int color) {
        android.graphics.Path p = new android.graphics.Path();
        // SVG path parser: M,L,H,V,Q,T,Z (absolute + relative)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "([MmLlHhVvQqTtZz])([^MmLlHhVvQqTtZz]*)").matcher(svgPath);
        float cx = 0, cy = 0, sx = 0, sy = 0;
        float prevQx = Float.NaN, prevQy = Float.NaN; char prevCmd = 0;
        while (m.find()) {
            char cmd = m.group(1).charAt(0);
            String raw = m.group(2).trim();
            String[] args = raw.isEmpty() ? new String[0] : raw.split("[,\\s]+");
            int i = 0;
            switch (cmd) {
                case 'M': while (i+1<args.length) { cx=f(args[i++]); cy=f(args[i++]); p.moveTo(cx,cy); sx=cx; sy=cy; } break;
                case 'm': while (i+1<args.length) { cx+=f(args[i++]); cy+=f(args[i++]); p.moveTo(cx,cy); sx=cx; sy=cy; } break;
                case 'L': while (i+1<args.length) { cx=f(args[i++]); cy=f(args[i++]); p.lineTo(cx,cy); } break;
                case 'l': while (i+1<args.length) { cx+=f(args[i++]); cy+=f(args[i++]); p.lineTo(cx,cy); } break;
                case 'H': while (i<args.length)   { cx=f(args[i++]); p.lineTo(cx,cy); } break;
                case 'h': while (i<args.length)   { cx+=f(args[i++]); p.lineTo(cx,cy); } break;
                case 'V': while (i<args.length)   { cy=f(args[i++]); p.lineTo(cx,cy); } break;
                case 'v': while (i<args.length)   { cy+=f(args[i++]); p.lineTo(cx,cy); } break;
                case 'Q': while (i+3<args.length) { float x1=f(args[i++]),y1=f(args[i++]),x2=f(args[i++]),y2=f(args[i++]); p.quadTo(x1,y1,x2,y2); prevQx=x1; prevQy=y1; cx=x2; cy=y2; } break;
                case 'q': while (i+3<args.length) { float x1=cx+f(args[i++]),y1=cy+f(args[i++]),x2=cx+f(args[i++]),y2=cy+f(args[i++]); p.quadTo(x1,y1,x2,y2); prevQx=x1; prevQy=y1; cx=x2; cy=y2; } break;
                case 'T': while (i+1<args.length) { float x1=(prevCmd=='Q'||prevCmd=='q'||prevCmd=='T'||prevCmd=='t')&&!Float.isNaN(prevQx)?2*cx-prevQx:cx, y1=(prevCmd=='Q'||prevCmd=='q'||prevCmd=='T'||prevCmd=='t')&&!Float.isNaN(prevQy)?2*cy-prevQy:cy, x2=f(args[i++]),y2=f(args[i++]); p.quadTo(x1,y1,x2,y2); prevQx=x1; prevQy=y1; cx=x2; cy=y2; } break;
                case 't': while (i+1<args.length) { float x1=(prevCmd=='Q'||prevCmd=='q'||prevCmd=='T'||prevCmd=='t')&&!Float.isNaN(prevQx)?2*cx-prevQx:cx, y1=(prevCmd=='Q'||prevCmd=='q'||prevCmd=='T'||prevCmd=='t')&&!Float.isNaN(prevQy)?2*cy-prevQy:cy, x2=cx+f(args[i++]),y2=cy+f(args[i++]); p.quadTo(x1,y1,x2,y2); prevQx=x1; prevQy=y1; cx=x2; cy=y2; } break;
                case 'Z': case 'z': p.close(); cx=sx; cy=sy; prevQx=Float.NaN; break;
                default: prevQx=Float.NaN; break;
            }
            if (cmd!='T'&&cmd!='t'&&cmd!='Q'&&cmd!='q') prevQx=Float.NaN;
            prevCmd=cmd;
        }
        final android.graphics.Path path = p;
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        return new android.graphics.drawable.Drawable() {
            @Override public void draw(android.graphics.Canvas c) {
                android.graphics.Rect b = getBounds();
                c.save();
                c.translate(b.left, b.top);
                c.scale(b.width() / 24f, b.height() / 24f);
                c.drawPath(path, paint);
                c.restore();
            }
            @Override public void setAlpha(int a) { paint.setAlpha(a); }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }
    private void applyRootBackground() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            rootFrame.setBackgroundColor(BG);
        } else {
            // Light mode: darker purple at top fading to BG at bottom
            int topColor = blendColor(BG, 0x14000000);
            android.graphics.drawable.GradientDrawable grad =
                new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{topColor, BG});
            rootFrame.setBackground(grad);
        }
    }

    private static float f(String s) { return Float.parseFloat(s); }

    private static int adjustAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | ((int)(alpha * 255) << 24);
    }

    /** Overlay src color (with alpha) on top of dst. */
    private static int blendColor(int dst, int src) {
        int a = (src >>> 24);
        int r = ((src >> 16 & 0xFF) * a + (dst >> 16 & 0xFF) * (255 - a)) / 255;
        int g = ((src >>  8 & 0xFF) * a + (dst >>  8 & 0xFF) * (255 - a)) / 255;
        int b = ((src       & 0xFF) * a + (dst       & 0xFF) * (255 - a)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private void addLog(String text) {
        // Capitalize first character (app messages only; raw device output uses addLogRaw)
        if (text != null && !text.isEmpty())
            text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        addLogRaw(text);
    }

    private void addLogRaw(String text) {
        String oneLine = text.replace('\n', ' ').replaceAll("  +", " ").trim();
        long now = System.currentTimeMillis();
        android.util.Log.i(TAG, oneLine);
        synchronized (logLines) {
            if (logLines.size() >= LOG_MAX) logLines.remove(0);
            logLines.add(new LogLine(now, oneLine));
        }
        persistLog();
        ui.post(() -> appendLogSpan(now, oneLine));
    }

    private void appendLogSpan(long timeMs, String text) {
        if (logView == null) return;
        if (text == null || text.trim().isEmpty()) return;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (logView.length() > 0) sb.append('\n');

        // dim timestamp — MD3 outline tone
        String stamp = clock.format(new Date(timeMs)) + "  ";
        int stampStart = sb.length();
        sb.append(stamp);
        sb.setSpan(new ForegroundColorSpan(MUTED),
                stampStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        // text — MD3 role colours (compare lowercase for case-insensitive matching)
        String tl = text.toLowerCase();
        int textColor;
        if (text.startsWith("Root success") || text.startsWith("Ready to Install OTA")
                || text.startsWith("OTA installed") || tl.startsWith("install done")
                || text.startsWith("VSOCB ready"))
            textColor = GREEN;
        else if (tl.startsWith("error") || text.startsWith("OTA failed")
                || tl.startsWith("exploit failed") || tl.startsWith("cannot")
                || text.startsWith("ABORT") || tl.startsWith("root failed")
                || tl.startsWith("install failed")
                || text.contains("no more attempts"))
            textColor = RED;
        else if (tl.startsWith("attempt") || tl.startsWith("timer parked")
                || tl.startsWith("timer binary mismatch") || tl.startsWith("warn:"))
            textColor = AMBER;
        else if (text.startsWith(">") || text.startsWith("$"))
            textColor = PRIMARY;
        else
            textColor = TEXT;

        int textStart = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(textColor),
                textStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        logView.append(sb);
        if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // --------------------------------------------------------- file picker

    private void showDeviceInfoJson(TextView btn) {
        android.app.ProgressDialog prog = new android.app.ProgressDialog(this);
        prog.setMessage("Reading device info…");
        prog.setCancelable(false);
        prog.show();
        new Thread(() -> {
            // Wait up to 2s for daemon ping to settle before reading info
            if (new java.io.File(SU_PIPE_IN).exists() && !_daemonReady) {
                for (int i = 0; i < 40 && !_daemonReady; i++) {
                    try { Thread.sleep(50); } catch (Exception ignored) {}
                }
            }

            // ── Batch tất cả getprop trong 1 lần sh() ──────────────────────
            String propsOut = sh(
                "getprop ro.product.name; echo '|';" +
                "getprop ro.product.brand; echo '|';" +
                "getprop ro.product.model; echo '|';" +
                "getprop ro.product.device; echo '|';" +
                "getprop ro.build.display.id; echo '|';" +
                "getprop ro.build.version.ota; echo '|';" +
                "getprop ro.build.date.utc; echo '|';" +
                "getprop ro.build.version.security_patch; echo '|';" +
                "getprop ro.boot.slot_suffix; echo '|';" +
                "getprop ro.virtual_ab.compression.enabled; echo '|';" +
                "getprop ro.virtual_ab.cow_version; echo '|';" +
                "getprop ro.separate.soft; echo '|';" +
                "getprop ro.build.version.sdk; echo '|';" +
                "getprop ro.product.cpu.abi; echo '|';" +
                "getprop ro.build.fingerprint; echo '|';" +
                "getprop ro.boot.verifiedbootstate; echo '|';" +
                "getprop ro.build.version.incremental; echo '|'"
            );
            String[] pv = propsOut.replace("\r", "").split("\\|", -1);
            String propName        = pv.length > 0  ? pv[0].trim()  : "";
            String propBrand       = pv.length > 1  ? pv[1].trim()  : "";
            String propModel       = pv.length > 2  ? pv[2].trim()  : "";
            String propDevice      = pv.length > 3  ? pv[3].trim()  : "";
            String propDisplayId   = pv.length > 4  ? pv[4].trim()  : "";
            String propOtaVer      = pv.length > 5  ? pv[5].trim()  : "";
            String propBuildUtc    = pv.length > 6  ? pv[6].trim()  : "";
            String propSecPatch    = pv.length > 7  ? pv[7].trim()  : "";
            String propSlot        = pv.length > 8  ? pv[8].trim()  : "_a";
            String propVabc        = pv.length > 9  ? pv[9].trim()  : "true";
            String propCowVer      = pv.length > 10 ? pv[10].trim() : "3";
            String propSepSoft     = pv.length > 11 ? pv[11].trim() : "";
            String propSdk         = pv.length > 12 ? pv[12].trim() : "";
            String propAbi         = pv.length > 13 ? pv[13].trim() : "";
            String propFingerprint = pv.length > 14 ? pv[14].trim() : "";
            String propVbs         = pv.length > 15 ? pv[15].trim() : "";
            String propIncremental = pv.length > 16 ? pv[16].trim() : "";
            if (propSlot.isEmpty())   propSlot   = "_a";
            if (propVabc.isEmpty())   propVabc   = "true";
            if (propCowVer.isEmpty()) propCowVer = "3";

            long buildUtc = 0;
            try { buildUtc = Long.parseLong(propBuildUtc); } catch (Exception ignored) {}
            long otaTs = buildUtc + 1; // OTA timestamp phải mới hơn build hiện tại

            // ── lpdump --json: nguồn duy nhất cho partition/group info ──────
            String lpdumpRaw = shLocal("lpdump --json 2>/dev/null");
            if (lpdumpRaw == null || lpdumpRaw.trim().isEmpty())
                lpdumpRaw = sh("lpdump --json 2>/dev/null");
            lpdumpRaw = lpdumpRaw.trim();

            // Parse lpdump JSON
            long dynGroupSize  = 0;
            long superTotal    = 0;
            String dynGroupName = "";
            // partition info từ lpdump: name → {size, fs_type, is_dynamic, group_name}
            java.util.LinkedHashMap<String, long[]>   lpSizes  = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<String, String>   lpFs     = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<String, Boolean>  lpIsDyn  = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<String, String>   lpGroup  = new java.util.LinkedHashMap<>();

            if (!lpdumpRaw.isEmpty() && lpdumpRaw.startsWith("{")) {
                try {
                    org.json.JSONObject lp = new org.json.JSONObject(lpdumpRaw);

                    // Groups: lấy group đầu tiên có maximum_size > 0 và không phải COW
                    org.json.JSONArray groups = lp.optJSONArray("groups");
                    if (groups != null) {
                        for (int i = 0; i < groups.length(); i++) {
                            org.json.JSONObject g = groups.getJSONObject(i);
                            long sz  = Long.parseLong(g.optString("maximum_size", "0"));
                            String gn = g.optString("name", "");
                            if (sz > 0 && !gn.toLowerCase().contains("cow")) {
                                dynGroupSize = sz;
                                dynGroupName = gn;
                                break;
                            }
                        }
                    }

                    // Super block device total size
                    org.json.JSONArray blkDevs = lp.optJSONArray("block_devices");
                    if (blkDevs != null && blkDevs.length() > 0) {
                        superTotal = Long.parseLong(
                            blkDevs.getJSONObject(0).optString("size", "0"));
                    }

                    // Partitions: strip _a/_b suffix, bỏ COW entries
                    org.json.JSONArray parts = lp.optJSONArray("partitions");
                    if (parts != null) {
                        for (int i = 0; i < parts.length(); i++) {
                            org.json.JSONObject p = parts.getJSONObject(i);
                            String pname = p.optString("name", "");
                            if (pname.endsWith("-cow")) continue;
                            // Strip slot suffix → bare name
                            String bare = (pname.endsWith("_a") || pname.endsWith("_b"))
                                ? pname.substring(0, pname.length() - 2) : pname;
                            if (lpSizes.containsKey(bare)) continue; // keep first (active slot)
                            long sz = Long.parseLong(p.optString("size", "0"));
                            String fs = p.optString("fs_type", "");
                            boolean dyn = p.optBoolean("is_dynamic", false);
                            String grp = p.optString("group_name", "");
                            lpSizes.put(bare,  new long[]{sz});
                            lpFs.put(bare,     fs);
                            lpIsDyn.put(bare,  dyn);
                            lpGroup.put(bare,  grp);
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Static partitions từ by-name (chỉ những cái không có trong lpdump)
            String bynameOut = sh("ls /dev/block/by-name/ 2>/dev/null").trim();
            if (!bynameOut.isEmpty()) {
                for (String e : bynameOut.split("\\s+")) {
                    if (e.isEmpty()) continue;
                    String base = (e.endsWith("_a") || e.endsWith("_b"))
                        ? e.substring(0, e.length() - 2) : e;
                    if (!lpSizes.containsKey(base)) {
                        lpSizes.put(base, new long[]{0});
                        lpFs.put(base, "");
                        lpIsDyn.put(base, false);
                        lpGroup.put(base, "");
                    }
                }
            }

            // ── Tạo JSON theo format ota_meta.json (compatible với --from-meta) ──
            int cowVerInt = 3;
            try { cowVerInt = Integer.parseInt(propCowVer); } catch (Exception ignored) {}

            // Build partition list theo format ota_meta.json
            java.util.List<String> sortedNames = new java.util.ArrayList<>(lpSizes.keySet());
            java.util.Collections.sort(sortedNames);
            java.util.List<String> dynPartNames = new java.util.ArrayList<>();
            for (String n : sortedNames) {
                if (Boolean.TRUE.equals(lpIsDyn.get(n))) dynPartNames.add(n);
            }

            StringBuilder json = new StringBuilder();
            json.append("{\n");

            // Top-level device metadata
            json.append("  \"_device\": {\n");
            json.append("    \"product_name\": \"").append(esc(propName)).append("\",\n");
            json.append("    \"brand\": \"").append(esc(propBrand)).append("\",\n");
            json.append("    \"model\": \"").append(esc(propModel)).append("\",\n");
            json.append("    \"device\": \"").append(esc(propDevice)).append("\",\n");
            json.append("    \"display_id\": \"").append(esc(propDisplayId)).append("\",\n");
            json.append("    \"fingerprint\": \"").append(esc(propFingerprint)).append("\",\n");
            json.append("    \"incremental\": \"").append(esc(propIncremental)).append("\",\n");
            json.append("    \"sdk\": \"").append(esc(propSdk)).append("\",\n");
            json.append("    \"abi\": \"").append(esc(propAbi)).append("\",\n");
            json.append("    \"slot\": \"").append(esc(propSlot)).append("\",\n");
            json.append("    \"verified_boot\": \"").append(esc(propVbs)).append("\",\n");
            json.append("    \"super_total\": ").append(superTotal).append(",\n");
            json.append("    \"dyn_group_name\": \"").append(esc(dynGroupName)).append("\"\n");
            json.append("  },\n");

            // ota_meta.json compatible fields
            json.append("  \"block_size\": 4096,\n");
            json.append("  \"minor_version\": 9,\n");
            json.append("  \"max_timestamp\": ").append(otaTs).append(",\n");
            json.append("  \"partial_update\": true,\n");
            json.append("  \"security_patch_level\": \"").append(esc(propSecPatch)).append("\",\n");

            json.append("  \"partitions\": [\n");
            for (int i = 0; i < sortedNames.size(); i++) {
                String n = sortedNames.get(i);
                long sz = lpSizes.get(n)[0];
                String fs = lpFs.getOrDefault(n, "");
                json.append("    {\"name\":\"").append(esc(n))
                    .append("\",\"size\":").append(sz)
                    .append(",\"filesystem_type\":\"").append(esc(fs))
                    .append("\",\"version\":\"").append(otaTs)
                    .append("\",\"incremental\":false}");
                if (i < sortedNames.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("  ],\n");

            json.append("  \"dynamic\": {\n");
            json.append("    \"groups\": [{\"name\":\"main\",\"size\":").append(dynGroupSize)
                .append(",\"partitions\":[");
            for (int i = 0; i < dynPartNames.size(); i++) {
                json.append("\"").append(esc(dynPartNames.get(i))).append("\"");
                if (i < dynPartNames.size() - 1) json.append(",");
            }
            json.append("]}],\n");
            json.append("    \"snapshot_enabled\": true,\n");
            json.append("    \"vabc_enabled\": ").append("true".equalsIgnoreCase(propVabc)).append(",\n");
            json.append("    \"vabc_compression_param\": \"lz4\",\n");
            json.append("    \"cow_version\": ").append(cowVerInt).append(",\n");
            json.append("    \"vabc_threaded\": false,\n");
            json.append("    \"vabc_batch_writes\": false,\n");
            json.append("    \"compression_factor\": 65536\n");
            json.append("  },\n");

            json.append("  \"zip_metadata\": {\n");
            json.append("    \"ota-type\": \"AB\",\n");
            json.append("    \"pre-device\": \"").append(esc(propName)).append("\",\n");
            json.append("    \"pre-build\": \"").append(esc(propFingerprint)).append("\",\n");
            json.append("    \"pre-build-incremental\": \"").append(esc(propIncremental)).append("\",\n");
            json.append("    \"my_manifest\": \"").append(esc(propOtaVer)).append("\",\n");
            json.append("    \"oplus_update_engine_verify_disable\": \"1\",\n");
            json.append("    \"oplus_separate_soft\": \"").append(esc(propSepSoft)).append("\"\n");
            json.append("  },\n");

            json.append("  \"payload_properties\": {\n");
            json.append("    \"SWITCH_SLOT_ON_REBOOT\": \"1\",\n");
            json.append("    \"oplus_update_engine_verify_disable\": \"1\",\n");
            json.append("    \"oplus_separate_soft\": \"").append(esc(propSepSoft)).append("\"\n");
            json.append("  }\n");
            json.append("}");

            String result = json.toString();
            runOnUiThread(() -> {
                prog.dismiss();
                if (btn != null) { btn.setEnabled(true); btn.setText("Info"); }
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setText(result);
                tv.setTextSize(11);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setPadding(dp(16), dp(8), dp(16), dp(8));
                tv.setTextIsSelectable(true);
                sv.addView(tv);
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Device Info")
                    .setView(sv)
                    .setPositiveButton("Copy", (d, w) -> {
                        android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("device_info", result));
                    })
                    .setNeutralButton("Save JSON", (d, w) -> saveDeviceInfoJson(result))
                    .setNegativeButton("Close", null)
                    .show();
            });
        }).start();
    }

    /** JSON-escape a string value. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private void saveDeviceInfoJson(String json) {
        pendingDeviceInfoJson = json;
        String model = sh("getprop ro.product.name 2>/dev/null").trim();
        if (model.isEmpty()) model = "device";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "device_info_" + model + ".json");
        startActivityForResult(intent, REQUEST_SAVE_JSON);
    }

    private void showSettings() {
        if (settingsDlg != null && settingsDlg.isShowing()) return;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(16));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(CARD); cardBg.setCornerRadius(dp(16));
        card.setBackground(cardBg);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(TEXT); title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD); title.setPadding(0, 0, 0, dp(16));
        card.addView(title);

        // ── Timer Binary ──────────────────────────────────────────────────
        sectionHeader(card, "Timer Binary");

        // timer radio indicators [0]=Auto [1]=Custom
        TextView[] timerInd = new TextView[2];
        TextView[] timerLbl = new TextView[2];
        TextView timerCustomLabel = new TextView(this); // updated after pick

        Runnable[] selectTimer = new Runnable[2];
        selectTimer[0] = () -> {
            customTimerPath = null;
            getPreferences(MODE_PRIVATE).edit().remove("customTimerPath").apply();
            applyRadioGroup(timerInd, timerLbl, 0);
            timerCustomLabel.setText("Pick file…");
            timerCustomLabel.setTextColor(MUTED);
        };
        selectTimer[1] = () -> applyRadioGroup(timerInd, timerLbl, 1);

        int timerSel = customTimerPath == null ? 0 : 1;
        String[] timerLabels = {"Auto (device-specific)", null};
        for (int i = 0; i < 2; i++) {
            timerInd[i] = radioIndicator(i == timerSel);
            timerLbl[i] = radioLabel("", i == timerSel);
        }
        timerLbl[0].setText("Auto (device-specific)");

        LinearLayout timerAutoRow = new LinearLayout(this);
        timerAutoRow.setOrientation(LinearLayout.HORIZONTAL);
        timerAutoRow.setGravity(Gravity.CENTER_VERTICAL);
        timerAutoRow.setPadding(0, dp(4), 0, dp(4));
        timerAutoRow.addView(timerInd[0]); timerAutoRow.addView(timerLbl[0]);
        timerAutoRow.setOnClickListener(v -> selectTimer[0].run());
        card.addView(timerAutoRow);

        LinearLayout timerCustomRow = new LinearLayout(this);
        timerCustomRow.setOrientation(LinearLayout.HORIZONTAL);
        timerCustomRow.setGravity(Gravity.CENTER_VERTICAL);
        timerCustomRow.setPadding(0, dp(4), 0, dp(4));
        timerCustomRow.addView(timerInd[1]);

        timerCustomLabel.setText(customTimerPath != null
                ? new java.io.File(customTimerPath).getName() : "Pick file…");
        timerCustomLabel.setTextColor(customTimerPath != null ? TEXT : MUTED);
        timerCustomLabel.setTextSize(14);
        timerCustomLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        timerCustomRow.addView(timerCustomLabel);

        TextView timerPickBtn = new TextView(this);
        timerPickBtn.setText("Pick"); timerPickBtn.setTextColor(PRIMARY);
        timerPickBtn.setTextSize(13); timerPickBtn.setTypeface(Typeface.DEFAULT_BOLD);
        timerPickBtn.setPadding(dp(10), dp(4), 0, dp(4));
        timerPickBtn.setOnClickListener(v -> pickTimerFile());
        timerCustomRow.addView(timerPickBtn);
        timerCustomRow.setOnClickListener(v -> { selectTimer[1].run(); pickTimerFile(); });
        card.addView(timerCustomRow);

        // ── OTA Certificate ───────────────────────────────────────────────
        View sep1 = new View(this);
        sep1.setBackgroundColor(LINE);
        card.addView(sep1, new LinearLayout.LayoutParams(-1, 1));
        card.addView(spacer(dp(12)));
        sectionHeader(card, "OTA Certificate");

        TextView[] certInd = new TextView[2];
        TextView[] certLbl = new TextView[2];
        TextView certCustomLabel = new TextView(this);

        Runnable[] selectCert = new Runnable[2];
        selectCert[0] = () -> { certMode = CERT_STOCK;
            getPreferences(MODE_PRIVATE).edit().putInt("certMode", CERT_STOCK).apply();
            applyRadioGroup(certInd, certLbl, 0); };
        selectCert[1] = () -> { certMode = CERT_CUSTOM;
            getPreferences(MODE_PRIVATE).edit().putInt("certMode", CERT_CUSTOM).apply();
            applyRadioGroup(certInd, certLbl, 1); };

        int certSel = certMode == CERT_CUSTOM ? 1 : 0;
        String[] certLabels = {"Stock (keep original)", null};
        for (int i = 0; i < 2; i++) {
            certInd[i] = radioIndicator(i == certSel);
            certLbl[i] = radioLabel(certLabels[i] != null ? certLabels[i] : "", i == certSel);
        }

        for (int i = 0; i < 1; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            row.addView(certInd[i]); row.addView(certLbl[i]);
            final int idx = i;
            row.setOnClickListener(v -> selectCert[idx].run());
            card.addView(row);
        }

        LinearLayout certCustomRow = new LinearLayout(this);
        certCustomRow.setOrientation(LinearLayout.HORIZONTAL);
        certCustomRow.setGravity(Gravity.CENTER_VERTICAL);
        certCustomRow.setPadding(0, dp(4), 0, dp(4));
        certCustomRow.addView(certInd[1]);

        certCustomLabel.setText(certMode == CERT_CUSTOM && customCertB64 != null
                ? "Custom cert loaded" : "Custom…");
        certCustomLabel.setTextColor(certMode == CERT_CUSTOM ? TEXT : MUTED);
        certCustomLabel.setTextSize(14);
        certCustomLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        certCustomRow.addView(certCustomLabel);

        TextView certPickBtn = new TextView(this);
        certPickBtn.setText("Pick"); certPickBtn.setTextColor(PRIMARY);
        certPickBtn.setTextSize(13); certPickBtn.setTypeface(Typeface.DEFAULT_BOLD);
        certPickBtn.setPadding(dp(10), dp(4), 0, dp(4));
        certPickBtn.setOnClickListener(v -> pickCertFile());
        certCustomRow.addView(certPickBtn);
        certCustomRow.setOnClickListener(v -> { selectCert[1].run(); pickCertFile(); });
        card.addView(certCustomRow);

        // ── Reboot ────────────────────────────────────────────────────────
        View sep2 = new View(this);
        sep2.setBackgroundColor(LINE);
        card.addView(sep2, new LinearLayout.LayoutParams(-1, 1));
        card.addView(spacer(dp(8)));

        boolean canReboot = transportReady();
        TextView rebootRow = new TextView(this);
        rebootRow.setText("Reboot device");
        rebootRow.setTextColor(canReboot ? 0xFFFF5252 : MUTED);
        rebootRow.setTextSize(14);
        rebootRow.setPadding(0, dp(6), 0, dp(4));
        if (canReboot) rebootRow.setOnClickListener(v -> {
            if (settingsDlg != null) settingsDlg.dismiss();
            reboot();
        });
        card.addView(rebootRow);

        // ── Close ─────────────────────────────────────────────────────────
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(8), 0, 0);
        TextView closeBtn = new TextView(this);
        closeBtn.setText("Close"); closeBtn.setTextColor(PRIMARY);
        closeBtn.setTextSize(14); closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setPadding(dp(12), dp(8), 0, dp(4));
        closeBtn.setOnClickListener(v -> { if (settingsDlg != null) settingsDlg.dismiss(); });
        btnRow.addView(closeBtn);
        card.addView(btnRow);

        settingsDlg = new android.app.AlertDialog.Builder(this)
                .setView(card).setCancelable(true).create();
        if (settingsDlg.getWindow() != null)
            settingsDlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        settingsDlg.show();
    }

    private void sectionHeader(LinearLayout parent, String text) {
        TextView h = new TextView(this);
        h.setText(text); h.setTextColor(MUTED); h.setTextSize(11);
        h.setTypeface(Typeface.DEFAULT_BOLD); h.setPadding(0, 0, 0, dp(6));
        parent.addView(h);
    }

    private TextView radioIndicator(boolean selected) {
        TextView v = new TextView(this);
        v.setText(selected ? "●" : "○");
        v.setTextColor(selected ? PRIMARY : MUTED);
        v.setTextSize(16); v.setPadding(0, 0, dp(10), 0);
        return v;
    }

    private TextView radioLabel(String text, boolean selected) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextColor(selected ? TEXT : MUTED); v.setTextSize(14);
        return v;
    }

    private void applyRadioGroup(TextView[] indicators, TextView[] labels, int selected) {
        for (int i = 0; i < indicators.length; i++) {
            boolean on = i == selected;
            indicators[i].setText(on ? "●" : "○");
            indicators[i].setTextColor(on ? PRIMARY : MUTED);
            if (labels[i] != null) labels[i].setTextColor(on ? TEXT : MUTED);
        }
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, h));
        return v;
    }

    private void pickTimerFile() {
        if (settingsDlg != null) settingsDlg.dismiss();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        try { startActivityForResult(intent, REQUEST_PICK_TIMER); }
        catch (Throwable t) { toast("Cannot open file picker: " + t.getMessage()); }
    }

    private void pickCertFile() {
        if (settingsDlg != null) settingsDlg.dismiss();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/zip", "application/octet-stream"});
        try { startActivityForResult(intent, REQUEST_PICK_CERT); }
        catch (Throwable t) { toast("Cannot open file picker: " + t.getMessage()); }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/zip", "application/octet-stream",
                        "application/x-zip-compressed"});
        try {
            startActivityForResult(intent, REQUEST_PICK_FILE);
        } catch (Throwable t) {
            toast("Cannot open file picker: " + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SAVE_JSON && resultCode == RESULT_OK && data != null && pendingDeviceInfoJson != null) {
            Uri uri = data.getData();
            String json = pendingDeviceInfoJson;
            pendingDeviceInfoJson = null;
            new Thread(() -> {
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(json.getBytes("UTF-8"));
                    ui.post(() -> android.widget.Toast.makeText(this, "Saved", android.widget.Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    ui.post(() -> android.widget.Toast.makeText(this, "Save failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                }
            }).start();
        }
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
                // some pickers don't offer persistable grants; resolvePickedUri
                // copies the file instead, so this is not fatal
            }
            new Thread(() -> {
                String path = resolvePickedUri(uri);
                autoFormatData = true;
                userPickedMode = false; // new file → allow auto-detect to set toggle
                ui.post(() -> {
                    if (copyProgressDialog != null) { copyProgressDialog.dismiss(); copyProgressDialog = null; }
                    selectedOta = path;
                    selectedOtaUri = uri;
                    addLog("file selected: " + path);
                    getSharedPreferences(PREFS_ROM, MODE_PRIVATE).edit()
                        .putString(KEY_ROM_PATH, path)
                        .putString(KEY_ROM_URI, uri.toString())
                        .apply();
                });
                showRomInfo(path);
            }).start();
        }
        if (requestCode == REQUEST_PICK_TIMER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Throwable ignored) {}
            new Thread(() -> {
                String path = resolvePickedUri(uri);
                ui.post(() -> {
                    if (copyProgressDialog != null) { copyProgressDialog.dismiss(); copyProgressDialog = null; }
                    customTimerPath = path;
                    getPreferences(MODE_PRIVATE).edit().putString("customTimerPath", path).apply();
                    addLog("custom timer: " + path);
                    showSettings();
                });
            }).start();
        }
        if (requestCode == REQUEST_PICK_CERT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Throwable ignored) {}
            new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                        int n;
                        while (is != null && (n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    }
                    String b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
                    ui.post(() -> {
                        certMode = CERT_CUSTOM;
                        customCertB64 = b64;
                        getPreferences(MODE_PRIVATE).edit()
                                .putInt("certMode", CERT_CUSTOM)
                                .putString("customCertB64", b64)
                                .apply();
                        addLog("custom cert loaded (" + baos.size() + " bytes)");
                        showSettings();
                    });
                } catch (Throwable t) {
                    ui.post(() -> toast("Failed to read cert: " + t.getMessage()));
                }
            }).start();
        }
    }

    /**
     * Resolves a content URI to a shell-accessible absolute path.
     *
     * Strategy:
     *  1. MediaStore _data column (older Android / some file managers).
     *  2. DocumentsProvider path parsing (primary:Download/foo.zip).
     *  3. Copy via ContentResolver to getExternalCacheDir() — the app can
     *     always write there and the shell can always read it.
     */
    private String readZipEntry(String path, Uri uri, String entryName) {
        // Try direct ZipFile first
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(path)) {
            java.util.zip.ZipEntry e = zf.getEntry(entryName);
            if (e == null) return null;
            java.io.InputStream in = zf.getInputStream(e);
            byte[] buf = new byte[8192]; int n = in.read(buf);
            return new String(buf, 0, Math.max(n, 0), "UTF-8").trim();
        } catch (Throwable ignored) {}
        // Fall back to ZipInputStream via ContentResolver
        if (uri == null) return null;
        try (java.io.InputStream raw = getContentResolver().openInputStream(uri);
             java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(raw)) {
            java.util.zip.ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (entryName.equals(ze.getName())) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] tmp = new byte[8192]; int n;
                    while ((n = zis.read(tmp)) > 0) bos.write(tmp, 0, n);
                    return bos.toString("UTF-8").trim();
                }
                zis.closeEntry();
            }
        } catch (Throwable t) { addLog("readZipEntry(" + entryName + ") failed: " + t); }
        return null;
    }

    private long[] readPayloadInfoFromUri(Uri uri) {
        try (ParcelFileDescriptor pfd =
                     getContentResolver().openFileDescriptor(uri, "r");
             java.io.FileInputStream fis = new java.io.FileInputStream(pfd.getFileDescriptor());
             java.nio.channels.FileChannel ch = fis.getChannel()) {
            java.nio.ByteBuffer hdr = java.nio.ByteBuffer.allocate(30);
            hdr.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            ch.read(hdr);
            hdr.flip();
            if (hdr.get(0) != 'P' || hdr.get(1) != 'K' || hdr.get(2) != 3 || hdr.get(3) != 4)
                return null;
            long compSize = hdr.getInt(18) & 0xFFFFFFFFL;
            long usize32  = hdr.getInt(22) & 0xFFFFFFFFL;
            int fnLen = hdr.getShort(26) & 0xFFFF;
            int exLen = hdr.getShort(28) & 0xFFFF;
            byte[] fn = new byte[fnLen];
            java.nio.ByteBuffer fnBuf = java.nio.ByteBuffer.wrap(fn);
            ch.read(fnBuf);
            if (!"payload.bin".equals(new String(fn, "UTF-8"))) return null;
            if (compSize == 0xFFFFFFFFL && exLen > 0) {
                byte[] extra = new byte[exLen];
                ch.read(java.nio.ByteBuffer.wrap(extra));
                int i = 0;
                while (i + 4 <= extra.length) {
                    int id = (extra[i]&0xFF)|((extra[i+1]&0xFF)<<8);
                    int sz = (extra[i+2]&0xFF)|((extra[i+3]&0xFF)<<8);
                    int p = i + 4;
                    if (p + sz > extra.length) break;
                    if (id == 0x0001) {
                        if (usize32 == 0xFFFFFFFFL) p += 8;
                        if (p + 8 <= i + 4 + sz) {
                            long v = 0;
                            for (int b = 7; b >= 0; b--) v = (v << 8) | (extra[p+b] & 0xFFL);
                            compSize = v;
                        }
                        break;
                    }
                    i = p + sz;
                }
            }
            long offset = 30L + fnLen + exLen;
            return new long[]{offset, compSize};
        } catch (Throwable t) {
            addLog("readPayloadInfoFromUri failed: " + t);
            return null;
        }
    }

    private boolean canReadDirect(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String resolvePickedUri(Uri uri) {
        ContentResolver cr = getContentResolver();

        // 1. _data column
        try (Cursor c = cr.query(uri, new String[]{"_data"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String p = c.getString(0);
                if (p != null && !p.isEmpty() && new File(p).exists()) return p;
            }
        } catch (Throwable ignored) {}

        // 2. Parse DocumentsProvider path: /document/primary:Download/foo.zip
        // Only return the path if it is actually openable (scoped storage blocks /sdcard/ on API 33+)
        String uriPath = uri.getPath();
        if (uriPath != null) {
            int colon = uriPath.lastIndexOf(':');
            if (colon >= 0) {
                String rel = uriPath.substring(colon + 1);
                for (String base : new String[]{"/sdcard/", "/storage/emulated/0/"}) {
                    File f = new File(base + rel);
                    if (f.exists() && canReadDirect(f)) return f.getAbsolutePath();
                }
            }
            File direct = new File(uriPath);
            if (direct.exists() && canReadDirect(direct)) return direct.getAbsolutePath();
        }

        // 3. Copy to external cache dir (app-writable, shell-readable)
        String name = "payload_picked.zip";
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) name = n;
            }
        } catch (Throwable ignored) {}

        File cacheDir = getExternalCacheDir();
        if (cacheDir == null) cacheDir = getCacheDir();
        cacheDir.mkdirs();
        File dest = new File(cacheDir, name);

        // Get file size for progress
        long fileSize = -1;
        try (Cursor sc = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (sc != null && sc.moveToFirst()) fileSize = sc.getLong(0);
        } catch (Throwable ignored) {}
        final long totalSize = fileSize;
        final String copyName = name;

        // Show progress dialog on UI thread
        ui.post(() -> {
            android.app.ProgressDialog d = new android.app.ProgressDialog(this);
            d.setTitle("Copying file");
            d.setMessage(copyName);
            d.setCancelable(false);
            if (totalSize > 0) {
                d.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
                d.setMax(100);
                d.setProgress(0);
            } else {
                d.setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER);
            }
            d.show();
            copyProgressDialog = d;
        });

        addLog("copy " + name + " → " + dest.getParent() + "/");
        try (InputStream in = cr.openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[131072];
            int n;
            long total = 0;
            int lastPct = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (totalSize > 0) {
                    int pct = (int) (total * 100 / totalSize);
                    if (pct != lastPct) {
                        lastPct = pct;
                        final int p = pct;
                        final long mb = total >> 20;
                        ui.post(() -> {
                            if (copyProgressDialog != null) {
                                copyProgressDialog.setProgress(p);
                                copyProgressDialog.setMessage(copyName + "\n" + mb + " MiB / " + (totalSize >> 20) + " MiB");
                            }
                        });
                    }
                }
            }
        } catch (Throwable t) {
            addLog("copy failed: " + t);
            ui.post(() -> { if (copyProgressDialog != null) { copyProgressDialog.dismiss(); copyProgressDialog = null; } });
            return dest.getAbsolutePath();
        }
        addLog("copy done: " + (dest.length() >> 20) + " MiB → " + dest.getAbsolutePath());
        return dest.getAbsolutePath();
    }

}
