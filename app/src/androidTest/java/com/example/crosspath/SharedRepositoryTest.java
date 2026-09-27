package com.example.crosspath;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.crosspath.ui.data.UiData;
import com.example.crosspath.data.SafetyRepository;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SharedRepositoryTest {
    @Test public void uiAndBleUseSameApplicationRepository() throws Exception {
        CrosspathApplication app = (CrosspathApplication) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        UiData.init(app);
        CompletableFuture<SafetyRepository> ready = new CompletableFuture<>();
        UiData.whenReady(ready::complete);
        assertSame(app.repository, ready.get(10, TimeUnit.SECONDS));
    }
}
