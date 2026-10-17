package com.locky.app;

import android.app.Application;
import android.content.ContentValues;
import android.os.SystemClock;
import java.time.Duration;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class ScreenHistoryTest {
    Application app;Store store;long base;
    @Before public void setup(){Store.instance=null;app=RuntimeEnvironment.getApplication();store=Store.get(app);store.getWritableDatabase();base=System.currentTimeMillis();}
    @After public void cleanup(){GuardService.live=null;store.close();Store.instance=null;}
    void state(long at,int state){ContentValues row=new ContentValues();row.put("time",at);row.put("state",state);store.getWritableDatabase().insertOrThrow("screen_states",null,row);}
    long duration(ScreenHistory.Evidence e){long n=0;for(long[] s:e.active())n+=s[1]-s[0];return n;}
    @Test public void mixedScreenAndKeyguardIntervalsExcludeSleepAndLockedScreen(){
        var marks=new ArrayList<>(List.of(new ScreenHistory.Mark(100,1,0),new ScreenHistory.Mark(800,1,1),new ScreenHistory.Mark(810,2,1),new ScreenHistory.Mark(900,2,0)));
        // Keyguard is already shown when screen turns on; interactive alone cannot unlock.
        marks.add(new ScreenHistory.Mark(120,2,1));
        var e=ScreenHistory.build(0,1000,1,marks);assertEquals(200,duration(e));assertTrue(e.hasInactive);
    }
    @Test public void unknownGapIsNotReconstructedFromTheCurrentActiveState(){
        var e=ScreenHistory.build(0,10000,-1,new ArrayList<>(List.of(new ScreenHistory.Mark(9000,0,1))));assertEquals(1000,duration(e));
        assertEquals(0,duration(ScreenHistory.build(0,10000,-1,new ArrayList<>())));
        assertEquals(10000,duration(ScreenHistory.build(0,10000,1,new ArrayList<>()))); // no arbitrary duration cap
    }
    @Test public void inactiveOriginalDoesNotReserveTimeAgainstRecoveryAndOriginalIsRetained() throws Exception {
        state(base-100,1);state(base+100,0);state(base+800,1);
        ContentValues raw=new ContentValues();raw.put("start",base);raw.put("end",base+1000);raw.put("pkg",app.getPackageName());raw.put("label","첫 화면");raw.put("managed",0);store.put("usage",raw);
        JSONArray corrected=store.screenUsage(base,base+1000);assertEquals(2,corrected.length());assertEquals(base+100,corrected.getJSONObject(0).getLong("end"));assertEquals(base+800,corrected.getJSONObject(1).getLong("start"));
        store.usage(base+100,base+800,"app.other","Other",false,0,"usage-access");
        assertEquals(1,store.rows("SELECT * FROM usage").length()); // proven OFF cannot become another app, either
        assertEquals(base+1000,store.rows("SELECT * FROM usage").getJSONObject(0).getLong("end"));
    }
    @Test public void unknownFirstScreenDoesNotBlockAnObservedExternalApp() throws Exception {
        ContentValues raw=new ContentValues();raw.put("start",base);raw.put("end",base+1000);raw.put("pkg",app.getPackageName());raw.put("label","첫 화면");raw.put("managed",0);store.put("usage",raw);
        assertEquals(0,store.screenUsage(base,base+1000).length());store.usage(base,base+1000,"app.other","Other",false,0,"usage-access");
        assertEquals(2,store.rows("SELECT * FROM usage").length());assertEquals("app.other",store.screenUsage(base,base+1000).getJSONObject(0).getString("pkg"));
    }
    @Test public void delayedCheckpointSplitsByPersistedScreenEvidence() throws Exception {
        GuardService g=Robolectric.buildService(GuardService.class).create().get();g.store=store;g.interactive=true;g.foreground=app.getPackageName();g.utility="첫 화면";
        // Robolectric's wall clock and elapsed clock are independent. Model a
        // real ten-second interval entirely after boot using both clock domains.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20));base=System.currentTimeMillis()-10000;
        g.segmentWall=base;g.segmentElapsed=SystemClock.elapsedRealtime()-10000;g.segmentUptime=SystemClock.uptimeMillis()-10000;state(base,1);state(base+1000,0);state(base+9000,1);
        g.checkpoint();JSONArray rows=store.screenUsage(base,base+10000);
        long sum=0;for(int i=0;i<rows.length();i++)sum+=rows.getJSONObject(i).getLong("end")-rows.getJSONObject(i).getLong("start");assertEquals(2000,sum);g.main.removeCallbacksAndMessages(null);
    }
}
