package com.jpindustries.qrlabel;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UsbScaleManager implements SerialInputOutputManager.Listener {
    private static final String ACTION_USB_SCALE_PERMISSION = "com.jpindustries.qrlabel.USB_SCALE_PERMISSION";
    private static final int DEFAULT_BAUD_RATE = 9600;
    private static final int DEFAULT_DATA_BITS = 8;
    private static final int DEFAULT_PARITY = UsbSerialPort.PARITY_NONE;
    private static final int DEFAULT_STOP_BITS = UsbSerialPort.STOPBITS_1;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    private final Context context;
    private final UsbManager usbManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService serialExecutor = Executors.newSingleThreadExecutor();
    private final StringBuilder serialBuffer = new StringBuilder();
    private final BroadcastReceiver usbReceiver;

    private Listener listener;
    private boolean receiverRegistered;
    private UsbSerialDriver pendingDriver;
    private int pendingBaudRate = DEFAULT_BAUD_RATE;
    private int pendingDataBits = DEFAULT_DATA_BITS;
    private int pendingParity = DEFAULT_PARITY;
    private int pendingStopBits = DEFAULT_STOP_BITS;
    private UsbSerialPort serialPort;
    private SerialInputOutputManager ioManager;

    public UsbScaleManager(Context context) {
        this.context = context.getApplicationContext();
        usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                String action = intent.getAction();
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                if (ACTION_USB_SCALE_PERMISSION.equals(action)) {
                    if (device == null || pendingDriver == null) {
                        notifyStatus("USB scale permission failed");
                        return;
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        openDriver(pendingDriver, pendingBaudRate, pendingDataBits, pendingParity, pendingStopBits);
                    } else {
                        notifyStatus("USB scale permission was denied");
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    if (device != null && serialPort != null && device.equals(serialPort.getDriver().getDevice())) {
                        closeCurrent();
                        notifyStatus("USB scale removed");
                    }
                }
            }
        };
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void connectFirstScale() {
        connectFirstScale(DEFAULT_BAUD_RATE, DEFAULT_DATA_BITS, DEFAULT_PARITY, DEFAULT_STOP_BITS);
    }

    public void connectFirstScale(int baudRate) {
        connectFirstScale(baudRate, DEFAULT_DATA_BITS, DEFAULT_PARITY, DEFAULT_STOP_BITS);
    }

    public void connectFirstScale(int baudRate, int dataBits, int parity, int stopBits) {
        registerReceiver();
        if (usbManager == null) {
            notifyStatus("USB is not available on this device");
            return;
        }

        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            notifyStatus("No USB serial scale found. Check OTG cable and converter.");
            return;
        }

        UsbSerialDriver driver = drivers.get(0);
        if (driver.getPorts().isEmpty()) {
            notifyStatus("USB scale has no serial port");
            return;
        }

        if (!usbManager.hasPermission(driver.getDevice())) {
            pendingDriver = driver;
            pendingBaudRate = baudRate;
            pendingDataBits = dataBits;
            pendingParity = parity;
            pendingStopBits = stopBits;
            requestPermission(driver.getDevice());
            return;
        }

        openDriver(driver, baudRate, dataBits, parity, stopBits);
    }

    public void release() {
        closeCurrent();
        if (receiverRegistered) {
            context.unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }
        serialExecutor.shutdownNow();
    }

    @Override
    public void onNewData(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        String text = readableText(data);
        if (!text.trim().isEmpty()) {
            String weight = appendAndExtractWeight(text);
            notifyRawData(text.trim());
            if (!weight.isEmpty()) {
                notifyWeight(weight);
            }
        } else {
            notifyRawData("HEX " + toHex(data));
        }
    }

    @Override
    public void onRunError(Exception exception) {
        notifyStatus("USB scale read stopped: " + exception.getMessage());
    }

    private void openDriver(UsbSerialDriver driver, int baudRate, int dataBits, int parity, int stopBits) {
        closeCurrent();
        UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
        if (connection == null) {
            notifyStatus("Could not open USB scale");
            return;
        }

        try {
            serialPort = driver.getPorts().get(0);
            serialPort.open(connection);
            serialPort.setParameters(baudRate, dataBits, stopBits, parity);
            ioManager = new SerialInputOutputManager(serialPort, this);
            serialExecutor.submit(ioManager);
            notifyStatus("USB scale connected: " + baudRate + " " + formatName(dataBits, parity, stopBits));
        } catch (Exception exception) {
            closeCurrent();
            notifyStatus("USB scale connection failed: " + exception.getMessage());
        }
    }

    private void requestPermission(UsbDevice device) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                context,
                1,
                new Intent(ACTION_USB_SCALE_PERMISSION).setPackage(context.getPackageName()),
                flags
        );
        usbManager.requestPermission(device, permissionIntent);
        notifyStatus("Allow USB scale access when prompted");
    }

    private void closeCurrent() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
        if (serialPort != null) {
            try {
                serialPort.close();
            } catch (Exception ignored) {
            }
            serialPort = null;
        }
        serialBuffer.setLength(0);
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_SCALE_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
        receiverRegistered = true;
    }

    private String appendAndExtractWeight(String text) {
        serialBuffer.append(text);
        if (serialBuffer.length() > 160) {
            serialBuffer.delete(0, serialBuffer.length() - 160);
        }

        String bufferedText = serialBuffer.toString();
        String lowerBufferedText = bufferedText.toLowerCase(Locale.US);
        int frameEnd = lowerBufferedText.lastIndexOf("kg");
        int endOffset = 2;
        if (frameEnd < 0) {
            frameEnd = serialBuffer.lastIndexOf("\n");
            endOffset = 1;
        }
        if (frameEnd < 0) {
            return "";
        }

        String completeFrame = serialBuffer.substring(0, frameEnd + endOffset);
        serialBuffer.delete(0, frameEnd + endOffset);

        String latestWeight = "";
        Matcher matcher = NUMBER_PATTERN.matcher(completeFrame);
        while (matcher.find()) {
            latestWeight = matcher.group();
        }
        return latestWeight;
    }

    private String readableText(byte[] data) {
        String raw = new String(data, StandardCharsets.US_ASCII);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char value = raw.charAt(index);
            if (value == '\r' || value == '\n' || value == '\t') {
                builder.append('\n');
            } else if (value >= 32 && value <= 126) {
                builder.append(value);
            }
        }
        return builder.toString();
    }

    private String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder();
        for (byte value : data) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(String.format(Locale.US, "%02X", value & 0xFF));
        }
        return builder.toString();
    }

    private String formatName(int dataBits, int parity, int stopBits) {
        String parityText;
        if (parity == UsbSerialPort.PARITY_EVEN) {
            parityText = "E";
        } else if (parity == UsbSerialPort.PARITY_ODD) {
            parityText = "O";
        } else {
            parityText = "N";
        }
        int stopText = stopBits == UsbSerialPort.STOPBITS_2 ? 2 : 1;
        return dataBits + parityText + stopText;
    }

    private void notifyStatus(String message) {
        if (listener != null) {
            mainHandler.post(() -> listener.onScaleStatus(message));
        }
    }

    private void notifyWeight(String weight) {
        if (listener != null) {
            mainHandler.post(() -> listener.onScaleWeightReceived(weight));
        }
    }

    private void notifyRawData(String data) {
        if (listener != null) {
            mainHandler.post(() -> listener.onScaleRawData(data));
        }
    }

    public interface Listener {
        void onScaleStatus(String message);

        void onScaleWeightReceived(String weight);

        void onScaleRawData(String data);
    }
}
