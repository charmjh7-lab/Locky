package com.locky.app;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import java.time.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class FocusBriefingTest {
    Context c;Store s;ZoneId zone=ZoneId.of("Asia/Seoul");TimeZone old;
    @Before public void setup(){old=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone(zone));c=RuntimeEnvironment.getApplication();Store.instance=null;s=Store.get(c);s.getWritableDatabase();Notices.channels(c);}
    @After public void cleanup(){if(s!=null)s.close();Store.instance=null;GuardService.live=null;TimeZone.setDefault(old);}
    long at(String date,String time){return LocalDateTime.parse(date+"T"+time).atZone(zone).toInstant().toEpochMilli();}
    void history(String date,int start,int end) {s.getWritableDatabase().delete("focus_config_history",null,null);config(at(date,"00:00"),true,start,end,zone.getId());s.settings.edit().putBoolean("enabled",true).putInt("start",start).putInt("end",end).commit();}
    void config(long at,boolean enabled,int start,int end,String zone){s.getWritableDatabase().execSQL("INSERT INTO focus_config_history(effective,enabled,start,end,zone,reason) VALUES(?,?,?,?,?,'test')",new Object[]{at,enabled?1:0,start,end,zone});}
    JSONObject span(long from,long to,String pkg,long parent) throws Exception {return new JSONObject().put("start",from).put("end",to).put("pkg",pkg).put("label",pkg).put("parent",parent).put("source","accessibility").put("rule_mode","none");}
    @Test public void overnightAndEditsPartitionExactlyWithoutInventingPast() throws Exception {
        long a=at("2026-10-12","00:00"),b=at("2026-10-14","00:00");history("2026-10-12",1320,420);
        config(at("2026-10-13","01:00"),false,1320,420,zone.getId());
        JSONArray p=FocusPeriods.between(FocusPeriods.history(s),a,b);long total=0,focus=0;
        for(int i=0;i<p.length();i++){JSONObject row=p.getJSONObject(i);total+=row.getLong("end")-row.getLong("start");if(row.optBoolean("focus"))focus+=row.getLong("end")-row.getLong("start");}
        assertEquals(b-a,total);assertEquals(10*3600000L,focus); // day 1 00–07 +22–24; day 2 00–01
        JSONObject unknown=FocusPeriods.data(s,a-86400000L,a);assertEquals(86400000L,unknown.getLong("unknownMillis"));assertEquals(0,unknown.getLong("configuredMillis"));
    }
    @Test public void timezoneChangeUsesEachHistoricalZone() throws Exception {
        history("2026-10-12",540,1260);long change=at("2026-10-12","15:00");config(change,true,540,1260,"UTC");
        JSONArray p=FocusPeriods.between(FocusPeriods.history(s),at("2026-10-12","14:00"),at("2026-10-12","20:00"));long focus=0;
        for(int i=0;i<p.length();i++)if(p.getJSONObject(i).optBoolean("focus"))focus+=p.getJSONObject(i).getLong("end")-p.getJSONObject(i).getLong("start");
        assertEquals(3*3600000L,focus); // Seoul 14–15; UTC09–11 == Seoul18–20
    }
    @Test public void usageCrossingFocusEndIsSplitAndManagedFlagIsIgnored() throws Exception {
        history("2026-10-12",540,1260);JSONArray use=new JSONArray().put(span(at("2026-10-12","20:50"),at("2026-10-12","21:10"),"video",0).put("managed",0));
        JSONObject b=BriefingAnalysis.analyze(s,at("2026-10-12","20:00"),at("2026-10-12","22:00"),use,new JSONArray(),FocusPeriods.data(s,at("2026-10-12","20:00"),at("2026-10-12","22:00")));
        assertEquals(1200000,b.getLong("total"));assertEquals(600000,b.getJSONObject("focus").getLong("observedMillis"));assertEquals(600000,b.getJSONObject("focus").getLong("freeObservedMillis"));assertEquals(2,b.getJSONArray("timeline").length());
        long left=at("2026-10-12","20:30"),mid=at("2026-10-12","21:00"),right=at("2026-10-12","21:30");
        JSONObject changed=BriefingAnalysis.analyze(s,left,right,new JSONArray().put(span(left,mid,"chat",0)).put(span(mid,right,"video",0)),new JSONArray(),FocusPeriods.data(s,left,right));
        assertTrue(changed.getJSONArray("highlights").toString().contains("focus-boundary-change"));
    }
    @Test public void explicitParentBeforeMidnightLinksButNewChoiceEndsOldIntent() throws Exception {
        history("2026-10-12",540,1260);long eventTime=at("2026-10-12","23:50"),newTime=at("2026-10-13","00:10");
        s.getWritableDatabase().execSQL("INSERT INTO events(id,time,kind,purpose,pkg,target,parent,aimless) VALUES(1,?,'unlock','문제 해결','','chat',0,0),(2,?,'app','영상','video','video',1,0)",new Object[]{eventTime,newTime});
        long a=at("2026-10-13","00:00"),b=at("2026-10-13","00:30");JSONArray use=new JSONArray().put(span(a,b,"browser",1));
        JSONObject result=BriefingAnalysis.analyze(s,a,b,use,s.rows("SELECT * FROM events WHERE time>=?",""+a),FocusPeriods.data(s,a,b));
        assertEquals(10*60000L,result.getLong("otherMillis"));assertEquals(2,result.getJSONArray("timeline").length());
        assertEquals("unconfirmed",result.getJSONArray("timeline").getJSONObject(1).getString("relation"));assertFalse(result.toString().contains("이탈"));
    }
    @Test public void rawV1RetainsOriginalRowsAndExcludesInternalFromAnalysis() throws Exception {
        LocalDate yesterday=LocalDate.now().minusDays(1);String day=yesterday.toString();history(day,540,1260);long a=at(day,"10:00");
        s.usage(a,a+600000,"test.external","외부 앱",false,0);s.usage(a+600000,a+900000,c.getPackageName(),"ChatGPT",false,0);
        s.usage(a+900000,a+1020000,"test.dictionary","팝업 사전",false,0);s.getWritableDatabase().execSQL("UPDATE usage SET surface='dictionary-tool' WHERE pkg='test.dictionary'");
        JSONObject raw=Briefing.rawDay(c,1);assertEquals(1,raw.getInt("schemaVersion"));assertEquals(3,raw.getJSONArray("usage").length());assertEquals(7,raw.getJSONArray("usage").getJSONObject(0).length());assertTrue(raw.getJSONArray("usage").getJSONObject(0).has("parent"));assertFalse(raw.getJSONArray("usage").getJSONObject(0).has("source"));assertTrue(raw.has("events"));assertTrue(raw.has("focus"));
        JSONObject b=Briefing.range(c,a,a+1020000);assertEquals(120000,b.getLong("toolMillis"));assertEquals(600000,b.getLong("total"));assertEquals(300000,b.getLong("internalMillis"));
    }
    @Test public void migrationPreservesV3RowsAndStartsHistoryOnlyNow() throws Exception {
        s.close();c.deleteDatabase("locky.db");Store.instance=null;
        SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(c.getDatabasePath("locky.db"),null);
        db.execSQL("CREATE TABLE usage(id INTEGER PRIMARY KEY,start INTEGER,end INTEGER,pkg TEXT,label TEXT,managed INTEGER,parent INTEGER)");db.execSQL("INSERT INTO usage VALUES(9,100,200,'old.app','기존',1,0)");
        db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY,body TEXT)");db.execSQL("INSERT INTO notes VALUES('memo','보존할 메모')");db.setVersion(3);db.close();
        s=Store.get(c);assertEquals(4,s.getReadableDatabase().getVersion());assertEquals("보존할 메모",s.rows("SELECT body FROM notes").getJSONObject(0).getString("body"));
        assertEquals(9,s.rows("SELECT * FROM usage").getJSONObject(0).getLong("id"));assertEquals("legacy",s.rows("SELECT * FROM usage").getJSONObject(0).getString("source"));
        assertEquals(1,FocusPeriods.history(s).length());assertTrue(FocusPeriods.history(s).getJSONObject(0).getLong("effective")>200);
    }
    @Test public void redundantSettingsDoNotAppendAndReopenRepairsPreferenceProjection() throws Exception {
        int before=FocusPeriods.history(s).length();s.saveFocus(true,540,1260,"user");assertEquals(before,FocusPeriods.history(s).length());
        s.saveFocus(false,600,1200,"user");assertEquals(before+1,FocusPeriods.history(s).length());
        s.settings.edit().putBoolean("enabled",true).putInt("start",1).commit();s.close();Store.instance=null;s=Store.get(c);s.getReadableDatabase();assertFalse(s.settings.getBoolean("enabled",true));assertEquals(600,s.settings.getInt("start",0));
    }
    @Test public void completedFocusSnapshotStaysFixedAfterLaterUseAndEdits() throws Exception {
        String day=LocalDate.now().minusDays(1).toString();history(day,540,1260);long a=at(day,"10:00");s.usage(a,a+600000,"test.external","외부",false,0);
        JSONArray windows=FocusPeriods.windows(s,at(day,"00:00"),at(day,"23:00"));String key=windows.getJSONObject(0).getString("key");JSONObject first=Briefing.focusResult(c,key);assertEquals(600000,first.getLong("total"));
        s.usage(a+600000,a+1200000,"test.external","외부",false,0);s.saveFocus(false,540,1260,"user");assertEquals(first.toString(),Briefing.focusResult(c,key).toString());
    }
    @Test public void bannerChannelAndImmutableTargetsAndDeniedDeliveryAreDistinct() throws Exception {
        NotificationManager manager=c.getSystemService(NotificationManager.class);assertEquals(NotificationManager.IMPORTANCE_HIGH,manager.getNotificationChannel(Notices.BRIEF_CHANNEL).getImportance());
        Shadows.shadowOf((Application)c).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        assertEquals("posted",Notices.postBriefing(c,"day:2026-10-12","하루","기록","brief|day|2026-10-12"));
        Intent route=Shadows.shadowOf(manager.getActiveNotifications()[0].getNotification().contentIntent).getSavedIntent();assertEquals("brief|day|2026-10-12",route.getStringExtra("screen"));
        Shadows.shadowOf((Application)c).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);assertEquals("blocked",Notices.postBriefing(c,"other","하루","기록","brief|day|2026-10-13"));assertEquals(1,manager.getActiveNotifications().length);
    }
    @Test public void schedulerPostsOnceAndRecordsBlockedWithoutHidingLocalRecords() throws Exception {
        s.settings.edit().putInt("dailyBriefTime",0).putBoolean("dailyBrief",true).putBoolean("focusBrief",false).putInt("weeklyDay",LocalDate.now().getDayOfWeek().getValue()%7+1).commit();
        Shadows.shadowOf((Application)c).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);Scheduler.fire(c);Scheduler.fire(c);
        NotificationManager manager=c.getSystemService(NotificationManager.class);assertEquals(1,manager.getActiveNotifications().length);
        String key="day:"+LocalDate.now().minusDays(1);assertEquals("posted",s.rows("SELECT state FROM briefing_delivery WHERE key=?",key).getJSONObject(0).getString("state"));
        manager.cancelAll();s.getWritableDatabase().delete("briefing_delivery","key=?",new String[]{key});Shadows.shadowOf((Application)c).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        Scheduler.fire(c);Scheduler.fire(c);assertEquals(0,manager.getActiveNotifications().length);assertEquals("blocked",s.rows("SELECT state FROM briefing_delivery WHERE key=?",key).getJSONObject(0).getString("state"));assertNotNull(Briefing.build(c,1,1).getJSONArray("events"));
    }
    @Test public void dailyRanksPreserveExistingRelativeOrderWhenAddingAndDeleting() {
        List<String> list=List.of("a","b","c","d"),before=Rules.shuffle(list,"2026-10-16","phone"),added=Rules.shuffle(List.of("a","b","c","d","new"),"2026-10-16","phone");added.remove("new");assertEquals(before,added);
        assertEquals(before,Rules.shuffle(list,"2026-10-16","phone"));assertNotEquals(before,Rules.shuffle(list,"2026-10-17","phone"));
    }
}
