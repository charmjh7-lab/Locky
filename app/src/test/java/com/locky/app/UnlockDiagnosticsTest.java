package com.locky.app;

import android.app.Application;
import android.content.Context;
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
    UnlockDiagnostics recorder;Context context;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();context.getSharedPreferences("locky-settings",0).edit().clear().commit();
        recorder=new UnlockDiagnostics(context);recorder.clear();UnlockDiagnostics.live=recorder;
    }
    @After public void cleanup() throws Exception {recorder.setEnabled(false);recorder.clear();UnlockDiagnostics.live=null;}
    @Test public void optInTakesEffectOnTheNextCycleAndOffStopsCollectionImmediately() throws Exception {
        recorder.begin();recorder.record("not collected");assertEquals(0,recorder.status().getInt("cycles"));
        recorder.setEnabled(true);recorder.record("not a cycle yet");assertEquals(0,recorder.status().getInt("cycles"));
        recorder.begin();recorder.record("window update requested type=2038 alpha=1 flagsBefore=8 flagsAfter=8");
        recorder.confirmed(2038);recorder.setEnabled(false);recorder.record("after disabled");
        assertEquals(1,recorder.status().getInt("unlocks"));assertFalse(recorder.text().contains("after disabled"));
        assertTrue(recorder.text().contains("disabled"));assertTrue(recorder.text().contains("갱신 요청 1"));
    }
    @Test public void boundedCyclesSurviveReloadAndKeepTotalCountersWhenNoisyEventsAreTrimmed() throws Exception {
        recorder.setEnabled(true);
        for(int n=0;n<7;n++){recorder.begin();recorder.confirmed(2038);for(int i=0;i<320;i++)recorder.record("window update requested type=2038 alpha=1 flagsBefore=8 flagsAfter=8 "+"x".repeat(180));recorder.transitionEnded("stable-application-focus");}
        recorder.flush();assertTrue(recorder.status().getInt("cycles")<=5);
        byte[] file=Files.readAllBytes(recorder.file.getBaseFile().toPath());assertTrue(file.length<=UnlockDiagnostics.MAX_BYTES);
        UnlockDiagnostics restored=new UnlockDiagnostics(context);
        assertEquals(recorder.status().getInt("cycles"),restored.status().getInt("cycles"));
        assertTrue(restored.text().contains("갱신 요청 320"));assertTrue(restored.text().contains("stable-application-focus"));
    }
    @Test public void chronologicalMetadataDistinguishesAlphaFocusAndAndroidCallbacksWithoutUserContent() throws Exception {
        recorder.setEnabled(true);recorder.begin();WebHost.trace("alpha requested 1.0->0.0 state=SUSPENDED");
        WebHost.trace("window update requested type=2038 alpha=1 flagsBefore=8 flagsAfter=0");WebHost.trace("window focus observed=true");
        recorder.failure("update",new SecurityException("PRIVATE_NOTE_QUESTION_TOKEN"));recorder.flush();
        String text=recorder.text();assertTrue(text.indexOf("alpha requested")<text.indexOf("window focus observed"));
        assertTrue(text.contains("alpha 변경 요청 1"));assertTrue(text.contains("+0ms"));assertTrue(text.contains("합성 완료"));
        assertFalse(text.contains("PRIVATE_NOTE_QUESTION_TOKEN"));
        assertFalse(new String(Files.readAllBytes(recorder.file.getBaseFile().toPath()),StandardCharsets.UTF_8).contains("PRIVATE_NOTE_QUESTION_TOKEN"));
    }
    @Test public void clearCannotBeUndoneByAnOlderQueuedWriteAndNextCycleCanRecordAgain() throws Exception {
        recorder.setEnabled(true);recorder.begin();recorder.record("old cycle");recorder.flush();recorder.clear();
        assertEquals(0,recorder.status().getInt("cycles"));assertFalse(recorder.file.getBaseFile().exists());
        assertFalse(new UnlockDiagnostics(context).text().contains("old cycle"));
        recorder.begin();recorder.record("new cycle");assertEquals(1,recorder.status().getInt("cycles"));
    }
    @Test public void aLongLockedIntervalDoesNotLoseTheFollowingUnlockCapture() throws Exception {
        recorder.setEnabled(true);recorder.begin();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(2));
        recorder.record("outside bounded preparation span");assertFalse(recorder.text().contains("outside bounded preparation span"));
        recorder.resumeCapture();recorder.confirmed(2038);recorder.record("window focus observed=true after long lock");
        assertEquals(1,recorder.status().getInt("cycles"));assertEquals(1,recorder.status().getInt("unlocks"));assertTrue(recorder.text().contains("after long lock"));
    }
    @Test public void basicLastWindowAndFailureStatusDoesNotRequireDiagnosticRecording() throws Exception {
        recorder.confirmed(2038);recorder.failure("add",new SecurityException("secret"));
        assertFalse(recorder.enabled());assertEquals("application",recorder.windowStatus().getString("lastUnlockWindow"));
        assertEquals("add:SecurityException",recorder.windowStatus().getString("lastFailure"));assertEquals(0,recorder.status().getInt("cycles"));
    }
}
