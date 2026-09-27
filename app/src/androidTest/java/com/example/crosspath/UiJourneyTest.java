package com.example.crosspath;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.crosspath.data.*;
import com.example.crosspath.ui.*;
import com.example.crosspath.ui.data.*;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import static org.junit.Assert.*;


/** Runs only in the isolated .debug application. Receipt fixtures never enter production code. */
@RunWith(AndroidJUnit4.class)
public class UiJourneyTest {
    private void waitFor(ActivityScenario<MainActivity> scenario, Predicate<MainActivity> condition) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10_000;
        AtomicBoolean matched = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> matched.set(condition.test(activity)));
            if (matched.get()) return;
            Thread.sleep(50);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        fail("UI condition timed out");
    }
    private boolean screen(MainActivity activity, Class<?> type) {
        return type.isInstance(activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container));
    }
    private void selectFirstMunicipality(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            MaterialAutoCompleteTextView prefecture = activity.findViewById(R.id.sc03_prefecture_dropdown);
            prefecture.setText(prefecture.getAdapter().getItem(0).toString(), false);
            prefecture.getOnItemClickListener().onItemClick(null, prefecture, 0, 0);
            MaterialAutoCompleteTextView municipality = activity.findViewById(R.id.sc03_municipality_dropdown);
            municipality.setText(municipality.getAdapter().getItem(0).toString(), false);
            municipality.getOnItemClickListener().onItemClick(null, municipality, 0, 0);
        });
    }

    @Test public void registrationCancellationRotationConfirmationTargetsAndNotificationTap() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.example.crosspath.debug", context.getPackageName());
        context.deleteDatabase("crosspath.db");
        assertTrue(context.getSharedPreferences("user_profile", Context.MODE_PRIVATE).edit().clear().commit());
        // 実サーバーへは接続せず、計測テストでは採番結果を固定する（ID手入力のデモ欄は廃止済み）
        com.example.crosspath.registration.RegistrationProvider.overrideGatewayForTests(
                (requestId, name) -> 1001);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS")) {
                try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                    while (input.read() != -1) { }
                }
            }
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitFor(scenario, activity -> screen(activity, Sc01RegistrationFragment.class));
            scenario.onActivity(activity -> ((TextView) activity.findViewById(R.id.sc01_name_edit_text)).setText("Demo Person"));
            scenario.onActivity(activity -> activity.findViewById(R.id.sc01_button_register).performClick());
            waitFor(scenario, activity -> activity.findViewById(R.id.sc01_button_home).getVisibility() == View.VISIBLE);
            assertEquals(1001, UserProfile.personalId(context));
            scenario.onActivity(activity -> activity.findViewById(R.id.sc01_button_home).performClick());
            waitFor(scenario, activity -> screen(activity, Sc02HomeFragment.class));
            scenario.onActivity(activity -> activity.findViewById(R.id.sc02_button_survival).performClick());
            waitFor(scenario, activity -> screen(activity, Sc03MunicipalityFragment.class));
            assertNull(UiData.execute(SafetyRepository::currentSession).get(10, TimeUnit.SECONDS));
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            waitFor(scenario, activity -> screen(activity, Sc02HomeFragment.class));
            assertNull(UiData.execute(SafetyRepository::currentSession).get(10, TimeUnit.SECONDS));
            scenario.onActivity(activity -> activity.findViewById(R.id.sc02_button_survival).performClick());
            waitFor(scenario, activity -> screen(activity, Sc03MunicipalityFragment.class));
            selectFirstMunicipality(scenario);
            scenario.recreate();
            waitFor(scenario, activity -> screen(activity, Sc03MunicipalityFragment.class)
                    && activity.findViewById(R.id.sc03_button_confirm).isEnabled());
            long before = System.currentTimeMillis();
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.sc03_button_confirm).performClick();
                activity.findViewById(R.id.sc03_button_confirm).performClick();
            });
            waitFor(scenario, activity -> screen(activity, Sc04EmergencyFragment.class));
            ActiveSession period = UiData.execute(SafetyRepository::currentSession).get(10, TimeUnit.SECONDS);
            assertTrue(period.startedAtWall >= before);
            assertEquals(72L * 3_600_000, period.endsAtWall - period.startedAtWall);
            scenario.recreate();
            waitFor(scenario, activity -> screen(activity, Sc04EmergencyFragment.class));
            assertEquals(period.sessionId, UiData.execute(SafetyRepository::currentSession).get(10, TimeUnit.SECONDS).sessionId);
            scenario.onActivity(activity -> activity.navigateTab(Screen.SC05));
            waitFor(scenario, activity -> screen(activity, Sc05WatchTargetFragment.class));
            scenario.onActivity(activity -> ((TextView) activity.findViewById(R.id.sc05_id_edit_text)).setText("2002"));
            scenario.onActivity(activity -> ((TextView) activity.findViewById(R.id.sc05_name_edit_text)).setText("Watched Person"));
            scenario.onActivity(activity -> activity.findViewById(R.id.sc05_button_register).performClick());
            waitFor(scenario, activity -> ((TextView) activity.findViewById(R.id.sc05_id_edit_text)).getText().length() == 0);
            assertEquals(1, UiData.execute(SafetyRepository::watchTargets).get(10, TimeUnit.SECONDS).size());
            scenario.onActivity(activity -> activity.navigateTab(Screen.SC06));
            waitFor(scenario, activity -> screen(activity, Sc06NotificationHistoryFragment.class));
            scenario.recreate();
            waitFor(scenario, activity -> screen(activity, Sc06NotificationHistoryFragment.class));
            assertEquals(WatchStatus.State.NOT_RECEIVED, UiData.execute(SafetyRepository::currentWatchStatuses).get(10, TimeUnit.SECONDS).get(0).state);
            // Fixture is confined to instrumentation. Production has no synthetic receipt path.
            SafetyRepository.BatchResult result = UiData.execute(repo -> repo.receive(period.sessionId,
                    repo.municipalityMasterVersion(), 2002, KyushuMunicipalities.prefectures().get(0).municipalities.get(0).code)).get(10, TimeUnit.SECONDS);
            UiData.onBatchCommitted(result);
            assertEquals(WatchStatus.State.RECEIVED, UiData.execute(SafetyRepository::currentWatchStatuses).get(10, TimeUnit.SECONDS).get(0).state);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            waitFor(scenario, activity -> manager.getActiveNotifications().length > 0);
            android.service.notification.StatusBarNotification posted = manager.getActiveNotifications()[0];
            assertEquals("history:" + result.notifications.get(0).notificationId, posted.getTag());
            assertTrue((posted.getNotification().flags & android.app.Notification.FLAG_ONLY_ALERT_ONCE) != 0);
            scenario.onActivity(activity -> activity.navigateTab(Screen.SC05));
            waitFor(scenario, activity -> screen(activity, Sc05WatchTargetFragment.class));
            java.util.concurrent.atomic.AtomicReference<Intent> launchIntent = new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(activity -> launchIntent.set(new Intent(activity.getIntent())));
            posted.getNotification().contentIntent.send();
            waitFor(scenario, activity -> screen(activity, Sc06NotificationHistoryFragment.class));
            // ActivityScenario 1.5 filters lifecycle events by the original launch intent.
            scenario.onActivity(activity -> activity.setIntent(launchIntent.get()));
        }
    }
}
