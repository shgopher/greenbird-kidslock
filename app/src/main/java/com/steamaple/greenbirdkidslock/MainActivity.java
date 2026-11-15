package com.steamaple.greenbirdkidslock;

import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.net.Uri;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * 设置主界面（改造版）。
 * 家长可配置：家长/孩子模式、孩子年级/科目、出题间隔、解锁题数、PIN。
 * 并可在"孩子模式/家长模式"之间切换。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private PrefManager pref;
    private QuestionBank bank;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvStatus, tvRemaining, tvMode, tvGrade, tvSubjects, tvInterval, tvUnlockCount, tvOverlayHint;
    private TextView tvOnline;
    private Button btnGradeDown, btnGradeUp;
    private Button btnEnableChild, btnEnableParent, btnAdminProtect;
    private TextView tvAdminHint;
    private Button btnConfigOnline, btnToggleOnline;
    /** 本次打开 App 是否已经自动弹过“开机锁屏权限”引导，避免反复打扰。 */
    private boolean permissionPromptShown = false;
    /** 刚从系统电池设置页返回时，检查是否真的生效，没生效就提示手动路径。 */
    private boolean batterySettingsAttempted = false;
    /** 若本机是 Device Owner，只调用一次 setUninstallBlocked。 */
    private boolean ownerUninstallBlocked = false;

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pref = new PrefManager(this);
        bank = QuestionBank.loadFromAssets(this);

        // 锁屏状态下禁止进入设置（防绕过）
        if (pref.isLocked()) {
            Intent lockIntent = new Intent(this, LockScreenActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(lockIntent);
            finish();
            return;
        }

        // 孩子模式下（包括答题后的自由观看窗口），进设置前必须先做家长验证，
        // 否则孩子可以直接改 PIN、改解锁问题或切到家长模式。
        if (pref.isChildMode()) {
            Intent gate = new Intent(this, LockScreenActivity.class);
            gate.putExtra(LockScreenActivity.EXTRA_PARENT_GATE, true);
            gate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(gate);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        initViews();
        setupListeners();
        updateStatus();
        promptPermissionsIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(updateRunnable);
        if (batterySettingsAttempted) {
            batterySettingsAttempted = false;
            if (!isIgnoringBatteryOptimizations()) {
                toast("如系统页面没有反应，请重新点“②”并选择“打开应用详情”手动允许后台运行");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(updateRunnable);
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvRemaining = findViewById(R.id.tvRemaining);
        tvMode = findViewById(R.id.tvMode);
        tvOverlayHint = findViewById(R.id.tvOverlayHint);
        // 状态行作为“权限管理”入口：总是打开三项设置引导
        tvOverlayHint.setOnClickListener(v -> {
            if (isFinishing()) return;
            showPermissionGuideDialog(true, true);
        });
        tvGrade = findViewById(R.id.tvGrade);
        tvSubjects = findViewById(R.id.tvSubjects);
        tvInterval = findViewById(R.id.tvInterval);
        tvUnlockCount = findViewById(R.id.tvUnlockCount);
        btnGradeDown = findViewById(R.id.btnGradeDown);
        btnGradeUp = findViewById(R.id.btnGradeUp);
        btnEnableChild = findViewById(R.id.btnEnableChild);
        btnEnableParent = findViewById(R.id.btnEnableParent);
        btnAdminProtect = findViewById(R.id.btnAdminProtect);
        tvAdminHint = findViewById(R.id.tvAdminHint);
        tvOnline = findViewById(R.id.tvOnline);
        btnConfigOnline = findViewById(R.id.btnConfigOnline);
        btnToggleOnline = findViewById(R.id.btnToggleOnline);
    }

    private void setupListeners() {
        // 模式切换统一放在"操作"区（开启孩子模式 / 切换到家长模式），不再保留顶部重复的切换按钮

        // 年级调整（1~9）
        btnGradeDown.setOnClickListener(v -> { pref.setGrade(Math.max(1, pref.getGrade() - 1)); updateStatus(); });
        btnGradeUp.setOnClickListener(v -> { pref.setGrade(Math.min(9, pref.getGrade() + 1)); updateStatus(); });

        // 出题间隔（内部统一按"秒"存；最后一个 10 秒按钮是【测试专用】）
        // 【测试用】10 秒来自 PrefManager.TEST_INTERVAL_SECONDS，仅用于在电视上快速验证
        // "答题解锁 → 到点重新锁屏"的循环，正式给孩子使用时请选 10 分钟及以上。
        int[] intButtons = {R.id.btnInt10s, R.id.btnInt10, R.id.btnInt15, R.id.btnInt30,
                R.id.btnInt45, R.id.btnInt60, R.id.btnInt90, R.id.btnInt120};
        int[] intValues = {PrefManager.TEST_INTERVAL_SECONDS,
                10 * 60, 15 * 60, 30 * 60, 45 * 60, 60 * 60, 90 * 60, 120 * 60};
        for (int i = 0; i < intButtons.length; i++) {
            final int seconds = intValues[i];
            findViewById(intButtons[i]).setOnClickListener(v -> {
                pref.setIntervalSeconds(seconds);
                updateStatus();
                toast("出题间隔设为 " + formatInterval(seconds)
                        + (seconds < 60 ? "（测试用）" : ""));
            });
        }

        // 解锁题数（1 题 = 答对一道即解锁，适合低龄/快速验证）
        int[] countButtons = {R.id.btnUnlock1, R.id.btnUnlock3, R.id.btnUnlock5, R.id.btnUnlock10};
        int[] countValues = {1, 3, 5, 10};
        for (int i = 0; i < countButtons.length; i++) {
            final int count = countValues[i];
            findViewById(countButtons[i]).setOnClickListener(v -> {
                pref.setUnlockCount(count);
                updateStatus();
                toast("解锁题数设为 " + count + " 题");
            });
        }

        // 在线题库（NAS 后台）
        btnConfigOnline.setOnClickListener(v -> showServerDialog());
        btnToggleOnline.setOnClickListener(v -> {
            if (pref.isOnline()) {
                pref.setOnline(false);
                toast("已关闭联网拉题，改用内置题库");
            } else {
                if (pref.getServerUrl().isEmpty() || pref.getChildId().isEmpty()) {
                    toast("请先配置服务器地址与孩子ID");
                    showServerDialog();
                    return;
                }
                pref.setOnline(true);
                toast("已开启联网拉题");
            }
            updateStatus();
        });

        // PIN
        findViewById(R.id.btnSetPin).setOnClickListener(v -> showPinDialog());

        // 忘记 PIN 时的解锁问题（父母出生年份）
        findViewById(R.id.btnSetSecurity).setOnClickListener(v -> showSecurityQuestionsDialog());

        // 开启孩子模式（立即锁屏）
        btnEnableChild.setOnClickListener(v -> enterChildModeAndLock());

        // 切换到家长模式（解锁）
        btnEnableParent.setOnClickListener(v -> enterParentMode());

        // 防卸载保护：设备管理器（可选，需用户手动激活）
        btnAdminProtect.setOnClickListener(v -> toggleAdminProtection());
    }

    /**
     * 进入家长模式：解除锁屏、停止自由观看计时和前台服务、关闭 HOME 拦截。
     * 由“操作-切换到家长模式（解锁）”按钮调用。
     */
    private void enterParentMode() {
        pref.setMode(PrefManager.MODE_PARENT);
        pref.setLocked(false);
        pref.setHomeAliasEnabled(this, false);
        pref.stopTimer();
        // 停服务会走 onDestroy → hideOverlay()，悬浮窗一并移除
        stopService(new Intent(this, LockService.class));
        toast("已切换到家长模式，锁屏已解除，不再弹题");
        updateStatus();
    }

    /**
     * 进入孩子模式并立即锁屏。
     * Android 10+ 后台不能直接弹 Activity，必须先确认悬浮窗权限，
     * 否则到点不会自动出现答题界面、按 HOME 也能绕过。
     */
    private void enterChildModeAndLock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("建议开启「悬浮窗」权限")
                    .setMessage("从 Android 10 开始，系统会拦截后台自动弹出答题界面。\n"
                            + "开启「悬浮窗/显示在其他应用上层」后，到点会用全屏悬浮窗挡住电视，点击即可答题；"
                            + "不开启时，按 HOME 也可能绕过锁屏。")
                    .setPositiveButton("去开启", (d, w) -> openOverlaySettings())
                    .setNegativeButton("仍要锁屏", (d, w) -> doEnterChildModeAndLock())
                    .show();
            return;
        }
        doEnterChildModeAndLock();
    }

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            // 少数电视没有直接的悬浮窗授权页，退回应用详情页让用户手动开
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e2) {
                toast("请在系统设置 → 应用权限中开启「悬浮窗/显示在其他应用上层」");
            }
        }
    }

    private boolean needsOverlayPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this);
    }

    /**
     * 打开 App 时检查“开机自动锁屏”需要的三项设置：
     * 悬浮窗权限（可检测）、电池省电白名单（可检测）、开机自启动（无法检测，只能引导）。
     * 每次打开 App 只提示一次；三项都正常时不再打扰。
     */
    private void promptPermissionsIfNeeded() {
        if (permissionPromptShown) return;
        boolean needOverlay = needsOverlayPermission();
        boolean needBattery = !isIgnoringBatteryOptimizations();
        // “开机自启动”无法检测，至少保证首次启动时把入口展示过一次
        boolean autoStartGuideSeen = getSharedPreferences("kids_lock_ui", MODE_PRIVATE)
                .getBoolean("autostart_guide_seen", false);
        if (!needOverlay && !needBattery && autoStartGuideSeen) return;

        permissionPromptShown = true;
        getSharedPreferences("kids_lock_ui", MODE_PRIVATE)
                .edit().putBoolean("autostart_guide_seen", true).apply();
        showPermissionGuideDialog(needOverlay, needBattery);
    }

    /** 合并的权限引导弹窗：三个按钮分别去对应设置页。 */
    private void showPermissionGuideDialog(boolean needOverlay, boolean needBattery) {
        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(20), dp(28), dp(20));
        box.setMinimumWidth(dp(520));

        TextView title = new TextView(this);
        title.setText("开机自动锁屏还需要以下设置");
        title.setTextColor(getResources().getColor(R.color.accent));
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView desc = new TextView(this);
        desc.setText("建议全部开启，否则关机重启或到点后可能无法自动弹题：");
        desc.setTextColor(getResources().getColor(R.color.text_secondary));
        desc.setTextSize(16);
        desc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.setMargins(0, dp(8), 0, dp(12));
        box.addView(desc, descLp);

        if (needOverlay) {
            box.addView(guideButton("① 开启「悬浮窗」权限", v -> openOverlaySettings()));
        }
        if (needBattery) {
            box.addView(guideButton("② 加入电池省电白名单", v -> requestIgnoreBatteryOptimizations()));
        }
        box.addView(guideButton("③ 允许「开机自启动」", v -> openAutoStartSettings()));

        Button close = guideButton("稍后再说", v -> {
            if (holder[0] != null) holder[0].dismiss();
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        closeLp.setMargins(dp(4), dp(12), dp(4), dp(4));
        box.addView(close, closeLp);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(box).create();
        holder[0] = dialog;
        dialog.show();
    }

    private Button guideButton(String text, android.view.View.OnClickListener listener) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        b.setText(text);
        b.setTextSize(18);
        b.setTextColor(getResources().getColor(R.color.btn_text));
        b.setBackgroundResource(R.drawable.btn_settings_bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) {
            toast("本应用已在省电白名单中，无需设置");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("加入电池省电白名单")
                .setMessage("为避免锁屏服务被系统清理，请允许本应用后台运行。\n\n"
                        + "一般路径：设置 → 应用 → 绿鸟儿童锁屏大师 → 电池/省电 → 允许后台运行（或“不限制”）。\n\n"
                        + "电视系统如果不支持自动跳转，可点“打开应用详情”手动设置。")
                .setPositiveButton("尝试打开系统设置", (d, w) -> openBatterySettings())
                .setNeutralButton("打开应用详情", (d, w) -> openAppDetailsSettings())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 先试系统的“忽略电池优化”确认框，再试电池优化列表；都不行就退到应用详情。 */
    private void openBatterySettings() {
        try {
            batterySettingsAttempted = true;
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
            return;
        } catch (Exception e) {
            batterySettingsAttempted = false;
            Log.w(TAG, "battery request page unavailable", e);
        }
        try {
            batterySettingsAttempted = true;
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        } catch (Exception e) {
            batterySettingsAttempted = false;
            Log.w(TAG, "battery settings list unavailable", e);
        }
        toast("电视系统没有电池设置页，已打开应用详情，请手动允许后台运行");
        openAppDetailsSettings();
    }

    private void openAppDetailsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("请到电视「设置 → 应用」中找到本应用，手动允许后台运行");
        }
    }

    /** 开机自启动没有统一 API：先试小米安全中心，再退回应用详情页。 */
    private void openAutoStartSettings() {
        try {
            Intent miui = new Intent();
            miui.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            startActivity(miui);
            return;
        } catch (Exception ignored) {
        }
        toast("若没有自启动选项，请到电视「安全中心 → 自启动管理」中允许本应用");
        openAppDetailsSettings();
    }

    private void doEnterChildModeAndLock() {
        pref.setMode(PrefManager.MODE_CHILD);
        pref.setLocked(true);
        pref.setHomeAliasEnabled(this, true);
        pref.stopTimer();
        startLockService();
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(lockIntent);
    }

    /**
     * 设置家长 PIN：改用遥控器九宫格数字键，而不是 EditText + 系统输入法。
     * TV 上 EditText 弹出软键盘后，每输入一位焦点会被对话框按钮抢走（表现为“一输数字就跳到确认键”），
     * 所以这里和锁屏的 PIN 键盘一样，全程只用可聚焦按钮，不依赖输入法。
     */
    private ComponentName adminComponent() {
        return new ComponentName(this, KidsLockDeviceAdminReceiver.class);
    }

    private boolean isAdminActive() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(adminComponent());
    }

    /**
     * 开启/关闭“设备管理器”防卸载保护。
     * 注意：普通应用无法真正禁止卸载；启用后系统会要求先停用设备管理器才能卸载。
     */
    private void toggleAdminProtection() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = adminComponent();
        if (dpm == null) {
            toast("当前电视不支持设备管理器");
            return;
        }

        if (dpm.isAdminActive(admin)) {
            new AlertDialog.Builder(this)
                    .setTitle("关闭防卸载保护？")
                    .setMessage("关闭后，本应用会像普通应用一样可以被直接卸载。")
                    .setPositiveButton("关闭", (d, w) -> {
                        try {
                            dpm.removeActiveAdmin(admin);
                            ownerUninstallBlocked = false;
                            toast("已关闭防卸载保护");
                        } catch (Exception e) {
                            toast("取消失败，请到系统设置 → 设备管理器中停用");
                        }
                        updateStatus();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        Intent intent = new Intent(Settings.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "开启后，卸载本应用前必须先停用设备管理器，用于防止孩子误卸载。");
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("系统没有设备管理器页面，无法开启防卸载保护");
        }
    }

    private void showPinDialog() {
        final StringBuilder pin = new StringBuilder();
        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(32), dp(20), dp(32), dp(20));
        box.setMinimumWidth(dp(380));

        TextView title = new TextView(this);
        title.setText("设置家长PIN码（4位数字）");
        title.setTextColor(getResources().getColor(R.color.accent));
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        final TextView display = new TextView(this);
        display.setText("○  ○  ○  ○");
        display.setTextColor(getResources().getColor(R.color.text_primary));
        display.setTextSize(28);
        display.setLetterSpacing(0.3f);
        display.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams displayLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        displayLp.setMargins(0, dp(16), 0, dp(16));
        box.addView(display, displayLp);

        // 底部“保存”按钮，输入满 4 位后才可用
        final Button save = pinPadButton("保存");
        save.setEnabled(false);
        save.setAlpha(0.5f);
        save.setOnClickListener(v -> {
            if (pin.length() != 4) return;
            pref.setParentPin(pin.toString());
            toast("PIN码已设置");
            if (holder[0] != null) holder[0].dismiss();
        });

        // 1~9 三行
        for (int row = 0; row < 3; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            for (int col = 0; col < 3; col++) {
                final int digit = row * 3 + col + 1;
                Button b = pinPadButton(String.valueOf(digit));
                b.setOnClickListener(v -> appendPinDigit(pin, digit, display, save));
                line.addView(b);
            }
            box.addView(line);
        }

        // 清除 | 0 | 取消
        LinearLayout lastRow = new LinearLayout(this);
        lastRow.setOrientation(LinearLayout.HORIZONTAL);
        lastRow.setGravity(Gravity.CENTER);

        Button clear = pinPadButton("清除");
        clear.setOnClickListener(v -> {
            pin.setLength(0);
            updatePinDots(display, 0);
            save.setEnabled(false);
            save.setAlpha(0.5f);
        });
        lastRow.addView(clear);

        Button zero = pinPadButton("0");
        zero.setOnClickListener(v -> appendPinDigit(pin, 0, display, save));
        lastRow.addView(zero);

        Button cancel = pinPadButton("取消");
        cancel.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
        });
        lastRow.addView(cancel);
        box.addView(lastRow);

        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        saveLp.setMargins(dp(4), dp(12), dp(4), dp(4));
        box.addView(save, saveLp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .create();
        holder[0] = dialog;
        dialog.show();
    }

    /** PIN 键盘按钮（遥控器可聚焦）。 */
    private Button pinPadButton(String text) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(76), dp(56));
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        b.setText(text);
        b.setTextSize(20);
        b.setTextColor(getResources().getColor(R.color.btn_text));
        b.setBackgroundResource(R.drawable.btn_pin_bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setAllCaps(false);
        return b;
    }

    /** 追加一位 PIN：满 4 位后启用“保存”并把焦点移过去（输入过程中焦点不会乱跳）。 */
    private void appendPinDigit(StringBuilder pin, int digit, TextView display, Button save) {
        if (pin.length() >= 4) return;
        pin.append(digit);
        updatePinDots(display, pin.length());
        if (pin.length() == 4) {
            save.setEnabled(true);
            save.setAlpha(1f);
            save.post(() -> save.requestFocus());
        }
    }

    private void updatePinDots(TextView display, int filled) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append("  ");
            sb.append(i < filled ? "●" : "○");
        }
        display.setText(sb.toString());
    }

    // ==================== 忘记 PIN 的解锁问题 ====================

    /** 年份输入回调（不用 java.util.function，兼容低版本 Android）。 */
    private interface YearCallback {
        void onYearEntered(int year);
    }

    /**
     * 设置“忘记 PIN”的两道解锁问题：必须依次录入母亲、父亲两个出生年份，
     * 两个都录完才保存，避免只设置一半。
     */
    private void showSecurityQuestionsDialog() {
        showYearEntryDialog("解锁问题 1/2：母亲的出生年份", "下一步", motherYear ->
                showYearEntryDialog("解锁问题 2/2：父亲的出生年份", "保存", fatherYear -> {
                    pref.setSecurityAnswers(String.valueOf(motherYear), String.valueOf(fatherYear));
                    toast("解锁问题已设置：母亲 " + motherYear + " 年，父亲 " + fatherYear + " 年");
                }));
    }

    /** 通用年份录入弹窗（遥控器数字键盘，不弹输入法）。 */
    private void showYearEntryDialog(String titleText, String actionText, YearCallback callback) {
        final StringBuilder year = new StringBuilder();
        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(32), dp(20), dp(32), dp(20));
        box.setMinimumWidth(dp(380));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(getResources().getColor(R.color.accent));
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("请输入 4 位出生年份，例如 1975");
        hint.setTextColor(getResources().getColor(R.color.text_secondary));
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(8), 0, dp(8));
        box.addView(hint, hintLp);

        final TextView display = new TextView(this);
        display.setTextColor(getResources().getColor(R.color.text_primary));
        display.setTextSize(28);
        display.setLetterSpacing(0.3f);
        display.setGravity(Gravity.CENTER);
        updatePinDots(display, 0);
        LinearLayout.LayoutParams displayLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        displayLp.setMargins(0, dp(8), 0, dp(12));
        box.addView(display, displayLp);

        final Button action = pinPadButton(actionText);
        action.setEnabled(false);
        action.setAlpha(0.5f);
        action.setOnClickListener(v -> {
            if (year.length() != 4) return;
            int value = Integer.parseInt(year.toString());
            if (value < 1900 || value > 2099) {
                toast("年份需在 1900–2099 之间");
                year.setLength(0);
                updatePinDots(display, 0);
                action.setEnabled(false);
                action.setAlpha(0.5f);
                return;
            }
            if (holder[0] != null) holder[0].dismiss();
            callback.onYearEntered(value);
        });

        // 1~9 三行
        for (int row = 0; row < 3; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            for (int col = 0; col < 3; col++) {
                final int digit = row * 3 + col + 1;
                Button b = pinPadButton(String.valueOf(digit));
                b.setOnClickListener(v -> appendPinDigit(year, digit, display, action));
                line.addView(b);
            }
            box.addView(line);
        }

        // 清除 | 0 | 取消
        LinearLayout lastRow = new LinearLayout(this);
        lastRow.setOrientation(LinearLayout.HORIZONTAL);
        lastRow.setGravity(Gravity.CENTER);

        Button clear = pinPadButton("清除");
        clear.setOnClickListener(v -> {
            year.setLength(0);
            updatePinDots(display, 0);
            action.setEnabled(false);
            action.setAlpha(0.5f);
        });
        lastRow.addView(clear);

        Button zero = pinPadButton("0");
        zero.setOnClickListener(v -> appendPinDigit(year, 0, display, action));
        lastRow.addView(zero);

        Button cancel = pinPadButton("取消");
        cancel.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
        });
        lastRow.addView(cancel);
        box.addView(lastRow);

        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        actionLp.setMargins(dp(4), dp(12), dp(4), dp(4));
        box.addView(action, actionLp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .create();
        holder[0] = dialog;
        dialog.show();
    }

    private void showServerDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        final EditText etUrl = new EditText(this);
        etUrl.setHint("服务器地址，如 http://192.168.1.10:8000");
        etUrl.setText(pref.getServerUrl());
        etUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        final EditText etChild = new EditText(this);
        etChild.setHint("孩子ID（自定义，如 ming）");
        etChild.setText(pref.getChildId());
        etChild.setInputType(InputType.TYPE_CLASS_TEXT);

        box.addView(etUrl);
        box.addView(etChild);

        new AlertDialog.Builder(this)
                .setTitle("配置在线题库（NAS 后台）")
                .setView(box)
                .setPositiveButton("保存并测试", (d, w) -> {
                    String url = etUrl.getText().toString().trim();
                    String child = etChild.getText().toString().trim();
                    if (url.isEmpty() || child.isEmpty()) {
                        toast("地址与孩子ID都不能为空");
                        return;
                    }
                    pref.setServerUrl(url);
                    pref.setChildId(child);
                    pref.setOnline(true);
                    updateStatus();
                    testServer(url, child);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void testServer(final String url, final String child) {
        final int grade = pref.getGrade();
        final String edition = pref.getEdition();
        toast("测试连接中…");
        new Thread(() -> {
            List<QuestionBank.Question> r = QuestionBank.fetchRoundFromServer(url, child, grade, edition, 1);
            runOnUiThread(() -> toast(r != null && !r.isEmpty() ? "连接成功，已能拉取题目" : "连接失败，请检查地址/后台是否启动"));
        }).start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void startLockService() {
        Intent serviceIntent = new Intent(this, LockService.class);
        serviceIntent.putExtra("action", "start_timer");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void updateStatus() {
        // 模式
        boolean child = pref.isChildMode();
        tvMode.setText("当前模式：" + (child ? "孩子模式（答题解锁）" : "家长模式（不弹题）"));

        // 悬浮窗权限（Android 10+ 自动锁屏依赖它）
        if (tvOverlayHint != null) {
            boolean canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(this);
            if (canOverlay) {
                tvOverlayHint.setText("悬浮窗权限：已开启（点击管理开机锁屏权限）");
                tvOverlayHint.setTextColor(getResources().getColor(R.color.correct_green));
            } else {
                tvOverlayHint.setText("悬浮窗权限：未开启（点击设置悬浮窗/自启/省电白名单）");
                tvOverlayHint.setTextColor(getResources().getColor(R.color.wrong_red));
            }
        }

        // 年级 / 科目
        int grade = pref.getGrade();
        String gradeLine = "当前年级：" + gradeLabel(grade);
        // 内置题库目前只录了 5 年级：选了其它年级时提前告知，锁屏界面会自动就近取题
        if (!bank.hasQuestionsForGrade(grade)) {
            int fallbackGrade = bank.nearestGradeWithQuestions(grade);
            if (fallbackGrade > 0) {
                gradeLine += "\n⚠ 该年级暂无内置题目，锁屏将改用" + fallbackGrade + "年级题目";
            }
        }
        tvGrade.setText(gradeLine);
        List<String> subs = bank.gradeSubjects(grade);
        tvSubjects.setText("科目范围：" + join(subs, "、"));

        // 间隔 / 题数
        // 【测试用】选中 10 秒时这里会显示"10 秒"，方便确认当前处于测试档位
        tvInterval.setText("出题间隔（自由观看）：" + formatInterval(pref.getIntervalSeconds()));
        tvUnlockCount.setText("解锁题数：" + pref.getUnlockCount() + " 道连续答对");

        // 状态
        if (pref.isLocked()) {
            tvStatus.setText("当前状态：已锁屏");
            tvStatus.setTextColor(getResources().getColor(R.color.wrong_red));
            tvRemaining.setText("请答题解锁");
        } else if (pref.isTimerActive()) {
            long remaining = pref.getRemainingMillis();
            if (remaining <= 0) {
                tvStatus.setText("当前状态：时间到");
                tvStatus.setTextColor(getResources().getColor(R.color.wrong_red));
            } else {
                long totalSec = remaining / 1000;
                tvStatus.setText("当前状态：自由观看中");
                tvStatus.setTextColor(getResources().getColor(R.color.correct_green));
                tvRemaining.setText("距下次答题：" + String.format("%02d:%02d", totalSec / 60, totalSec % 60));
            }
        } else {
            tvStatus.setText("当前状态：待命");
            tvStatus.setTextColor(getResources().getColor(R.color.text_secondary));
            tvRemaining.setText("");
        }

        // 防卸载保护状态
        if (btnAdminProtect != null && tvAdminHint != null) {
            boolean adminActive = isAdminActive();
            btnAdminProtect.setText(adminActive
                    ? "关闭防卸载保护（关闭后才能卸载）"
                    : "开启防卸载保护（设备管理器）");
            tvAdminHint.setText(adminActive
                    ? "防卸载保护：已开启（卸载前需先在系统设置里停用设备管理器）"
                    : "防卸载保护：未开启（孩子可以直接卸载本应用）");
            tvAdminHint.setTextColor(getResources().getColor(
                    adminActive ? R.color.correct_green : R.color.wrong_red));

            // 如果本机已配置为 Device Owner，可以真正禁止卸载（普通设备做不到）
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            try {
                if (!adminActive) {
                    ownerUninstallBlocked = false;
                } else if (!ownerUninstallBlocked && dpm != null
                        && dpm.isDeviceOwnerApp(getPackageName())) {
                    dpm.setUninstallBlocked(adminComponent(), getPackageName(), true);
                    ownerUninstallBlocked = true;
                }
            } catch (Exception ignored) {
            }
        }

        // 在线题库状态
        if (tvOnline != null) {
            if (pref.isOnline() && !pref.getServerUrl().isEmpty()) {
                tvOnline.setText("在线题库：开启 · " + pref.getServerUrl() + " · 孩子ID=" + pref.getChildId());
            } else {
                tvOnline.setText("在线题库：关闭（使用内置题库）");
            }
        }
    }

    /** 间隔文案：不足 60 秒按秒显示（目前只有【测试专用】的 10 秒档会走到这里）。 */
    private static String formatInterval(int seconds) {
        if (seconds < 60) return seconds + " 秒";
        return (seconds / 60) + " 分钟";
    }

    private static String gradeLabel(int grade) {
        if (grade <= 6) return "小学" + grade + "年级";
        switch (grade) {
            case 7: return "初一";
            case 8: return "初二";
            case 9: return "初三";
            default: return grade + "年级";
        }
    }

    /** 手动拼接（String.join 需 API 26，Android 6.0/API 23 不可用）。 */
    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
