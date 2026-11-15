package com.kidslock.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 锁屏答题界面（改造版）：
 * - 展示一道题（语文/数学/英语，按年级/教材/进度抽取），4 个选项。
 * - 一轮 requiredCount 道题；任一道答错 → 展示参考答案与解析 → 重新开始本轮。
 * - 一轮全对 → 解锁并进入 interval 秒（正常为分钟换算）自由观看，到点再次锁屏。
 * - 家长 PIN 解锁 → 切换到家长模式（不再弹题）。
 * - 按 HOME/BACK 无法绕过；断电重启仍锁（由 PrefManager + BootReceiver 保证）。
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String TAG = "LockScreen";

    /** 答题界面是否在前台（供 LockService 判断要不要加悬浮窗，避免盖住题目）。 */
    private static boolean sShowing = false;

    public static boolean isShowing() {
        return sShowing;
    }

    /**
     * 【隐藏总解锁码】家长同时忘记 PIN 和解锁问题时的最终兜底。
     * 只存在于代码里，不在任何界面、提示或文档中显示；普通 PIN 为 4 位，它是 8 位。
     */
    private static final String HIDDEN_MASTER_PIN = "31415926";
    /** 输入内容恰好是隐藏码前缀时，允许的最长停顿（给按键慢的家长留足时间）。 */
    private static final int PIN_HIDDEN_PREFIX_TIMEOUT_MS = 6000;
    private PrefManager pref;
    private QuestionBank bank;

    // 本轮状态
    private List<QuestionBank.Question> round;
    private int roundIndex = 0;
    private int requiredCount;
    private QuestionBank.Question current;
    private List<String> currentOptions;
    /** 题库兜底提示（如“5 年级暂无题，已改用 4 年级题”），为空表示正常。 */
    private String bankNotice = "";

    // 答题结果（用于回传 NAS 后台更新掌握度）
    private final JSONArray resultsJson = new JSONArray();

    // Views
    private View layoutMain;
    private TextView tvSubject;
    private TextView tvStem;
    private TextView tvProgress;
    private TextView tvFeedback;
    private Button[] optionButtons;

    // PIN
    private View layoutPin;
    private TextView tvPinDisplay;
    private StringBuilder pinInput = new StringBuilder();

    // 忘记密码：回答“母亲/父亲出生年份”，任一答对即可解锁
    private boolean recoveryMode = false;
    private int recoveryQuestion = 0; // 1=母亲的出生年份 2=父亲的出生年份
    private final StringBuilder recoveryInput = new StringBuilder();
    private TextView tvPinTitle;
    private View layoutRecoveryQuestions;
    private Button btnForgotPin;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 4 位 PIN 输错后的延迟清理：留出继续输入 8 位隐藏码的时间。 */
    private final Runnable pinWrongRunnable = new Runnable() {
        @Override
        public void run() {
            handlePinWrong();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock_screen);

        pref = new PrefManager(this);
        bank = QuestionBank.loadFromAssets(this);
        requiredCount = pref.getUnlockCount();

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );
        hideSystemUI();

        initViews();
        loadRound();
    }

    private void initViews() {
        layoutMain = findViewById(R.id.layoutMain);
        tvSubject = findViewById(R.id.tvSubject);
        tvStem = findViewById(R.id.tvStem);
        tvProgress = findViewById(R.id.tvProgress);
        tvFeedback = findViewById(R.id.tvFeedback);

        optionButtons = new Button[]{
                findViewById(R.id.btnOption1),
                findViewById(R.id.btnOption2),
                findViewById(R.id.btnOption3),
                findViewById(R.id.btnOption4)
        };
        for (int i = 0; i < optionButtons.length; i++) {
            final int idx = i;
            optionButtons[i].setOnClickListener(v -> onOptionSelected(idx));
        }

        findViewById(R.id.btnParentUnlock).setOnClickListener(v -> showPinPad());

        // PIN 界面
        layoutPin = findViewById(R.id.layoutPin);
        tvPinDisplay = findViewById(R.id.tvPinDisplay);
        tvPinTitle = findViewById(R.id.tvPinTitle);
        layoutRecoveryQuestions = findViewById(R.id.layoutRecoveryQuestions);
        btnForgotPin = findViewById(R.id.btnForgotPin);

        int[] pinButtonIds = {
                R.id.btnPin0, R.id.btnPin1, R.id.btnPin2, R.id.btnPin3,
                R.id.btnPin4, R.id.btnPin5, R.id.btnPin6, R.id.btnPin7,
                R.id.btnPin8, R.id.btnPin9
        };
        for (int i = 0; i <= 9; i++) {
            final int digit = i;
            findViewById(pinButtonIds[i]).setOnClickListener(v -> onPinDigit(digit));
        }
        findViewById(R.id.btnPinClear).setOnClickListener(v -> {
            handler.removeCallbacks(pinWrongRunnable);
            if (recoveryMode) {
                recoveryInput.setLength(0);
                updateRecoveryDisplay();
            } else {
                pinInput.setLength(0);
                updatePinDisplay();
            }
        });
        findViewById(R.id.btnPinBack).setOnClickListener(v -> {
            handler.removeCallbacks(pinWrongRunnable);
            if (recoveryMode) {
                exitRecoveryMode();
                return;
            }
            layoutPin.setVisibility(View.GONE);
            layoutMain.setVisibility(View.VISIBLE);
            pinInput.setLength(0);
            updatePinDisplay();
        });

        // 忘记密码入口：PIN 码右侧
        btnForgotPin.setOnClickListener(v -> {
            if (recoveryMode) {
                exitRecoveryMode();
            } else {
                enterRecoveryMode();
            }
        });
        findViewById(R.id.btnQMother).setOnClickListener(v -> selectRecoveryQuestion(1));
        findViewById(R.id.btnQFather).setOnClickListener(v -> selectRecoveryQuestion(2));
    }

    // ==================== 出题 ====================

    private void loadRound() {
        bankNotice = "";
        // 在线模式：后台线程拉题（Android 6+ 禁止主线程网络）；失败自动回退内置题库
        if (pref.isOnline() && !pref.getServerUrl().isEmpty() && !pref.getChildId().isEmpty()) {
            final String serverUrl = pref.getServerUrl();
            final String childId = pref.getChildId();
            final String edition = pref.getEdition();
            final int grade = pref.getGrade();
            tvStem.setText("正在加载题目…");
            tvProgress.setText("");
            for (Button b : optionButtons) b.setVisibility(View.GONE);
            new Thread(() -> {
                final List<QuestionBank.Question> remote =
                        QuestionBank.fetchRoundFromServer(serverUrl, childId, grade, edition, requiredCount);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    // 在线接口可能返回空题干/空选项的数据，直接展示会出现“锁屏没有题目”，
                    // 这里先清洗，全部无效则回退内置题库。
                    List<QuestionBank.Question> validRemote = validQuestions(remote);
                    if (!validRemote.isEmpty()) {
                        round = validRemote;
                        roundIndex = 0;
                        loadCurrent();
                    } else {
                        loadLocalRound(); // 网络失败/数据无效 → 回退内置题库，保证离线可用
                    }
                });
            }).start();
        } else {
            loadLocalRound();
        }
    }

    /** 过滤掉题干或选项缺失的题（在线接口数据异常时兜底）。 */
    private List<QuestionBank.Question> validQuestions(List<QuestionBank.Question> src) {
        List<QuestionBank.Question> out = new ArrayList<>();
        if (src == null) return out;
        for (QuestionBank.Question q : src) {
            if (q.stem != null && !q.stem.trim().isEmpty()
                    && q.options != null && q.options.size() >= 2 && q.options.size() <= 4) {
                out.add(q);
            }
        }
        return out;
    }

    /** 内置题库（离线）出题。 */
    private void loadLocalRound() {
        int grade = pref.getGrade();
        List<String> subjects = bank.gradeSubjects(grade);
        round = bank.getRound(grade, subjects, requiredCount, pref.getMasteryJson());
        bankNotice = "";

        // 兜底：内置题库目前只录了 5 年级。家长把年级改成 1~4/6~9 后，
        // 原来会直接显示“暂无题目”并锁死答题区（孩子只能找家长输 PIN）。
        // 这里改为就近取一个有题的年级，并在进度行标明，避免锁屏空白。
        if (round.isEmpty() && bank.size() > 0) {
            int fallbackGrade = bank.nearestGradeWithQuestions(grade);
            if (fallbackGrade > 0) {
                round = bank.getRound(fallbackGrade, bank.gradeSubjects(fallbackGrade),
                        requiredCount, pref.getMasteryJson());
                bankNotice = "（" + grade + "年级暂无题库，已改用" + fallbackGrade + "年级题）";
                Log.w(TAG, "grade " + grade + " has no questions, fallback to " + fallbackGrade);
            }
        }

        if (round.isEmpty()) {
            // 题库为空（不该发生）：避免把孩子锁死，提示家长
            tvStem.setText(bank.size() == 0
                    ? "题库加载失败，请检查内置题库文件。"
                    : grade + "年级暂无内置题目（当前题库只覆盖部分年级），请到设置页调整年级，或联系家长添加题库。");
            tvProgress.setText("");
            for (Button b : optionButtons) b.setVisibility(View.GONE);
            return;
        }
        roundIndex = 0;
        loadCurrent();
    }

    private void loadCurrent() {
        current = round.get(roundIndex);
        currentOptions = current.options;
        tvSubject.setText(current.subject + " · " + current.unit);
        tvStem.setText(current.stem);
        // 题干较长（如英语）时缩小字号，避免溢出
        tvStem.setTextSize(current.stem.length() > 16 ? 28 : 40);

        // 选项长短差异很大（如“人民民主专政的社会主义国家”），按最长选项自适应字号，
        // 避免长选项在固定高度的按钮里被截断。中文按 1 个字宽、英文按 0.55 估算。
        double maxOptionWidth = 0;
        for (String o : currentOptions) {
            if (o == null) continue;
            double w = 0;
            for (int k = 0; k < o.length(); k++) {
                w += o.charAt(k) < 128 ? 0.55 : 1.0;
            }
            maxOptionWidth = Math.max(maxOptionWidth, w);
        }
        float optionSp = (float) Math.min(20.0, 190.0 / Math.max(1.0, maxOptionWidth));

        // 选项数量不固定（2~4 个）：多余的按钮必须隐藏，否则会残留上一题的旧选项
        for (int i = 0; i < optionButtons.length; i++) {
            if (i < currentOptions.size()) {
                optionButtons[i].setVisibility(View.VISIBLE);
                optionButtons[i].setText(currentOptions.get(i));
                optionButtons[i].setTextSize(optionSp);
                optionButtons[i].setBackgroundResource(R.drawable.btn_option_bg);
                optionButtons[i].setEnabled(true);
            } else {
                optionButtons[i].setVisibility(View.GONE);
                optionButtons[i].setEnabled(false);
            }
        }
        updateProgress();
        tvFeedback.setVisibility(View.GONE);
    }

    private void onOptionSelected(int index) {
        if (current == null || index >= currentOptions.size()) return;
        for (Button b : optionButtons) b.setEnabled(false);

        String selected = currentOptions.get(index);
        boolean correct = selected.equals(current.answer);
        recordResult(current.knowledge, correct);

        if (correct) {
            pref.recordAnswer(current.knowledge, true);
            optionButtons[index].setBackgroundResource(R.drawable.btn_option_correct);
            tvFeedback.setText("答对啦！");
            tvFeedback.setTextColor(getResources().getColor(R.color.correct_green));
            tvFeedback.setVisibility(View.VISIBLE);

            roundIndex++;
            if (roundIndex >= round.size()) {
                handler.postDelayed(this::unlockChild, 700); // 本轮全对 → 解锁
            } else {
                handler.postDelayed(this::loadCurrent, 900);
            }
        } else {
            pref.recordAnswer(current.knowledge, false);
            optionButtons[index].setBackgroundResource(R.drawable.btn_option_wrong);
            for (int i = 0; i < currentOptions.size(); i++) {
                if (currentOptions.get(i).equals(current.answer)) {
                    optionButtons[i].setBackgroundResource(R.drawable.btn_option_correct);
                    break;
                }
            }
            // 反馈 + 参考答案/解析
            String fb = "答错了。正确答案：" + current.answer + "。";
            if (current.explanation != null && !current.explanation.isEmpty()) {
                fb += " " + current.explanation;
            }
            tvFeedback.setText(fb);
            tvFeedback.setTextColor(getResources().getColor(R.color.wrong_red));
            tvFeedback.setVisibility(View.VISIBLE);
            // 重新开始本轮
            handler.postDelayed(this::loadRound, 2600);
        }
    }

    private void updateProgress() {
        int total = round != null ? round.size() : requiredCount;
        tvProgress.setText("第 " + (roundIndex + 1) + "/" + total + " 题 · 本轮全部答对即可解锁"
                + bankNotice);
    }

    /** 记录一次答题结果，用于本轮结束后回传后台。 */
    private void recordResult(String knowledge, boolean correct) {
        try {
            JSONObject r = new JSONObject();
            r.put("knowledge", knowledge == null ? "" : knowledge);
            r.put("correct", correct);
            resultsJson.put(r);
        } catch (Exception ignore) {
        }
    }

    // ==================== 解锁 ====================

    /** 孩子模式：本轮全对后解锁，进入 interval 秒自由观看（测试档为 10 秒），到点再锁。 */
    private void unlockChild() {
        pref.setLocked(false);
        pref.setHomeAliasEnabled(this, false);
        pref.resetPinFailures();
        submitIfOnline();
        // 启动自由窗口计时（单位秒：正式为分钟×60，测试模式下即 10 秒）
        pref.startTimerSeconds(pref.getIntervalSeconds());
        LockService.requestFreeWindow(this);        // 隐藏悬浮窗，继续守护（到点再锁）
        finishAffinity();
    }

    /** 在线模式：把本轮答题结果回传后台更新掌握度（后台线程，静默失败）。 */
    private void submitIfOnline() {
        if (!pref.isOnline() || pref.getServerUrl().isEmpty() || pref.getChildId().isEmpty()) return;
        if (resultsJson.length() == 0) return;
        final String serverUrl = pref.getServerUrl();
        final String childId = pref.getChildId();
        final String payload = resultsJson.toString();
        new Thread(() -> QuestionBank.submitResults(serverUrl, childId, payload)).start();
    }

    /**
     * 家长 PIN 解锁：直接切换到家长模式（不再弹题）。
     * 效果与设置页“操作-切换到家长模式（解锁）”一致，省去回主界面再手动切换的步骤。
     */
    private void unlockToParentMode() {
        pref.setMode(PrefManager.MODE_PARENT);
        pref.setLocked(false);
        pref.setHomeAliasEnabled(this, false);
        pref.stopTimer();
        pref.resetPinFailures();
        LockService.requestUnlock(this); // 停前台服务 + 移除悬浮窗
        Toast.makeText(this, "已切换到家长模式，不再弹题", Toast.LENGTH_SHORT).show();
        finishAffinity();
    }

    // ==================== 家长PIN ====================

    private void showPinPad() {
        handler.removeCallbacks(pinWrongRunnable);
        exitRecoveryMode(); // 从主界面进入时总是回到 PIN 模式
        layoutMain.setVisibility(View.GONE);
        layoutPin.setVisibility(View.VISIBLE);
        pinInput.setLength(0);
        updatePinDisplay();
        findViewById(R.id.btnPin1).requestFocus();
    }

    private void onPinDigit(int digit) {
        if (recoveryMode) {
            if (recoveryQuestion == 0) {
                Toast.makeText(this, "请先选择要回答的问题", Toast.LENGTH_SHORT).show();
                return;
            }
            if (recoveryInput.length() < 4) {
                recoveryInput.append(digit);
                updateRecoveryDisplay();
                if (recoveryInput.length() == 4) {
                    handler.postDelayed(this::checkRecoveryAnswer, 200);
                }
            }
            return;
        }
        if (pinInput.length() >= HIDDEN_MASTER_PIN.length()) return;

        handler.removeCallbacks(pinWrongRunnable);
        pinInput.append(digit);
        updatePinDisplay();

        int len = pinInput.length();
        if (len < 4) {
            // 不足 4 位：不启动任何超时，随便输多慢都不会被清空
            return;
        }

        if (len == 4) {
            if (pinInput.toString().equals(pref.getParentPin())) {
                if (pref.isPinLocked()) {
                    long remainingSec = pref.getPinLockRemainingMillis() / 1000;
                    Toast.makeText(this, "尝试次数过多，请 " + remainingSec + " 秒后再试",
                            Toast.LENGTH_SHORT).show();
                    pinInput.setLength(0);
                    updatePinDisplay();
                } else {
                    unlockToParentMode();
                }
                return;
            }
            if (!HIDDEN_MASTER_PIN.startsWith(pinInput.toString())) {
                // 普通 4 位 PIN 输错：立即提示并清空（和以前行为一致）
                handlePinWrong();
            } else {
                // 恰好是隐藏码的前 4 位：先别清空，给足时间继续输入
                handler.postDelayed(pinWrongRunnable, PIN_HIDDEN_PREFIX_TIMEOUT_MS);
            }
            return;
        }

        if (len == 8) {
            // 隐藏总解锁码（只做比较，不在界面显示）
            if (pinInput.toString().equals(HIDDEN_MASTER_PIN)) {
                unlockToParentMode();
            } else {
                handlePinWrong();
            }
            return;
        }

        // 5~7 位：只有隐藏码前缀才会走到这里（否则上一轮已经清空）
        if (!HIDDEN_MASTER_PIN.startsWith(pinInput.toString())) {
            handlePinWrong();
        } else {
            handler.postDelayed(pinWrongRunnable, PIN_HIDDEN_PREFIX_TIMEOUT_MS);
        }
    }

    private void handlePinWrong() {
        handler.removeCallbacks(pinWrongRunnable);
        pref.recordPinFailure();
        Toast.makeText(this, "PIN码错误", Toast.LENGTH_SHORT).show();
        pinInput.setLength(0);
        updatePinDisplay();
    }

    private void updatePinDisplay() {
        int slots = pinInput.length() > 4 ? HIDDEN_MASTER_PIN.length() : 4;
        tvPinDisplay.setText(pinDots(pinInput.length(), slots));
    }

    private void updateRecoveryDisplay() {
        tvPinDisplay.setText(pinDots(recoveryInput.length(), 4));
    }

    private String pinDots(int filled, int slots) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slots; i++) {
            if (i > 0) sb.append("  ");
            sb.append(i < filled ? "●" : "○");
        }
        return sb.toString();
    }

    // ==================== 忘记密码：回答解锁问题 ====================

    /** 进入“忘记密码”模式：显示两道题，选一题输入 4 位出生年份。 */
    private void enterRecoveryMode() {
        handler.removeCallbacks(pinWrongRunnable);
        recoveryMode = true;
        recoveryQuestion = 0;
        recoveryInput.setLength(0);
        tvPinTitle.setText("忘记密码：请选择一个问题（任一答对即可）");
        layoutRecoveryQuestions.setVisibility(View.VISIBLE);
        pinInput.setLength(0);
        btnForgotPin.setText("返回 PIN 解锁");
        updateRecoveryDisplay();
        findViewById(R.id.btnQMother).requestFocus();
    }

    private void exitRecoveryMode() {
        recoveryMode = false;
        recoveryQuestion = 0;
        recoveryInput.setLength(0);
        if (tvPinTitle != null) {
            tvPinTitle.setText(getString(R.string.enter_pin));
            layoutRecoveryQuestions.setVisibility(View.GONE);
            btnForgotPin.setText("忘记密码？");
        }
        updatePinDisplay();
    }

    private void selectRecoveryQuestion(int question) {
        recoveryQuestion = question;
        recoveryInput.setLength(0);
        updateRecoveryDisplay();
        tvPinTitle.setText(question == 1
                ? "问题一：母亲的出生年份是？"
                : "问题二：父亲的出生年份是？");
        findViewById(R.id.btnPin1).requestFocus();
    }

    /** 任意一题答对即解锁（与 PIN 解锁同样切到家长模式）。 */
    private void checkRecoveryAnswer() {
        if (!recoveryMode || recoveryQuestion == 0) return;
        String expected = recoveryQuestion == 1 ? pref.getMotherYear() : pref.getFatherYear();
        if (recoveryInput.toString().equals(expected)) {
            Toast.makeText(this, "回答正确，已切换到家长模式", Toast.LENGTH_SHORT).show();
            unlockToParentMode();
        } else {
            Toast.makeText(this, "答案不对，可以换另一题再试", Toast.LENGTH_SHORT).show();
            recoveryInput.setLength(0);
            updateRecoveryDisplay();
        }
    }

    // ==================== 防绕过 ====================

    private static final long RELOCK_DELAY_MS = 800;

    private final Runnable reLockRunnable = new Runnable() {
        @Override
        public void run() {
            if (pref.isLocked()) {
                Intent lockIntent = new Intent(LockScreenActivity.this, LockScreenActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(lockIntent);
            }
        }
    };

    @Override
    public void onBackPressed() {
        if (layoutPin.getVisibility() == View.VISIBLE) {
            handler.removeCallbacks(pinWrongRunnable);
            if (recoveryMode) {
                exitRecoveryMode();
                return;
            }
            layoutPin.setVisibility(View.GONE);
            layoutMain.setVisibility(View.VISIBLE);
            pinInput.setLength(0);
            updatePinDisplay();
        }
        // 锁屏主界面按返回不退出
    }

    @Override
    protected void onPause() {
        super.onPause();
        sShowing = false;
        // 被 HOME/返回切走时让服务重新盖上悬浮窗，防止孩子绕过锁屏
        LockService.setLockScreenVisible(false);
        if (pref.isLocked()) {
            handler.postDelayed(reLockRunnable, RELOCK_DELAY_MS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(reLockRunnable);
        hideSystemUI();
        sShowing = true;
        // 答题界面在前台：移走悬浮窗，否则会挡住题目
        LockService.setLockScreenVisible(true);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);
    }
}
