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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Visible-screen debug harness. Production registration, persistence and FGS are separate work. */
public class MainActivity extends AppCompatActivity {
    private static final String[] PERMISSIONS = {Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT};
    private final ScheduledExecutorService protocolExecutor = Executors.newSingleThreadScheduledExecutor();
    private BleTransport transport;
    private FullSyncCoordinator exchange;
    private SafetyRepository repository;
    private long requestGeneration;
    private java.util.concurrent.ScheduledFuture<?> expiryTimer;
    private MunicipalityMaster master;
    private TextView status;
    private Spinner role, municipality;
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
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        status = findViewById(R.id.status);
        role = findViewById(R.id.role); municipality = findViewById(R.id.municipality);
        userId = findViewById(R.id.user_id); mtu23 = findViewById(R.id.mtu23);
        pauseAck = findViewById(R.id.pause_ack);
        pauseAck.setVisibility(BuildConfig.DEBUG ? android.view.View.VISIBLE : android.view.View.GONE);
        master = KyushuMunicipalities.load();
        repository = ((CrosspathApplication) getApplication()).repository;
        role.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Client（端末A）", "Server（端末B）", "自動役割決定"}));
        ArrayList<String> locations = new ArrayList<>();
        for (int i = 1; i <= 233; i++) locations.add(master.requireName(i));
        municipality.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, locations));
        transport = new BleTransport(this, protocolExecutor, new BleTransport.Listener() {
            @Override public void onStatus(String text) { show(text); }
            @Override public void onReady(boolean client, long token) { if (exchange != null) exchange.ready(client, token); }
            @Override public void onMessage(MessageAssembler.Message message) { if (exchange != null) exchange.receive(message); }
            @Override public void onDisconnected() { if (exchange != null) { exchange.stop(); exchange = null; } }
        });
        ContextCompat.registerReceiver(this, bluetoothState, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED);
        findViewById(R.id.start).setEnabled(BuildConfig.DEBUG);
        findViewById(R.id.confirm).setEnabled(BuildConfig.DEBUG);
        findViewById(R.id.confirm).setOnClickListener(v -> {
            try {
                int id = Integer.parseInt(userId.getText().toString());
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
            protocolExecutor.execute(() -> {
                stopTransport();
                long generation = requestGeneration;
                repository.currentSession().whenComplete((session, error) -> dispatch(() -> {
                    if (generation != requestGeneration) return;
                    if (error != null || session == null || !"ACTIVE".equals(session.state)
                            || !session.relayEnabled || System.currentTimeMillis() >= session.endsAtWall) {
                        show("有効な登録がありません。ID・市町村を選んで登録を確定してください。"); return;
                    }
                    SecureRandom random = new SecureRandom();
                    android.content.SharedPreferences prefs = getSharedPreferences("full-sync", MODE_PRIVATE);
                    int start = session.sessionId.equals(prefs.getString("session", ""))
                            ? prefs.getInt("after", 0) : Math.max(0, random.nextInt(16384) * 1024 - 1);
                    prefs.edit().putString("session", session.sessionId).putInt("after", start).apply();
                    transport.start(selected, small);
                    exchange = new FullSyncCoordinator(new FullSyncCoordinator.Link() {
                        @Override public void send(int type, byte[] body, Runnable done) { transport.send(type, body, done); }
                        @Override public boolean matchesToken(long token) { return transport.matchesPeerToken(token); }
                        @Override public void status(String text) { show(text); }
                        @Override public void close(String reason) {
                            if (reason.startsWith("双方向FULL交換完了")) prefs.edit().clear().apply();
                            transport.abort(reason);
                        }
                    }, new RoomSyncStore(repository, session.sessionId), master, protocolExecutor, start,
                            random.nextInt(), after -> prefs.edit().putString("session", session.sessionId).putInt("after", after).apply());
                    exchange.pauseAckAfterCommitForDebug(holdAck);
                    expiryTimer = protocolExecutor.schedule(() -> {
                        stopTransport(); show("通信期間が終了しました");
                    }, Math.max(0, session.endsAtWall - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
                }));
            });
    }
    private void stopTransport() {
        requestGeneration++;
        if (expiryTimer != null) expiryTimer.cancel(false);
        transport.stop();
    }
    private void dispatch(Runnable work) {
        try { protocolExecutor.execute(work); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private void refreshRegistration() {
        repository.currentSession().thenAccept(session -> {
            boolean current = session != null && "ACTIVE".equals(session.state) && System.currentTimeMillis() < session.endsAtWall;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                userId.setEnabled(!current); municipality.setEnabled(!current);
                findViewById(R.id.confirm).setEnabled(BuildConfig.DEBUG && !current);
            });
            if (current) repository.self(session.sessionId).thenAccept(self -> runOnUiThread(() -> {
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
    @Override protected void onStart() { super.onStart(); visible = true; refreshRegistration(); }
    @Override protected void onStop() {
        visible = false;
        protocolExecutor.execute(() -> { stopTransport(); show("画面を離れたため検証通信を停止しました"); });
        super.onStop();
    }
    @Override protected void onDestroy() {
        unregisterReceiver(bluetoothState);
        protocolExecutor.shutdown();
        super.onDestroy();
    }
}
