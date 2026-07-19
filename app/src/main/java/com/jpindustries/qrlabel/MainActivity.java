package com.jpindustries.qrlabel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity
        implements BluetoothPrinterManager.Listener, UsbPrinterManager.Listener, UsbScaleManager.Listener {
    private static final int BLUETOOTH_PERMISSION_REQUEST = 1001;
    private static final long SCALE_PREVIEW_DEBOUNCE_MS = 2500L;
    private static final long SCALE_PRINT_DELAY_MS = 300L;
    private static final long INACTIVITY_LOGOUT_MS = 90_000L;
    private static final int MAX_BOX_REELS = 18;
    private static final String BATCH_ID_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String[] REEL_BATCH_COUNT_OPTIONS = {
            "1", "2", "4", "8", "16", "18"
    };
    private static final String[] SWG_OPTIONS = {
            "",
            "8", "9", "10", "11", "12", "13", "14", "15",
            "16", "17", "17.5", "18", "18.5", "19", "19.5", "20",
            "20.5", "21", "21.5", "22", "22.5", "23", "24", "25", "26",
            "36P", "35P", "34P", "33P", "32P", "31P", "30P", "29P", "28P", "27P"
    };
    private static final String[] COLOUR_OPTIONS = {
            "", "Black", "Green", "Off white", "White"
    };
    private static final String[] SPOOL_SIZE_OPTIONS = {
            "", "2\"", "4\"", "6\"", "8\""
    };
    private static final String[] BRAND_OPTIONS = {
            "", "Treveni", "JIPRO", "Indica", "JPI"
    };
    private static final String[] SCALE_BAUD_OPTIONS = {
            "9600", "2400", "4800", "1200", "19200", "38400", "57600", "115200"
    };
    private static final String[] SCALE_FORMAT_OPTIONS = {
            "8N1", "7E1", "7O1", "8E1", "8O1", "8N2"
    };

    private EditText labelTextInput;
    private TextView labelSizeDropdown;
    private EditText swgInput;
    private TextView colourDropdown;
    private TextView spoolSizeDropdown;
    private TextView reelBatchCountDropdown;
    private TextView reelBatchStatusText;
    private LinearLayout reelBatchList;
    private TextView boxBrandDropdown;
    private TextView boxReelScanTarget;
    private TextView boxReelCountText;
    private LinearLayout boxReelList;
    private EditText tareWeightInput;
    private EditText grossWeightInput;
    private EditText netWeightInput;
    private EditText spoolWeightInput;
    private LabelSize selectedLabelSize = LabelSize.STANDARD_SIZES[0];
    private String selectedSwg = "";
    private String selectedColour = "";
    private String selectedSpoolSize = "";
    private String selectedBrand = "";
    private String tareWeight = "";
    private String grossWeight = "";
    private String netWeight = "";
    private String spoolWeight = "";
    private int selectedReelBatchCount = 1;
    private int printedReelsInBatch;
    private String activeReelBatchId = "";
    private String activeSingleReelId = "";
    private String lastCompletedReelBatchId = "";
    private String lastCompletedReelBatchTotal = "";
    private int lastCompletedReelBatchCount;
    private Button generateQrButton;
    private TextView printerStatus;
    private String activeQrSection = "Reel QR";
    private AuthStore authStore;
    private BluetoothPrinterManager printerManager;
    private UsbPrinterManager usbPrinterManager;
    private UsbScaleManager usbScaleManager;
    private PrinterTarget selectedPrinter;
    private AlertDialog printerDialog;
    private ArrayAdapter<String> printerAdapter;
    private TextView scaleStatus;
    private TextView scaleBaudDropdown;
    private TextView scaleFormatDropdown;
    private TextView scaleDiagnosticLogText;
    private TextView scaleDiagnosticParsedText;
    private TextView scaleDiagnosticCountText;
    private TextView scaleDiagnosticGapText;
    private String selectedScaleBaud = "9600";
    private String selectedScaleFormat = "8N1";
    private ScanField activeScanField = ScanField.SWG;
    private boolean qrPreviewShowing;
    private boolean updatingSwgInput;
    private boolean scaleDiagnosticRunning;
    private int scaleDiagnosticRawCount;
    private int scaleDiagnosticParsedCount;
    private long scaleDiagnosticLastMessageAtMs;
    private String lastScalePreviewWeight = "";
    private long lastScalePreviewAtMs;
    private final Handler inactivityHandler = new Handler(Looper.getMainLooper());
    private final Runnable inactivityLogoutRunnable = () -> {
        if (authStore == null || !authStore.isLoggedIn()) {
            return;
        }
        authStore.logout();
        releasePrinterManagers();
        Toast.makeText(this, "Session timed out", Toast.LENGTH_SHORT).show();
        showLoginScreen();
    };
    private final SecureRandom batchRandom = new SecureRandom();
    private final StringBuilder scannerBuffer = new StringBuilder();
    private final StringBuilder scaleDiagnosticLog = new StringBuilder();
    private final List<BluetoothDevice> visibleBluetoothPrinters = new ArrayList<>();
    private final List<UsbDevice> visibleUsbPrinters = new ArrayList<>();
    private final List<PrinterTarget> visiblePrinters = new ArrayList<>();
    private final List<ReelScanItem> boxReels = new ArrayList<>();
    private final List<String> reelBatchNetWeights = new ArrayList<>();
    private final List<BatchReelItem> reelBatchItems = new ArrayList<>();
    private final List<BatchReelItem> lastCompletedReelBatchItems = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authStore = new AuthStore(this);
        if (authStore.isLoggedIn()) {
            openMainApp();
        } else {
            showLoginScreen();
        }
    }

    private void openMainApp() {
        printerManager = new BluetoothPrinterManager(this);
        printerManager.setListener(this);
        usbPrinterManager = new UsbPrinterManager(this);
        usbPrinterManager.setListener(this);
        usbScaleManager = new UsbScaleManager(this);
        usbScaleManager.setListener(this);
        requestBluetoothPermissions();
        buildScreen();
        printerManager.loadPairedPrinters();
        usbPrinterManager.loadPrinters();
        resetInactivityTimer();
    }

    private void showLoginScreen() {
        stopInactivityTimer();
        setContentView(buildAuthScreen("Login", "Login", false));
    }

    private void showSignUpScreen() {
        stopInactivityTimer();
        setContentView(buildAuthScreen("Create Account", "Sign Up", true));
    }

    private ScrollView buildAuthScreen(String titleText, String actionText, boolean signUpMode) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(244, 247, 251));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(44), dp(18), dp(18));
        scrollView.addView(content, matchParentWrap());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundStroke(Color.WHITE, Color.rgb(225, 229, 235), dp(10), 1));
        content.addView(card, matchWrap());

        TextView section = new TextView(this);
        section.setText("JP INDUSTRIES");
        section.setTextSize(13);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setTextColor(Color.rgb(107, 114, 128));
        card.addView(section, matchWrap());

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setPadding(0, dp(4), 0, dp(18));
        card.addView(title, matchWrap());

        EditText usernameInput = input("Username", "");
        EditText passwordInput = input("Password", "");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addLabeledView(card, "Username", usernameInput);
        addLabeledView(card, "Password", passwordInput);

        Button actionButton = primaryButton(actionText, true);
        card.addView(actionButton, spacedMatchWrap(dp(16)));

        Button switchButton = secondaryButton(signUpMode ? "Already have an account? Login" : "New user? Sign Up");
        card.addView(switchButton, spacedMatchWrap(dp(10)));

        actionButton.setOnClickListener(view -> {
            String username = usernameInput.getText().toString().trim();
            String password = passwordInput.getText().toString();
            boolean success = signUpMode ? authStore.signUp(username, password) : authStore.login(username, password);
            if (success) {
                Toast.makeText(this, signUpMode ? "Account created" : "Logged in", Toast.LENGTH_SHORT).show();
                openMainApp();
            } else if (signUpMode && authStore.userExists(username)) {
                Toast.makeText(this, "User already exists", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, signUpMode ? "Use a username and 4+ character password" : "Invalid username or password", Toast.LENGTH_SHORT).show();
            }
        });

        switchButton.setOnClickListener(view -> {
            if (signUpMode) {
                showLoginScreen();
            } else {
                showSignUpScreen();
            }
        });

        addContentFiller(content);
        return scrollView;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            resetInactivityTimer();
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        if (getCurrentFocus() instanceof EditText) {
            return super.dispatchKeyEvent(event);
        }

        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_TAB) {
            if (activeScanField == ScanField.DONE) {
                if (generateQrButton != null && generateQrButton.isEnabled()) {
                    if ("Spool QR".equals(activeQrSection)) {
                        submitSpoolQr();
                    } else if (isReelBatchMode()) {
                        printCurrentBatchReelIfReady();
                    } else if ("Reel QR".equals(activeQrSection)) {
                        printSingleReelIfReady();
                    } else {
                        showQrPreviewOverlay();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            processScannedValue(scannerBuffer.toString());
            scannerBuffer.setLength(0);
            return true;
        }

        if (activeScanField == ScanField.DONE) {
            return super.dispatchKeyEvent(event);
        }

        int unicodeChar = event.getUnicodeChar();
        if (unicodeChar > 0) {
            scannerBuffer.append((char) unicodeChar);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            resetInactivityTimer();
        }
        return super.dispatchTouchEvent(event);
    }

    private void resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityLogoutRunnable);
        if (authStore != null && authStore.isLoggedIn()) {
            inactivityHandler.postDelayed(inactivityLogoutRunnable, INACTIVITY_LOGOUT_MS);
        }
    }

    private void stopInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityLogoutRunnable);
    }

    private void buildScreen() {
        activeQrSection = "Reel QR";
        selectedLabelSize = LabelSize.REEL_3X2_INCH;
        labelTextInput = null;
        LinearLayout shell = buildAppShell();
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(18), dp(14), dp(18));
        scrollView.addView(content, matchParentWrap());

        addTopBar(content, "Reel QR");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundStroke(Color.WHITE, Color.rgb(225, 229, 235), dp(10), 1));
        content.addView(card, matchWrap());

        TextView section = new TextView(this);
        section.setText("LABEL PRINTING");
        section.setTextSize(13);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setTextColor(Color.rgb(107, 114, 128));
        card.addView(section, matchWrap());

        TextView title = new TextView(this);
        title.setText("JP Industries QR Label");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setPadding(0, dp(4), 0, dp(18));
        card.addView(title, matchWrap());

        reelBatchCountDropdown = dropdownField();
        setDropdownText(reelBatchCountDropdown, String.valueOf(selectedReelBatchCount));
        reelBatchCountDropdown.setOnClickListener(view -> showDropdown(reelBatchCountDropdown, REEL_BATCH_COUNT_OPTIONS, value -> selectReelBatchCount(value)));
        addLabeledView(card, "Reel Count", reelBatchCountDropdown);

        reelBatchStatusText = deviceStatusText("");
        reelBatchStatusText.setPadding(0, 0, 0, dp(6));
        card.addView(reelBatchStatusText, matchWrap());
        reelBatchList = new LinearLayout(this);
        reelBatchList.setOrientation(LinearLayout.VERTICAL);
        card.addView(reelBatchList, matchWrap());
        updateReelBatchStatusText();
        refreshReelBatchList();

        labelSizeDropdown = dropdownField();
        setDropdownText(labelSizeDropdown, selectedLabelSize.toString());
        labelSizeDropdown.setOnClickListener(view -> showDropdown(labelSizeDropdown, LabelSize.STANDARD_SIZES, value -> {
            selectedLabelSize = value;
            setDropdownText(labelSizeDropdown, value.toString());
        }));
        addLabeledView(card, "Label size", labelSizeDropdown);

        LinearLayout firstDetailRow = detailRow();
        swgInput = input("SWG", selectedSwg);
        configureSwgInput(swgInput);
        addInputField(firstDetailRow, "SWG", swgInput, ScanField.SWG);
        colourDropdown = addDropdownField(firstDetailRow, "Colour", COLOUR_OPTIONS, ScanField.COLOUR, value -> selectColour(value, false));
        setDropdownText(colourDropdown, selectedColour);
        card.addView(firstDetailRow, matchWrap());

        LinearLayout secondDetailRow = detailRow();
        spoolSizeDropdown = addDropdownField(secondDetailRow, "Spool Size", SPOOL_SIZE_OPTIONS, ScanField.SPOOL_SIZE, value -> selectSpoolSize(value, false));
        setDropdownText(spoolSizeDropdown, selectedSpoolSize);
        tareWeightInput = input("Tare Wt.", tareWeight);
        configureWeightInput(tareWeightInput, ScanField.TARE_WEIGHT, value -> setTareWeight(value, true));
        addInputField(secondDetailRow, "Tare Wt.", tareWeightInput, ScanField.TARE_WEIGHT);
        card.addView(secondDetailRow, matchWrap());

        LinearLayout thirdDetailRow = detailRow();
        grossWeightInput = input("Gross Wt.", grossWeight);
        configureWeightInput(grossWeightInput, ScanField.GROSS_WEIGHT, value -> setGrossWeight(value, true));
        addInputField(thirdDetailRow, "Gross Wt.", grossWeightInput, ScanField.GROSS_WEIGHT);
        card.addView(thirdDetailRow, matchWrap());

        netWeightInput = input("Net Wt.", netWeight);
        netWeightInput.setEnabled(false);
        netWeightInput.setTextColor(Color.rgb(17, 24, 39));
        netWeightInput.setBackground(roundStroke(Color.rgb(248, 250, 252), Color.rgb(220, 224, 230), dp(9), 1));
        addLabeledView(card, "Net Wt.", netWeightInput);

        generateQrButton = primaryButton("Print Label", false);
        generateQrButton.setText("Print Label");
        generateQrButton.setOnClickListener(view -> {
            if (isReelBatchMode()) {
                printCurrentBatchReelIfReady();
            } else {
                printSingleReelIfReady();
            }
        });
        card.addView(generateQrButton, spacedMatchWrap(dp(14)));
        updateGenerateButtonState();

        addDevicePanel(content, true);

        addContentFiller(content);
        shell.addView(scrollView, weightedMatch());
        shell.addView(bottomNavigation(), matchWrap());
        setContentView(shell);
        setActiveScanField(ScanField.SWG);
    }

    private void buildSpoolQrScreen() {
        activeQrSection = "Spool QR";
        LinearLayout shell = buildAppShell();
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(18), dp(14), dp(18));
        scrollView.addView(content, matchParentWrap());

        addTopBar(content, "Spool QR");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundStroke(Color.WHITE, Color.rgb(225, 229, 235), dp(10), 1));
        content.addView(card, matchWrap());

        TextView section = new TextView(this);
        section.setText("SPOOL QR");
        section.setTextSize(13);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setTextColor(Color.rgb(107, 114, 128));
        card.addView(section, matchWrap());

        TextView title = new TextView(this);
        title.setText("Spool QR");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setPadding(0, dp(4), 0, dp(18));
        card.addView(title, matchWrap());

        labelTextInput = input("Label text", "JP Industries");
        addLabeledView(card, "Label text", labelTextInput);

        labelSizeDropdown = dropdownField();
        labelSizeDropdown.setOnClickListener(view -> showDropdown(labelSizeDropdown, LabelSize.STANDARD_SIZES, value -> {
            selectedLabelSize = value;
            setDropdownText(labelSizeDropdown, value.toString());
        }));
        addLabeledView(card, "Label size", labelSizeDropdown);

        spoolWeightInput = input("Spool Wt.", spoolWeight);
        spoolWeightInput.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        spoolWeightInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                spoolWeight = text == null ? "" : text.toString().trim();
                updateGenerateButtonState();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        spoolWeightInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                setActiveScanField(ScanField.SPOOL_WEIGHT);
            }
        });
        spoolWeightInput.setOnEditorActionListener((view, actionId, event) -> {
            setSpoolWeight(getSpoolWeightValue(), false);
            if (!spoolWeight.isEmpty()) {
                setActiveScanField(ScanField.DONE);
            }
            return false;
        });
        spoolWeightInput.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                setSpoolWeight(getSpoolWeightValue(), false);
                if (!spoolWeight.isEmpty()) {
                    setActiveScanField(ScanField.DONE);
                }
                return true;
            }
            return false;
        });
        addLabeledView(card, "Spool Wt.", spoolWeightInput);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(14), 0, 0);

        generateQrButton = primaryButton("Submit", false);
        generateQrButton.setText("Submit");
        generateQrButton.setOnClickListener(view -> submitSpoolQr());

        Button clearButton = secondaryButton("Clear");
        clearButton.setOnClickListener(view -> clearSpoolWeight());

        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        submitParams.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clearParams.setMargins(dp(8), 0, 0, 0);
        actionRow.addView(generateQrButton, submitParams);
        actionRow.addView(clearButton, clearParams);
        card.addView(actionRow, matchWrap());
        updateGenerateButtonState();

        addDevicePanel(content, true);

        addContentFiller(content);
        shell.addView(scrollView, weightedMatch());
        shell.addView(bottomNavigation(), matchWrap());
        setContentView(shell);
        setActiveScanField(ScanField.SPOOL_WEIGHT);
    }

    private void buildBoxQrScreen() {
        activeQrSection = "BOX QR";
        selectedLabelSize = LabelSize.STANDARD_SIZES[3];
        LinearLayout shell = buildAppShell();
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(18), dp(14), dp(18));
        scrollView.addView(content, matchParentWrap());

        addTopBar(content, "Box QR");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundStroke(Color.WHITE, Color.rgb(225, 229, 235), dp(10), 1));
        content.addView(card, matchWrap());

        labelSizeDropdown = dropdownField();
        setDropdownText(labelSizeDropdown, selectedLabelSize.toString());
        labelSizeDropdown.setOnClickListener(view -> showDropdown(labelSizeDropdown, LabelSize.STANDARD_SIZES, value -> {
            selectedLabelSize = value;
            setDropdownText(labelSizeDropdown, value.toString());
        }));
        addLabeledView(card, "Label size", labelSizeDropdown);

        boxBrandDropdown = dropdownField();
        setDropdownText(boxBrandDropdown, selectedBrand);
        boxBrandDropdown.setOnClickListener(view -> {
            setActiveScanField(ScanField.BRAND);
            showDropdown(boxBrandDropdown, BRAND_OPTIONS, value -> selectBrand(value, true));
        });
        addLabeledView(card, "Brand", boxBrandDropdown);

        boxReelScanTarget = dropdownField();
        boxReelScanTarget.setText("Scan Reel QR");
        boxReelScanTarget.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_camera, 0);
        boxReelScanTarget.setOnClickListener(view -> setActiveScanField(ScanField.BOX_REEL));
        addLabeledView(card, "Reel QR Scanner", boxReelScanTarget);

        boxReelCountText = previewText("", 14, true);
        boxReelCountText.setPadding(0, dp(14), 0, dp(8));
        card.addView(boxReelCountText, matchWrap());

        boxReelList = new LinearLayout(this);
        boxReelList.setOrientation(LinearLayout.VERTICAL);
        card.addView(boxReelList, matchWrap());
        refreshBoxReelList();

        generateQrButton = null;
        updateGenerateButtonState();

        addDevicePanel(content, false);

        addContentFiller(content);
        shell.addView(scrollView, weightedMatch());
        shell.addView(bottomNavigation(), matchWrap());
        setContentView(shell);
        setActiveScanField(ScanField.BRAND);
    }

    private void buildScaleDiagnosticScreen() {
        activeQrSection = "Scale Test";
        LinearLayout shell = buildAppShell();
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(18), dp(14), dp(18));
        scrollView.addView(content, matchParentWrap());

        addTopBar(content, "Scale Diagnostic");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundStroke(Color.WHITE, Color.rgb(225, 229, 235), dp(10), 1));
        content.addView(card, matchWrap());

        TextView title = previewText("Scale behavior test", 18, true);
        card.addView(title, matchWrap());

        scaleStatus = deviceStatusText("Scale: Not connected");
        card.addView(scaleStatus, spacedMatchWrap(dp(10)));

        scaleDiagnosticParsedText = deviceStatusText("Last parsed: -");
        card.addView(scaleDiagnosticParsedText, spacedMatchWrap(dp(6)));

        scaleDiagnosticCountText = deviceStatusText("RAW: 0 | PARSED: 0");
        card.addView(scaleDiagnosticCountText, spacedMatchWrap(dp(6)));

        scaleDiagnosticGapText = deviceStatusText("Gap: -");
        card.addView(scaleDiagnosticGapText, spacedMatchWrap(dp(12)));

        LinearLayout firstRow = compactActionRow();
        Button connectButton = compactButton("Connect Scale");
        connectButton.setOnClickListener(view -> connectUsbScale());
        Button startButton = compactButton("Start Test");
        startButton.setOnClickListener(view -> startScaleDiagnosticTest());
        firstRow.addView(connectButton, compactActionParams(true));
        firstRow.addView(startButton, compactActionParams(false));
        card.addView(firstRow, spacedMatchWrap(dp(8)));

        LinearLayout secondRow = compactActionRow();
        Button stopButton = compactButton("Stop Test");
        stopButton.setOnClickListener(view -> stopScaleDiagnosticTest());
        Button clearButton = compactButton("Clear Log");
        clearButton.setOnClickListener(view -> clearScaleDiagnosticLog());
        secondRow.addView(stopButton, compactActionParams(true));
        secondRow.addView(clearButton, compactActionParams(false));
        card.addView(secondRow, spacedMatchWrap(dp(14)));

        scaleDiagnosticLogText = previewText("", 12, false);
        scaleDiagnosticLogText.setTypeface(Typeface.MONOSPACE);
        scaleDiagnosticLogText.setTextColor(Color.rgb(17, 24, 39));
        scaleDiagnosticLogText.setPadding(dp(12), dp(12), dp(12), dp(12));
        scaleDiagnosticLogText.setMinHeight(dp(260));
        scaleDiagnosticLogText.setBackground(roundStroke(Color.rgb(248, 250, 252), Color.rgb(220, 224, 230), dp(8), 1));
        card.addView(scaleDiagnosticLogText, matchWrap());
        refreshScaleDiagnosticLog();

        addContentFiller(content);
        shell.addView(scrollView, weightedMatch());
        shell.addView(bottomNavigation(), matchWrap());
        setContentView(shell);
    }

    private LinearLayout buildAppShell() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.rgb(244, 247, 251));
        return shell;
    }

    private void addTopBar(LinearLayout content, String titleText) {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, dp(18));
        content.addView(topBar, matchWrap());

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);

        TextView appName = new TextView(this);
        appName.setText("JP Industries");
        appName.setTextSize(22);
        appName.setTypeface(Typeface.DEFAULT_BOLD);
        appName.setTextColor(Color.rgb(17, 24, 39));
        titleBlock.addView(appName, matchWrap());

        TextView screenName = new TextView(this);
        screenName.setText(titleText);
        screenName.setTextSize(13);
        screenName.setTypeface(Typeface.DEFAULT_BOLD);
        screenName.setTextColor(Color.rgb(107, 114, 128));
        titleBlock.addView(screenName, matchWrap());
        topBar.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView spacer = new TextView(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(dp(12), 1));

        TextView avatar = new TextView(this);
        avatar.setText(getUserInitial());
        avatar.setTextSize(18);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setTextColor(Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(roundFill(Color.rgb(37, 99, 235), dp(28)));
        avatar.setOnClickListener(view -> showUserMenu());
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        topBar.addView(avatar, avatarParams);
    }

    private void addContentFiller(LinearLayout content) {
        TextView filler = new TextView(this);
        filler.setText("");
        content.addView(filler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(22),
                1f
        ));
    }

    private void addDevicePanel(LinearLayout content, boolean includeScale) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(16));
        panel.setBackground(roundStroke(Color.WHITE, Color.rgb(225, 229, 235), dp(10), 1));
        LinearLayout.LayoutParams panelParams = spacedMatchWrap(dp(12));
        content.addView(panel, panelParams);

        TextView title = new TextView(this);
        title.setText("DEVICES");
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(107, 114, 128));
        panel.addView(title, matchWrap());

        printerStatus = deviceStatusText("Printer: Not selected");
        panel.addView(printerStatus, spacedMatchWrap(dp(8)));

        Button findUsbPrinterButton = compactButton("Connect Printer");
        findUsbPrinterButton.setOnClickListener(view -> findUsbPrinter());
        panel.addView(findUsbPrinterButton, spacedMatchWrap(dp(8)));

        if (includeScale) {
            scaleStatus = deviceStatusText("Scale: Not connected");
            panel.addView(scaleStatus, spacedMatchWrap(dp(12)));

            Button connectScaleButton = compactButton("Connect Scale");
            connectScaleButton.setOnClickListener(view -> connectUsbScale());
            panel.addView(connectScaleButton, spacedMatchWrap(dp(8)));
        }
    }

    private TextView deviceStatusText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(75, 85, 99));
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    private LinearLayout compactActionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private Button compactButton(String text) {
        Button button = secondaryButton(text);
        button.setTextSize(13);
        button.setMinHeight(dp(46));
        return button;
    }

    private TextView compactDropdown(String text) {
        TextView dropdown = dropdownField();
        dropdown.setText(text);
        dropdown.setTextSize(14);
        dropdown.setMinHeight(dp(46));
        return dropdown;
    }

    private LinearLayout.LayoutParams compactActionParams(boolean left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (left) {
            params.setMargins(0, 0, dp(6), 0);
        } else {
            params.setMargins(dp(6), 0, 0, 0);
        }
        return params;
    }

    private LinearLayout bottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(10), dp(8), dp(10), dp(10));
        nav.setBackground(roundStroke(Color.WHITE, Color.rgb(225, 229, 235), dp(0), 1));

        nav.addView(bottomNavItem("Spool QR", "Spool", android.R.drawable.ic_menu_manage), navItemParams());
        nav.addView(bottomNavItem("Reel QR", "Reel", android.R.drawable.ic_menu_upload), navItemParams());
        nav.addView(bottomNavItem("BOX QR", "Box", android.R.drawable.ic_menu_view), navItemParams());
        nav.addView(bottomNavItem("Scale Test", "Scale", android.R.drawable.ic_menu_info_details), navItemParams());
        return nav;
    }

    private TextView bottomNavItem(String section, String label, int iconRes) {
        TextView item = new TextView(this);
        boolean active = section.equals(activeQrSection);
        item.setText(label);
        item.setTextSize(12);
        item.setTypeface(Typeface.DEFAULT_BOLD);
        item.setGravity(Gravity.CENTER);
        item.setMinHeight(dp(58));
        item.setTextColor(active ? Color.rgb(37, 99, 235) : Color.rgb(75, 85, 99));
        item.setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0);
        item.setCompoundDrawablePadding(dp(4));
        item.setBackground(active
                ? roundStroke(Color.rgb(239, 246, 255), Color.rgb(37, 99, 235), dp(8), 1)
                : roundFill(Color.WHITE, dp(8)));
        item.setOnClickListener(view -> navigateToSection(section));
        return item;
    }

    private LinearLayout.LayoutParams navItemParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private void navigateToSection(String section) {
        if (section.equals(activeQrSection)) {
            return;
        }
        if ("Spool QR".equals(section)) {
            buildSpoolQrScreen();
        } else if ("Reel QR".equals(section)) {
            buildScreen();
        } else if ("BOX QR".equals(section)) {
            buildBoxQrScreen();
        } else if ("Scale Test".equals(section)) {
            buildScaleDiagnosticScreen();
        } else {
            Toast.makeText(this, section + " screen is not added yet", Toast.LENGTH_SHORT).show();
        }
    }

    private void showUserMenu() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(6));

        TextView usernameTitle = previewText("Signed in as", 13, false);
        usernameTitle.setTextColor(Color.rgb(107, 114, 128));
        content.addView(usernameTitle, matchWrap());

        TextView username = previewText(authStore.getActiveUser(), 18, true);
        username.setPadding(0, dp(2), 0, dp(14));
        content.addView(username, matchWrap());

        AlertDialog accountDialog = new AlertDialog.Builder(this)
                .setTitle("User Details")
                .setView(content)
                .create();

        Button signOutButton = primaryButton("Sign Out", true);
        content.addView(signOutButton, spacedMatchWrap(dp(8)));
        signOutButton.setOnClickListener(view -> {
            accountDialog.dismiss();
            stopInactivityTimer();
            authStore.logout();
            releasePrinterManagers();
            showLoginScreen();
        });

        accountDialog.show();
    }

    private void showSideNavigation() {
        LinearLayout drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setPadding(dp(14), dp(26), dp(14), dp(18));
        drawer.setBackgroundColor(Color.rgb(244, 247, 251));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(16), dp(14), dp(16));
        panel.setBackground(roundStroke(Color.WHITE, Color.rgb(225, 229, 235), dp(10), 1));
        drawer.addView(panel, matchWrap());

        TextView title = new TextView(this);
        title.setText("JP Industries");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setPadding(dp(4), 0, dp(4), dp(4));
        panel.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("QR LABELS");
        subtitle.setTextSize(13);
        subtitle.setTypeface(Typeface.DEFAULT_BOLD);
        subtitle.setTextColor(Color.rgb(107, 114, 128));
        subtitle.setPadding(dp(4), 0, dp(4), dp(14));
        panel.addView(subtitle, matchWrap());

        PopupWindow[] holder = new PopupWindow[1];
        panel.addView(sideNavItem("Spool QR", holder), matchWrap());
        panel.addView(sideNavItem("Reel QR", holder), matchWrap());
        panel.addView(sideNavItem("BOX QR", holder), matchWrap());

        PopupWindow drawerWindow = new PopupWindow(
                drawer,
                dp(280),
                ViewGroup.LayoutParams.MATCH_PARENT,
                true
        );
        drawerWindow.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        drawerWindow.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            drawerWindow.setElevation(dp(10));
        }
        holder[0] = drawerWindow;
        drawerWindow.showAtLocation(getWindow().getDecorView(), Gravity.START | Gravity.TOP, 0, 0);
    }

    private TextView sideNavItem(String text, PopupWindow[] drawerWindowHolder) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setTextSize(17);
        item.setTypeface(Typeface.DEFAULT_BOLD);
        boolean active = text.equals(activeQrSection);
        item.setTextColor(active ? Color.rgb(37, 99, 235) : Color.rgb(17, 24, 39));
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), 0, dp(14), 0);
        item.setMinHeight(dp(56));
        item.setBackground(active
                ? roundStroke(Color.rgb(239, 246, 255), Color.rgb(37, 99, 235), dp(9), 1)
                : roundStroke(Color.WHITE, Color.rgb(226, 232, 240), dp(9), 1));
        item.setOnClickListener(view -> {
            activeQrSection = text;
            if (drawerWindowHolder[0] != null) {
                drawerWindowHolder[0].dismiss();
            }
            if ("Spool QR".equals(text)) {
                buildSpoolQrScreen();
            } else if ("Reel QR".equals(text)) {
                buildScreen();
            } else {
                Toast.makeText(this, text + " screen is not added yet", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams params = spacedMatchWrap(dp(8));
        item.setLayoutParams(params);
        return item;
    }

    private String getUserInitial() {
        String username = authStore == null ? null : authStore.getActiveUser();
        if (username == null || username.trim().isEmpty()) {
            return "U";
        }
        return username.trim().substring(0, 1).toUpperCase();
    }

    private String getEnteredByName() {
        String username = authStore == null ? null : authStore.getActiveUser();
        return username == null || username.trim().isEmpty() ? "Unknown" : username.trim();
    }

    private EditText input(String hint, String value) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(value);
        editText.setSingleLine(true);
        editText.setTextSize(17);
        editText.setTypeface(Typeface.DEFAULT_BOLD);
        editText.setTextColor(Color.rgb(17, 24, 39));
        editText.setHintTextColor(Color.rgb(148, 163, 184));
        editText.setPadding(dp(14), 0, dp(14), 0);
        editText.setMinHeight(dp(58));
        editText.setBackground(roundStroke(Color.WHITE, Color.rgb(220, 224, 230), dp(9), 1));
        return editText;
    }

    private void showQrPreviewOverlay() {
        if (qrPreviewShowing) {
            return;
        }
        String qrData = createQrPayload();
        if (qrData.isEmpty()) {
            Toast.makeText(this, "Select at least one QR field", Toast.LENGTH_SHORT).show();
            return;
        }
        qrPreviewShowing = true;

        Bitmap bitmap = createLabelPreviewBitmap(qrData);
        LinearLayout previewContent = new LinearLayout(this);
        previewContent.setOrientation(LinearLayout.VERTICAL);
        previewContent.setPadding(dp(20), dp(12), dp(20), dp(6));

        ImageView previewImage = new ImageView(this);
        previewImage.setImageBitmap(bitmap);
        previewImage.setAdjustViewBounds(true);
        previewImage.setPadding(dp(10), dp(10), dp(10), dp(10));
        previewImage.setBackground(roundStroke(Color.WHITE, Color.rgb(226, 232, 240), dp(10), 1));
        int previewWidth = Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(96));
        int previewHeight = previewWidth;
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(previewWidth, previewHeight);
        imageParams.gravity = Gravity.CENTER_HORIZONTAL;
        previewContent.addView(previewImage, imageParams);

        TextView qrValue = previewText("QR: " + qrData, 16, true);
        qrValue.setPadding(0, dp(14), 0, dp(6));
        previewContent.addView(qrValue, matchWrap());

        if (!"Reel QR".equals(activeQrSection) && labelTextInput != null) {
            previewContent.addView(previewText("Label: " + labelTextInput.getText().toString().trim(), 14, false), matchWrap());
        }
        previewContent.addView(previewText("Details: " + createDetailText(), 14, false), matchWrap());
        String footerText = createFooterText();
        if (!footerText.isEmpty()) {
            previewContent.addView(previewText(footerText, 14, false), matchWrap());
        }
        previewContent.addView(previewText("Size: " + getSelectedLabelSize(), 14, false), matchWrap());

        AlertDialog previewDialog = new AlertDialog.Builder(this)
                .setTitle("QR Label Preview")
                .setView(previewContent)
                .create();
        previewDialog.setOnDismissListener(dialog -> qrPreviewShowing = false);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(18), 0, 0);

        Button cancelButton = secondaryButton("Cancel");
        Button printButton = primaryButton("Print", true);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelParams.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams printParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        printParams.setMargins(dp(8), 0, 0, 0);
        actionRow.addView(cancelButton, cancelParams);
        actionRow.addView(printButton, printParams);
        previewContent.addView(actionRow, matchWrap());

        cancelButton.setOnClickListener(view -> previewDialog.dismiss());
        printButton.setOnClickListener(view -> {
            previewDialog.dismiss();
            printLabel();
            resetReelForNextScanIfNeeded();
        });

        attachPreviewScannerPrint(previewDialog, printButton);

        previewDialog.show();
    }

    private void showBoxQrPreviewOverlay() {
        if (selectedBrand.trim().isEmpty()) {
            Toast.makeText(this, "Select Brand", Toast.LENGTH_SHORT).show();
            setActiveScanField(ScanField.BRAND);
            return;
        }
        if (boxReels.isEmpty()) {
            Toast.makeText(this, "Scan at least one Reel QR", Toast.LENGTH_SHORT).show();
            setActiveScanField(ScanField.BOX_REEL);
            return;
        }

        LinearLayout previewContent = new LinearLayout(this);
        previewContent.setOrientation(LinearLayout.VERTICAL);
        previewContent.setPadding(dp(20), dp(12), dp(20), dp(6));

        Map<String, List<Integer>> groups = getBoxGroupedReelIndexes();
        for (List<Integer> indexes : groups.values()) {
            addBoxGroupPreviewImage(
                    previewContent,
                    "Group Label",
                    createBoxGroupQrPayload(indexes),
                    indexes
            );
        }

        ScrollView previewScrollView = new ScrollView(this);
        previewScrollView.addView(previewContent, matchParentWrap());

        AlertDialog previewDialog = new AlertDialog.Builder(this)
                .setTitle("Box QR Preview")
                .setView(previewScrollView)
                .create();

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(18), 0, 0);

        Button cancelButton = secondaryButton("Cancel");
        Button printButton = primaryButton("Print", true);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelParams.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams printParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        printParams.setMargins(dp(8), 0, 0, 0);
        actionRow.addView(cancelButton, cancelParams);
        actionRow.addView(printButton, printParams);
        previewContent.addView(actionRow, matchWrap());

        cancelButton.setOnClickListener(view -> previewDialog.dismiss());
        printButton.setOnClickListener(view -> {
            previewDialog.dismiss();
            printLabel();
        });
        attachPreviewScannerPrint(previewDialog, printButton);

        previewDialog.show();
    }

    private void attachPreviewScannerPrint(AlertDialog previewDialog, Button printButton) {
        StringBuilder previewScannerBuffer = new StringBuilder();
        previewDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            if (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_TAB) {
                if (previewScannerBuffer.length() > 0) {
                    previewScannerBuffer.setLength(0);
                    printButton.performClick();
                    return true;
                }
                return false;
            }

            int unicodeChar = event.getUnicodeChar();
            if (unicodeChar > 0) {
                previewScannerBuffer.append((char) unicodeChar);
                return true;
            }
            return false;
        });
    }

    private void addBoxPreviewImage(
            LinearLayout previewContent,
            String heading,
            String qrData,
            String labelText,
            String detailText,
            String footerText
    ) {
        previewContent.addView(previewText(heading, 15, true), spacedMatchWrap(dp(10)));
        Bitmap bitmap = createGenericLabelPreviewBitmap(qrData, labelText, detailText, footerText, getSelectedLabelSize());
        ImageView previewImage = new ImageView(this);
        previewImage.setImageBitmap(bitmap);
        previewImage.setAdjustViewBounds(true);
        previewImage.setPadding(dp(10), dp(10), dp(10), dp(10));
        previewImage.setBackground(roundStroke(Color.WHITE, Color.rgb(226, 232, 240), dp(10), 1));
        int previewWidth = Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(96));
        int previewHeight = previewWidth;
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(previewWidth, previewHeight);
        imageParams.gravity = Gravity.CENTER_HORIZONTAL;
        previewContent.addView(previewImage, imageParams);
        previewContent.addView(previewText("QR: " + qrData, 12, false), matchWrap());
        previewContent.addView(previewText("Details: " + detailText, 12, false), matchWrap());
    }

    private void addBoxGroupPreviewImage(
            LinearLayout previewContent,
            String heading,
            String qrData,
            List<Integer> indexes
    ) {
        previewContent.addView(previewText(heading, 15, true), spacedMatchWrap(dp(10)));
        Bitmap bitmap = createBoxGroupLabelPreviewBitmap(qrData, indexes);
        ImageView previewImage = new ImageView(this);
        previewImage.setImageBitmap(bitmap);
        previewImage.setAdjustViewBounds(true);
        previewImage.setPadding(dp(10), dp(10), dp(10), dp(10));
        previewImage.setBackground(roundStroke(Color.WHITE, Color.rgb(226, 232, 240), dp(10), 1));
        int previewWidth = Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(96));
        int previewHeight = Math.max(dp(120), Math.round(previewWidth * (bitmap.getHeight() / (float) bitmap.getWidth())));
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(previewWidth, previewHeight);
        imageParams.gravity = Gravity.CENTER_HORIZONTAL;
        previewContent.addView(previewImage, imageParams);
        previewContent.addView(previewText("QR: " + qrData, 12, false), matchWrap());
        previewContent.addView(previewText("Details: " + createBoxGroupDetailText(indexes), 12, false), matchWrap());
    }

    private void addBoxOuterPreviewImage(
            LinearLayout previewContent,
            String heading,
            List<Integer> indexes
    ) {
        String date = createBoxDateText();
        String time = createBoxTimeText();
        String qrData = createBoxOuterQrPayload(indexes, createBoxQrDateText(), time);
        previewContent.addView(previewText(heading, 15, true), spacedMatchWrap(dp(10)));
        Bitmap bitmap = createBoxOuterStickerPreviewBitmap(qrData, indexes, date, time);
        ImageView previewImage = new ImageView(this);
        previewImage.setImageBitmap(bitmap);
        previewImage.setAdjustViewBounds(true);
        previewImage.setPadding(dp(10), dp(10), dp(10), dp(10));
        previewImage.setBackground(roundStroke(Color.WHITE, Color.rgb(226, 232, 240), dp(10), 1));
        int previewWidth = Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(96));
        int previewHeight = Math.max(dp(140), Math.round(previewWidth * 0.62f));
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(previewWidth, previewHeight);
        imageParams.gravity = Gravity.CENTER_HORIZONTAL;
        previewContent.addView(previewImage, imageParams);
    }

    private Bitmap createLabelPreviewBitmap(String qrData) {
        if ("Reel QR".equals(activeQrSection)) {
            return createReelLabelPreviewBitmap();
        }
        return createGenericLabelPreviewBitmap(
                qrData,
                labelTextInput.getText().toString().trim(),
                createDetailText(),
                createFooterText(),
                getSelectedLabelSize()
        );
    }

    private Bitmap createGenericLabelPreviewBitmap(
            String qrData,
            String labelText,
            String detailText,
            String footerText,
            LabelSize labelSize
    ) {
        LabelSize size = labelSize == null || labelSize.isBlank() ? LabelSize.STANDARD_SIZES[3] : labelSize;
        int widthMm = size.getWidthMm();
        int heightMm = size.getHeightMm();
        int previewWidth = 900;
        int previewHeight = Math.max(220, Math.round(previewWidth * (heightMm / (float) widthMm)));
        Bitmap labelBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(labelBitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, previewWidth, previewHeight, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(Color.rgb(203, 213, 225));
        canvas.drawRoundRect(new RectF(2, 2, previewWidth - 2, previewHeight - 2), 16, 16, paint);
        paint.setStyle(Paint.Style.FILL);

        float scale = previewWidth / (float) (widthMm * 8);
        float margin = 32 * scale;
        int widthDots = widthMm * 8;
        int heightDots = heightMm * 8;
        int qrCellSize = Math.max(3, Math.min(7, Math.min(widthDots, heightDots) / 90));
        float qrSize = Math.min(previewHeight - (margin * 2), qrCellSize * 33f * scale);
        qrSize = Math.max(dp(64), qrSize);

        Bitmap qrBitmap = QrCodeGenerator.create(qrData, 800);
        RectF qrRect = new RectF(margin, margin, margin + qrSize, margin + qrSize);
        canvas.drawBitmap(qrBitmap, null, qrRect, null);

        paint.setColor(Color.rgb(17, 24, 39));
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(Math.max(22, 28 * scale));
        float textX = margin;
        float labelTextY = Math.max(margin + paint.getTextSize(), previewHeight - (96 * scale));
        canvas.drawText(labelText, textX, labelTextY, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(Math.max(18, 20 * scale));
        paint.setColor(Color.rgb(55, 65, 81));
        float detailTextY = Math.max(labelTextY + paint.getTextSize() + 8,
                previewHeight - ((footerText.isEmpty() ? 56 : 78) * scale));
        canvas.drawText(detailText, textX, detailTextY, paint);
        if (!footerText.isEmpty()) {
            float footerTextY = Math.max(detailTextY + paint.getTextSize() + 8, previewHeight - (38 * scale));
            canvas.drawText(footerText, textX, footerTextY, paint);
        }
        return labelBitmap;
    }

    private Bitmap createBoxGroupLabelPreviewBitmap(String qrData, List<Integer> indexes) {
        return createBoxGroupLabelPrintBitmap(qrData, indexes);
    }

    private Bitmap createBoxGroupLabelPrintBitmap(String qrData, List<Integer> indexes) {
        ReelScanItem firstReel = boxReels.get(indexes.get(0));
        int reelCount = getBoxGroupReelCount(indexes);
        if (reelCount <= 1) {
            return createSingleReelBoxLabelPrintBitmap(qrData, firstReel);
        }
        String brand = selectedBrand == null ? "" : selectedBrand.trim();
        if (brand.equalsIgnoreCase("Treveni")) {
            return createTreveniBoxGroupLabelPrintBitmap(qrData, indexes);
        } else if (brand.equalsIgnoreCase("JIPRO")) {
            return createJiproBoxGroupLabelPrintBitmap(qrData, indexes);
        } else if (brand.equalsIgnoreCase("Indica")) {
            return createIndicaBoxGroupLabelPrintBitmap(qrData, indexes);
        }
        int width = TsplBitmapEncoder.dotsForMm(LabelSize.BOX_4X4_INCH.getWidthMm());
        int height = TsplBitmapEncoder.dotsForMm(LabelSize.BOX_4X4_INCH.getHeightMm());
        Bitmap labelBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(labelBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface boxTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        float scale = width / 720f;

        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, width, height, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        paint.setColor(Color.BLACK);
        canvas.drawRoundRect(new RectF(scaled(18, scale), scaled(18, scale), width - scaled(18, scale), height - scaled(18, scale)), scaled(16, scale), scaled(16, scale), paint);
        paint.setStyle(Paint.Style.FILL);

        drawCenteredPrintText(canvas, paint, selectedBrand.trim().isEmpty() ? "JPI" : selectedBrand.trim(), width / 2f, scaled(66, scale), scaled(40, scale), boxTypeface);
        drawCenteredPrintText(canvas, paint, "SUPER ENAMELLED COPPER WINDING WIRE", width / 2f, scaled(96, scale), scaled(17, scale), boxTypeface);
        paint.setStrokeWidth(3);
        canvas.drawLine(scaled(48, scale), scaled(118, scale), width - scaled(48, scale), scaled(118, scale), paint);

        drawFitPrintText(canvas, paint, reelCount <= 1 ? "SINGLE REEL BOX" : "MULTIPLE REEL BOX", scaled(54, scale), scaled(157, scale), scaled(310, scale), scaled(20, scale), boxTypeface);
        drawFitPrintText(canvas, paint, "ID: " + getBoxGroupUniqueId(indexes), scaled(476, scale), scaled(157, scale), scaled(190, scale), scaled(22, scale), boxTypeface);

        drawBoxInfoTile(canvas, paint, "SWG", displayValue(firstReel.swg), 54, 178, 150, 108, scale, boxTypeface);
        drawBoxInfoTile(canvas, paint, "SPOOL", displayValue(firstReel.spoolSize), 218, 178, 120, 108, scale, boxTypeface);
        drawBoxInfoTile(canvas, paint, "COLOUR", boxColourName(firstReel.colour), 352, 178, 130, 108, scale, boxTypeface);

        Bitmap qrBitmap = QrCodeGenerator.create(qrData, 700);
        canvas.drawBitmap(qrBitmap, null, new RectF(scaled(508, scale), scaled(166, scale), scaled(640, scale), scaled(298, scale)), null);

        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(scaled(54, scale), scaled(306, scale), scaled(666, scale), scaled(418, scale)), scaled(12, scale), scaled(12, scale), paint);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        canvas.drawRoundRect(new RectF(scaled(54, scale), scaled(306, scale), scaled(666, scale), scaled(418, scale)), scaled(12, scale), scaled(12, scale), paint);
        paint.setStyle(Paint.Style.FILL);
        drawFitPrintText(canvas, paint, "TOTAL NET WT.", scaled(82, scale), scaled(342, scale), scaled(260, scale), scaled(24, scale), boxTypeface);
        drawFitPrintText(canvas, paint, getBoxGroupNetWeight(indexes) + " kg", scaled(80, scale), scaled(402, scale), scaled(420, scale), scaled(58, scale), boxTypeface);
        drawFitPrintText(canvas, paint, "REELS: " + reelCount, scaled(510, scale), scaled(382, scale), scaled(135, scale), scaled(22, scale), boxTypeface);

        drawFitPrintText(canvas, paint, "REEL WEIGHTS", scaled(54, scale), scaled(458, scale), scaled(240, scale), scaled(22, scale), boxTypeface);
        paint.setStrokeWidth(2);
        canvas.drawLine(scaled(54, scale), scaled(470, scale), scaled(666, scale), scaled(470, scale), paint);
        drawBoxWeightGrid(canvas, paint, createBoxGroupWeightList(indexes), scale, boxTypeface);
        drawFitPrintText(canvas, paint, "PACKED BY: " + getEnteredByName(), scaled(54, scale), scaled(678, scale), scaled(430, scale), scaled(20, scale), boxTypeface);
        return labelBitmap;
    }

    private Bitmap createTreveniBoxGroupLabelPrintBitmap(String qrData, List<Integer> indexes) {
        ReelScanItem firstReel = boxReels.get(indexes.get(0));
        int reelCount = getBoxGroupReelCount(indexes);
        int width = TsplBitmapEncoder.dotsForMm(LabelSize.BOX_4X4_INCH.getWidthMm());
        int height = TsplBitmapEncoder.dotsForMm(LabelSize.BOX_4X4_INCH.getHeightMm());
        Bitmap labelBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(labelBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface boxTypeface = Typeface.create("sans-serif", Typeface.BOLD);
        float scale = width / 720f;

        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(scaled(4, scale));
        canvas.drawRoundRect(new RectF(scaled(18, scale), scaled(18, scale), width - scaled(18, scale), height - scaled(18, scale)), scaled(16, scale), scaled(16, scale), paint);
        paint.setStyle(Paint.Style.FILL);

        drawFitPrintText(canvas, paint, "A SYMBOL OF QUALITY", scaled(46, scale), scaled(58, scale), scaled(330, scale), scaled(28, scale), boxTypeface);
        drawFitPrintText(canvas, paint, "SUPER ENAMELLED WINDING COPPER WIRE", scaled(46, scale), scaled(86, scale), scaled(390, scale), scaled(16, scale), boxTypeface);
        drawCenteredPrintText(canvas, paint, "Treveni", scaled(536, scale), scaled(86, scale), scaled(44, scale), boxTypeface);
        paint.setStrokeWidth(scaled(4, scale));
        canvas.drawLine(scaled(38, scale), scaled(116, scale), width - scaled(38, scale), scaled(116, scale), paint);

        drawLabelCell(canvas, paint, "SWG", 44, 142, 142, 80, scale, boxTypeface, 18);
        drawLabelCell(canvas, paint, "SPOOL", 186, 142, 142, 80, scale, boxTypeface, 18);
        drawLabelCell(canvas, paint, "COLOR", 328, 142, 142, 80, scale, boxTypeface, 18);
        drawLabelCell(canvas, paint, "REELS", 470, 142, 142, 80, scale, boxTypeface, 18);
        drawValueCell(canvas, paint, displayValue(firstReel.swg), 44, 222, 142, 80, scale, boxTypeface, 36);
        drawValueCell(canvas, paint, displayValue(firstReel.spoolSize), 186, 222, 142, 80, scale, boxTypeface, 36);
        drawValueCell(canvas, paint, boxColourName(firstReel.colour), 328, 222, 142, 80, scale, boxTypeface, 24);
        drawValueCell(canvas, paint, String.valueOf(reelCount), 470, 222, 142, 80, scale, boxTypeface, 36);

        drawLabelCell(canvas, paint, "NET WT.", 44, 330, 358, 118, scale, boxTypeface, 24);
        drawValueCell(canvas, paint, getBoxGroupNetWeight(indexes) + "kg", 44, 370, 358, 78, scale, boxTypeface, 48);
        Bitmap qrBitmap = QrCodeGenerator.create(qrData, 700);
        canvas.drawBitmap(qrBitmap, null, new RectF(scaled(458, scale), scaled(320, scale), scaled(594, scale), scaled(456, scale)), null);

        drawFitPrintText(canvas, paint, "REEL WEIGHTS", scaled(54, scale), scaled(492, scale), scaled(240, scale), scaled(20, scale), boxTypeface);
        drawCompactBoxWeightGrid(canvas, paint, createBoxGroupWeightList(indexes), scale, boxTypeface, 510, 30);
        drawFitPrintText(canvas, paint, "PACKED BY: " + getEnteredByName(), scaled(54, scale), scaled(638, scale), scaled(430, scale), scaled(18, scale), boxTypeface);
        return labelBitmap;
    }

    private Bitmap createJiproBoxGroupLabelPrintBitmap(String qrData, List<Integer> indexes) {
        ReelScanItem firstReel = boxReels.get(indexes.get(0));
        int reelCount = getBoxGroupReelCount(indexes);
        int width = TsplBitmapEncoder.dotsForMm(LabelSize.BOX_4X4_INCH.getWidthMm());
        int height = TsplBitmapEncoder.dotsForMm(LabelSize.BOX_4X4_INCH.getHeightMm());
        Bitmap labelBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(labelBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface boxTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        float scale = width / 720f;

        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(scaled(4, scale));
        canvas.drawRoundRect(new RectF(scaled(18, scale), scaled(18, scale), width - scaled(18, scale), height - scaled(18, scale)), scaled(16, scale), scaled(16, scale), paint);

        RectF headerRect = new RectF(scaled(38, scale), scaled(38, scale), scaled(612, scale), scaled(116, scale));
        canvas.drawRect(headerRect, paint);
        paint.setStyle(Paint.Style.FILL);
        drawFitPrintText(canvas, paint, "JIPRO", scaled(60, scale), scaled(91, scale), scaled(160, scale), scaled(52, scale), boxTypeface);
        drawFitPrintText(canvas, paint, "SUPER ENAMELLED", scaled(246, scale), scaled(64, scale), scaled(250, scale), scaled(16, scale), boxTypeface);
        drawFitPrintText(canvas, paint, "COPPER WINDING WIRE", scaled(246, scale), scaled(91, scale), scaled(260, scale), scaled(16, scale), boxTypeface);

        drawFitPrintText(canvas, paint, "BOX LABEL", scaled(44, scale), scaled(166, scale), scaled(220, scale), scaled(20, scale), boxTypeface);
        drawFitPrintText(canvas, paint, "ID: " + getBoxGroupUniqueId(indexes), scaled(460, scale), scaled(166, scale), scaled(160, scale), scaled(20, scale), boxTypeface);
        drawBoxInfoTile(canvas, paint, "WIRE SIZE", displayValue(firstReel.swg), 44, 188, 214, 96, scale, boxTypeface);
        drawBoxInfoTile(canvas, paint, "SPOOL", displayValue(firstReel.spoolSize), 276, 188, 104, 96, scale, boxTypeface);
        drawBoxInfoTile(canvas, paint, "COLOR", boxColourName(firstReel.colour), 398, 188, 118, 96, scale, boxTypeface);
        drawBoxInfoTile(canvas, paint, "REELS", String.valueOf(reelCount), 538, 182, 76, 76, scale, boxTypeface);

        drawLabelCell(canvas, paint, "TOTAL NET WEIGHT", 44, 318, 340, 44, scale, boxTypeface, 20);
        drawValueCell(canvas, paint, getBoxGroupNetWeight(indexes) + " kg", 44, 362, 340, 74, scale, boxTypeface, 46);
        Bitmap qrBitmap = QrCodeGenerator.create(qrData, 700);
        canvas.drawBitmap(qrBitmap, null, new RectF(scaled(432, scale), scaled(304, scale), scaled(568, scale), scaled(440, scale)), null);

        drawFitPrintText(canvas, paint, "INDIVIDUAL REEL WT.", scaled(44, scale), scaled(474, scale), scaled(260, scale), scaled(20, scale), boxTypeface);
        drawCompactBoxWeightGrid(canvas, paint, createBoxGroupWeightList(indexes), scale, boxTypeface, 492, 0);
        paint.setStrokeWidth(scaled(2, scale));
        canvas.drawLine(scaled(44, scale), scaled(604, scale), width - scaled(108, scale), scaled(604, scale), paint);
        drawFitPrintText(canvas, paint, "PACKED BY: " + getEnteredByName(), scaled(44, scale), scaled(634, scale), scaled(430, scale), scaled(18, scale), boxTypeface);
        return labelBitmap;
    }

    private Bitmap createIndicaBoxGroupLabelPrintBitmap(String qrData, List<Integer> indexes) {
        ReelScanItem firstReel = boxReels.get(indexes.get(0));
        int reelCount = getBoxGroupReelCount(indexes);
        int width = TsplBitmapEncoder.dotsForMm(LabelSize.BOX_4X4_INCH.getWidthMm());
        int height = TsplBitmapEncoder.dotsForMm(LabelSize.BOX_4X4_INCH.getHeightMm());
        Bitmap labelBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(labelBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface boxTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        float scale = width / 720f;

        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(scaled(4, scale));
        canvas.drawRoundRect(new RectF(scaled(18, scale), scaled(18, scale), width - scaled(18, scale), height - scaled(18, scale)), scaled(16, scale), scaled(16, scale), paint);
        paint.setStyle(Paint.Style.FILL);

        drawFitPrintText(canvas, paint, "INDICA", scaled(46, scale), scaled(64, scale), scaled(220, scale), scaled(42, scale), boxTypeface);
        drawFitPrintText(canvas, paint, "COPPER WINDING WIRE / BOX IDENTIFICATION", scaled(46, scale), scaled(94, scale), scaled(430, scale), scaled(15, scale), boxTypeface);
        drawCenteredPrintText(canvas, paint, "ID: " + getBoxGroupUniqueId(indexes), scaled(536, scale), scaled(64, scale), scaled(16, scale), boxTypeface);
        drawCenteredPrintText(canvas, paint, "REELS: " + reelCount, scaled(536, scale), scaled(94, scale), scaled(16, scale), boxTypeface);
        paint.setStrokeWidth(scaled(4, scale));
        canvas.drawLine(scaled(38, scale), scaled(118, scale), width - scaled(38, scale), scaled(118, scale), paint);

        drawLabelCell(canvas, paint, "SWG", 44, 144, 157, 56, scale, boxTypeface, 15);
        drawLabelCell(canvas, paint, "SPOOL", 201, 144, 157, 56, scale, boxTypeface, 15);
        drawLabelCell(canvas, paint, "COLOR", 358, 144, 157, 56, scale, boxTypeface, 15);
        drawLabelCell(canvas, paint, "MATERIAL", 515, 144, 157, 56, scale, boxTypeface, 15);
        drawValueCell(canvas, paint, displayValue(firstReel.swg), 44, 200, 157, 56, scale, boxTypeface, 34);
        drawValueCell(canvas, paint, displayValue(firstReel.spoolSize), 201, 200, 157, 56, scale, boxTypeface, 34);
        drawValueCell(canvas, paint, boxColourName(firstReel.colour), 358, 200, 157, 56, scale, boxTypeface, 24);
        drawValueCell(canvas, paint, "Cu", 515, 200, 157, 56, scale, boxTypeface, 26);

        drawLabelCell(canvas, paint, "TOTAL NET WT.", 44, 282, 390, 46, scale, boxTypeface, 18);
        drawValueCell(canvas, paint, getBoxGroupNetWeight(indexes) + " kg", 44, 328, 390, 80, scale, boxTypeface, 46);
        Bitmap qrBitmap = QrCodeGenerator.create(qrData, 700);
        canvas.drawBitmap(qrBitmap, null, new RectF(scaled(494, scale), scaled(276, scale), scaled(626, scale), scaled(408, scale)), null);

        RectF weightPanel = new RectF(scaled(44, scale), scaled(438, scale), scaled(672, scale), scaled(618, scale));
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(weightPanel, scaled(8, scale), scaled(8, scale), paint);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(scaled(3, scale));
        canvas.drawRoundRect(weightPanel, scaled(8, scale), scaled(8, scale), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        canvas.drawRect(scaled(44, scale), scaled(438, scale), scaled(672, scale), scaled(474, scale), paint);
        drawFitPrintText(canvas, paint, "REEL WEIGHTS", scaled(62, scale), scaled(463, scale), scaled(260, scale), scaled(16, scale), boxTypeface);
        drawCenteredPrintText(canvas, paint, reelCount + " VALUES", scaled(544, scale), scaled(463, scale), scaled(16, scale), boxTypeface);
        drawIndicaWeightGrid(canvas, paint, createBoxGroupWeightList(indexes), scale, boxTypeface);

        drawFitPrintText(canvas, paint, "PACKED BY: " + getEnteredByName(), scaled(44, scale), scaled(656, scale), scaled(360, scale), scaled(15, scale), boxTypeface);
        drawFitPrintText(canvas, paint, "PRINTED BOX LABEL", scaled(456, scale), scaled(656, scale), scaled(220, scale), scaled(15, scale), boxTypeface);
        return labelBitmap;
    }

    private Bitmap createSingleReelBoxLabelPrintBitmap(String qrData, ReelScanItem reel) {
        int width = TsplBitmapEncoder.dotsForMm(LabelSize.BOX_4X4_INCH.getWidthMm());
        int height = TsplBitmapEncoder.dotsForMm(LabelSize.BOX_4X4_INCH.getHeightMm());
        Bitmap labelBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(labelBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface boxTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        float scale = width / 720f;

        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        canvas.drawRoundRect(new RectF(scaled(18, scale), scaled(18, scale), width - scaled(18, scale), height - scaled(18, scale)), scaled(16, scale), scaled(16, scale), paint);
        paint.setStyle(Paint.Style.FILL);

        drawCenteredPrintText(canvas, paint, selectedBrand.trim().isEmpty() ? "JPI" : selectedBrand.trim(), width / 2f, scaled(60, scale), scaled(34, scale), boxTypeface);
        drawCenteredPrintText(canvas, paint, "SUPER ENAMELLED COPPER WINDING WIRE", width / 2f, scaled(90, scale), scaled(16, scale), boxTypeface);
        paint.setStrokeWidth(3);
        canvas.drawLine(scaled(42, scale), scaled(118, scale), width - scaled(42, scale), scaled(118, scale), paint);

        drawLabelCell(canvas, paint, "SWG", 42, 138, 154, 70, scale, boxTypeface, 22);
        drawValueCell(canvas, paint, displayValue(reel.swg), 196, 138, 142, 70, scale, boxTypeface, 34);
        drawLabelCell(canvas, paint, "SPOOL COLOR", 338, 138, 170, 70, scale, boxTypeface, 18);
        drawValueCell(canvas, paint, boxColourName(reel.colour), 508, 138, 170, 70, scale, boxTypeface, 22);

        drawLabelCell(canvas, paint, "TESTED BY", 42, 208, 154, 56, scale, boxTypeface, 18);
        drawValueCell(canvas, paint, getEnteredByName(), 196, 208, 142, 56, scale, boxTypeface, 18);
        drawLabelCell(canvas, paint, "PACKED BY", 338, 208, 170, 56, scale, boxTypeface, 18);
        drawValueCell(canvas, paint, displayValue(reel.packedBy), 508, 208, 170, 56, scale, boxTypeface, 18);

        drawLabelCell(canvas, paint, "SPOOL SIZE", 42, 264, 154, 56, scale, boxTypeface, 18);
        drawValueCell(canvas, paint, displayValue(reel.spoolSize), 196, 264, 142, 56, scale, boxTypeface, 20);
        drawLabelCell(canvas, paint, "MATERIAL", 338, 264, 170, 56, scale, boxTypeface, 18);
        drawValueCell(canvas, paint, "Cu", 508, 264, 170, 56, scale, boxTypeface, 20);

        drawLabelCell(canvas, paint, "NET WT.", 42, 336, 210, 80, scale, boxTypeface, 30);
        drawValueCell(canvas, paint, displayValue(reel.netWeight) + "kg", 252, 336, 426, 80, scale, boxTypeface, 46);

        drawLabelCell(canvas, paint, "BATCH", 42, 430, 150, 48, scale, boxTypeface, 17);
        drawValueCell(canvas, paint, displayValue(reel.uniqueId), 192, 430, 188, 48, scale, boxTypeface, 18);
        drawLabelCell(canvas, paint, "TARE NO.", 42, 478, 150, 48, scale, boxTypeface, 17);
        drawValueCell(canvas, paint, "-", 192, 478, 188, 48, scale, boxTypeface, 18);
        drawLabelCell(canvas, paint, "CLASS", 42, 526, 150, 48, scale, boxTypeface, 17);
        drawValueCell(canvas, paint, "-", 192, 526, 188, 48, scale, boxTypeface, 18);
        drawLabelCell(canvas, paint, "CUST PO", 42, 574, 150, 48, scale, boxTypeface, 17);
        drawValueCell(canvas, paint, "-", 192, 574, 188, 48, scale, boxTypeface, 18);

        Bitmap qrBitmap = QrCodeGenerator.create(qrData, 700);
        canvas.drawBitmap(qrBitmap, null, new RectF(scaled(434, scale), scaled(438, scale), scaled(636, scale), scaled(640, scale)), null);

        drawCenteredPrintText(canvas, paint, "MFG. OF ALL KIND OF COPPER WINDING WIRE", width / 2f, scaled(680, scale), scaled(20, scale), boxTypeface);
        return labelBitmap;
    }

    private void drawLabelCell(Canvas canvas, Paint paint, String text, float x, float y, float width, float height, float scale, Typeface typeface, float textSize) {
        drawTableCell(canvas, paint, text, x, y, width, height, scale, typeface, textSize, false);
    }

    private void drawValueCell(Canvas canvas, Paint paint, String text, float x, float y, float width, float height, float scale, Typeface typeface, float textSize) {
        drawTableCell(canvas, paint, text, x, y, width, height, scale, typeface, textSize, true);
    }

    private void drawTableCell(Canvas canvas, Paint paint, String text, float x, float y, float width, float height, float scale, Typeface typeface, float textSize, boolean centered) {
        RectF rect = new RectF(scaled(x, scale), scaled(y, scale), scaled(x + width, scale), scaled(y + height, scale));
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(rect, paint);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        canvas.drawRect(rect, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(typeface);
        paint.setTextSize(scaled(textSize, scale));
        paint.setTextAlign(centered ? Paint.Align.CENTER : Paint.Align.LEFT);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = rect.centerY() - ((metrics.ascent + metrics.descent) / 2f);
        float textX = centered ? rect.centerX() : rect.left + scaled(10, scale);
        canvas.drawText(text, textX, baseline, paint);
    }

    private Bitmap createBoxOuterStickerPreviewBitmap(String qrData, List<Integer> indexes, String date, String time) {
        ReelScanItem firstReel = boxReels.get(indexes.get(0));
        int previewWidth = 900;
        int previewHeight = 560;
        Bitmap labelBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(labelBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, previewWidth, previewHeight, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(Color.rgb(203, 213, 225));
        canvas.drawRect(2, 2, previewWidth - 2, previewHeight - 2, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(Color.BLACK);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(32);
        canvas.drawText(selectedBrand.trim(), 70, 74, paint);

        paint.setTextSize(44);
        canvas.drawText("S.W.G", 115, 170, paint);
        canvas.drawText(displayValue(firstReel.swg), 128, 228, paint);
        canvas.drawText(boxColourName(firstReel.colour), 128, 286, paint);

        Bitmap qrBitmap = QrCodeGenerator.create(qrData, 500);
        canvas.drawBitmap(qrBitmap, null, new RectF(420, 54, 610, 244), null);

        paint.setTextSize(54);
        canvas.drawText("NET WT.", 520, 326, paint);
        paint.setTextSize(84);
        canvas.drawText(getBoxGroupNetWeight(indexes) + " kg", 420, 450, paint);

        return labelBitmap;
    }

    private Bitmap createReelLabelPreviewBitmap() {
        int previewWidth = 900;
        int previewHeight = 600;
        Bitmap labelBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(labelBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, previewWidth, previewHeight, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(Color.rgb(190, 190, 190));
        canvas.drawRect(2, 2, previewWidth - 2, previewHeight - 2, paint);
        paint.setStyle(Paint.Style.FILL);

        Bitmap printBitmap = createReelLabelPrintBitmap();
        canvas.drawBitmap(printBitmap, null, new RectF(6, 6, previewWidth - 6, previewHeight - 6), null);
        return labelBitmap;
    }

    private Bitmap createReelLabelPrintBitmap() {
        int width = TsplBitmapEncoder.dotsForMm(LabelSize.REEL_3X2_INCH.getWidthMm());
        int height = TsplBitmapEncoder.dotsForMm(LabelSize.REEL_3X2_INCH.getHeightMm());
        Bitmap labelBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(labelBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, width, height, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(Color.BLACK);
        canvas.drawRect(1, 1, width - 2, height - 2, paint);
        paint.setStyle(Paint.Style.FILL);

        Typeface reelTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        drawCenteredPrintText(canvas, paint, "SUPER ENAMELLED COPPER WINDING WIRE", width / 2f, 28, 24, reelTypeface);
        paint.setStrokeWidth(2);
        canvas.drawLine(16, 43, width - 16, 43, paint);

        drawFitPrintText(canvas, paint, "SWG", 24, 72, 180, 17, reelTypeface);
        drawFitPrintText(canvas, paint, selectedSwg, 22, 130, 250, 58, reelTypeface);
        drawFitPrintText(canvas, paint, "COLOUR", 318, 72, 160, 17, reelTypeface);
        drawFitPrintText(canvas, paint, selectedColour, 316, 128, 270, 46, reelTypeface);

        paint.setColor(Color.rgb(248, 248, 248));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(18, 150, width - 18, 282), 8, 8, paint);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        canvas.drawRoundRect(new RectF(18, 150, width - 18, 282), 8, 8, paint);
        paint.setStyle(Paint.Style.FILL);

        drawFitPrintText(canvas, paint, "NET WT.", 38, 188, 250, 24, reelTypeface);
        drawFitPrintText(canvas, paint, netWeight + "kg", 36, 250, 390, 60, reelTypeface);

        Bitmap packedQr = QrCodeGenerator.create(createReelQrData(), 420);
        canvas.drawBitmap(packedQr, null, new RectF(450, 158, 566, 274), null);

        drawFitPrintText(canvas, paint, "GROSS WT: " + grossWeight + "kg", 28, 322, 275, 22, reelTypeface);
        drawFitPrintText(canvas, paint, "TARE WT: " + tareWeight + "kg", 28, 352, 275, 22, reelTypeface);
        drawFitPrintText(canvas, paint, "SPOOL: " + selectedSpoolSize, 320, 322, 260, 22, reelTypeface);
        drawFitPrintText(canvas, paint, (isReelBatchMode() ? "BATCH: " : "ID: ") + getCurrentReelUniqueId(), 320, 352, 260, 20, reelTypeface);
        drawFitPrintText(canvas, paint, "PACKED BY: " + getEnteredByName(), 28, 388, 560, 20, reelTypeface);
        return labelBitmap;
    }

    private Bitmap createReelBatchSummaryPrintBitmap(String batchId, String totalNetWeight, int reelCount) {
        int width = TsplBitmapEncoder.dotsForMm(LabelSize.REEL_3X2_INCH.getWidthMm());
        int height = TsplBitmapEncoder.dotsForMm(LabelSize.REEL_3X2_INCH.getHeightMm());
        Bitmap labelBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(labelBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface reelTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD);

        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, width, height, paint);

        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        canvas.drawRect(1, 1, width - 2, height - 2, paint);
        paint.setStyle(Paint.Style.FILL);

        drawCenteredPrintText(canvas, paint, "REEL BATCH SUMMARY", width / 2f, 38, 30, reelTypeface);
        canvas.drawLine(18, 52, width - 18, 52, paint);

        drawFitPrintText(canvas, paint, "BATCH ID", 28, 92, 250, 24, reelTypeface);
        drawFitPrintText(canvas, paint, batchId, 28, 142, 360, 48, reelTypeface);

        drawFitPrintText(canvas, paint, "TOTAL NET WT.", 28, 202, 290, 25, reelTypeface);
        drawFitPrintText(canvas, paint, totalNetWeight + "kg", 28, 270, 370, 64, reelTypeface);
        drawFitPrintText(canvas, paint, "REELS: " + reelCount, 28, 332, 220, 30, reelTypeface);
        drawFitPrintText(canvas, paint, "PACKED BY: " + getEnteredByName(), 28, 382, 360, 20, reelTypeface);

        String qrData = batchId + "," + selectedSwg + "," + colourQrCode(selectedColour) + "," + selectedSpoolSize + "," + totalNetWeight + "kg," + reelCount + "," + createBatchReelWeightsPayload();
        Bitmap batchQr = QrCodeGenerator.create(qrData, 420);
        canvas.drawBitmap(batchQr, null, new RectF(420, 115, 580, 275), null);
        return labelBitmap;
    }

    private void drawFitPrintText(Canvas canvas, Paint paint, String text, float x, float baseline, float maxWidth, float size, Typeface typeface) {
        paint.setColor(Color.BLACK);
        paint.setAntiAlias(true);
        paint.setTypeface(typeface);
        paint.setTextAlign(Paint.Align.LEFT);
        float textSize = size;
        paint.setTextSize(textSize);
        while (paint.measureText(text) > maxWidth && textSize > 18f) {
            textSize -= 1f;
            paint.setTextSize(textSize);
        }
        canvas.drawText(text, x, baseline, paint);
    }

    private float scaled(float value, float scale) {
        return value * scale;
    }

    private void drawBoxLogoHeader(Canvas canvas, Paint paint, float scale) {
        Bitmap logo = BitmapFactory.decodeResource(getResources(), R.drawable.jpi_logo_header);
        if (logo == null) {
            Typeface fallbackTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            drawCenteredPrintText(canvas, paint, "JPI", scaled(360, scale), scaled(66, scale), scaled(40, scale), fallbackTypeface);
            drawCenteredPrintText(canvas, paint, "SUPER ENAMELLED COPPER WINDING WIRE", scaled(360, scale), scaled(96, scale), scaled(17, scale), fallbackTypeface);
            return;
        }

        RectF destination = new RectF(scaled(78, scale), scaled(26, scale), scaled(642, scale), scaled(142, scale));
        canvas.drawBitmap(logo, findContentBounds(logo), destination, null);
    }

    private android.graphics.Rect findContentBounds(Bitmap bitmap) {
        int left = bitmap.getWidth();
        int top = bitmap.getHeight();
        int right = 0;
        int bottom = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.red(pixel) < 245 || Color.green(pixel) < 245 || Color.blue(pixel) < 245) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (left >= right || top >= bottom) {
            return new android.graphics.Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }
        int padding = Math.max(6, bitmap.getWidth() / 45);
        return new android.graphics.Rect(
                Math.max(0, left - padding),
                Math.max(0, top - padding),
                Math.min(bitmap.getWidth(), right + padding),
                Math.min(bitmap.getHeight(), bottom + padding)
        );
    }

    private void drawBoxInfoTile(Canvas canvas, Paint paint, String label, String value, float x, float y, float width, float height, float scale, Typeface typeface) {
        RectF rect = new RectF(scaled(x, scale), scaled(y, scale), scaled(x + width, scale), scaled(y + height, scale));
        paint.setColor(Color.rgb(248, 248, 248));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(rect, scaled(10, scale), scaled(10, scale), paint);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(scaled(3, scale));
        canvas.drawRoundRect(rect, scaled(10, scale), scaled(10, scale), paint);
        paint.setStyle(Paint.Style.FILL);
        drawFitPrintText(canvas, paint, label, scaled(x + 22, scale), scaled(y + 35, scale), scaled(width - 30, scale), scaled(20, scale), typeface);
        drawFitPrintText(canvas, paint, value, scaled(x + 20, scale), scaled(y + 92, scale), scaled(width - 30, scale), scaled(50, scale), typeface);
    }

    private void drawBoxWeightGrid(Canvas canvas, Paint paint, List<String> weights, float scale, Typeface typeface) {
        int count = Math.max(1, weights.size());
        int columns = count <= 2 ? count : count <= 4 ? 2 : count > 16 ? 6 : 4;
        int rows = (int) Math.ceil(count / (double) columns);
        float startX = 54;
        float startY = count <= 2 ? 514 : count <= 4 ? 500 : 486;
        float gapX = 18;
        float gapY = 10;
        float tileWidth = (612 - ((columns - 1) * gapX)) / columns;
        float tileHeight = Math.min(94, (650 - startY - ((rows - 1) * gapY)) / rows);
        float fontSize = count <= 2 ? 42 : count <= 4 ? 34 : count <= 8 ? 29 : count > 16 ? 20 : 23;

        for (int index = 0; index < weights.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            float x = startX + column * (tileWidth + gapX);
            float y = startY + row * (tileHeight + gapY);
            RectF rect = new RectF(scaled(x, scale), scaled(y, scale), scaled(x + tileWidth, scale), scaled(y + tileHeight, scale));
            paint.setColor(Color.rgb(248, 248, 248));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(rect, scaled(8, scale), scaled(8, scale), paint);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(scaled(2, scale));
            canvas.drawRoundRect(rect, scaled(8, scale), scaled(8, scale), paint);
            paint.setStyle(Paint.Style.FILL);

            String weight = weights.get(index);
            paint.setTypeface(typeface);
            paint.setTextSize(scaled(fontSize, scale));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.BLACK);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = scaled(y + (tileHeight / 2f), scale) - ((metrics.ascent + metrics.descent) / 2f);
            canvas.drawText(weight, scaled(x + (tileWidth / 2f), scale), baseline, paint);
        }
    }

    private void drawCompactBoxWeightGrid(Canvas canvas, Paint paint, List<String> weights, float scale, Typeface typeface, float startY, float xOffset) {
        int columns = 6;
        float startX = 54 + xOffset;
        float gapX = 8;
        float gapY = 8;
        float tileWidth = (560 - ((columns - 1) * gapX)) / columns;
        float tileHeight = 30;
        float fontSize = 13;

        for (int index = 0; index < weights.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            float x = startX + column * (tileWidth + gapX);
            float y = startY + row * (tileHeight + gapY);
            RectF rect = new RectF(scaled(x, scale), scaled(y, scale), scaled(x + tileWidth, scale), scaled(y + tileHeight, scale));
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(rect, paint);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(scaled(1.5f, scale));
            canvas.drawRect(rect, paint);
            paint.setStyle(Paint.Style.FILL);

            paint.setTypeface(typeface);
            paint.setTextSize(scaled(fontSize, scale));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.BLACK);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = scaled(y + (tileHeight / 2f), scale) - ((metrics.ascent + metrics.descent) / 2f);
            canvas.drawText(weights.get(index), scaled(x + (tileWidth / 2f), scale), baseline, paint);
        }
    }

    private void drawIndicaWeightGrid(Canvas canvas, Paint paint, List<String> weights, float scale, Typeface typeface) {
        int columns = 6;
        float startX = 58;
        float startY = 488;
        float tileWidth = 88;
        float tileHeight = 30;
        float gapX = 12;
        float gapY = 14;
        float fontSize = 15;

        for (int index = 0; index < weights.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            float x = startX + column * (tileWidth + gapX);
            float y = startY + row * (tileHeight + gapY);
            RectF rect = new RectF(scaled(x, scale), scaled(y, scale), scaled(x + tileWidth, scale), scaled(y + tileHeight, scale));
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(rect, paint);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(scaled(1.5f, scale));
            canvas.drawRect(rect, paint);
            paint.setStyle(Paint.Style.FILL);

            paint.setTypeface(typeface);
            paint.setTextSize(scaled(fontSize, scale));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.BLACK);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = scaled(y + (tileHeight / 2f), scale) - ((metrics.ascent + metrics.descent) / 2f);
            canvas.drawText(weights.get(index), scaled(x + (tileWidth / 2f), scale), baseline, paint);
        }
    }

    private void drawCenteredPrintText(Canvas canvas, Paint paint, String text, float centerX, float baseline, float size, Typeface typeface) {
        paint.setColor(Color.BLACK);
        paint.setAntiAlias(true);
        paint.setTypeface(typeface);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, centerX, baseline, paint);
    }

    private void drawBoldText(Canvas canvas, Paint paint, String text, float x, float baseline, float size) {
        paint.setColor(Color.BLACK);
        paint.setAntiAlias(true);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(text, x, baseline, paint);
    }

    private void drawCenteredText(Canvas canvas, Paint paint, String text, float centerX, float baseline, float size) {
        paint.setColor(Color.BLACK);
        paint.setAntiAlias(true);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, centerX, baseline, paint);
    }

    private void findUsbPrinter() {
        usbPrinterManager.loadPrinters();
        visibleBluetoothPrinters.clear();
        showPrinterPicker("Select USB TSC printer", "No USB printer found. Connect the printer with an OTG cable, then tap Find USB again.");
    }

    private void findBluetoothPrinter() {
        if (printerManager.isBluetoothAvailable() && !printerManager.isBluetoothEnabled()) {
            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            Toast.makeText(this, "Turn on Bluetooth, then come back to search.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!printerManager.isBluetoothAvailable()) {
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasNeededBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }

        visibleUsbPrinters.clear();
        printerManager.startDiscovery();
        showPrinterPicker("Select Bluetooth TSC printer", "No Bluetooth printer found yet. Keep the printer on and wait for the scan.");
    }

    private void connectUsbScale() {
        if (usbScaleManager == null) {
            Toast.makeText(this, "USB scale reader is not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        int baudRate = 2400;
        ScaleSerialFormat format = new ScaleSerialFormat(7, UsbSerialPort.PARITY_EVEN, UsbSerialPort.STOPBITS_1);
        setScaleStatusText("Searching for USB scale...");
        usbScaleManager.connectFirstScale(baudRate, format.dataBits, format.parity, format.stopBits);
    }

    private void startScaleDiagnosticTest() {
        scaleDiagnosticRunning = true;
        scaleDiagnosticRawCount = 0;
        scaleDiagnosticParsedCount = 0;
        scaleDiagnosticLastMessageAtMs = 0L;
        scaleDiagnosticLog.setLength(0);
        appendScaleDiagnosticLog("TEST STARTED");
        updateScaleDiagnosticSummary("-", "");
    }

    private void stopScaleDiagnosticTest() {
        scaleDiagnosticRunning = false;
        appendScaleDiagnosticLog("TEST STOPPED");
    }

    private void clearScaleDiagnosticLog() {
        scaleDiagnosticRawCount = 0;
        scaleDiagnosticParsedCount = 0;
        scaleDiagnosticLastMessageAtMs = 0L;
        scaleDiagnosticLog.setLength(0);
        updateScaleDiagnosticSummary("-", "");
        refreshScaleDiagnosticLog();
    }

    private void appendScaleDiagnosticRaw(String data) {
        if (!scaleDiagnosticRunning) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        String gapText = scaleDiagnosticLastMessageAtMs == 0L ? "-" : (now - scaleDiagnosticLastMessageAtMs) + " ms";
        scaleDiagnosticLastMessageAtMs = now;
        scaleDiagnosticRawCount++;
        appendScaleDiagnosticLog(timestampText() + " RAW    [" + gapText + "] " + data);
        updateScaleDiagnosticSummary(gapText, null);
    }

    private void appendScaleDiagnosticParsed(String weight) {
        if (!scaleDiagnosticRunning) {
            return;
        }
        scaleDiagnosticParsedCount++;
        appendScaleDiagnosticLog(timestampText() + " PARSED " + weight);
        updateScaleDiagnosticSummary(null, weight);
    }

    private void appendScaleDiagnosticLog(String line) {
        if (scaleDiagnosticLog.length() > 0) {
            scaleDiagnosticLog.append('\n');
        }
        scaleDiagnosticLog.append(line);
        trimScaleDiagnosticLog();
        refreshScaleDiagnosticLog();
    }

    private void trimScaleDiagnosticLog() {
        int maxLength = 12000;
        if (scaleDiagnosticLog.length() <= maxLength) {
            return;
        }
        scaleDiagnosticLog.delete(0, scaleDiagnosticLog.length() - maxLength);
        int firstLineBreak = scaleDiagnosticLog.indexOf("\n");
        if (firstLineBreak >= 0) {
            scaleDiagnosticLog.delete(0, firstLineBreak + 1);
        }
    }

    private void refreshScaleDiagnosticLog() {
        if (scaleDiagnosticLogText != null) {
            scaleDiagnosticLogText.setText(scaleDiagnosticLog.length() == 0 ? "No scale data logged yet." : scaleDiagnosticLog.toString());
        }
    }

    private void updateScaleDiagnosticSummary(String gapText, String parsedWeight) {
        if (scaleDiagnosticCountText != null) {
            scaleDiagnosticCountText.setText("RAW: " + scaleDiagnosticRawCount + " | PARSED: " + scaleDiagnosticParsedCount);
        }
        if (gapText != null && scaleDiagnosticGapText != null) {
            scaleDiagnosticGapText.setText("Gap: " + gapText);
        }
        if (parsedWeight != null && scaleDiagnosticParsedText != null) {
            scaleDiagnosticParsedText.setText(parsedWeight.isEmpty() ? "Last parsed: -" : "Last parsed: " + parsedWeight);
        }
    }

    private String timestampText() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private int parseScaleBaud() {
        try {
            return Integer.parseInt(selectedScaleBaud);
        } catch (NumberFormatException exception) {
            return 9600;
        }
    }

    private ScaleSerialFormat parseScaleFormat() {
        if ("7E1".equals(selectedScaleFormat)) {
            return new ScaleSerialFormat(7, UsbSerialPort.PARITY_EVEN, UsbSerialPort.STOPBITS_1);
        } else if ("7O1".equals(selectedScaleFormat)) {
            return new ScaleSerialFormat(7, UsbSerialPort.PARITY_ODD, UsbSerialPort.STOPBITS_1);
        } else if ("8E1".equals(selectedScaleFormat)) {
            return new ScaleSerialFormat(8, UsbSerialPort.PARITY_EVEN, UsbSerialPort.STOPBITS_1);
        } else if ("8O1".equals(selectedScaleFormat)) {
            return new ScaleSerialFormat(8, UsbSerialPort.PARITY_ODD, UsbSerialPort.STOPBITS_1);
        } else if ("8N2".equals(selectedScaleFormat)) {
            return new ScaleSerialFormat(8, UsbSerialPort.PARITY_NONE, UsbSerialPort.STOPBITS_2);
        }
        return new ScaleSerialFormat(8, UsbSerialPort.PARITY_NONE, UsbSerialPort.STOPBITS_1);
    }

    private void showPrinterPicker(String title, String emptyMessage) {
        rebuildVisiblePrinters();
        printerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        updatePrinterAdapter(emptyMessage);

        printerDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setAdapter(printerAdapter, (dialog, which) -> {
                    if (which >= 0 && which < visiblePrinters.size()) {
                        selectPrinter(visiblePrinters.get(which));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updatePrinterAdapter() {
        updatePrinterAdapter("No printer found yet.");
    }

    private void updatePrinterAdapter(String emptyMessage) {
        if (printerAdapter == null) {
            return;
        }

        rebuildVisiblePrinters();
        printerAdapter.clear();
        if (visiblePrinters.isEmpty()) {
            printerAdapter.add(emptyMessage);
        } else {
            for (PrinterTarget printer : visiblePrinters) {
                printerAdapter.add(printer.displayName);
            }
        }
        printerAdapter.notifyDataSetChanged();
    }

    private void selectPrinter(PrinterTarget printer) {
        if (printer.isPlaceholder()) {
            return;
        }

        selectedPrinter = printer;
        printerStatus.setText("Selected: " + printer.displayName);

        if (printer.bluetoothDevice != null
                && printer.bluetoothDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
            printerManager.pair(printer.bluetoothDevice);
            Toast.makeText(this, "Confirm pairing with the printer, then tap Print Label", Toast.LENGTH_LONG).show();
        } else if (printer.usbDevice != null) {
            usbPrinterManager.requestPermission(printer.usbDevice);
        }
    }

    private void printLabel() {
        if (createQrPayload().isEmpty()) {
            Toast.makeText(this, "Select at least one QR field", Toast.LENGTH_SHORT).show();
            return;
        }

        if ("Reel QR".equals(activeQrSection)) {
            printBytes(TsplBitmapEncoder.buildBitmapLabel(createReelLabelPrintBitmap(), LabelSize.REEL_3X2_INCH));
        } else if ("BOX QR".equals(activeQrSection)) {
            printBytes(buildBoxPrintBytes());
        } else {
            String labelText = labelTextInput == null ? "" : labelTextInput.getText().toString().trim();
            String command = TsplCommandBuilder.buildSampleLabel(
                    createQrPayload(),
                    labelText,
                    getSelectedLabelSize(),
                    createDetailText(),
                    createFooterText()
            );
            printCommand(command);
        }
    }

    private void printCommand(String command) {
        if (selectedPrinter == null) {
            Toast.makeText(this, "Select a printer first", Toast.LENGTH_SHORT).show();
        } else if (selectedPrinter.bluetoothDevice != null) {
            printerManager.print(selectedPrinter.bluetoothDevice, command);
        } else if (selectedPrinter.usbDevice != null) {
            usbPrinterManager.print(selectedPrinter.usbDevice, command);
        }
    }

    private void printBytes(byte[] commandBytes) {
        if (selectedPrinter == null) {
            Toast.makeText(this, "Select a printer first", Toast.LENGTH_SHORT).show();
        } else if (selectedPrinter.bluetoothDevice != null) {
            printerManager.print(selectedPrinter.bluetoothDevice, commandBytes);
        } else if (selectedPrinter.usbDevice != null) {
            usbPrinterManager.print(selectedPrinter.usbDevice, commandBytes);
        }
    }

    private String buildBoxPrintCommand() {
        StringBuilder command = new StringBuilder();
        for (List<Integer> indexes : getBoxGroupedReelIndexes().values()) {
            ReelScanItem firstReel = boxReels.get(indexes.get(0));
            command.append(TsplCommandBuilder.buildBoxGroupLabel(
                    createBoxGroupQrPayload(indexes),
                    selectedBrand.trim(),
                    firstReel.swg,
                    firstReel.colour,
                    createBoxGroupReelWeights(indexes),
                    getBoxGroupNetWeight(indexes),
                    LabelSize.BOX_4X4_INCH,
                    getEnteredByName()
            ));
        }
        return command.toString();
    }

    private byte[] buildBoxPrintBytes() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (List<Integer> indexes : getBoxGroupedReelIndexes().values()) {
            byte[] labelBytes = TsplBitmapEncoder.buildBitmapLabel(
                    createBoxGroupLabelPrintBitmap(createBoxGroupQrPayload(indexes), indexes),
                    LabelSize.BOX_4X4_INCH
            );
            output.writeBytes(labelBytes);
        }
        return output.toByteArray();
    }

    private void resetReelForNextScanIfNeeded() {
        if (!"Reel QR".equals(activeQrSection)) {
            return;
        }
        tareWeight = "";
        grossWeight = "";
        netWeight = "";
        activeSingleReelId = "";
        lastScalePreviewWeight = "";
        lastScalePreviewAtMs = 0L;
        buildScreen();
        getWindow().getDecorView().post(() -> {
            if (tareWeightInput != null) {
                tareWeightInput.requestFocus();
            }
        });
    }

    private void requestBluetoothPermissions() {
        if (hasNeededBluetoothPermissions()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, BLUETOOTH_PERMISSION_REQUEST);
        } else {
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, BLUETOOTH_PERMISSION_REQUEST);
        }
    }

    private boolean hasNeededBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private LabelSize getSelectedLabelSize() {
        return selectedLabelSize == null || selectedLabelSize.isBlank() ? LabelSize.STANDARD_SIZES[3] : selectedLabelSize;
    }

    private void updateGenerateButtonState() {
        if (generateQrButton == null) {
            return;
        }

        boolean enabled;
        if ("Spool QR".equals(activeQrSection)) {
            enabled = !getSpoolWeightValue().isEmpty();
        } else if ("BOX QR".equals(activeQrSection)) {
            enabled = !selectedBrand.trim().isEmpty() && !boxReels.isEmpty();
        } else {
            enabled = allDropdownsPopulated();
        }
        generateQrButton.setEnabled(enabled);
        generateQrButton.setText(isReelBatchMode() ? "Print Current Reel" : "Print Label");
        generateQrButton.setTextColor(enabled ? Color.rgb(37, 99, 235) : Color.rgb(148, 163, 184));
        generateQrButton.setBackground(enabled
                ? roundStroke(Color.rgb(239, 246, 255), Color.rgb(37, 99, 235), dp(9), 1)
                : roundStroke(Color.rgb(248, 250, 252), Color.rgb(226, 232, 240), dp(9), 1));
    }

    private boolean allDropdownsPopulated() {
        return !selectedSwg.trim().isEmpty()
                && !selectedColour.trim().isEmpty()
                && !tareWeight.trim().isEmpty()
                && !selectedSpoolSize.trim().isEmpty()
                && !grossWeight.trim().isEmpty();
    }

    private LinearLayout detailRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        return row;
    }

    private TextView addDropdownField(LinearLayout row, String label, String[] values, ScanField scanField, OptionSelected<String> selected) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);
        field.setPadding(0, 0, 0, 0);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setPadding(0, dp(8), 0, dp(6));
        field.addView(title, matchWrap());

        TextView dropdown = dropdownField();
        dropdown.setOnClickListener(view -> {
            setActiveScanField(scanField);
            showDropdown(dropdown, values, selected);
        });
        field.addView(dropdown, matchWrap());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(0, 0, dp(8), 0);
        row.addView(field, params);
        return dropdown;
    }

    private void addInputField(LinearLayout row, String label, EditText input, ScanField scanField) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setPadding(0, dp(8), 0, dp(6));
        field.addView(title, matchWrap());

        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                setActiveScanField(scanField);
            }
        });
        field.addView(input, matchWrap());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(0, 0, dp(8), 0);
        row.addView(field, params);
    }

    private void clearTareWeightValue() {
        tareWeight = "";
        if (tareWeightInput != null) {
            tareWeightInput.setText("");
        }
    }

    private void clearGrossWeightValue() {
        grossWeight = "";
        if (grossWeightInput != null) {
            grossWeightInput.setText("");
        }
    }

    private void clearNetWeightValue() {
        netWeight = "";
        if (netWeightInput != null) {
            netWeightInput.setText("");
        }
    }

    private void configureWeightInput(EditText input, ScanValueSelected selected) {
        configureWeightInput(input, null, selected);
    }

    private void configureSwgInput(EditText input) {
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                String value = text == null ? "" : text.toString().trim();
                if (value.isEmpty() || isValidSwgScan(value)) {
                    selectedSwg = value;
                    updateGenerateButtonState();
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (updatingSwgInput) {
                    return;
                }
                String value = editable == null ? "" : editable.toString().trim();
                if (!value.isEmpty() && !isValidSwgScan(value)) {
                    showWrongSwgScan();
                    setSwgInputText(selectedSwg);
                }
            }
        });
        input.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                selectSwg(input.getText().toString(), true);
                return true;
            }
            return false;
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            selectSwg(input.getText().toString(), true);
            return false;
        });
    }

    private void configureWeightInput(EditText input, ScanField scanField, ScanValueSelected selected) {
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (input == spoolWeightInput) {
                    spoolWeight = text == null ? "" : text.toString().trim();
                } else if (input == tareWeightInput) {
                    tareWeight = text == null ? "" : text.toString().trim();
                    updateNetWeight();
                } else if (input == grossWeightInput) {
                    grossWeight = text == null ? "" : text.toString().trim();
                    updateNetWeight();
                }
                updateGenerateButtonState();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        input.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                selected.onSelected(input.getText().toString().trim());
                return true;
            }
            return false;
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            selected.onSelected(input.getText().toString().trim());
            return true;
        });
    }

    private void processScannedValue(String scannedValue) {
        String value = scannedValue == null ? "" : scannedValue.trim();
        if (value.isEmpty()) {
            return;
        }

        boolean matched;
        if (activeScanField == ScanField.SPOOL_WEIGHT) {
            setSpoolWeight(value, true);
            return;
        } else if (activeScanField == ScanField.SWG) {
            selectSwg(value, true);
            return;
        } else if (activeScanField == ScanField.COLOUR) {
            matched = matchAndSelect(value, COLOUR_OPTIONS, matchedValue -> selectColour(matchedValue, true));
        } else if (activeScanField == ScanField.SPOOL_SIZE) {
            matched = matchAndSelect(normalizeSpoolSize(value), SPOOL_SIZE_OPTIONS, matchedValue -> selectSpoolSize(matchedValue, true));
        } else if (activeScanField == ScanField.BRAND) {
            matched = matchAndSelect(value, BRAND_OPTIONS, matchedValue -> selectBrand(matchedValue, true));
            if (!matched) {
                selectBrand(value, true);
            }
            return;
        } else if (activeScanField == ScanField.BOX_REEL) {
            addBoxReel(value);
            return;
        } else if (activeScanField == ScanField.TARE_WEIGHT) {
            setTareWeight(value, true);
            return;
        } else if (activeScanField == ScanField.GROSS_WEIGHT) {
            Toast.makeText(this, "Gross Wt. is received from weighing scale", Toast.LENGTH_SHORT).show();
            focusActiveScanField();
            return;
        } else {
            matched = false;
        }

        if (!matched) {
            Toast.makeText(this, "No match for " + value + " in " + activeScanField.getLabel(), Toast.LENGTH_SHORT).show();
            focusActiveScanField();
        }
    }

    private boolean matchAndSelect(String scannedValue, String[] options, OptionSelected<String> selected) {
        for (String option : options) {
            if (option != null && !option.trim().isEmpty() && option.equalsIgnoreCase(scannedValue.trim())) {
                selected.onSelected(option);
                return true;
            }
        }
        return false;
    }

    private boolean isValidSwgScan(String value) {
        String swgValue = value == null ? "" : value.trim();
        return !swgValue.isEmpty() && Character.isDigit(swgValue.charAt(0));
    }

    private void showWrongSwgScan() {
        new AlertDialog.Builder(this)
                .setTitle("Wrong Scan")
                .setMessage("No such value is allowed in SWG.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void setSwgInputText(String value) {
        if (swgInput == null) {
            return;
        }
        String swgValue = value == null ? "" : value;
        if (swgValue.equals(swgInput.getText().toString())) {
            return;
        }
        updatingSwgInput = true;
        swgInput.setText(swgValue);
        swgInput.setSelection(swgInput.getText().length());
        updatingSwgInput = false;
    }

    private void selectSwg(String value, boolean advance) {
        String swgValue = value == null ? "" : value.trim();
        if (!isValidSwgScan(swgValue)) {
            showWrongSwgScan();
            setSwgInputText(selectedSwg);
            return;
        }
        selectedSwg = swgValue;
        setSwgInputText(selectedSwg);
        updateGenerateButtonState();
        if (advance) {
            setActiveScanField(ScanField.COLOUR);
        }
    }

    private void selectColour(String value, boolean advance) {
        selectedColour = value == null ? "" : value;
        setDropdownText(colourDropdown, selectedColour);
        updateGenerateButtonState();
        if (advance) {
            setActiveScanField(ScanField.SPOOL_SIZE);
        }
    }

    private void selectSpoolSize(String value, boolean advance) {
        selectedSpoolSize = normalizeSpoolSize(value);
        setDropdownText(spoolSizeDropdown, selectedSpoolSize);
        updateGenerateButtonState();
        if (advance) {
            setActiveScanField(ScanField.TARE_WEIGHT);
        }
    }

    private String normalizeSpoolSize(String value) {
        String spoolValue = value == null ? "" : value.trim();
        if (spoolValue.isEmpty()) {
            return "";
        }
        String lowerValue = spoolValue.toLowerCase(Locale.US);
        if (lowerValue.endsWith("inch")) {
            spoolValue = spoolValue.substring(0, lowerValue.lastIndexOf("inch")).trim();
        } else if (lowerValue.endsWith("inches")) {
            spoolValue = spoolValue.substring(0, lowerValue.lastIndexOf("inches")).trim();
        }
        if (!spoolValue.endsWith("\"")) {
            spoolValue = spoolValue + "\"";
        }
        return spoolValue;
    }

    private void selectReelBatchCount(String value) {
        int newCount = parseBatchCount(value);
        if (printedReelsInBatch > 0 && newCount != selectedReelBatchCount) {
            Toast.makeText(this, "Finish the current batch before changing Reel Count", Toast.LENGTH_SHORT).show();
            setDropdownText(reelBatchCountDropdown, String.valueOf(selectedReelBatchCount));
            return;
        }

        selectedReelBatchCount = newCount;
        setDropdownText(reelBatchCountDropdown, String.valueOf(selectedReelBatchCount));
        if (isReelBatchMode()) {
            clearLastCompletedReelBatch();
            ensureReelBatchId();
        } else {
            activeReelBatchId = "";
            printedReelsInBatch = 0;
            reelBatchNetWeights.clear();
            reelBatchItems.clear();
        }
        updateReelBatchStatusText();
        refreshReelBatchList();
        updateGenerateButtonState();
    }

    private int parseBatchCount(String value) {
        try {
            int count = Integer.parseInt(value == null ? "1" : value.trim());
            return count == 2 || count == 4 || count == 8 || count == 16 || count == 18 ? count : 1;
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private boolean isReelBatchMode() {
        return "Reel QR".equals(activeQrSection) && selectedReelBatchCount > 1;
    }

    private void ensureReelBatchId() {
        if (activeReelBatchId == null || activeReelBatchId.trim().isEmpty()) {
            activeReelBatchId = generateBatchId();
        }
    }

    private String generateBatchId() {
        return randomBatchPart(5);
    }

    private void ensureSingleReelId() {
        if (activeSingleReelId == null || activeSingleReelId.trim().isEmpty()) {
            activeSingleReelId = randomBatchPart(5);
        }
    }

    private String getCurrentReelUniqueId() {
        if (isReelBatchMode()) {
            ensureReelBatchId();
            return activeReelBatchId;
        }
        ensureSingleReelId();
        return activeSingleReelId;
    }

    private String randomBatchPart(int length) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < length; index++) {
            builder.append(BATCH_ID_CHARACTERS.charAt(batchRandom.nextInt(BATCH_ID_CHARACTERS.length())));
        }
        return builder.toString();
    }

    private void updateReelBatchStatusText() {
        if (reelBatchStatusText == null) {
            return;
        }
        if (!isReelBatchMode()) {
            reelBatchStatusText.setText("Single reel label");
            return;
        }
        ensureReelBatchId();
        reelBatchStatusText.setText("Batch " + activeReelBatchId + " · " + printedReelsInBatch + "/" + selectedReelBatchCount + " printed");
    }

    private void refreshReelBatchList() {
        if (reelBatchList == null) {
            return;
        }
        reelBatchList.removeAllViews();
        List<BatchReelItem> rows = reelBatchItems;
        String title = "Printed reels total: " + getReelBatchTotalNetWeight() + "kg";
        if (!isReelBatchMode()) {
            rows = lastCompletedReelBatchItems;
            title = lastCompletedReelBatchId.trim().isEmpty()
                    ? ""
                    : "Last batch " + lastCompletedReelBatchId + " · " + lastCompletedReelBatchCount
                    + " reels · Total: " + lastCompletedReelBatchTotal + "kg";
        }
        if (rows.isEmpty()) {
            return;
        }

        TextView totalText = previewText(title, 14, true);
        totalText.setTextColor(Color.rgb(17, 24, 39));
        totalText.setPadding(0, dp(4), 0, dp(4));
        reelBatchList.addView(totalText, matchWrap());

        for (int index = 0; index < rows.size(); index++) {
            reelBatchList.addView(reelBatchRow(rows.get(index), index), spacedMatchWrap(dp(8)));
        }
    }

    private View reelBatchRow(BatchReelItem reel, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(roundStroke(Color.rgb(248, 250, 252), Color.rgb(226, 232, 240), dp(8), 1));

        TextView title = previewText("Reel " + (index + 1) + " printed", 14, true);
        title.setTextColor(Color.rgb(17, 24, 39));
        row.addView(title, matchWrap());

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.HORIZONTAL);
        details.setPadding(0, dp(6), 0, 0);
        details.addView(boxReelDetail("SWG", reel.swg), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        details.addView(boxReelDetail("Colour", reel.colour), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        details.addView(boxReelDetail("Spool", reel.spoolSize), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        details.addView(boxReelDetail("Net Wt.", reel.netWeight), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(details, matchWrap());
        return row;
    }

    private void selectBrand(String value, boolean advance) {
        selectedBrand = value == null ? "" : value.trim();
        setDropdownText(boxBrandDropdown, selectedBrand);
        updateGenerateButtonState();
        if (advance) {
            setActiveScanField(ScanField.BOX_REEL);
        }
    }

    private void addBoxReel(String qrValue) {
        if (selectedBrand.trim().isEmpty()) {
            Toast.makeText(this, "Select Brand", Toast.LENGTH_SHORT).show();
            setActiveScanField(ScanField.BRAND);
            return;
        }
        ReelScanItem reel = parseReelScan(qrValue);
        if (Math.max(1, reel.reelCount) > MAX_BOX_REELS) {
            Toast.makeText(this, "Maximum 18 reels can be added to a box", Toast.LENGTH_SHORT).show();
            setActiveScanField(ScanField.BOX_REEL);
            return;
        }
        boxReels.clear();
        boxReels.add(reel);
        refreshBoxReelList();
        printBoxLabelForIndexes(singleIndexList(0));
        setActiveScanField(ScanField.BOX_REEL);
    }

    private List<Integer> singleIndexList(int index) {
        List<Integer> indexes = new ArrayList<>();
        indexes.add(index);
        return indexes;
    }

    private void printBoxLabelForIndexes(List<Integer> indexes) {
        if (selectedPrinter == null) {
            Toast.makeText(this, "Select a printer first", Toast.LENGTH_SHORT).show();
            return;
        }
        printBytes(TsplBitmapEncoder.buildBitmapLabel(
                createBoxGroupLabelPrintBitmap(createBoxGroupQrPayload(indexes), indexes),
                LabelSize.BOX_4X4_INCH
        ));
        Toast.makeText(this, "Box label sent to printer", Toast.LENGTH_SHORT).show();
    }

    private ReelScanItem parseReelScan(String qrValue) {
        String raw = qrValue == null ? "" : qrValue.trim();
        String[] parts = raw.split(",");
        if (parts.length >= 7 && !isSwgOption(parts[0]) && parts[6].contains("|")) {
            String uniqueId = parts[0].trim();
            String swg = parts[1].trim();
            String colour = parts[2].trim();
            String spoolSize = normalizeSpoolSize(parts[3]);
            String netWeightValue = normalizeWeightText(parts[4].trim().replace("kg", "").replace("KG", ""));
            int reelCount = parsePositiveInt(parts[5].trim(), 1);
            List<String> weights = parsePipeSeparatedWeights(parts[6]);
            return new ReelScanItem(swg, colour, netWeightValue, uniqueId, spoolSize, "", Math.max(reelCount, weights.size()), weights, raw);
        }
        if (parts.length >= 6 && !isSwgOption(parts[0]) && parts[5].contains("|")) {
            String uniqueId = parts[0].trim();
            String swg = parts[1].trim();
            String colour = parts[2].trim();
            String netWeightValue = normalizeWeightText(parts[3].trim().replace("kg", "").replace("KG", ""));
            int reelCount = parsePositiveInt(parts[4].trim(), 1);
            List<String> weights = parsePipeSeparatedWeights(parts[5]);
            return new ReelScanItem(swg, colour, netWeightValue, uniqueId, "", "", Math.max(reelCount, weights.size()), weights, raw);
        }

        String swg = parts.length > 0 ? parts[0].trim() : "";
        String colour = parts.length > 1 ? parts[1].trim() : "";
        String netWeightValue = parts.length > 2 ? normalizeWeightText(parts[2].trim().replace("kg", "").replace("KG", "")) : "";
        String uniqueId = parts.length > 3 && !parts[3].trim().isEmpty() ? parts[3].trim() : randomBatchPart(5);
        String spoolSize = parts.length > 4 ? normalizeSpoolSize(parts[4]) : "";
        String packedBy = parts.length > 5 ? parts[5].trim() : "";
        List<String> weights = new ArrayList<>();
        if (!netWeightValue.isEmpty()) {
            weights.add(netWeightValue);
        }
        return new ReelScanItem(swg, colour, netWeightValue, uniqueId, spoolSize, packedBy, 1, weights, raw);
    }

    private boolean isSwgOption(String value) {
        if (value == null) {
            return false;
        }
        for (String option : SWG_OPTIONS) {
            if (option != null && !option.trim().isEmpty() && option.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private List<String> parsePipeSeparatedWeights(String value) {
        List<String> weights = new ArrayList<>();
        if (value == null) {
            return weights;
        }
        String[] parts = value.split("\\|");
        for (String part : parts) {
            String weight = normalizeWeightText(part.trim().replace("kg", "").replace("KG", ""));
            if (!weight.isEmpty()) {
                weights.add(weight);
            }
        }
        return weights;
    }

    private void refreshBoxReelList() {
        updateGenerateButtonState();
        if (boxReelCountText != null) {
            boxReelCountText.setText(boxReels.isEmpty()
                    ? "Scan a single reel QR or summary QR to print"
                    : "Last scanned: " + getBoxTotalReelCount() + " reel(s) | Net Wt.: " + getBoxTotalNetWeight());
        }
        if (boxReelList == null) {
            return;
        }

        boxReelList.removeAllViews();
        if (boxReels.isEmpty()) {
            TextView emptyText = previewText("No QR scanned yet", 14, false);
            emptyText.setTextColor(Color.rgb(107, 114, 128));
            emptyText.setGravity(Gravity.CENTER);
            emptyText.setMinHeight(dp(70));
            emptyText.setBackground(roundStroke(Color.rgb(248, 250, 252), Color.rgb(226, 232, 240), dp(8), 1));
            boxReelList.addView(emptyText, matchWrap());
            return;
        }

        List<Integer> indexes = singleIndexList(0);
        boxReelList.addView(boxReelGroupHeader(indexes), matchWrap());
        boxReelList.addView(boxReelRow(boxReels.get(0), 0), spacedMatchWrap(dp(8)));
    }

    private Map<String, List<Integer>> getBoxGroupedReelIndexes() {
        Map<String, List<Integer>> groupedReels = new LinkedHashMap<>();
        for (int index = 0; index < boxReels.size(); index++) {
            ReelScanItem reel = boxReels.get(index);
            String key = boxReelGroupKey(reel);
            List<Integer> indexes = groupedReels.get(key);
            if (indexes == null) {
                indexes = new ArrayList<>();
                groupedReels.put(key, indexes);
            }
            indexes.add(index);
        }
        return groupedReels;
    }

    private String createBoxGroupQrPayload(List<Integer> indexes) {
        ReelScanItem reel = boxReels.get(indexes.get(0));
        return displayValue(reel.swg)
                + normalizeSpoolSize(reel.spoolSize)
                + "," + colourQrCode(reel.colour)
                + "," + getBoxGroupNetWeight(indexes)
                + "," + createBoxQrDateText()
                + "," + createBoxTimeText();
    }

    private String createBoxGroupDetailText(List<Integer> indexes) {
        ReelScanItem reel = boxReels.get(indexes.get(0));
        return "Brand " + selectedBrand.trim()
                + " | SWG " + displayValue(reel.swg)
                + " | Spool " + displayValue(reel.spoolSize)
                + " | Colour " + boxColourName(reel.colour)
                + " | Reels " + getBoxGroupReelCount(indexes)
                + " | Net Wt. " + getBoxGroupNetWeight(indexes);
    }

    private String[] createBoxGroupReelWeights(List<Integer> indexes) {
        List<String> weights = createBoxGroupWeightList(indexes);
        return weights.toArray(new String[0]);
    }

    private List<String> createBoxGroupWeightList(List<Integer> indexes) {
        List<String> weights = new ArrayList<>();
        for (int index : indexes) {
            ReelScanItem reel = boxReels.get(index);
            if (reel.reelWeights != null && !reel.reelWeights.isEmpty()) {
                weights.addAll(reel.reelWeights);
            } else if (reel.netWeight != null && !reel.netWeight.trim().isEmpty()) {
                weights.add(reel.netWeight);
            }
        }
        return weights;
    }

    private String createBoxGroupWeightsPayload(List<Integer> indexes) {
        StringBuilder builder = new StringBuilder();
        for (String weight : createBoxGroupWeightList(indexes)) {
            if (builder.length() > 0) {
                builder.append("|");
            }
            builder.append(normalizeWeightText(weight));
        }
        return builder.toString();
    }

    private String getBoxGroupUniqueId(List<Integer> indexes) {
        for (int index : indexes) {
            String uniqueId = boxReels.get(index).uniqueId;
            if (uniqueId != null && !uniqueId.trim().isEmpty()) {
                return uniqueId.trim();
            }
        }
        return randomBatchPart(5);
    }

    private String createBoxOuterQrPayload(List<Integer> indexes, String date, String time) {
        ReelScanItem reel = boxReels.get(indexes.get(0));
        return displayValue(reel.swg)
                + normalizeSpoolSize(reel.spoolSize)
                + "," + colourQrCode(reel.colour)
                + "," + getBoxGroupNetWeight(indexes)
                + "," + date
                + "," + time;
    }

    private String createBoxOuterDetailText(List<Integer> indexes, String date, String time) {
        ReelScanItem reel = boxReels.get(indexes.get(0));
        return "SWG " + displayValue(reel.swg)
                + " | Colour " + boxColourName(reel.colour)
                + " | Net Wt. " + getBoxGroupNetWeight(indexes)
                + " | " + date
                + " " + time;
    }

    private String createBoxDateText() {
        return new SimpleDateFormat("dd-MM-yyyy", Locale.US).format(new Date());
    }

    private String createBoxQrDateText() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.YEAR, -10);
        return new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(calendar.getTime());
    }

    private String createBoxTimeText() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private String boxReelGroupKey(ReelScanItem reel) {
        return (reel.swg == null ? "" : reel.swg.trim().toUpperCase(java.util.Locale.US))
                + "|"
                + boxColourName(reel.colour).toUpperCase(java.util.Locale.US)
                + "|"
                + normalizeSpoolSize(reel.spoolSize).toUpperCase(java.util.Locale.US);
    }

    private TextView boxReelGroupHeader(List<Integer> indexes) {
        ReelScanItem firstReel = boxReels.get(indexes.get(0));
        TextView header = previewText(
                "SWG " + displayValue(firstReel.swg)
                        + " | Spool " + displayValue(firstReel.spoolSize)
                        + " | Colour " + boxColourName(firstReel.colour)
                        + " | Reels: " + getBoxGroupReelCount(indexes)
                        + " | Net Wt.: " + getBoxGroupNetWeight(indexes),
                14,
                true
        );
        header.setSingleLine(false);
        header.setPadding(dp(12), dp(10), dp(12), dp(10));
        header.setBackground(roundStroke(Color.rgb(239, 246, 255), Color.rgb(147, 197, 253), dp(8), 1));
        return header;
    }

    private LinearLayout boxReelRow(ReelScanItem reel, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(roundStroke(Color.WHITE, Color.rgb(226, 232, 240), dp(8), 1));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = previewText((reel.reelCount > 1 ? "Batch scan " : "Reel ") + (index + 1), 14, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button deleteButton = secondaryButton("Delete");
        deleteButton.setMinHeight(dp(38));
        deleteButton.setTextSize(13);
        deleteButton.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_delete, 0, 0, 0);
        deleteButton.setCompoundDrawablePadding(dp(4));
        deleteButton.setOnClickListener(view -> deleteBoxReel(index));
        header.addView(deleteButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        row.addView(header, matchWrap());

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.HORIZONTAL);
        details.setPadding(0, dp(6), 0, 0);
        details.addView(boxReelDetail("SWG", reel.swg), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        details.addView(boxReelDetail("Colour", reel.colour), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        details.addView(boxReelDetail("Net Wt.", reel.netWeight), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        details.addView(boxReelDetail("Reels", String.valueOf(reel.reelCount)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(details, matchWrap());

        if (reel.swg.isEmpty() || reel.colour.isEmpty() || reel.netWeight.isEmpty()) {
            TextView raw = previewText("QR: " + reel.rawValue, 12, false);
            raw.setTextColor(Color.rgb(75, 85, 99));
            raw.setSingleLine(false);
            raw.setPadding(0, dp(8), 0, 0);
            row.addView(raw, matchWrap());
        }
        return row;
    }

    private void deleteBoxReel(int index) {
        if (index < 0 || index >= boxReels.size()) {
            return;
        }
        boxReels.remove(index);
        refreshBoxReelList();
        Toast.makeText(this, "Reel removed", Toast.LENGTH_SHORT).show();
        setActiveScanField(ScanField.BOX_REEL);
    }

    private TextView boxReelDetail(String label, String value) {
        TextView detail = previewText(label + "\n" + (value == null || value.trim().isEmpty() ? "-" : value.trim()), 13, true);
        detail.setSingleLine(false);
        detail.setGravity(Gravity.CENTER);
        detail.setTextColor(Color.rgb(17, 24, 39));
        return detail;
    }

    private String displayValue(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    private String getBoxTotalNetWeight() {
        double total = 0d;
        boolean hasWeight = false;
        for (ReelScanItem reel : boxReels) {
            Double value = parseWeight(reel.netWeight);
            if (value != null) {
                total += value;
                hasWeight = true;
            }
        }
        return hasWeight ? trimWeight(total) : "0";
    }

    private int getBoxTotalReelCount() {
        int count = 0;
        for (ReelScanItem reel : boxReels) {
            count += Math.max(1, reel.reelCount);
        }
        return count;
    }

    private int getBoxGroupReelCount(List<Integer> indexes) {
        int count = 0;
        for (int index : indexes) {
            count += Math.max(1, boxReels.get(index).reelCount);
        }
        return count;
    }

    private String getBoxGroupNetWeight(List<Integer> indexes) {
        double total = 0d;
        boolean hasWeight = false;
        for (int index : indexes) {
            Double value = parseWeight(boxReels.get(index).netWeight);
            if (value != null) {
                total += value;
                hasWeight = true;
            }
        }
        return hasWeight ? trimWeight(total) : "0";
    }

    private void setTareWeight(String value, boolean advance) {
        String tareValue = normalizeWeightText(value);
        if (advance && !isCompleteWeightText(tareValue)) {
            clearTareWeightValue();
            clearNetWeightValue();
            updateGenerateButtonState();
            Toast.makeText(this, "Scan complete Tare Wt.", Toast.LENGTH_SHORT).show();
            if (tareWeightInput != null) {
                tareWeightInput.requestFocus();
            }
            return;
        }
        tareWeight = tareValue;
        if (tareWeightInput != null) {
            tareWeightInput.setText(tareWeight);
            tareWeightInput.setSelection(tareWeightInput.getText().length());
        }
        updateGenerateButtonState();
        if (advance) {
            setActiveScanField(ScanField.GROSS_WEIGHT);
        }
    }

    private void setGrossWeight(String value, boolean advance) {
        String grossValue = normalizeWeightText(value);
        if (advance && !isCompleteWeightText(grossValue)) {
            clearGrossWeightValue();
            clearNetWeightValue();
            updateGenerateButtonState();
            Toast.makeText(this, "Scan complete Gross Wt.", Toast.LENGTH_SHORT).show();
            if (grossWeightInput != null) {
                grossWeightInput.requestFocus();
            }
            return;
        }
        grossWeight = grossValue;
        if (grossWeightInput != null) {
            grossWeightInput.setText(grossWeight);
            grossWeightInput.setSelection(grossWeightInput.getText().length());
        }
        updateGenerateButtonState();
    }

    private void printReelFromScaleIfReady(String normalizedWeight) {
        if (generateQrButton == null || !generateQrButton.isEnabled()) {
            return;
        }

        long now = android.os.SystemClock.elapsedRealtime();
        if (normalizedWeight.equals(lastScalePreviewWeight)
                && now - lastScalePreviewAtMs < SCALE_PREVIEW_DEBOUNCE_MS) {
            return;
        }

        lastScalePreviewWeight = normalizedWeight;
        lastScalePreviewAtMs = now;
        if (isReelBatchMode()) {
            printCurrentBatchReelIfReady();
        } else {
            printSingleReelIfReady();
        }
    }

    private void printSingleReelIfReady() {
        if (isReelBatchMode()) {
            return;
        }
        if (!allDropdownsPopulated()) {
            Toast.makeText(this, "Complete reel details", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasValidReelWeights()) {
            Toast.makeText(this, "Gross Wt. must be greater than Tare Wt.", Toast.LENGTH_SHORT).show();
            if (grossWeightInput != null) {
                grossWeightInput.requestFocus();
            }
            return;
        }
        if (selectedPrinter == null) {
            Toast.makeText(this, "Select a printer first", Toast.LENGTH_SHORT).show();
            return;
        }

        ensureSingleReelId();
        printBytes(TsplBitmapEncoder.buildBitmapLabel(createReelLabelPrintBitmap(), LabelSize.REEL_3X2_INCH));
        resetReelForNextScanIfNeeded();
    }

    private void printCurrentBatchReelIfReady() {
        if (!isReelBatchMode()) {
            return;
        }
        if (!allDropdownsPopulated()) {
            Toast.makeText(this, "Complete current reel details", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasValidReelWeights()) {
            Toast.makeText(this, "Gross Wt. must be greater than Tare Wt.", Toast.LENGTH_SHORT).show();
            if (grossWeightInput != null) {
                grossWeightInput.requestFocus();
            }
            return;
        }
        if (selectedPrinter == null) {
            Toast.makeText(this, "Select a printer first", Toast.LENGTH_SHORT).show();
            return;
        }

        ensureReelBatchId();
        printBytes(TsplBitmapEncoder.buildBitmapLabel(createReelLabelPrintBitmap(), LabelSize.REEL_3X2_INCH));
        reelBatchNetWeights.add(netWeight);
        reelBatchItems.add(new BatchReelItem(selectedSwg, selectedColour, selectedSpoolSize, netWeight));
        printedReelsInBatch++;
        updateReelBatchStatusText();
        refreshReelBatchList();

        if (printedReelsInBatch >= selectedReelBatchCount) {
            String completedBatchId = activeReelBatchId;
            String totalNetWeight = getReelBatchTotalNetWeight();
            int completedCount = selectedReelBatchCount;
            printBytes(TsplBitmapEncoder.buildBitmapLabel(
                    createReelBatchSummaryPrintBitmap(completedBatchId, totalNetWeight, completedCount),
                    LabelSize.REEL_3X2_INCH
            ));
            Toast.makeText(this, "Batch completed: " + completedBatchId, Toast.LENGTH_LONG).show();
            rememberCompletedReelBatch(completedBatchId, totalNetWeight, completedCount);
            resetCompletedReelBatch();
        }

        resetReelForNextScanIfNeeded();
    }

    private String getReelBatchTotalNetWeight() {
        double total = 0d;
        boolean hasWeight = false;
        for (String weight : reelBatchNetWeights) {
            Double value = parseWeight(weight);
            if (value != null) {
                total += value;
                hasWeight = true;
            }
        }
        return hasWeight ? trimWeight(total) : "0";
    }

    private boolean hasValidReelWeights() {
        Double tare = parseWeight(tareWeight);
        Double gross = parseWeight(grossWeight);
        return tare != null && gross != null && gross > tare;
    }

    private String createBatchReelWeightsPayload() {
        StringBuilder builder = new StringBuilder();
        for (String weight : reelBatchNetWeights) {
            String normalizedWeight = normalizeWeightText(weight);
            if (normalizedWeight.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("|");
            }
            builder.append(normalizedWeight);
        }
        return builder.toString();
    }

    private void resetCompletedReelBatch() {
        selectedReelBatchCount = 1;
        printedReelsInBatch = 0;
        activeReelBatchId = "";
        reelBatchNetWeights.clear();
        reelBatchItems.clear();
    }

    private void rememberCompletedReelBatch(String batchId, String totalNetWeight, int reelCount) {
        lastCompletedReelBatchId = batchId == null ? "" : batchId;
        lastCompletedReelBatchTotal = totalNetWeight == null ? "" : totalNetWeight;
        lastCompletedReelBatchCount = reelCount;
        lastCompletedReelBatchItems.clear();
        lastCompletedReelBatchItems.addAll(reelBatchItems);
    }

    private void clearLastCompletedReelBatch() {
        lastCompletedReelBatchId = "";
        lastCompletedReelBatchTotal = "";
        lastCompletedReelBatchCount = 0;
        lastCompletedReelBatchItems.clear();
    }

    private void updateNetWeight() {
        Double tare = parseWeight(tareWeight);
        Double gross = parseWeight(grossWeight);
        netWeight = tare == null || gross == null ? "" : trimWeight(gross - tare);
        if (netWeightInput != null) {
            netWeightInput.setText(netWeight);
        }
    }

    private Double parseWeight(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        if (!isCompleteWeightText(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean isCompleteWeightText(String value) {
        String weight = value == null ? "" : value.trim();
        return weight.matches("[-+]?\\d+(?:\\.\\d+)?");
    }

    private String normalizeWeightText(String value) {
        String weight = value == null ? "" : value.trim();
        if (weight.isEmpty() || !isCompleteWeightText(weight)) {
            return weight;
        }

        String sign = "";
        if (weight.startsWith("-") || weight.startsWith("+")) {
            sign = weight.substring(0, 1);
            weight = weight.substring(1);
        }

        int dotIndex = weight.indexOf('.');
        String whole = dotIndex >= 0 ? weight.substring(0, dotIndex) : weight;
        String decimal = dotIndex >= 0 ? weight.substring(dotIndex) : "";
        whole = whole.replaceFirst("^0+(?!$)", "");
        if (whole.isEmpty()) {
            whole = "0";
        }
        return sign + whole + decimal;
    }

    private String trimWeight(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001d) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(java.util.Locale.US, "%.3f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private void setSpoolWeight(String value, boolean advance) {
        spoolWeight = value == null ? "" : value.trim();
        if (spoolWeightInput != null) {
            spoolWeightInput.setText(spoolWeight);
            spoolWeightInput.setSelection(spoolWeightInput.getText().length());
        }
        updateGenerateButtonState();
        if (advance) {
            setActiveScanField(ScanField.DONE);
        }
    }

    private void submitSpoolQr() {
        setSpoolWeight(getSpoolWeightValue(), false);
        if (spoolWeight.isEmpty()) {
            Toast.makeText(this, "Enter Spool Wt.", Toast.LENGTH_SHORT).show();
            setActiveScanField(ScanField.SPOOL_WEIGHT);
            return;
        }
        showQrPreviewOverlay();
    }

    private void clearSpoolWeight() {
        setSpoolWeight("", false);
        setActiveScanField(ScanField.SPOOL_WEIGHT);
    }

    private String getSpoolWeightValue() {
        if (spoolWeightInput == null) {
            return spoolWeight == null ? "" : spoolWeight.trim();
        }
        return spoolWeightInput.getText().toString().trim();
    }

    private void setActiveScanField(ScanField scanField) {
        activeScanField = scanField;
        scannerBuffer.setLength(0);
        clearTextFocusForScannerField(scanField);
        updateScanFieldHighlights();
        focusActiveScanField();
    }

    private boolean isDirectTextScanField(ScanField scanField) {
        return scanField == ScanField.SWG
                || scanField == ScanField.TARE_WEIGHT
                || scanField == ScanField.GROSS_WEIGHT
                || scanField == ScanField.SPOOL_WEIGHT;
    }

    private void clearTextFocusForScannerField(ScanField scanField) {
        if (isDirectTextScanField(scanField)) {
            return;
        }
        View currentFocus = getCurrentFocus();
        if (currentFocus instanceof EditText) {
            currentFocus.clearFocus();
        }
    }

    private void updateScanFieldHighlights() {
        setInputActive(swgInput, activeScanField == ScanField.SWG);
        setDropdownActive(colourDropdown, activeScanField == ScanField.COLOUR);
        setDropdownActive(spoolSizeDropdown, activeScanField == ScanField.SPOOL_SIZE);
        setDropdownActive(boxBrandDropdown, activeScanField == ScanField.BRAND);
        setDropdownActive(boxReelScanTarget, activeScanField == ScanField.BOX_REEL);
        setInputActive(tareWeightInput, activeScanField == ScanField.TARE_WEIGHT);
        setInputActive(grossWeightInput, activeScanField == ScanField.GROSS_WEIGHT);
        setInputActive(spoolWeightInput, activeScanField == ScanField.SPOOL_WEIGHT);
    }

    private void setDropdownActive(TextView dropdown, boolean active) {
        if (dropdown == null) {
            return;
        }
        dropdown.setBackground(active
                ? roundStroke(Color.WHITE, Color.rgb(37, 99, 235), dp(9), 2)
                : roundStroke(Color.WHITE, Color.rgb(220, 224, 230), dp(9), 1));
    }

    private void setInputActive(EditText input, boolean active) {
        if (input == null) {
            return;
        }
        input.setBackground(active
                ? roundStroke(Color.WHITE, Color.rgb(37, 99, 235), dp(9), 2)
                : roundStroke(Color.WHITE, Color.rgb(220, 224, 230), dp(9), 1));
    }

    private void focusActiveScanField() {
        if (activeScanField == ScanField.SWG && swgInput != null) {
            swgInput.requestFocus();
            swgInput.selectAll();
        } else if (activeScanField == ScanField.COLOUR) {
            colourDropdown.requestFocus();
        } else if (activeScanField == ScanField.SPOOL_SIZE && spoolSizeDropdown != null) {
            spoolSizeDropdown.requestFocus();
        } else if (activeScanField == ScanField.BRAND && boxBrandDropdown != null) {
            boxBrandDropdown.requestFocus();
        } else if (activeScanField == ScanField.BOX_REEL && boxReelScanTarget != null) {
            boxReelScanTarget.requestFocus();
        } else if (activeScanField == ScanField.TARE_WEIGHT && tareWeightInput != null) {
            tareWeightInput.requestFocus();
            tareWeightInput.selectAll();
        } else if (activeScanField == ScanField.GROSS_WEIGHT && grossWeightInput != null) {
            grossWeightInput.requestFocus();
            grossWeightInput.selectAll();
        } else if (activeScanField == ScanField.SPOOL_WEIGHT && spoolWeightInput != null) {
            spoolWeightInput.requestFocus();
            spoolWeightInput.selectAll();
        } else if (generateQrButton != null) {
            generateQrButton.requestFocus();
        }
    }

    private String createQrPayload() {
        if ("Spool QR".equals(activeQrSection)) {
            return getSpoolWeightValue();
        } else if ("Reel QR".equals(activeQrSection)) {
            return createReelQrData();
        } else if ("BOX QR".equals(activeQrSection)) {
            return selectedBrand.trim();
        }
        String[] values = {
                selectedSwg,
                selectedColour,
                tareWeight,
                grossWeight,
                netWeight
        };
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private String createReelQrData() {
        return selectedSwg + "," + colourQrCode(selectedColour) + "," + netWeight + "," + getCurrentReelUniqueId() + "," + selectedSpoolSize + "," + getEnteredByName();
    }

    private String colourQrCode(String colour) {
        String value = colour == null ? "" : colour.trim();
        return value.isEmpty() ? "" : value.substring(0, 1).toUpperCase(Locale.US);
    }

    private String boxColourName(String colour) {
        String value = colour == null ? "" : colour.trim();
        if (value.equalsIgnoreCase("B")) {
            return "Black";
        } else if (value.equalsIgnoreCase("G")) {
            return "Green";
        } else if (value.equalsIgnoreCase("O")) {
            return "Off white";
        } else if (value.equalsIgnoreCase("W")) {
            return "White";
        }
        return displayValue(value);
    }

    private String createDetailText() {
        if ("Spool QR".equals(activeQrSection)) {
            String value = getSpoolWeightValue();
            return value.isEmpty() ? "" : "Spool Wt. " + value;
        } else if ("BOX QR".equals(activeQrSection)) {
            return selectedBrand.trim().isEmpty() ? "" : "Brand " + selectedBrand.trim();
        }
        List<String> details = new ArrayList<>();
        addDetail(details, "SWG " + selectedSwg, selectedSwg);
        addDetail(details, selectedColour, selectedColour);
        addDetail(details, "Tare Wt. " + tareWeight, tareWeight);
        addDetail(details, "Gross Wt. " + grossWeight, grossWeight);
        addDetail(details, "Net Wt. " + netWeight, netWeight);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < details.size(); index++) {
            if (index > 0) {
                builder.append(" | ");
            }
            builder.append(details.get(index));
        }
        return builder.toString();
    }

    private String createFooterText() {
        if ("Reel QR".equals(activeQrSection) || "Spool QR".equals(activeQrSection) || "BOX QR".equals(activeQrSection)) {
            return "Entered by: " + getEnteredByName();
        }
        return "";
    }

    private TextView previewText(String text, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.rgb(17, 24, 39));
        view.setPadding(0, dp(4), 0, dp(4));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private void addDetail(List<String> details, String displayValue, String rawValue) {
        if (rawValue != null && !rawValue.trim().isEmpty()) {
            details.add(displayValue);
        }
    }

    private void addLabeledView(LinearLayout parent, String label, android.view.View view) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setPadding(0, dp(8), 0, dp(6));
        parent.addView(title, matchWrap());
        parent.addView(view, matchWrap());
    }

    private TextView dropdownField() {
        TextView view = spinnerTextView();
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setClickable(true);
        view.setBackground(roundStroke(Color.WHITE, Color.rgb(220, 224, 230), dp(9), 1));
        view.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0);
        view.setCompoundDrawablePadding(dp(8));
        return view;
    }

    private void setDropdownText(TextView dropdown, String value) {
        dropdown.setText(value == null ? "" : value);
    }

    private <T> void showDropdown(TextView anchor, T[] values, OptionSelected<T> selected) {
        ListView listView = new ListView(this);
        listView.setDividerHeight(0);
        listView.setBackgroundColor(Color.WHITE);
        listView.setAdapter(dropdownAdapter(values));

        PopupWindow popupWindow = new PopupWindow(
                listView,
                anchor.getWidth(),
                Math.min(dp(220), Math.max(dp(44), values.length * dp(44))),
                true
        );
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        popupWindow.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupWindow.setElevation(dp(8));
        }

        listView.setOnItemClickListener((parent, view, position, id) -> {
            selected.onSelected(values[position]);
            popupWindow.dismiss();
        });
        popupWindow.showAsDropDown(anchor, 0, dp(4));
    }

    private <T> ArrayAdapter<T> dropdownAdapter(T[] values) {
        return new ArrayAdapter<T>(this, android.R.layout.simple_list_item_1, values) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = dropdownOptionTextView();
                view.setText(getItem(position) == null ? "" : getItem(position).toString());
                view.setBackgroundColor(Color.WHITE);
                return view;
            }
        };
    }

    private TextView spinnerTextView() {
        TextView view = new TextView(this);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTextSize(16);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(17, 24, 39));
        view.setPadding(dp(14), 0, dp(10), 0);
        view.setMinHeight(dp(58));
        return view;
    }

    private TextView dropdownOptionTextView() {
        TextView view = new TextView(this);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(17, 24, 39));
        view.setPadding(dp(14), 0, dp(10), 0);
        view.setMinHeight(dp(44));
        return view;
    }

    private interface OptionSelected<T> {
        void onSelected(T value);
    }

    private interface ScanValueSelected {
        void onSelected(String value);
    }

    private enum ScanField {
        SWG("SWG"),
        COLOUR("Colour"),
        BRAND("Brand"),
        BOX_REEL("Reel QR"),
        TARE_WEIGHT("Tare Wt."),
        SPOOL_SIZE("Spool Size"),
        GROSS_WEIGHT("Gross Wt."),
        SPOOL_WEIGHT("Spool Wt."),
        DONE("Print Label");

        private final String label;

        ScanField(String label) {
            this.label = label;
        }

        private String getLabel() {
            return label;
        }
    }

    private static final class ReelScanItem {
        private final String swg;
        private final String colour;
        private final String netWeight;
        private final String uniqueId;
        private final String spoolSize;
        private final String packedBy;
        private final int reelCount;
        private final List<String> reelWeights;
        private final String rawValue;

        private ReelScanItem(String swg, String colour, String netWeight, String uniqueId, String spoolSize, String packedBy, int reelCount, List<String> reelWeights, String rawValue) {
            this.swg = swg;
            this.colour = colour;
            this.netWeight = netWeight;
            this.uniqueId = uniqueId;
            this.spoolSize = spoolSize;
            this.packedBy = packedBy;
            this.reelCount = reelCount;
            this.reelWeights = reelWeights;
            this.rawValue = rawValue;
        }
    }

    private static final class BatchReelItem {
        private final String swg;
        private final String colour;
        private final String spoolSize;
        private final String netWeight;

        private BatchReelItem(String swg, String colour, String spoolSize, String netWeight) {
            this.swg = swg;
            this.colour = colour;
            this.spoolSize = spoolSize;
            this.netWeight = netWeight;
        }
    }

    private Button primaryButton(String text, boolean filled) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(58));
        if (filled) {
            button.setTextColor(Color.WHITE);
            button.setBackground(roundFill(Color.rgb(37, 99, 235), dp(7)));
        } else {
            button.setTextColor(Color.rgb(37, 99, 235));
            button.setBackground(roundStroke(Color.rgb(239, 246, 255), Color.rgb(37, 99, 235), dp(9), 1));
        }
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.rgb(31, 41, 55));
        button.setMinHeight(dp(54));
        button.setBackground(roundStroke(Color.WHITE, Color.rgb(220, 224, 230), dp(9), 1));
        return button;
    }

    private LinearLayout.LayoutParams spacedMatchWrap(int topMargin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, topMargin, 0, 0);
        return params;
    }

    private GradientDrawable roundFill(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable roundStroke(int fillColor, int strokeColor, int radius, int strokeWidthDp) {
        GradientDrawable drawable = roundFill(fillColor, radius);
        drawable.setStroke(dp(strokeWidthDp), strokeColor);
        return drawable;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST && hasNeededBluetoothPermissions()) {
            printerManager.loadPairedPrinters();
        }
    }

    @Override
    public void onPrintersChanged(List<BluetoothDevice> printers) {
        visibleBluetoothPrinters.clear();
        visibleBluetoothPrinters.addAll(printers);
        updatePrinterAdapter();
        int count = visibleBluetoothPrinters.size() + visibleUsbPrinters.size();
        if (selectedPrinter == null && count > 0) {
            printerStatus.setText("Printers found: " + count);
        }
    }

    @Override
    public void onPrinterStatus(String message) {
        printerStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onUsbPrintersChanged(List<UsbDevice> printers) {
        visibleUsbPrinters.clear();
        visibleUsbPrinters.addAll(printers);
        updatePrinterAdapter();
        int count = visibleBluetoothPrinters.size() + visibleUsbPrinters.size();
        if (selectedPrinter == null && count > 0) {
            printerStatus.setText("Printers found: " + count);
        }
    }

    @Override
    public void onUsbPrinterStatus(String message) {
        printerStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onScaleStatus(String message) {
        setScaleStatusText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onScaleWeightReceived(String weight) {
        if (weight == null || weight.trim().isEmpty()) {
            return;
        }
        String normalizedWeight = normalizeWeightText(weight);
        if ("Scale Test".equals(activeQrSection)) {
            appendScaleDiagnosticParsed(normalizedWeight);
        } else if ("Spool QR".equals(activeQrSection)) {
            setSpoolWeight(normalizedWeight, true);
        } else if ("Reel QR".equals(activeQrSection)) {
            if (getCurrentFocus() != grossWeightInput) {
                setScaleStatusText("Scale weight ignored until Gross Wt. is active");
                return;
            }
            setGrossWeight(normalizedWeight, false);
            inactivityHandler.postDelayed(() -> printReelFromScaleIfReady(normalizedWeight), SCALE_PRINT_DELAY_MS);
        } else {
            spoolWeight = normalizedWeight;
        }
        setScaleStatusText("Scale weight received: " + normalizedWeight);
    }

    @Override
    public void onScaleRawData(String data) {
        if (data == null || data.trim().isEmpty()) {
            return;
        }
        if ("Scale Test".equals(activeQrSection)) {
            appendScaleDiagnosticRaw(data.trim());
        }
        setScaleStatusText("Scale data: " + data.trim());
    }

    private void setScaleStatusText(String message) {
        if (scaleStatus != null) {
            scaleStatus.setText(message);
        }
    }

    @Override
    protected void onDestroy() {
        stopInactivityTimer();
        releasePrinterManagers();
        super.onDestroy();
    }

    private void releasePrinterManagers() {
        if (printerManager != null) {   
            printerManager.release();
            printerManager = null;
        }
        if (usbPrinterManager != null) {
            usbPrinterManager.release();
            usbPrinterManager = null;
        }
        if (usbScaleManager != null) {
            usbScaleManager.release();
            usbScaleManager = null;
        }
    }

    private void rebuildVisiblePrinters() {
        visiblePrinters.clear();
        for (UsbDevice printer : visibleUsbPrinters) {
            visiblePrinters.add(PrinterTarget.usb(printer, usbPrinterManager.getDisplayName(printer)));
        }
        for (BluetoothDevice printer : visibleBluetoothPrinters) {
            visiblePrinters.add(PrinterTarget.bluetooth(printer, "Bluetooth: " + printerManager.getDisplayName(printer)));
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchParentWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedMatch() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class PrinterTarget {
        private final BluetoothDevice bluetoothDevice;
        private final UsbDevice usbDevice;
        private final String displayName;

        private PrinterTarget(BluetoothDevice bluetoothDevice, UsbDevice usbDevice, String displayName) {
            this.bluetoothDevice = bluetoothDevice;
            this.usbDevice = usbDevice;
            this.displayName = displayName;
        }

        private static PrinterTarget bluetooth(BluetoothDevice device, String displayName) {
            return new PrinterTarget(device, null, displayName);
        }

        private static PrinterTarget usb(UsbDevice device, String displayName) {
            return new PrinterTarget(null, device, displayName);
        }

        private boolean isPlaceholder() {
            return bluetoothDevice == null && usbDevice == null;
        }
    }

    private static final class ScaleSerialFormat {
        private final int dataBits;
        private final int parity;
        private final int stopBits;

        private ScaleSerialFormat(int dataBits, int parity, int stopBits) {
            this.dataBits = dataBits;
            this.parity = parity;
            this.stopBits = stopBits;
        }
    }
}
