package com.example.crosspath.service;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.content.ContextCompat;
import com.example.crosspath.*;
import com.example.crosspath.ble.BleTransport;
import com.example.crosspath.data.*;
import com.example.crosspath.protocol.*;
import com.example.crosspath.sync.*;
import java.security.SecureRandom;
import java.util.concurrent.*;

/** Owns automatic encounters independently of Activity lifetime (design sections 3, 4, 9). */
public final class RelayForegroundService extends Service {
    public static final String[] BLE_PERMISSIONS = {Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT};
    private static final String CHANNEL = "relay";
    private static final int NOTIFICATION = 1001;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final SessionTransportGate gate = new SessionTransportGate(System::currentTimeMillis, SystemClock::elapsedRealtime);
    private final SecureRandom random = new SecureRandom();
    private CrosspathApplication app;
    private BleTransport transport;
    private DeltaSyncCoordinator exchange;
    private SessionStatus session;
    private ScheduledFuture<?> restart, expiry;
    private boolean active, resetting, checking, discoveryPending;
    private volatile boolean destroyed;
    private long generation, peer;
    private boolean peerKnown;
    private String lastResult = "";
    private final SessionStopHandler sessionStop = id -> {
        gate.stopSession(id);
        dispatch(() -> {
            if (session != null && id.equals(session.sessionId)) finish("期限または時計の確認により通信を停止しました");
        });
    };
    private final BroadcastReceiver bluetooth = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            dispatch(() -> {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON)
                    finish("BluetoothをONにし、アプリで通信を再開してください");
            });
        }
    };
    public static boolean hasPermissions(Context context) {
        for (String permission : BLE_PERMISSIONS)
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    public static String unavailableReason(Context context) {
        if (!hasPermissions(context)) return "付近のデバイス権限を許可してください";
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isMultipleAdvertisementSupported()) return "この端末はBLE中継に対応していません";
            if (!adapter.isEnabled()) return "BluetoothをONにしてください";
        } catch (SecurityException denied) { return "付近のデバイス権限を許可してください"; }
        return null;
    }
    @Override public void onCreate() {
        super.onCreate();
        app = (CrosspathApplication) getApplication();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "生存情報の中継", NotificationManager.IMPORTANCE_LOW));
        app.sessionStops.add(sessionStop);
        ContextCompat.registerReceiver(this, bluetooth, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED);
        transport = new BleTransport(this, executor, new BleTransport.Listener() {
            @Override public void onPeerIdentified(long token) { peer = token; peerKnown = true; }
            @Override public void onStatus(String text) {
                if (!active) return;
                if (text.startsWith("待機中")) publish(RelayStatus.State.DISCOVERING, "周辺の端末を探索中");
                else if (text.equals("接続中")) publish(RelayStatus.State.CONNECTING, "近くの端末に接続中");
                else if (text.startsWith("GATT準備")) publish(RelayStatus.State.SYNCING, "生存情報を照合中");
                else if (text.contains("開始ボタン")) {
                    if (!lastResult.startsWith("同期完了")) lastResult = "前回の通信は未完了です。再試行します";
                    if (text.contains("接続時間枠終了")) lastResult = "前回は時間内に同期できませんでした。再試行します";
                    publish(RelayStatus.State.COOLDOWN, "再探索を待っています");
                } else if (text.contains("非対応") || text.contains("できません") || text.contains("してください")) finish(text);
            }
            @Override public void onReady(boolean client, long token) {
                if (!active || session == null) return;
                DeltaSyncCoordinator.Link link = new DeltaSyncCoordinator.Link() {
                    @Override public int mtu() { return transport.negotiatedMtu(); }
                    @Override public long issuedTxBytes() { return transport.issuedTxBytes(); }
                    @Override public long receivedRxBytes() { return transport.receivedRxBytes(); }
                    @Override public void send(int type, byte[] body, Runnable done) { transport.send(type, body, done); }
                    @Override public boolean matchesToken(long value) {
                        boolean accepted = transport.matchesPeerToken(value);
                        if (accepted) { peer = value; peerKnown = true; }
                        return accepted;
                    }
                    @Override public void status(String text) {
                        if (BuildConfig.DEBUG) android.util.Log.d("CrosspathRelay", text);
                        if (text.startsWith("DBコミット完了")) app.relayDataChanges.postValue(SystemClock.elapsedRealtime());
                    }
                    @Override public void close(String reason) {
                        lastResult = exchange != null && exchange.verifiedComplete()
                                ? (exchange.verifiedEqual() ? "同期完了：相手との不足なし" : "同期完了：不足分を保存しました")
                                : "前回の通信は未完了です。再試行します";
                        if (BuildConfig.DEBUG) android.util.Log.d("CrosspathRelay", reason);
                        transport.abort(reason);
                    }
                };
                exchange = new DeltaSyncCoordinator(link, new RoomSyncStore(app.repository, session.sessionId),
                        KyushuMunicipalities.load(), executor, Protocol.HIERARCHICAL, random.nextInt(), 65536, 45000);
                exchange.ready(client, token);
            }
            @Override public void onMessage(MessageAssembler.Message message) { if (exchange != null) exchange.receive(message); }
            @Override public void onDisconnected() {
                if (resetting) return;
                if (peerKnown && session != null) {
                    boolean success = exchange != null && exchange.verifiedComplete();
                    long revision = exchange != null && exchange.comparedRevision() >= 0 ? exchange.comparedRevision() : session.dataRevision;
                    app.encounters.record(peer, revision, exchange == null ? null : exchange.comparisonDigest(), success, SystemClock.elapsedRealtime());
                    app.encounters.convergence(peer, success, success && exchange.verifiedEqual(), exchange == null ? 0 : exchange.newReceived());
                    if (!success) lastResult = "前回の通信は未完了です。再試行します";
                }
                if (exchange != null) exchange.stop();
                exchange = null; peerKnown = false;
                if (active) {
                    publish(RelayStatus.State.COOLDOWN, "再探索を待っています");
                    if (restart != null) restart.cancel(false);
                    restart = executor.schedule(() -> checkSession(true), 10, TimeUnit.SECONDS);
                }
            }
        }, () -> !destroyed && !app.bleDebugActive && gate.allowsCommunication());
        executor.scheduleWithFixedDelay(() -> { if (active) checkSession(false); }, 30, 30, TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String reason = unavailableReason(this);
        if (reason != null || app.bleDebugActive) {
            publish(RelayStatus.State.BLOCKED, reason == null ? "BLE検証画面を使用中です" : reason);
            stopSelf(); return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION, notification("登録期間中、生存情報を交換・中継します"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (RuntimeException denied) {
            publish(RelayStatus.State.BLOCKED, "通信を開始できません。アプリを開いて再開してください");
            stopSelf(); return START_NOT_STICKY;
        }
        dispatch(() -> {
            if (!active) { active = true; publish(RelayStatus.State.STARTING, "通信を準備しています"); checkSession(true); }
        });
        return START_STICKY;
    }
    private void checkSession(boolean discover) {
        if (!active || destroyed) return;
        discoveryPending |= discover;
        if (checking) return;
        if (app.bleDebugActive) { finish("BLE検証画面を使用中です"); return; }
        String reason = unavailableReason(this);
        if (reason != null) { finish(reason); return; }
        checking = true;
        long epoch = generation, sampledAt = SystemClock.elapsedRealtime();
        app.repository.checkAndEndExpiredSession().whenComplete((current, error) -> dispatch(() -> {
            checking = false;
            if (!active || epoch != generation) return;
            if (error != null || current == null || !current.canCommunicate) {
                finish(error != null ? "保存状態を確認できません。アプリで再開してください"
                        : current != null && current.state == SessionStatus.State.CLOCK_UNCERTAIN
                        ? "端末の時計を確認してください" : "通信期間が終了しました");
                return;
            }
            if (session != null && !session.sessionId.equals(current.sessionId)) { finish("登録期間が変わりました。アプリで再開してください"); return; }
            session = current;
            if (discoveryPending) {
                discoveryPending = false;
                gate.open(current, sampledAt);
                if (expiry != null) expiry.cancel(false);
                expiry = executor.schedule(() -> {
                    gate.close(); finish("通信期間が終了しました"); app.repository.checkAndEndExpiredSession();
                }, Math.max(0, current.remainingMillis - (SystemClock.elapsedRealtime() - sampledAt)), TimeUnit.MILLISECONDS);
                long token = app.encounters.begin(current.sessionId);
                transport.configureEncounter(token, value -> app.encounters.delay(value, session.dataRevision, SystemClock.elapsedRealtime()) == 0);
                resetting = true;
                try { transport.start(BleTransport.Role.AUTO, false); }
                finally { resetting = false; }
            }
        }));
    }
    private Notification notification(String text) {
        PendingIntent content = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_sync_24)
                .setContentTitle("crosspath 生存情報の中継").setContentText(text)
                .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).build();
    }
    private void publish(RelayStatus.State state, String text) {
        app.relayStatus.postValue(new RelayStatus(state, text, lastResult));
        if (BuildConfig.DEBUG) android.util.Log.d("CrosspathRelay", state + ": " + text);
    }
    private void finish(String reason) {
        active = false; generation++; gate.close();
        if (restart != null) restart.cancel(false);
        if (expiry != null) expiry.cancel(false);
        if (exchange != null) { exchange.stop(); exchange = null; }
        resetting = true;
        transport.stop(); resetting = false;
        publish(RelayStatus.State.BLOCKED, reason);
        new Handler(Looper.getMainLooper()).post(() -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); });
    }
    private void dispatch(Runnable task) {
        if (destroyed) return;
        try { executor.execute(() -> { if (!destroyed) task.run(); }); }
        catch (RejectedExecutionException ignored) { }
    }
    @Override public void onDestroy() {
        destroyed = true; gate.close();
        app.sessionStops.remove(sessionStop); unregisterReceiver(bluetooth);
        executor.execute(() -> {
            if (active) publish(RelayStatus.State.STOPPED, "通信は停止しています");
            active = false; resetting = true;
            if (exchange != null) exchange.stop();
            transport.stop();
            executor.shutdownNow();
        });
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
