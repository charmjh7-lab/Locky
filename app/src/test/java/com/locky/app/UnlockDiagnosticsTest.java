package com.locky.app;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class UnlockDiagnosticsTest {
    UnlockDiagnostics recorder; Context context;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("locky-settings",0).edit().clear().commit();
        recorder=new UnlockDiagnostics(context);
        recorder.clear();
        UnlockDiagnostics.live=recorder;
    }
    @After public void cleanup() throws Exception {
        recorder.setEnabled(false);
        recorder.clear();
        UnlockDiagnostics.live=null;
    }
    @Test public void unlockEventsAreNotCapturedAndAppGateStagesAreOptIn() throws Exception {
        recorder.begin();recorder.record("unlock content");
        recorder.beginGate(1,SystemClock.uptimeMillis());
        assertEquals(0,recorder.status().getInt("appGates"));
        recorder.setEnabled(true);
        recorder.begin();recorder.record("unlock content");
        recorder.confirmedActivity();
        recorder.beginGate(2,SystemClock.uptimeMillis());
        recorder.gateRecord(2,"gate payload ready");
        recorder.gateRecord(1,"stale navigation");
        recorder.finishGate(2,"shown-and-hidden");
        assertEquals(1,recorder.status().getInt("appGates"));
        assertTrue(recorder.text().contains("gate payload ready"));
        assertTrue(recorder.text().contains("shown-and-hidden"));
        assertFalse(recorder.text().contains("unlock content"));
        assertFalse(recorder.text().contains("stale navigation"));
    }
    @Test public void boundedGateHistoryPersistsAndExceptionMessagesAreRedacted() throws Exception {
        recorder.setEnabled(true);
        for(int n=0;n<30;n++){
            recorder.beginGate(n,SystemClock.uptimeMillis());
            for(int i=0;i<80;i++)recorder.gateRecord(n,"visual callback requested "+i);
            recorder.failure("app-gate-layout",new SecurityException("PRIVATE_NOTE_QUESTION_TOKEN"));
            recorder.finishGate(n,"shown-and-hidden");
        }
        recorder.flush();
        byte[] bytes=Files.readAllBytes(recorder.file.getBaseFile().toPath());
        assertTrue(bytes.length<=UnlockDiagnostics.MAX_GATE_BYTES);
        assertFalse(new String(bytes,StandardCharsets.UTF_8).contains("PRIVATE_NOTE_QUESTION_TOKEN"));
        UnlockDiagnostics restored=new UnlockDiagnostics(context);
        assertEquals(recorder.status().getInt("appGates"),restored.status().getInt("appGates"));
        assertTrue(restored.status().getInt("appGates")<=UnlockDiagnostics.MAX_GATES);
    }
    @Test public void deletingHistoryPreventsOldQueuedWriteFromRestoringIt() throws Exception {
        recorder.setEnabled(true);
        recorder.beginGate(1,SystemClock.uptimeMillis());
        recorder.gateRecord(1,"old event");
        recorder.flush();
        recorder.clear();
        assertEquals(0,recorder.status().getInt("appGates"));
        assertFalse(recorder.file.getBaseFile().exists());
        recorder.beginGate(2,SystemClock.uptimeMillis());
        recorder.gateRecord(2,"new event");
        assertTrue(recorder.text().contains("new event"));
        assertFalse(recorder.text().contains("old event"));
    }
}