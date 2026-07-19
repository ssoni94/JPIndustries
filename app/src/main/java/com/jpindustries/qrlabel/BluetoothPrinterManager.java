package com.jpindustries.qrlabel;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BluetoothPrinterManager {
    private static final UUID SERIAL_PORT_PROFILE_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService printerExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, BluetoothDevice> devicesByAddress = new LinkedHashMap<>();
    private final BroadcastReceiver discoveryReceiver;

    private Listener listener;
    private boolean receiverRegistered;

    public BluetoothPrinterManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bluetoothManager = this.context.getSystemService(BluetoothManager.class);
        adapter = bluetoothManager == null ? BluetoothAdapter.getDefaultAdapter() : bluetoothManager.getAdapter();
        discoveryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        addDevice(device);
                        notifyDevicesChanged();
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    notifyStatus("Printer search finished");
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        addDevice(device);
                        notifyDevicesChanged();
                        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                            notifyStatus("Paired with " + getDisplayName(device));
                        }
                    }
                }
            }
        };
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isBluetoothAvailable() {
        return adapter != null;
    }

    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    public void loadPairedPrinters() {
        if (!hasConnectPermission()) {
            notifyStatus("Bluetooth permission is needed");
            return;
        }
        if (adapter == null) {
            notifyStatus("Bluetooth is not available on this device");
            return;
        }

        Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
        for (BluetoothDevice device : bondedDevices) {
            addDevice(device);
        }
        notifyDevicesChanged();
    }

    public void startDiscovery() {
        if (adapter == null) {
            notifyStatus("Bluetooth is not available on this device");
            return;
        }
        if (!adapter.isEnabled()) {
            notifyStatus("Turn on Bluetooth to find printers");
            return;
        }
        if (!hasScanPermission()) {
            notifyStatus("Bluetooth scan permission is needed");
            return;
        }

        registerReceiver();
        loadPairedPrinters();

        if (adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }

        boolean started = adapter.startDiscovery();
        notifyStatus(started ? "Searching for nearby printers..." : "Could not start printer search");
    }

    public void pair(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            notifyStatus("Bluetooth permission is needed before pairing");
            return;
        }
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            notifyStatus("Printer is already paired");
            return;
        }
        boolean started = device.createBond();
        notifyStatus(started ? "Pairing started. Confirm the pairing prompt." : "Could not start pairing");
    }

    public void print(BluetoothDevice device, String command) {
        print(device, command.getBytes(StandardCharsets.US_ASCII));
    }

    public void print(BluetoothDevice device, byte[] bytes) {
        if (device == null) {
            notifyStatus("Select a printer first");
            return;
        }
        if (!hasConnectPermission()) {
            notifyStatus("Bluetooth permission is needed before printing");
            return;
        }
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            notifyStatus("Pair with the printer before printing");
            pair(device);
            return;
        }

        notifyStatus("Sending label to " + getDisplayName(device) + "...");
        printerExecutor.execute(() -> {
            BluetoothSocket socket = null;
            try {
                if (adapter != null && adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                }
                socket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE_UUID);
                socket.connect();
                OutputStream outputStream = socket.getOutputStream();
                outputStream.write(bytes);
                outputStream.flush();
                notifyStatus("Label sent to printer");
            } catch (IOException exception) {
                notifyStatus("Print failed: " + exception.getMessage());
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        });
    }

    public List<BluetoothDevice> getDevices() {
        return new ArrayList<>(devicesByAddress.values());
    }

    public String getDisplayName(BluetoothDevice device) {
        if (device == null) {
            return "";
        }
        String name = hasConnectPermission() ? device.getName() : null;
        if (name == null || name.trim().isEmpty()) {
            name = "Unknown printer";
        }
        String paired = device.getBondState() == BluetoothDevice.BOND_BONDED ? "paired" : "tap to pair";
        return name + " (" + paired + ")\n" + device.getAddress();
    }

    public void release() {
        if (adapter != null && hasScanPermission() && adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }
        if (receiverRegistered) {
            context.unregisterReceiver(discoveryReceiver);
            receiverRegistered = false;
        }
        printerExecutor.shutdownNow();
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(discoveryReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void addDevice(BluetoothDevice device) {
        devicesByAddress.put(device.getAddress(), device);
    }

    private void notifyDevicesChanged() {
        if (listener == null) {
            return;
        }
        List<BluetoothDevice> snapshot = getDevices();
        mainHandler.post(() -> listener.onPrintersChanged(snapshot));
    }

    private void notifyStatus(String message) {
        if (listener == null) {
            return;
        }
        mainHandler.post(() -> listener.onPrinterStatus(message));
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public interface Listener {
        void onPrintersChanged(List<BluetoothDevice> printers);

        void onPrinterStatus(String message);
    }
}
