package com.example.crosspath.ble;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import com.example.crosspath.protocol.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Chapter 6 transport. Owner supplies one serial executor and handles runtime permissions/lifetime. */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
public final class BleTransport {
    public enum Role { CLIENT, SERVER, AUTO }
    public interface Listener {
        default void onPeerIdentified(long token) { }
        void onStatus(String status);
        void onReady(boolean client, long localToken);
        void onMessage(MessageAssembler.Message message);
        void onDisconnected();
    }
    private final Context context;
    private final ScheduledExecutorService executor;
    private final Listener listener;
    private final BooleanSupplier communicationAllowed;
    private final GattOperationQueue operations;
    private final MessageAssembler assembler = new MessageAssembler();
    private final SecureRandom random = new SecureRandom();
    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer server;
    private BluetoothGatt client;
    private BluetoothDevice peer;
    private BluetoothGattCharacteristic rx, tx;
    private AdvertiseCallback advertising;
    private ScanCallback scanning;
    private ScheduledFuture<?> scanTimer, connectionTimer;
    private boolean running, ready, subscribed, force23, initiating;
    private long epoch, peerToken, expectedToken;
    private int mtu = 23, messageId;
    private Role role;
    private java.util.function.LongPredicate acceptPeer = token -> true;
    private Long sessionToken;
    private long issuedTxBytes, receivedRxBytes;

    public void configureEncounter(Long token, java.util.function.LongPredicate acceptPeer) {
        this.sessionToken = token; this.acceptPeer = acceptPeer;
    }
    public int negotiatedMtu() { return mtu; }
    public long issuedTxBytes() { return issuedTxBytes; }
    public long receivedRxBytes() { return receivedRxBytes; }

    public BleTransport(Context context, ScheduledExecutorService executor, Listener listener) {
        this(context, executor, listener, () -> true);
    }
    public BleTransport(Context context, ScheduledExecutorService executor, Listener listener,
            BooleanSupplier communicationAllowed) {
        this.context = context.getApplicationContext(); this.executor = executor; this.listener = listener;
        this.communicationAllowed = communicationAllowed;
        operations = new GattOperationQueue(executor, Protocol.OP_TIMEOUT_MS, () -> abort("GATT操作失敗／タイムアウト"));
    }
    public void start(Role role, boolean force23) {
        stop();
        issuedTxBytes = receivedRxBytes = 0;
        if (!checkGate()) return;
        this.role = role; this.force23 = force23;
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            adapter = manager == null ? null : manager.getAdapter();
            if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
                    || adapter == null || !adapter.isMultipleAdvertisementSupported()) {
                listener.onStatus("対象端末条件を満たさない：BLE広告に非対応"); return;
            }
            if (!adapter.isEnabled()) { listener.onStatus("BluetoothをONにして開始してください"); return; }
            advertiser = adapter.getBluetoothLeAdvertiser(); scanner = adapter.getBluetoothLeScanner();
            if (advertiser == null || scanner == null) { listener.onStatus("BLE機能を利用できません"); return; }
            running = true; peerToken = sessionToken == null ? random.nextLong() : sessionToken;
            openServer();
        } catch (SecurityException error) { abort("付近のデバイス権限が必要です"); }
    }
    private void post(long generation, Runnable work) {
        try { executor.execute(() -> {
            if (!running || epoch != generation) return;
            if (!checkGate()) return;
            try { work.run(); }
            catch (SecurityException error) { abort("権限が取り消されました"); }
            catch (RuntimeException error) { abort("BLE／プロトコルエラー"); }
        }); } catch (RejectedExecutionException ignored) { /* Owner has destroyed the transport. */ }
    }
    private void openServer() {
        long generation = epoch;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        server = manager.openGattServer(context, serverCallback(generation));
        if (server == null) { abort("GATT Serverを開始できません"); return; }
        BluetoothGattService service = new BluetoothGattService(Protocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        rx = new BluetoothGattCharacteristic(Protocol.RX, BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        tx = new BluetoothGattCharacteristic(Protocol.TX, BluetoothGattCharacteristic.PROPERTY_INDICATE, 0);
        tx.addDescriptor(new BluetoothGattDescriptor(Protocol.CCCD,
                BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_READ));
        service.addCharacteristic(rx); service.addCharacteristic(tx);
        operations.add("service", () -> server.addService(service), this::discover);
    }
    private void discover() {
        if (!running || peer != null) return;
        if (!checkGate()) return;
        long generation = epoch;
        if (role != Role.CLIENT) {
            advertising = new AdvertiseCallback() {
                @Override public void onStartFailure(int code) { post(generation, () -> abort("広告開始失敗: " + code)); }
            };
            AdvertiseData data = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(Protocol.SERVICE))
                    .setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build();
            byte[] identity = ByteBuffer.allocate(9).put((byte) Protocol.MAJOR).putLong(peerToken).array();
            AdvertiseData response = new AdvertiseData.Builder()
                    .addServiceData(new ParcelUuid(Protocol.SERVICE), identity).build();
            advertiser.startAdvertising(new AdvertiseSettings.Builder().setConnectable(true)
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build(), data, response, advertising);
        }
        listener.onStatus("待機中（" + role + "）");
        if (role != Role.SERVER) scanWindow(generation);
    }
    private void scanWindow(long generation) {
        if (!running || generation != epoch || peer != null) return;
        scanning = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) { post(generation, () -> found(result)); }
            @Override public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) post(generation, () -> found(result));
            }
            @Override public void onScanFailed(int error) { post(generation, () -> abort("探索開始失敗: " + error)); }
        };
        scanner.startScan(Collections.singletonList(new ScanFilter.Builder()
                        .setServiceUuid(new ParcelUuid(Protocol.SERVICE)).build()),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanning);
        scanTimer = executor.schedule(() -> {
            if (!running || generation != epoch || peer != null) return;
            try {
                stopScan();
                scanTimer = executor.schedule(() -> post(generation, () -> scanWindow(generation)), 20, TimeUnit.SECONDS);
            } catch (SecurityException error) { abort("探索権限が取り消されました"); }
        }, 10, TimeUnit.SECONDS);
    }
    private void found(ScanResult result) {
        if (peer != null || result.getScanRecord() == null) return;
        byte[] data = result.getScanRecord().getServiceData(new ParcelUuid(Protocol.SERVICE));
        if (data == null || data.length != 9 || (data[0] & 255) != Protocol.MAJOR) return;
        long token = ByteBuffer.wrap(data, 1, 8).getLong();
        if (token == peerToken) { restartWithNewToken(); return; }
        if (!acceptPeer.test(token)) return;
        if (role == Role.AUTO && Long.compareUnsigned(peerToken, token) > 0) return;
        expectedToken = token;
        listener.onPeerIdentified(token);
        claim(result.getDevice(), true);
        client = peer.connectGatt(context, false, clientCallback(epoch), BluetoothDevice.TRANSPORT_LE);
        if (client == null) abort("接続開始失敗");
    }
    private void claim(BluetoothDevice device, boolean asClient) {
        trace("CONNECT client=" + asClient);
        peer = device; initiating = asClient;
        stopDiscovery();
        long generation = epoch;
        connectionTimer = executor.schedule(() -> post(generation, () -> abort("接続時間枠終了")),
                Protocol.CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        listener.onStatus("接続中");
    }
    private BluetoothGattCallback clientCallback(long generation) {
        return new BluetoothGattCallback() {
            private void call(BluetoothGatt gatt, Runnable work) {
                post(generation, () -> { if (client == gatt) work.run(); });
            }
            @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                call(g, () -> {
                    if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                        abort("切断"); return;
                    }
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        operations.add("discover", g::discoverServices, () -> configureClient(g));
                    }
                });
            }
            @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                call(g, () -> operations.complete("discover", status == BluetoothGatt.GATT_SUCCESS));
            }
            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                call(g, () -> operations.complete("cccd", Protocol.CCCD.equals(d.getUuid())
                        && Protocol.TX.equals(d.getCharacteristic().getUuid()) && status == BluetoothGatt.GATT_SUCCESS));
            }
            @Override public void onMtuChanged(BluetoothGatt g, int actual, int status) {
                call(g, () -> {
                    mtu = status == BluetoothGatt.GATT_SUCCESS && actual >= 23 && actual <= 517 ? actual : 23;
                    operations.complete("mtu", true);
                });
            }
            @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                call(g, () -> operations.complete("write", Protocol.RX.equals(c.getUuid()) && status == BluetoothGatt.GATT_SUCCESS));
            }
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
                byte[] copy = value.clone();
                call(g, () -> { if (!Protocol.TX.equals(c.getUuid())) abort("未知の受信属性"); else receive(copy); });
            }
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                if (Build.VERSION.SDK_INT < 33) onCharacteristicChanged(g, c, c.getValue());
            }
        };
    }
    private void configureClient(BluetoothGatt g) {
        BluetoothGattService service = g.getService(Protocol.SERVICE);
        if (service == null) { abort("専用サービスがありません"); return; }
        rx = service.getCharacteristic(Protocol.RX); tx = service.getCharacteristic(Protocol.TX);
        if (rx == null || tx == null || (rx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                || (tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) {
            abort("GATT属性が非対応"); return;
        }
        BluetoothGattDescriptor cccd = tx.getDescriptor(Protocol.CCCD);
        if (cccd == null || !g.setCharacteristicNotification(tx, true)) { abort("Indication設定失敗"); return; }
        operations.add("cccd", () -> {
            if (Build.VERSION.SDK_INT >= 33) return g.writeDescriptor(cccd,
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
            cccd.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE); return g.writeDescriptor(cccd);
        }, () -> {
            if (force23) { becomeReady(); return; }
            operations.add("mtu", () -> {
                if (!g.requestMtu(517)) {
                    // No callback will arrive when request could not be enqueued. Default ATT MTU remains 23.
                    executor.execute(() -> { if (client == g && running) operations.complete("mtu", true); });
                }
                return true;
            }, this::becomeReady);
        });
    }
    private BluetoothGattServerCallback serverCallback(long generation) {
        return new BluetoothGattServerCallback() {
            @Override public void onServiceAdded(int status, BluetoothGattService service) {
                post(generation, () -> operations.complete("service", status == BluetoothGatt.GATT_SUCCESS));
            }
            @Override public void onConnectionStateChange(BluetoothDevice device, int status, int state) {
                post(generation, () -> {
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        if (role == Role.CLIENT || peer != null || status != BluetoothGatt.GATT_SUCCESS) server.cancelConnection(device);
                        else claim(device, false);
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED && !initiating && device.equals(peer)) abort("切断");
                });
            }
            @Override public void onMtuChanged(BluetoothDevice device, int actual) {
                post(generation, () -> { if (!initiating && device.equals(peer) && actual >= 23 && actual <= 517) mtu = actual; });
            }
            @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor d,
                    boolean prepared, boolean responseNeeded, int offset, byte[] value) {
                byte[] copy = value.clone();
                post(generation, () -> {
                    boolean valid = !initiating && device.equals(peer) && !prepared && offset == 0 && responseNeeded
                            && Protocol.CCCD.equals(d.getUuid()) && Protocol.TX.equals(d.getCharacteristic().getUuid())
                            && Arrays.equals(copy, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    if (responseNeeded) server.sendResponse(device, requestId,
                            valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null);
                    if (valid) subscribed = true;
                    else if (device.equals(peer) && !initiating) abort("不正なCCCD書込み");
                });
            }
            @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor d) {
                post(generation, () -> {
                    boolean valid = device.equals(peer) && offset == 0 && Protocol.CCCD.equals(d.getUuid());
                    server.sendResponse(device, requestId, valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            offset, valid ? (subscribed ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) : null);
                });
            }
            @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic c,
                    boolean prepared, boolean responseNeeded, int offset, byte[] value) {
                byte[] copy = value.clone();
                post(generation, () -> {
                    boolean valid = !initiating && device.equals(peer) && subscribed && !prepared && responseNeeded
                            && offset == 0 && Protocol.RX.equals(c.getUuid()) && copy.length <= Math.min(mtu - 3, 512);
                    if (responseNeeded) server.sendResponse(device, requestId,
                            valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null);
                    if (!valid) { if (device.equals(peer) && !initiating) abort("不正なRX書込み"); return; }
                    if (!ready) becomeReady();
                    receive(copy);
                });
            }
            @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic c) {
                post(generation, () -> server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null));
            }
            @Override public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
                post(generation, () -> server.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null));
            }
            @Override public void onNotificationSent(BluetoothDevice device, int status) {
                post(generation, () -> { if (!initiating && device.equals(peer)) operations.complete("indicate", status == BluetoothGatt.GATT_SUCCESS); });
            }
        };
    }
    private void becomeReady() {
        ready = true; listener.onStatus("GATT準備完了 / MTU=" + mtu); listener.onReady(initiating, peerToken);
    }
    /** Called on the protocol executor after decoding HELLO, before accepting DATA. */
    public boolean matchesPeerToken(long token) {
        if (token == peerToken) { peerToken = random.nextLong(); return false; }
        // Suppress our outgoing attempts; an incoming peer may have newly received information.
        if (initiating && !acceptPeer.test(token)) return false;
        return initiating ? token == expectedToken : role != Role.AUTO || Long.compareUnsigned(token, peerToken) < 0;
    }
    private void receive(byte[] frame) {
        if (!ready) { abort("初期化前の受信"); return; }
        receivedRxBytes += frame.length + 4L;
        MessageAssembler.Message message = assembler.accept(frame, mtu);
        if (message != null) {
            trace("RX complete type=" + message.type + " mtu=" + mtu + " rx=" + receivedRxBytes);
            listener.onMessage(message);
        }
    }
    /** Completion means only GATT delivery. It is NEVER a database-save ACK. */
    public void send(int type, byte[] body, Runnable delivered) {
        if (!checkGate()) return;
        if (!ready) throw new IllegalStateException("Not connected");
        List<byte[]> frames = FrameCodec.fragment(type, messageId++ & 65535, body, mtu);
        trace("TX queued type=" + type + " frames=" + frames.size() + " mtu=" + mtu);
        long generation = epoch;
        for (int i = 0; i < frames.size(); i++) {
            if (!running || epoch != generation) return;
            byte[] frame = frames.get(i);
            Runnable complete = i == frames.size() - 1 ? () -> {
                trace("TX complete type=" + type + " tx=" + issuedTxBytes);
                delivered.run();
            } : () -> { };
            if (initiating) operations.add("write", () -> {
                if (!checkGate()) return false;
                boolean accepted;
                if (Build.VERSION.SDK_INT >= 33) accepted = client.writeCharacteristic(rx, frame,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
                else { rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); rx.setValue(frame); accepted = client.writeCharacteristic(rx); }
                if (accepted) issuedTxBytes += frame.length + 4L;
                return accepted;
            }, complete);
            else operations.add("indicate", () -> {
                if (!checkGate()) return false;
                boolean accepted;
                if (Build.VERSION.SDK_INT >= 33) accepted = server.notifyCharacteristicChanged(peer, tx, true, frame) == BluetoothStatusCodes.SUCCESS;
                else { tx.setValue(frame); accepted = server.notifyCharacteristicChanged(peer, tx, true); }
                if (accepted) issuedTxBytes += frame.length + 4L;
                return accepted;
            }, complete);
        }
    }
    private void restartWithNewToken() {
        stopDiscovery();
        peerToken = random.nextLong();
        discover();
    }
    private void stopScan() {
        if (scanning != null && scanner != null) { scanner.stopScan(scanning); scanning = null; }
    }
    private void stopDiscovery() {
        if (scanTimer != null) scanTimer.cancel(false);
        stopScan();
        if (advertising != null && advertiser != null) { advertiser.stopAdvertising(advertising); advertising = null; }
    }
    public void stop() {
        running = false; epoch++;
        operations.clear(); assembler.reset();
        if (connectionTimer != null) connectionTimer.cancel(false);
        try { stopDiscovery(); } catch (RuntimeException ignored) { }
        scanning = null; advertising = null;
        if (client != null) {
            try { client.disconnect(); } catch (RuntimeException ignored) { }
            try { client.close(); } catch (RuntimeException ignored) { }
            client = null;
        }
        if (server != null) {
            try { if (peer != null && !initiating) server.cancelConnection(peer); } catch (RuntimeException ignored) { }
            try { server.close(); } catch (RuntimeException ignored) { }
            server = null;
        }
        peer = null; ready = subscribed = initiating = false; mtu = 23; messageId = 0;
        listener.onDisconnected();
    }
    public void abort(String reason) {
        trace("END reason=" + reason + " mtu=" + mtu + " tx=" + issuedTxBytes + " rx=" + receivedRxBytes);
        stop(); listener.onStatus(reason + "（開始ボタンで再試行）");
    }
    private static void trace(String text) {
        if (com.example.crosspath.BuildConfig.DEBUG) android.util.Log.d("CrosspathBle", text);
    }
    private boolean checkGate() {
        if (communicationAllowed.getAsBoolean()) return true;
        abort("通信期間が終了、または時計の確認が必要です");
        return false;
    }
}
