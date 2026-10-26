package com.secure.calcvault;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class CalculatorActivity extends AppCompatActivity {

    private static final int SETUP_NONE = 0;
    private static final int SETUP_AWAITING_REAL_PWD = 1;
    private static final int SETUP_AWAITING_DECOY_PWD = 2;

    private static final String PREFS_UI = "calc_ui_prefs";
    private static final String KEY_HISTORY_JSON = "calc_history_json";
    private static final String KEY_ACCENT_HEX = "calc_accent_hex";
    private static final String KEY_CORNER_DP = "calc_corner_dp";

    private TextView tvDisplay;
    private TextView tvHistory;
    private TextView tvCalcHeaderTitle;
    private HorizontalScrollView scrollDisplay;
    private View keypadContainer;
    private MaterialButton btnHistory;
    private MaterialButton btnThemeSettings;

    private final StringBuilder currentInput = new StringBuilder();
    private final List<String> calculationHistory = new ArrayList<>();
    private int setupState = SETUP_NONE;
    private String pendingRealPassword = null;
    private boolean lastActionWasResult = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calculator);

        tvDisplay = findViewById(R.id.tvDisplay);
        tvHistory = findViewById(R.id.tvHistory);
        tvCalcHeaderTitle = findViewById(R.id.tvCalcHeaderTitle);
        scrollDisplay = findViewById(R.id.scrollDisplay);
        keypadContainer = findViewById(R.id.keypadContainer);
        btnHistory = findViewById(R.id.btnHistory);
        btnThemeSettings = findViewById(R.id.btnThemeSettings);

        loadHistoryFromPrefs();
        applySavedThemeFromPrefs();

        setupNumberButtons();
        setupOperatorButtons();
        setupActionButtons();

        btnHistory.setOnClickListener(v -> showCalculationHistoryDialog());
        btnThemeSettings.setOnClickListener(v -> showThemeCustomizationDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        VaultSession.getInstance().clearSession();
    }

    private void applyCompactDialogWindow(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int dialogWidth = (int) (screenWidth * 0.90f);
            dialog.getWindow().setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showCalculationHistoryDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_calculation_history, null);
        TextView tvEmpty = view.findViewById(R.id.tvHistoryEmptyState);
        ScrollView scrollList = view.findViewById(R.id.scrollHistoryList);
        LinearLayout llContainer = view.findViewById(R.id.llHistoryContainer);
        MaterialButton btnClear = view.findViewById(R.id.btnClearHistoryDialog);
        MaterialButton btnClose = view.findViewById(R.id.btnCloseHistoryDialog);

        SharedPreferences prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE);
        int accentColor = Color.parseColor(prefs.getString(KEY_ACCENT_HEX, "#FF7425"));
        btnClose.setBackgroundTintList(ColorStateList.valueOf(accentColor));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();

        if (calculationHistory.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            scrollList.setVisibility(View.GONE);
            btnClear.setEnabled(false);
            btnClear.setAlpha(0.4f);
        } else {
            tvEmpty.setVisibility(View.GONE);
            scrollList.setVisibility(View.VISIBLE);
            int padH = (int) (12 * getResources().getDisplayMetrics().density);
            int padV = (int) (10 * getResources().getDisplayMetrics().density);

            for (String entry : calculationHistory) {
                TextView row = new TextView(this);
                row.setText(entry);
                row.setTextColor(Color.WHITE);
                row.setTextSize(16f);
                row.setGravity(Gravity.END);
                row.setPadding(padH, padV, padH, padV);
                row.setOnClickListener(v -> {
                    int idx = entry.lastIndexOf('=');
                    if (idx != -1 && idx + 1 < entry.length()) {
                        String val = entry.substring(idx + 1).trim().replace(',', '.');
                        currentInput.setLength(0);
                        currentInput.append(val);
                        updateDisplay(formatForDisplay(val));
                        lastActionWasResult = true;
                    }
                    dialog.dismiss();
                });
                llContainer.addView(row);
            }
        }

        btnClear.setOnClickListener(v -> {
            calculationHistory.clear();
            saveHistoryToPrefs();
            Toast.makeText(this, "Histórico de contas apagado.", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        applyCompactDialogWindow(dialog);
    }

    private void showThemeCustomizationDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_theme_settings, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();

        SharedPreferences prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE);

        View.OnClickListener colorClickListener = v -> {
            String hex = "#FF7425";
            int id = v.getId();
            if (id == R.id.btnColorBlue) hex = "#3B82F6";
            else if (id == R.id.btnColorGreen) hex = "#10B981";
            else if (id == R.id.btnColorPurple) hex = "#8B5CF6";
            else if (id == R.id.btnColorRose) hex = "#F43F5E";

            prefs.edit().putString(KEY_ACCENT_HEX, hex).apply();
            applySavedThemeFromPrefs();
        };

        view.findViewById(R.id.btnColorOrange).setOnClickListener(colorClickListener);
        view.findViewById(R.id.btnColorBlue).setOnClickListener(colorClickListener);
        view.findViewById(R.id.btnColorGreen).setOnClickListener(colorClickListener);
        view.findViewById(R.id.btnColorPurple).setOnClickListener(colorClickListener);
        view.findViewById(R.id.btnColorRose).setOnClickListener(colorClickListener);

        view.findViewById(R.id.btnShapeCircle).setOnClickListener(v -> {
            prefs.edit().putInt(KEY_CORNER_DP, 999).apply();
            applySavedThemeFromPrefs();
        });

        view.findViewById(R.id.btnShapeSquircle).setOnClickListener(v -> {
            prefs.edit().putInt(KEY_CORNER_DP, 18).apply();
            applySavedThemeFromPrefs();
        });

        MaterialButton btnClose = view.findViewById(R.id.btnCloseThemeDialog);
        int currentAccent = Color.parseColor(prefs.getString(KEY_ACCENT_HEX, "#FF7425"));
        btnClose.setBackgroundTintList(ColorStateList.valueOf(currentAccent));
        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        applyCompactDialogWindow(dialog);
    }

    private void applySavedThemeFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE);
        String accentHex = prefs.getString(KEY_ACCENT_HEX, "#FF7425");
        final int cornerDp = prefs.getInt(KEY_CORNER_DP, 999);
        final int accentColor = Color.parseColor(accentHex);
        final float density = getResources().getDisplayMetrics().density;

        tvCalcHeaderTitle.setTextColor(accentColor);
        btnThemeSettings.setTextColor(accentColor);

        final int[] accentTextButtons = {
                R.id.btnClear, R.id.btnBackspace, R.id.btnPercent,
                R.id.btnDivide, R.id.btnMultiply, R.id.btnSubtract, R.id.btnAdd
        };
        final int[] numberButtons = {
                R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
                R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9, R.id.btnDot
        };

        for (int id : accentTextButtons) {
            MaterialButton b = findViewById(id);
            b.setTextColor(accentColor);
        }

        MaterialButton btnEquals = findViewById(R.id.btnEquals);
        btnEquals.setBackgroundTintList(ColorStateList.valueOf(accentColor));

        keypadContainer.post(() -> {
            for (int id : accentTextButtons) {
                MaterialButton b = findViewById(id);
                int r = (cornerDp >= 100 && b.getHeight() > 0)
                        ? Math.min(b.getWidth(), b.getHeight()) / 2
                        : (int) (cornerDp * density);
                b.setCornerRadius(r);
            }
            for (int id : numberButtons) {
                MaterialButton b = findViewById(id);
                int r = (cornerDp >= 100 && b.getHeight() > 0)
                        ? Math.min(b.getWidth(), b.getHeight()) / 2
                        : (int) (cornerDp * density);
                b.setCornerRadius(r);
            }
            int eqRadius = (cornerDp >= 100 && btnEquals.getHeight() > 0)
                    ? Math.min(btnEquals.getWidth(), btnEquals.getHeight()) / 2
                    : (int) (cornerDp * density);
            btnEquals.setCornerRadius(eqRadius);
        });
    }

    private void loadHistoryFromPrefs() {
        calculationHistory.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE);
        String raw = prefs.getString(KEY_HISTORY_JSON, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String entry = arr.getString(i);
                // Garante que apenas contas com operadores matemáticos fiquem no histórico
                if (containsMathOperator(entry)) {
                    calculationHistory.add(entry);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveHistoryToPrefs() {
        JSONArray arr = new JSONArray();
        for (String item : calculationHistory) {
            if (containsMathOperator(item)) {
                arr.put(item);
            }
        }
        getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY_JSON, arr.toString())
                .apply();
    }

    /**
     * Remove do histórico qualquer entrada que corresponda a uma senha configurada ou digitada.
     */
    private void removeSensitiveFromHistory(String secret) {
        if (secret == null || secret.isEmpty()) {
            return;
        }
        boolean modified = false;
        for (int i = calculationHistory.size() - 1; i >= 0; i--) {
            String item = calculationHistory.get(i);
            if (item.startsWith(secret + " ") || item.endsWith(" " + secret) || item.equals(secret)) {
                calculationHistory.remove(i);
                modified = true;
            }
        }
        if (modified) {
            saveHistoryToPrefs();
        }
    }

    private void setupNumberButtons() {
        int[] digitIds = {
                R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
                R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        };

        View.OnClickListener digitListener = v -> {
            MaterialButton btn = (MaterialButton) v;
            String digit = btn.getText().toString();

            if (lastActionWasResult) {
                currentInput.setLength(0);
                tvHistory.setText("");
                lastActionWasResult = false;
            }

            if (currentInput.length() >= 48) {
                return;
            }

            currentInput.append(digit);
            updateDisplay(formatForDisplay(currentInput.toString()));
        };

        for (int id : digitIds) {
            findViewById(id).setOnClickListener(digitListener);
        }

        findViewById(R.id.btnDot).setOnClickListener(v -> {
            if (setupState != SETUP_NONE) {
                return;
            }

            if (lastActionWasResult) {
                currentInput.setLength(0);
                tvHistory.setText("");
                lastActionWasResult = false;
            }

            if (canAppendDot(currentInput.toString())) {
                if (currentInput.length() == 0 || isOperator(currentInput.charAt(currentInput.length() - 1))) {
                    currentInput.append("0.");
                } else {
                    currentInput.append(".");
                }
                updateDisplay(formatForDisplay(currentInput.toString()));
            }
        });
    }

    private void setupOperatorButtons() {
        int[] opIds = {
                R.id.btnAdd, R.id.btnSubtract, R.id.btnMultiply, R.id.btnDivide, R.id.btnPercent
        };

        View.OnClickListener opListener = v -> {
            if (setupState != SETUP_NONE) {
                return;
            }

            MaterialButton btn = (MaterialButton) v;
            char op = btn.getText().charAt(0);

            if (lastActionWasResult) {
                lastActionWasResult = false;
            }

            if (currentInput.length() == 0) {
                if (op == '-') {
                    currentInput.append(op);
                    updateDisplay(formatForDisplay(currentInput.toString()));
                }
                return;
            }

            char lastChar = currentInput.charAt(currentInput.length() - 1);
            if (isOperator(lastChar)) {
                if (lastChar == '%' && op != '%') {
                    currentInput.append(op);
                } else {
                    currentInput.setCharAt(currentInput.length() - 1, op);
                }
            } else if (lastChar != '.') {
                currentInput.append(op);
            }

            updateDisplay(formatForDisplay(currentInput.toString()));
        };

        for (int id : opIds) {
            findViewById(id).setOnClickListener(opListener);
        }
    }

    private void setupActionButtons() {
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            currentInput.setLength(0);
            lastActionWasResult = false;

            if (setupState != SETUP_NONE) {
                if (setupState == SETUP_AWAITING_REAL_PWD) {
                    updateDisplay("DEFINIR SENHA 1 + =");
                } else if (setupState == SETUP_AWAITING_DECOY_PWD) {
                    updateDisplay("DEFINIR SENHA 2 + =");
                }
            } else {
                tvHistory.setText("");
                updateDisplay("0");
            }
        });

        findViewById(R.id.btnBackspace).setOnClickListener(v -> {
            if (lastActionWasResult) {
                currentInput.setLength(0);
                lastActionWasResult = false;
                updateDisplay("0");
                return;
            }

            if (currentInput.length() > 0) {
                currentInput.deleteCharAt(currentInput.length() - 1);
                if (currentInput.length() == 0) {
                    if (setupState == SETUP_AWAITING_REAL_PWD) {
                        updateDisplay("DEFINIR SENHA 1 + =");
                    } else if (setupState == SETUP_AWAITING_DECOY_PWD) {
                        updateDisplay("DEFINIR SENHA 2 + =");
                    } else {
                        updateDisplay("0");
                    }
                } else {
                    updateDisplay(formatForDisplay(currentInput.toString()));
                }
            }
        });

        findViewById(R.id.btnEquals).setOnClickListener(v -> handleEqualsPressed());
    }

    private void handleEqualsPressed() {
        String rawInput = currentInput.toString().trim();

        // 1. Etapas de configuração de senha (jamais entram no histórico)
        if (setupState != SETUP_NONE) {
            processStealthSetupStep(rawInput);
            return;
        }

        // 2. Comando furtivo 0000= (jamais entra no histórico)
        if (!CryptoManager.isVaultConfigured(this) && "0000".equals(rawInput)) {
            setupState = SETUP_AWAITING_REAL_PWD;
            pendingRealPassword = null;
            currentInput.setLength(0);
            tvHistory.setText("MODO DE CONFIGURAÇÃO");
            updateDisplay("DEFINIR SENHA 1 + =");
            return;
        }

        // 3. Autenticação silenciosa do cofre (jamais grava a senha no histórico!)
        if (CryptoManager.isVaultConfigured(this) && isDigitsOnly(rawInput)) {
            int authResult = CryptoManager.authenticateAndInitSession(this, rawInput);
            if (authResult == CryptoManager.AUTH_REAL_VAULT || authResult == CryptoManager.AUTH_DECOY_VAULT) {
                removeSensitiveFromHistory(rawInput);
                currentInput.setLength(0);
                tvHistory.setText("");
                updateDisplay("0");
                lastActionWasResult = false;

                Intent intent = new Intent(CalculatorActivity.this, VaultActivity.class);
                startActivity(intent);
                return;
            }
        }

        evaluateMathExpression(rawInput);
    }

    private void processStealthSetupStep(String input) {
        if (setupState == SETUP_AWAITING_REAL_PWD) {
            if (!isDigitsOnly(input) || input.length() < 4 || "0000".equals(input)) {
                currentInput.setLength(0);
                tvHistory.setText("MÍNIMO 4 DÍGITOS (DIFERENTE DE 0000)");
                updateDisplay("DEFINIR SENHA 1 + =");
                return;
            }

            pendingRealPassword = input;
            currentInput.setLength(0);
            setupState = SETUP_AWAITING_DECOY_PWD;
            tvHistory.setText("SEGUNDA CREDENCIAL");
            updateDisplay("DEFINIR SENHA 2 + =");

        } else if (setupState == SETUP_AWAITING_DECOY_PWD) {
            if (!isDigitsOnly(input) || input.length() < 4) {
                currentInput.setLength(0);
                tvHistory.setText("MÍNIMO 4 DÍGITOS NUMÉRICOS");
                updateDisplay("DEFINIR SENHA 2 + =");
                return;
            }

            if (input.equals(pendingRealPassword)) {
                currentInput.setLength(0);
                tvHistory.setText("DEVE SER DIFERENTE DA SENHA 1");
                updateDisplay("DEFINIR SENHA 2 + =");
                return;
            }

            String realPwd = pendingRealPassword;
            boolean success = CryptoManager.setupPasswords(this, realPwd, input);
            removeSensitiveFromHistory(realPwd);
            removeSensitiveFromHistory(input);
            removeSensitiveFromHistory("0000");

            pendingRealPassword = null;
            currentInput.setLength(0);
            setupState = SETUP_NONE;

            if (success) {
                tvHistory.setText("");
                updateDisplay("CONFIGURADO");
                lastActionWasResult = true;
            } else {
                tvHistory.setText("");
                updateDisplay("ERRO");
                lastActionWasResult = true;
            }
        }
    }

    private void evaluateMathExpression(String expression) {
        if (expression.isEmpty()) {
            return;
        }

        String cleaned = expression;
        while (cleaned.length() > 0) {
            char last = cleaned.charAt(cleaned.length() - 1);
            if ((isOperator(last) && last != '%') || last == '.') {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            } else {
                break;
            }
        }

        if (cleaned.isEmpty()) {
            return;
        }

        try {
            double result = parseAndEvaluate(cleaned);
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                tvHistory.setText("");
                currentInput.setLength(0);
                updateDisplay("Erro");
                lastActionWasResult = true;
                return;
            }

            BigDecimal bd = BigDecimal.valueOf(result)
                    .setScale(8, RoundingMode.HALF_UP)
                    .stripTrailingZeros();

            String resultPlain = bd.toPlainString();
            if ("-0".equals(resultPlain)) {
                resultPlain = "0";
            }

            String formattedExpr = formatForDisplay(cleaned);
            String formattedRes = formatForDisplay(resultPlain);

            // Segurança Anti-Vazamento: Somente grava no histórico se houver operação matemática (+, -, ×, ÷, %).
            // Senhas, tentativas de PIN ou números puros digitados seguidos de '=' NUNCA vão para o histórico.
            boolean shouldRecord = containsMathOperator(cleaned) && !isDigitsOnly(cleaned) && !"0000".equals(cleaned);
            if (shouldRecord) {
                tvHistory.setText(formattedExpr + " =");
                calculationHistory.add(0, formattedExpr + " = " + formattedRes);
                if (calculationHistory.size() > 30) {
                    calculationHistory.remove(calculationHistory.size() - 1);
                }
                saveHistoryToPrefs();
            } else {
                tvHistory.setText("");
            }

            currentInput.setLength(0);
            currentInput.append(resultPlain);
            updateDisplay(formattedRes);
            lastActionWasResult = true;

        } catch (Exception e) {
            tvHistory.setText("");
            currentInput.setLength(0);
            updateDisplay("Erro");
            lastActionWasResult = true;
        }
    }

    private void updateDisplay(String text) {
        if (text.length() > 12) {
            tvDisplay.setTextSize(24f);
        } else {
            tvDisplay.setTextSize(52f);
        }
        tvDisplay.setText(text);
        scrollDisplay.post(() -> scrollDisplay.fullScroll(View.FOCUS_RIGHT));
    }

    private String formatForDisplay(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "0";
        }
        return raw.replace('.', ',');
    }

    private boolean canAppendDot(String expr) {
        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (c == '.') {
                return false;
            }
            if (isOperator(c)) {
                return true;
            }
        }
        return true;
    }

    private boolean isOperator(char c) {
        return c == '+' || c == '-' || c == '×' || c == '÷' || c == '%';
    }

    private boolean containsMathOperator(String expr) {
        if (expr == null || expr.isEmpty()) {
            return false;
        }
        String trimmed = expr.trim();
        if (isDigitsOnly(trimmed)) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (isOperator(c)) {
                if (i == 0 && c == '-' && isDigitsOnly(trimmed.substring(1))) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    private boolean isDigitsOnly(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static double parseAndEvaluate(final String str) {
        return new Object() {
            int pos = -1;
            int ch;

            void nextChar() {
                ch = (++pos < str.length()) ? str.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') {
                    nextChar();
                }
                if (ch == charToEat) {
                    nextChar();
                    return true;
                }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < str.length()) {
                    throw new RuntimeException("Caractere inesperado");
                }
                return x;
            }

            double parseExpression() {
                double x = parseTerm();
                for (; ; ) {
                    if (eat('+')) {
                        x += parseTerm();
                    } else if (eat('-')) {
                        x -= parseTerm();
                    } else {
                        return x;
                    }
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (; ; ) {
                    if (eat('×')) {
                        x *= parseFactor();
                    } else if (eat('÷')) {
                        double divisor = parseFactor();
                        if (divisor == 0.0) {
                            return Double.NaN;
                        }
                        x /= divisor;
                    } else {
                        return x;
                    }
                }
            }

            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();

                double x;
                int startPos = this.pos;
                if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') {
                        nextChar();
                    }
                    x = Double.parseDouble(str.substring(startPos, this.pos));
                } else {
                    throw new RuntimeException("Número esperado");
                }

                while (eat('%')) {
                    x = x / 100.0;
                }

                return x;
            }
        }.parse();
    }
}
