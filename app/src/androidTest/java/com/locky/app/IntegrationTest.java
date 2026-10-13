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
        long now=System.currentTimeMillis();s.usage(now-60000,now,"test.pkg","테스트 앱",true,0);assertEquals(60000,s.used("test.pkg",now));
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
            ContentValues v=new ContentValues();v.put("pkg","com.android.settings");v.put("label","설정 테스트");v.put("mode","limited");v.put("minutes",5);s.put("rules",v);
            device.executeShellCommand("settings put secure enabled_accessibility_services com.locky.app/com.locky.app.GuardService");device.executeShellCommand("settings put secure accessibility_enabled 1");
            long deadline=System.currentTimeMillis()+12000;while(GuardService.live==null&&System.currentTimeMillis()<deadline)Thread.sleep(100);assertNotNull("Accessibility service did not connect; enabled="+device.executeShellCommand("settings get secure enabled_accessibility_services"),GuardService.live);
            device.executeShellCommand("am start -a android.settings.SETTINGS");assertTrue("App gate not displayed",device.wait(Until.hasObject(By.textContains("들어가나요")),15000));
            GuardService g=GuardService.live;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{g.allowApp(new JSONObject().put("label","연락 확인"),5);g.sessionRemaining=1500;}catch(Exception e){throw new RuntimeException(e);}});
            boolean renewed=device.wait(Until.hasObject(By.textContains("머물고 싶나요")),15000);assertTrue("Extension screen not displayed; state="+g.state()+"; foreground="+g.foreground+"; systemPaused="+g.systemPaused+"; interactive="+g.interactive+"; overlay="+g.overlayVisible+"; scheduled="+g.pulseScheduled+"; live="+(GuardService.live==g),renewed);assertTrue(device.hasObject(By.textContains("연락 확인")));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->g.extend(5));assertTrue(g.sessionRemaining>0);
            long exhaustedAt=System.currentTimeMillis();s.usage(exhaustedAt-300000,exhaustedAt,"com.android.settings","설정 테스트",true,0);g.policyChanged();assertTrue("Daily exhaustion screen not displayed",device.wait(Until.hasObject(By.textContains("쉬어갈 시간")),10000));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{s.settings.edit().putBoolean("enabled",false).commit();g.policyChanged();});Thread.sleep(500);assertFalse(g.overlayVisible);
            device.sleep();Thread.sleep(300);device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");assertTrue("Unlock screen not displayed",device.wait(Until.hasObject(By.textContains("무엇을")),12000));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{g.choosePhone(new JSONObject().put("label","그냥 열었어요"));}catch(Exception e){throw new RuntimeException(e);}});assertFalse(g.overlayVisible);
        } finally {
            s.getWritableDatabase().delete("rules","pkg='com.android.settings'",null);s.settings.edit().putInt("start",oldStart).putInt("end",oldEnd).putBoolean("enabled",oldEnabled).commit();
            device.executeShellCommand("settings put secure enabled_accessibility_services "+(before.equals("null")?"''":before));device.pressHome();
        }
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
