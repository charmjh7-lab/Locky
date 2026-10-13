package com.locky.app;
import android.content.*;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class IntegrationTest {
    @Before public void keepOtherAccessibilityServicesEnabled(){Configurator.getInstance().setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);}

    @Test public void storedPurposeMemoChatAndBudgetSurviveActivityRestart() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();Store s=Store.get(c);
        JSONObject purpose=s.purpose(new JSONObject().put("id","test-purpose").put("label","테스트 연락 확인"),"*");assertEquals("테스트 연락 확인",purpose.getString("label"));
        s.note(new JSONObject().put("id","test-note").put("title","오늘의 할 일").put("body","메모 저장 확인").put("checks",new JSONArray().put(new JSONObject().put("text","책 읽기").put("done",true))));
        String chat=s.newChat();s.message(chat,"user","기억할 메시지","complete");assertEquals("기억할 메시지",s.rows("SELECT body FROM messages WHERE chat=?",chat).getJSONObject(0).getString("body"));
        long now=Rules.dayStart(System.currentTimeMillis(),java.time.ZoneId.systemDefault())+60000;s.usage(now-60000,now,"test.pkg","테스트 앱",true,0);assertEquals(60000,s.used("test.pkg",now));
        UiDevice d=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));assertTrue(d.wait(Until.hasObject(By.textContains("Locky")),10000));d.pressHome();c.startActivity(new Intent(c,MainActivity.class).putExtra("screen","home").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));assertTrue(d.wait(Until.hasObject(By.textContains("무엇을")),10000));
        assertEquals("메모 저장 확인",s.rows("SELECT body FROM notes WHERE id='test-note'").getJSONObject(0).getString("body"));
        s.deleteChat(chat);assertEquals(0,s.rows("SELECT id FROM messages WHERE chat=?",chat).length());
    }
    @Test public void accessibilityActuallyGatesExtendsAndReleasesAtFreeTime() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();Store s=Store.get(c);UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String before=device.executeShellCommand("settings get secure enabled_accessibility_services").trim();
        int oldStart=s.settings.getInt("start",540),oldEnd=s.settings.getInt("end",1260);boolean oldEnabled=s.settings.getBoolean("enabled",true);
        try {
            s.settings.edit().putBoolean("enabled",true).putInt("start",0).putInt("end",0).commit();
            ContentValues v=new ContentValues();v.put("pkg","com.locky.fixture");v.put("label","테스트 앱");v.put("mode","limited");v.put("minutes",5);s.put("rules",v);
            device.executeShellCommand("pm grant com.locky.app android.permission.POST_NOTIFICATIONS");
            device.executeShellCommand("settings put secure enabled_accessibility_services com.locky.app/com.locky.app.GuardService");device.executeShellCommand("settings put secure accessibility_enabled 1");
            long deadline=System.currentTimeMillis()+12000;while(GuardService.live==null&&System.currentTimeMillis()<deadline)Thread.sleep(100);assertNotNull("Accessibility service did not connect; enabled="+device.executeShellCommand("settings get secure enabled_accessibility_services"),GuardService.live);
            device.executeShellCommand("am start -n com.locky.fixture/.ProbeActivity");assertTrue("App gate not displayed",device.wait(Until.hasObject(By.textContains("들어가나요")),15000));
            GuardService g=GuardService.live;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{g.allowApp(new JSONObject().put("label","연락 확인"),5);g.sessionRemaining=1500;}catch(Exception e){throw new RuntimeException(e);}});
            boolean renewed=device.wait(Until.hasObject(By.textContains("머물고 싶나요")),15000);assertTrue("Extension screen not displayed; state="+g.state()+"; foreground="+g.foreground+"; systemPaused="+g.systemPaused+"; interactive="+g.interactive+"; overlay="+g.overlayVisible+"; scheduled="+g.pulseScheduled+"; live="+(GuardService.live==g)+"; ticks="+g.pulseCount+"; home="+g.homePackage,renewed);assertTrue(device.hasObject(By.textContains("연락 확인")));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->g.extend(5));assertTrue(g.sessionRemaining>0);
            boolean timerNotice=false;long noticeDeadline=System.currentTimeMillis()+3000;
            while(!timerNotice&&System.currentTimeMillis()<noticeDeadline){for(android.service.notification.StatusBarNotification notice:c.getSystemService(android.app.NotificationManager.class).getActiveNotifications())if(notice.getId()==11&&notice.getNotification().extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER))timerNotice=true;if(!timerNotice)Thread.sleep(100);}
            final boolean observedTimer=timerNotice;final String timerState="permission="+Notices.allowed(c)+"; notifications="+c.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()+"; foreground="+g.foreground+"; paused="+g.systemPaused+"; windows="+g.lastWindows;
            long allowed=g.sessionRemaining;Thread.sleep(1300);assertTrue("Foreground timer did not decrease",g.sessionRemaining<allowed);
            long chosenSession=g.sessionEvent;device.openNotification();long shadeDeadline=System.currentTimeMillis()+4000;while(!g.systemPaused&&System.currentTimeMillis()<shadeDeadline)Thread.sleep(100);assertTrue("Shade not detected: "+g.lastWindows,g.systemPaused);long paused=g.sessionRemaining;Thread.sleep(1100);assertTrue("Shade time was charged: paused="+g.systemPaused+"; windows="+g.lastWindows,Math.abs(g.sessionRemaining-paused)<300);
            device.pressBack();Thread.sleep(1200);assertEquals("Notification drawer ended the chosen session",chosenSession,g.sessionEvent);assertFalse("Notification drawer redisplayed the app start gate",g.overlayVisible);
            device.pressHome();long homeDeadline=System.currentTimeMillis()+5000;while(!g.sessionPkg.isEmpty()&&System.currentTimeMillis()<homeDeadline)Thread.sleep(100);assertEquals("Home did not end the session; foreground="+g.foreground+"; paused="+g.systemPaused+"; windows="+g.lastWindows+"; timer="+observedTimer+"; "+timerState,"",g.sessionPkg);long usedAtHome=s.used("com.locky.fixture",System.currentTimeMillis());Thread.sleep(1100);assertEquals("Home time was charged",usedAtHome,s.used("com.locky.fixture",System.currentTimeMillis()));
            device.pressRecentApps();
            UiObject2 recentCard=device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile(".*:id/(snapshot|task_thumbnail|thumbnail)"))),2000);
            if(recentCard==null){recentCard=device.wait(Until.findObject(By.descContains("Locky test target")),5000);if(recentCard==null)recentCard=device.wait(Until.findObject(By.text("Locky test target")),3000);
                if(recentCard!=null){android.graphics.Rect bounds=recentCard.getVisibleBounds();while(recentCard.getParent()!=null&&(bounds.width()<device.getDisplayWidth()/2||bounds.height()<device.getDisplayHeight()/4)){recentCard=recentCard.getParent();bounds=recentCard.getVisibleBounds();}}
            }
            if(recentCard==null){java.io.ByteArrayOutputStream xml=new java.io.ByteArrayOutputStream();device.dumpWindowHierarchy(xml);fail("Recent task card not found; timer="+observedTimer+"; "+timerState+"; XML="+xml.toString("UTF-8"));}
            recentCard.click();
            assertTrue("Recent-task reentry did not show app gate",device.wait(Until.hasObject(By.textContains("들어가나요")),10000));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{g.allowApp(new JSONObject().put("label","다시 사용"),5);}catch(Exception e){throw new RuntimeException(e);}});
            long exhaustedAt=Rules.dayStart(System.currentTimeMillis(),java.time.ZoneId.systemDefault())+1200000;s.usage(exhaustedAt-300000,exhaustedAt,"com.locky.fixture","테스트 앱",true,0);g.policyChanged();assertTrue("Daily exhaustion screen not displayed",device.wait(Until.hasObject(By.textContains("쉬어갈 시간")),10000));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{s.settings.edit().putBoolean("enabled",false).commit();g.policyChanged();});Thread.sleep(500);assertFalse(g.overlayVisible);
            device.sleep();Thread.sleep(300);device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");assertTrue("Unlock screen not displayed",device.wait(Until.hasObject(By.textContains("무엇을")),12000));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{g.choosePhone(new JSONObject().put("label","그냥 열었어요"));}catch(Exception e){throw new RuntimeException(e);}});assertFalse(g.overlayVisible);assertTrue("Running timer notification missing: "+timerState,observedTimer);
        } finally {
            s.getWritableDatabase().delete("rules","pkg='com.locky.fixture'",null);s.settings.edit().putInt("start",oldStart).putInt("end",oldEnd).putBoolean("enabled",oldEnabled).commit();
            device.executeShellCommand("settings put secure enabled_accessibility_services "+(before.equals("null")?"''":before));device.pressHome();
        }
    }
    @Test public void recoveredIntervalsDoNotDoubleChargeAndPurposesStayIndependent() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();Store s=Store.get(c);long end=Rules.dayStart(System.currentTimeMillis(),java.time.ZoneId.systemDefault())+720000,start=end-120000;String pkg="test.recovery";
        s.usage(start,end-60000,pkg,"복구 앱",true,0);s.usage(start,end,pkg,"복구 앱",true,0);s.usage(start,end,pkg,"복구 앱",true,0);assertEquals(120000,s.used(pkg,end));
        assertTrue(s.purposes("phone").length()>0);assertTrue(s.purposes("com.locky.fixture").length()>0);
        JSONObject phone=s.purpose(new JSONObject().put("id","test-phone-scope").put("label","전화 목적"),"phone");JSONObject app=s.purpose(new JSONObject().put("id","test-app-scope").put("label","앱 목적"),"app");
        for(int i=0;i<s.purposes("phone").length();i++)assertNotEquals(app.getString("id"),s.purposes("phone").getJSONObject(i).getString("id"));
        for(int i=0;i<s.purposes("com.locky.fixture").length();i++)assertNotEquals(phone.getString("id"),s.purposes("com.locky.fixture").getJSONObject(i).getString("id"));
        s.getWritableDatabase().delete("purposes","id IN ('test-phone-scope','test-app-scope')",null);s.getWritableDatabase().delete("usage","pkg=?",new String[]{pkg});
    }
    @Test public void upgradePreservesExistingMemoAndChats() throws Exception {
        android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.create(null);
        try {
            db.execSQL("CREATE TABLE purposes(id TEXT PRIMARY KEY,label TEXT,pkg TEXT DEFAULT '',uri TEXT DEFAULT '',aimless INTEGER DEFAULT 0,scope TEXT DEFAULT '*')");
            db.execSQL("INSERT INTO purposes(id,label) VALUES('custom-old','기존 목적')");
            db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT,body TEXT,checks TEXT,updated INTEGER)");
            db.execSQL("INSERT INTO notes VALUES('old','제목','잊지 않을 글','[{\"text\":\"할 일\",\"done\":true}]',1)");
            db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY)");
            db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY,body TEXT)");db.execSQL("INSERT INTO messages VALUES(1,'기존 대화')");
            Store.get(ApplicationProvider.getApplicationContext()).onUpgrade(db,1,2);
            try(android.database.Cursor memo=db.rawQuery("SELECT body,checks,mode FROM notes WHERE id='old'",null)){assertTrue(memo.moveToFirst());assertEquals("check",memo.getString(2));JSONArray checks=new JSONArray(memo.getString(1));assertEquals("잊지 않을 글",checks.getJSONObject(0).getString("text"));assertTrue(checks.getJSONObject(1).getBoolean("done"));}
            try(android.database.Cursor messages=db.rawQuery("SELECT body FROM messages",null)){assertTrue(messages.moveToFirst());assertEquals("기존 대화",messages.getString(0));}
        }finally{db.close();}
    }
    @Test public void overnightExhaustionIsNotUndoneByMidnight() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();Store s=Store.get(c);int start=s.settings.getInt("start",540),end=s.settings.getInt("end",1260);boolean enabled=s.settings.getBoolean("enabled",true);String pkg="test.overnight";
        try {
            s.settings.edit().putBoolean("enabled",true).putInt("start",1320).putInt("end",420).commit();ContentValues v=new ContentValues();v.put("pkg",pkg);v.put("label","밤 사용 테스트");v.put("mode","limited");v.put("minutes",60);s.put("rules",v);
            java.time.ZoneId zone=java.time.ZoneId.systemDefault();long night=java.time.ZonedDateTime.of(2026,10,13,23,30,0,0,zone).toInstant().toEpochMilli();s.blockUntilFree(pkg,night);
            long afterMidnight=java.time.ZonedDateTime.of(2026,10,14,0,1,0,0,zone).toInstant().toEpochMilli();assertEquals(0,s.remaining(pkg,afterMidnight));long free=java.time.ZonedDateTime.of(2026,10,14,7,0,0,0,zone).toInstant().toEpochMilli();assertEquals(3600000,s.remaining(pkg,free));
        }finally{s.getWritableDatabase().delete("rules","pkg=?",new String[]{pkg});s.settings.edit().remove("blocked-until:"+pkg).putInt("start",start).putInt("end",end).putBoolean("enabled",enabled).commit();}
    }
}
