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
    @Before public void setup() throws Exception {context=RuntimeEnvironment.getApplication();context.getSharedPreferences("locky-settings",0).edit().clear().commit();recorder=new UnlockDiagnostics(context);recorder.clear();UnlockDiagnostics.live=recorder;}
    @After public void cleanup() throws Exception {recorder.setEnabled(false);recorder.clear();UnlockDiagnostics.live=null;}
    @Test public void optInAndDisableControlLifecycleCollection() throws Exception {
        recorder.begin();recorder.record("not collected");assertEquals(0,recorder.status().getInt("cycles"));
        recorder.setEnabled(true);recorder.begin();recorder.record("phone-activity onCreate webview=new");recorder.confirmedActivity();recorder.setEnabled(false);recorder.record("after disabled");
        assertEquals(1,recorder.status().getInt("unlocks"));assertTrue(recorder.text().contains("onCreate"));assertFalse(recorder.text().contains("after disabled"));assertTrue(recorder.text().contains("disabled"));
    }
    @Test public void boundedHistorySurvivesReloadWithoutLeakingExceptionContents() throws Exception {
        recorder.setEnabled(true);
        for(int n=0;n<7;n++){recorder.begin();recorder.confirmedActivity();for(int i=0;i<320;i++)recorder.record("phone-activity onResume "+"x".repeat(180));}
        recorder.failure("activity-launch",new SecurityException("PRIVATE_NOTE_QUESTION_TOKEN"));recorder.flush();
        byte[] data=Files.readAllBytes(recorder.file.getBaseFile().toPath());assertTrue(data.length<=UnlockDiagnostics.MAX_BYTES);assertFalse(new String(data,StandardCharsets.UTF_8).contains("PRIVATE_NOTE_QUESTION_TOKEN"));
        UnlockDiagnostics restored=new UnlockDiagnostics(context);assertEquals(recorder.status().getInt("cycles"),restored.status().getInt("cycles"));assertTrue(restored.status().getInt("cycles")<=5);assertTrue(restored.text().contains("onResume"));
    }
    @Test public void clearCannotBeUndoneByAnOlderQueuedWriteAndNextCycleCanRecordAgain() throws Exception {
        recorder.setEnabled(true);recorder.begin();recorder.record("old cycle");recorder.flush();recorder.clear();assertEquals(0,recorder.status().getInt("cycles"));assertFalse(recorder.file.getBaseFile().exists());assertFalse(new UnlockDiagnostics(context).text().contains("old cycle"));recorder.begin();recorder.record("new cycle");assertEquals(1,recorder.status().getInt("cycles"));
    }
    @Test public void longLockedIntervalRetainsTheFollowingUnlockLifecycle() throws Exception {
        recorder.setEnabled(true);recorder.begin();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(2));recorder.record("outside bounded preparation span");assertFalse(recorder.text().contains("outside bounded preparation span"));recorder.resumeCapture();recorder.confirmedActivity();recorder.record("phone-activity onResume after long lock");assertEquals(1,recorder.status().getInt("unlocks"));assertTrue(recorder.text().contains("after long lock"));
    }
    @Test public void lastLaunchStatusWorksWithRecordingDisabled() throws Exception {
        recorder.confirmedActivity();recorder.failure("activity-launch",new SecurityException("secret"));assertEquals("activity",recorder.windowStatus().getString("lastUnlockWindow"));assertEquals("activity-launch:SecurityException",recorder.windowStatus().getString("lastFailure"));assertEquals(0,recorder.status().getInt("cycles"));
    }
}
