package com.example.crosspath;

import android.Manifest;
import android.content.pm.PackageManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.*;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.example.crosspath.ble.BleTransport;
import com.example.crosspath.data.*;
import com.example.crosspath.protocol.*;
import com.example.crosspath.sync.*;
import com.example.crosspath.ui.data.UserProfile;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Foreground debug harness sharing the normal UI's repository and session expiry gate. */
public class BleDebugActivity extends AppCompatActivity {
    private static final String[] PERMISSIONS = {Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT};
    private final ScheduledExecutorService protocolExecutor = Executors.newSingleThreadScheduledExecutor();
    private BleTransport transport;
    private SyncExchange exchange;
    private Runnable encounterClosed = () -> {};
    private java.util.function.LongConsumer peerDiscovered = token -> {};
    private SafetyRepository repository;
    private long requestGeneration;
    private java.util.concurrent.ScheduledFuture<?> expiryTimer;
    private java.util.concurrent.ScheduledFuture<?> maintenanceTimer;
    private final SessionTransportGate communicationGate = new SessionTransportGate(
            System::currentTimeMillis, android.os.SystemClock::elapsedRealtime);
    private String communicationSession;
    private final SessionStopHandler sessionStop = id -> {
        communicationGate.stopSession(id);
        dispatch(() -> {
            if (id.equals(communicationSession)) {
                stopTransport();
                show("期限・時計・保存状態により通信を停止しました");
            }
        });
    };
    private MunicipalityMaster master;
    private TextView status;
    private Spinner role, municipality, syncMode;
    private EditText byteBudget, contactSeconds;
    private EditText userId;
    private CheckBox mtu23, pauseAck;
    private boolean visible;
    private final BroadcastReceiver bluetoothState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())
                    && intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                protocolExecutor.execute(() -> transport.abort("BluetoothがOFFになりました"));
            }
        }
    };
    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasPermissions() && visible) startTest();
                else show("付近のデバイス権限を許可してください。拒否した場合は端末の設定から変更できます。");
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!BuildConfig.DEBUG) { finish(); return; }
        ((CrosspathApplication) getApplication()).bleDebugActive = true;
        stopService(new Intent(this, com.example.crosspath.service.RelayForegroundService.class));
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_ble_debug);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        status = findViewById(R.id.status);
        role = findViewById(R.id.role); municipality = findViewById(R.id.municipality);
        userId = findViewById(R.id.user_id); mtu23 = findViewById(R.id.mtu23);
        syncMode = findViewById(R.id.sync_mode);
        syncMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"FULL（従来の全件交換）", "FLAT（SUMMARY・ID一覧）", "HIERARCHICAL（階層同期）"}));
        syncMode.setSelection(2);
        byteBudget = findViewById(R.id.byte_budget); contactSeconds = findViewById(R.id.contact_seconds);
        pauseAck = findViewById(R.id.pause_ack);
        pauseAck.setVisibility(BuildConfig.DEBUG ? android.view.View.VISIBLE : android.view.View.GONE);
        master = KyushuMunicipalities.load();
        repository = ((CrosspathApplication) getApplication()).repository;
        ((CrosspathApplication) getApplication()).sessionStops.add(sessionStop);
        role.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Client（端末A）", "Server（端末B）", "自動役割決定"}));
        ArrayList<String> locations = new ArrayList<>();
        for (int i = 1; i <= 233; i++) locations.add(master.requireName(i));
        municipality.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, locations));
        transport = new BleTransport(this, protocolExecutor, new BleTransport.Listener() {
            @Override public void onPeerIdentified(long token) { peerDiscovered.accept(token); }
            @Override public void onStatus(String text) { show(text); }
            @Override public void onReady(boolean client, long token) { if (exchange != null) exchange.ready(client, token); }
            @Override public void onMessage(MessageAssembler.Message message) { if (exchange != null) exchange.receive(message); }
            @Override public void onDisconnected() {
                encounterClosed.run(); encounterClosed = () -> {};
                peerDiscovered = token -> {};
                if (exchange != null) { exchange.stop(); exchange = null; }
            }
        }, communicationGate::allowsCommunication);
        ContextCompat.registerReceiver(this, bluetoothState, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED);
        findViewById(R.id.start).setEnabled(BuildConfig.DEBUG);
        findViewById(R.id.confirm).setEnabled(BuildConfig.DEBUG);
        findViewById(R.id.confirm).setOnClickListener(v -> {
            try {
                int id = Integer.parseInt(userId.getText().toString());
                if (UserProfile.isRegistered(this) && id != UserProfile.personalId(this)) {
                    show("通常画面で登録済みの個人IDを使用してください。"); return;
                }
                int code = municipality.getSelectedItemPosition() + 1;
                repository.startSession(id, code).whenComplete((session, error) -> {
                    if (error != null) show("登録できません。期間中の再登録・地点変更はできません。");
                    else { show("登録を保存しました。72時間の期間を開始します。"); refreshRegistration(); }
                });
            } catch (IllegalArgumentException error) { show("IDは1〜16777215の整数を入力してください"); }
        });
        findViewById(R.id.start).setOnClickListener(v -> {
            if (!hasPermissions()) permissions.launch(PERMISSIONS); else startTest();
        });
        findViewById(R.id.stop).setOnClickListener(v -> protocolExecutor.execute(() -> { stopTransport(); show("停止しました（保存済みデータは保持）"); }));
    }
    private boolean hasPermissions() {
        for (String permission : PERMISSIONS) if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    private void startTest() {
        if (!BuildConfig.DEBUG || !visible) return;
            BleTransport.Role selected = BleTransport.Role.values()[role.getSelectedItemPosition()];
            boolean small = mtu23.isChecked();
            boolean holdAck = BuildConfig.DEBUG && pauseAck.isChecked();
            int selectedMode = syncMode.getSelectedItemPosition() + 1;
            final long budget, seconds;
            try {
                budget = Long.parseLong(byteBudget.getText().toString());
                seconds = Long.parseLong(contactSeconds.getText().toString());
                if (budget < 1024 || budget > 100_000_000 || seconds < 1 || seconds > 45) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { show("予算は1024〜100000000B、接触時間は1〜45秒で指定してください"); return; }
            protocolExecutor.execute(() -> {
                stopTransport();
                long generation = requestGeneration;
                long requestedAt = android.os.SystemClock.elapsedRealtime();
                repository.checkAndEndExpiredSession().whenComplete((session, error) -> dispatch(() -> {
                    if (generation != requestGeneration) return;
                    if (error != null || session == null || !session.canCommunicate) {
                        show("通信できる登録がありません。登録状態・期限・端末の時計を確認してください。"); return;
                    }
                    communicationGate.open(session, requestedAt);
                    SecureRandom random = new SecureRandom();
                    android.content.SharedPreferences prefs = getSharedPreferences("full-sync", MODE_PRIVATE);
                    int start = session.sessionId.equals(prefs.getString("session", ""))
                            ? prefs.getInt("after", 0) : Math.max(0, random.nextInt(16384) * 1024 - 1);
                    prefs.edit().putString("session", session.sessionId).putInt("after", start).apply();
                    EncounterPolicy policy = ((CrosspathApplication) getApplication()).encounters;
                    long localToken = policy.begin(session.sessionId);
                    long[] nextNotice = {0}, peerToken = {0}; boolean[] peerKnown = {false};
                    transport.configureEncounter(selectedMode == Protocol.FULL ? null : localToken, peer -> {
                        if (selectedMode == Protocol.FULL) return true;
                        long now = android.os.SystemClock.elapsedRealtime();
                        long delay = policy.delay(peer, session.dataRevision, now);
                        if (delay > 0 && now >= nextNotice[0]) {
                            nextNotice[0] = now + 5000; show("再接続抑制中（残り約" + ((delay + 999) / 1000) + "秒）");
                        }
                        return delay == 0;
                    });
                    transport.start(selected, small);
                    peerDiscovered = token -> { peerToken[0] = token; peerKnown[0] = true; };
                    communicationSession = session.sessionId;
                    DeltaSyncCoordinator.Link link = new DeltaSyncCoordinator.Link() {
                        @Override public int mtu() { return transport.negotiatedMtu(); }
                        @Override public long issuedTxBytes() { return transport.issuedTxBytes(); }
                        @Override public long receivedRxBytes() { return transport.receivedRxBytes(); }
                        @Override public void send(int type, byte[] body, Runnable done) { transport.send(type, body, done); }
                        @Override public boolean matchesToken(long token) {
                            boolean accepted = transport.matchesPeerToken(token);
                            if (accepted) { peerToken[0] = token; peerKnown[0] = true; }
                            return accepted;
                        }
                        @Override public void status(String text) { show(text); }
                        @Override public void close(String reason) {
                            if (reason.startsWith("双方向FULL交換完了")) prefs.edit().clear().apply();
                            transport.abort(reason);
                        }
                    };
                    RoomSyncStore store = new RoomSyncStore(repository, session.sessionId);
                    if (selectedMode == Protocol.FULL) {
                        exchange = new FullSyncCoordinator(link, store, master, protocolExecutor, start,
                                random.nextInt(), after -> prefs.edit().putString("session", session.sessionId).putInt("after", after).apply());
                    } else {
                        DeltaSyncCoordinator delta = new DeltaSyncCoordinator(link, store, master, protocolExecutor,
                                selectedMode, random.nextInt(), budget, seconds * 1000);
                        exchange = delta;
                        encounterClosed = () -> {
                            if (!peerKnown[0]) return;
                            long revision = delta.comparedRevision() < 0 ? session.dataRevision : delta.comparedRevision();
                            policy.record(peerToken[0], revision, delta.comparisonDigest(), delta.verifiedComplete(), android.os.SystemClock.elapsedRealtime());
                            policy.convergence(peerToken[0], delta.verifiedComplete(), delta.verifiedEqual(), delta.newReceived());
                            show("差分なし接触=" + policy.consecutiveEmpty() + "回（K未設定・推定収束は無効）");
                        };
                    }
                    exchange.pauseAckAfterCommitForDebug(holdAck);
                    expiryTimer = protocolExecutor.schedule(() -> {
                        stopTransport(); show("通信期間が終了しました");
                        repository.checkAndEndExpiredSession();
                    }, Math.max(0, session.remainingMillis - (android.os.SystemClock.elapsedRealtime() - requestedAt)),
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    maintenanceTimer = protocolExecutor.scheduleWithFixedDelay(() -> {
                        if (!communicationGate.allowsCommunication()) {
                            stopTransport(); show("通信期間が終了、または時計の確認が必要です");
                        }
                        repository.checkAndEndExpiredSession().whenComplete((gate, failure) -> {
                            if (failure != null) dispatch(() -> {
                                if (generation == requestGeneration) { stopTransport(); show("期限確認に失敗したため停止しました"); }
                            });
                        });
                    }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
                }));
            });
    }
    private void stopTransport() {
        requestGeneration++;
        communicationGate.close();
        communicationSession = null;
        if (expiryTimer != null) expiryTimer.cancel(false);
        if (maintenanceTimer != null) maintenanceTimer.cancel(false);
        if (transport != null) transport.stop();
    }
    private void dispatch(Runnable work) {
        try { protocolExecutor.execute(work); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private void refreshRegistration() {
        repository.checkAndEndExpiredSession().thenAccept(session -> {
            boolean current = session.state == SessionStatus.State.ACTIVE
                    || session.state == SessionStatus.State.CLOCK_UNCERTAIN;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                boolean profileRegistered = UserProfile.isRegistered(this);
                userId.setEnabled(!current && !profileRegistered); municipality.setEnabled(!current);
                if (!current && profileRegistered) userId.setText(String.valueOf(UserProfile.personalId(this)));
                findViewById(R.id.confirm).setEnabled(BuildConfig.DEBUG && !current);
            });
            if (session.canCommunicate) repository.self(session.sessionId).thenAccept(self -> runOnUiThread(() -> {
                if (isDestroyed() || self == null) return;
                userId.setText(Integer.toString(self.userId)); municipality.setSelection(self.municipalityCode - 1);
            }));
        });
    }
    private void show(String text) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            String old = status.getText().toString();
            if (old.length() > 3000) old = old.substring(old.length() - 2000);
            status.setText(old + "\n" + text);
        });
    }
    @Override protected void onStart() {
        super.onStart(); visible = true;
        ((CrosspathApplication) getApplication()).bleDebugActive = true;
        stopService(new Intent(this, com.example.crosspath.service.RelayForegroundService.class));
        if (repository != null) refreshRegistration();
    }
    @Override protected void onStop() {
        visible = false;
        communicationGate.close();
        ((CrosspathApplication) getApplication()).bleDebugActive = false;
        protocolExecutor.execute(() -> { stopTransport(); if (status != null) show("画面を離れたため検証通信を停止しました"); });
        super.onStop();
    }
    @Override protected void onDestroy() {
        ((CrosspathApplication) getApplication()).bleDebugActive = false;
        ((CrosspathApplication) getApplication()).sessionStops.remove(sessionStop);
        if (transport != null) unregisterReceiver(bluetoothState);
        protocolExecutor.shutdown();
        super.onDestroy();
    }
}
