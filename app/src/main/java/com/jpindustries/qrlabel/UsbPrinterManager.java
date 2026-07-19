package com.jpindustries.qrlabel;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UsbPrinterManager {
    private static final String ACTION_USB_PERMISSION = "com.jpindustries.qrlabel.USB_PERMISSION";

    private final Context context;
    private final UsbManager usbManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService printerExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, UsbDevice> devicesByKey = new LinkedHashMap<>();
    private final BroadcastReceiver usbReceiver;

    private Listener listener;
    private boolean receiverRegistered;

    public UsbPrinterManager(Context context) {
        this.context = context.getApplicationContext();
        usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                String action = intent.getAction();
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                if (ACTION_USB_PERMISSION.equals(action)) {
                    if (device == null) {
                        notifyStatus("USB printer permission failed");
                        return;
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        addDevice(device);
                        notifyDevicesChanged();
                        notifyStatus("USB printer ready: " + getDisplayName(device));
                    } else {
                        notifyStatus("USB printer permission was denied");
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    loadPrinters();
                    if (device != null) {
                        notifyStatus("USB device connected: " + getDisplayName(device));
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    if (device != null) {
                        devicesByKey.remove(getDeviceKey(device));
                        notifyDevicesChanged();
                        notifyStatus("USB device removed");
                    }
                }
            }
        };
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void loadPrinters() {
        registerReceiver();
        devicesByKey.clear();
        if (usbManager == null) {
            notifyStatus("USB is not available on this device");
            return;
        }

        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (findWritableInterface(device) != null) {
                addDevice(device);
            }
        }
        notifyDevicesChanged();
    }

    public void requestPermission(UsbDevice device) {
        registerReceiver();
        if (usbManager == null || device == null) {
            notifyStatus("USB printer is not available");
            return;
        }
        if (usbManager.hasPermission(device)) {
            notifyStatus("USB printer already has permission");
            return;
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()),
                flags
        );
        usbManager.requestPermission(device, permissionIntent);
        notifyStatus("Allow USB printer access when prompted");
    }

    public void print(UsbDevice device, String command) {
        print(device, command.getBytes(StandardCharsets.US_ASCII));
    }

    public void print(UsbDevice device, byte[] bytes) {
        if (device == null) {
            notifyStatus("Select a USB printer first");
            return;
        }
        if (usbManager == null) {
            notifyStatus("USB is not available on this device");
            return;
        }
        if (!usbManager.hasPermission(device)) {
            requestPermission(device);
            return;
        }

        WritableUsbTarget target = findWritableInterface(device);
        if (target == null) {
            notifyStatus("No writable USB printer endpoint found");
            return;
        }

        notifyStatus("Sending label to " + getDisplayName(device) + "...");
        printerExecutor.execute(() -> {
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                notifyStatus("Could not open USB printer");
                return;
            }

            try {
                if (!connection.claimInterface(target.usbInterface, true)) {
                    notifyStatus("Could not claim USB printer interface");
                    return;
                }

                int sent = sendBytes(connection, target.outEndpoint, bytes);
                notifyStatus(sent == bytes.length ? "Label sent to USB printer" : "USB print failed");
            } finally {
                try {
                    connection.releaseInterface(target.usbInterface);
                } catch (RuntimeException ignored) {
                }
                connection.close();
            }
        });
    }

    private int sendBytes(UsbDeviceConnection connection, UsbEndpoint endpoint, byte[] bytes) {
        int offset = 0;
        while (offset < bytes.length) {
            int chunkSize = Math.min(16384, bytes.length - offset);
            int sent = connection.bulkTransfer(endpoint, bytes, offset, chunkSize, 5000);
            if (sent <= 0) {
                return offset;
            }
            offset += sent;
        }
        return offset;
    }

    public List<UsbDevice> getDevices() {
        return new ArrayList<>(devicesByKey.values());
    }

    public String getDisplayName(UsbDevice device) {
        if (device == null) {
            return "";
        }

        String name = device.getProductName();
        if (name == null || name.trim().isEmpty()) {
            name = device.getDeviceName();
        }

        String permission = usbManager != null && usbManager.hasPermission(device) ? "ready" : "tap to allow";
        return "USB: " + name + " (" + permission + ")\n"
                + "VID " + device.getVendorId() + " / PID " + device.getProductId();
    }

    public void release() {
        if (receiverRegistered) {
            context.unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }
        printerExecutor.shutdownNow();
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
        receiverRegistered = true;
    }

    private WritableUsbTarget findWritableInterface(UsbDevice device) {
        for (int interfaceIndex = 0; interfaceIndex < device.getInterfaceCount(); interfaceIndex++) {
            UsbInterface usbInterface = device.getInterface(interfaceIndex);
            for (int endpointIndex = 0; endpointIndex < usbInterface.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(endpointIndex);
                if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT
                        && endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    return new WritableUsbTarget(usbInterface, endpoint);
                }
            }
        }
        return null;
    }

    private void addDevice(UsbDevice device) {
        devicesByKey.put(getDeviceKey(device), device);
    }

    private String getDeviceKey(UsbDevice device) {
        return device.getDeviceName() + ":" + device.getVendorId() + ":" + device.getProductId();
    }

    private void notifyDevicesChanged() {
        if (listener == null) {
            return;
        }
        List<UsbDevice> snapshot = getDevices();
        mainHandler.post(() -> listener.onUsbPrintersChanged(snapshot));
    }

    private void notifyStatus(String message) {
        if (listener == null) {
            return;
        }
        mainHandler.post(() -> listener.onUsbPrinterStatus(message));
    }

    private static final class WritableUsbTarget {
        private final UsbInterface usbInterface;
        private final UsbEndpoint outEndpoint;

        private WritableUsbTarget(UsbInterface usbInterface, UsbEndpoint outEndpoint) {
            this.usbInterface = usbInterface;
            this.outEndpoint = outEndpoint;
        }
    }

    public interface Listener {
        void onUsbPrintersChanged(List<UsbDevice> printers);

        void onUsbPrinterStatus(String message);
    }
}
