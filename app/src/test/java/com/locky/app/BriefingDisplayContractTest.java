package com.locky.app;

import android.app.Application;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
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
public class BriefingDisplayContractTest {
    Context c;Store s;TimeZone old;ZoneId zone=ZoneId.of("Asia/Seoul");
    @Before public void setup(){old=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone(zone));c=RuntimeEnvironment.getApplication();Store.instance=null;s=Store.get(c);s.getWritableDatabase();s.getWritableDatabase().delete("events",null,null);s.getWritableDatabase().delete("focus_config_history",null,null);s.getWritableDatabase().execSQL("INSERT INTO focus_config_history(effective,enabled,start,end,zone,reason) VALUES(?,1,540,1260,'Asia/Seoul','test')",new Object[]{at("2026-11-12T00:00")});}
    @After public void cleanup(){s.close();Store.instance=null;GuardService.live=null;TimeZone.setDefault(old);}
    long at(String value){return LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli();}
    void event(int id,String time,String kind,String purpose,String pkg,String target,int parent){s.getWritableDatabase().execSQL("INSERT INTO events(id,time,kind,purpose,pkg,target,parent,aimless) VALUES(?,?,?,?,?,?,?,0)",new Object[]{id,at(time),kind,purpose,pkg,target,parent});}
    JSONObject span(String a,String b,String pkg,long parent) throws Exception{return new JSONObject().put("start",at(a)).put("end",at(b)).put("pkg",pkg).put("label",pkg.equals("browser")?"브라우저":pkg.equals("chat")?"ChatGPT":"동영상").put("parent",parent).put("source",parent==0?"usage-access":"accessibility").put("rule_mode","none");}
    JSONObject analyze(long start,long end,JSONArray raw,int days) throws Exception{
        JSONArray clipped=new JSONArray();for(int i=0;i<raw.length();i++){JSONObject t=raw.getJSONObject(i);long a=Math.max(start,t.getLong("start")),b=Math.min(end,t.getLong("end"));if(b>a)clipped.put(new JSONObject(t.toString()).put("start",a).put("end",b));}
        return BriefingAnalysis.analyze(s,start,end,clipped,s.rows("SELECT * FROM events WHERE time>=? AND time<? ORDER BY time,id",""+start,""+end),FocusPeriods.data(s,start,end)).put("timeZone",zone.getId()).put("days",days).put("date","2026-11-17").put("kind",days==7?"week":"day").put("complete",true).put("usageAccess",true);
    }
    @Test public void exportActualAnalysisForBrowserReview() throws Exception{
        event(1,"2026-11-17T09:00","unlock","자료 확인","","",0);
        event(2,"2026-11-17T09:02","app","자료 확인","chat","chat",1);
        event(3,"2026-11-17T09:10","unlock","메모 확인","","",0);
        event(4,"2026-11-17T09:20","app","장보기","browser","browser",3);
        JSONArray raw=new JSONArray().put(span("2026-11-17T09:00:10","2026-11-17T09:00:30","browser",1)).put(span("2026-11-17T09:02:10","2026-11-17T09:07:10","chat",2)).put(span("2026-11-17T09:11","2026-11-17T09:12","chat",2)).put(span("2026-11-17T09:15","2026-11-17T09:16","video",0)).put(span("2026-11-17T23:59","2026-11-18T00:01","browser",1));
        JSONObject daily=analyze(at("2026-11-17T00:00"),at("2026-11-18T00:00"),raw,1),weekly=analyze(at("2026-11-12T00:00"),at("2026-11-19T00:00"),raw,7);
        assertEquals(500000L,daily.getLong("total"));assertEquals(560000L,weekly.getLong("total"));
        boolean unknown=false;JSONArray timeline=daily.getJSONArray("timeline");for(int i=0;i<timeline.length();i++){JSONObject t=timeline.getJSONObject(i);if(t.getLong("parent")==2&&t.getLong("start")==at("2026-11-17T09:11")){assertEquals(0,t.getLong("eventId"));assertEquals("unconfirmed",t.getString("relation"));unknown=true;}}assertTrue(unknown);
        assertEquals(4,daily.getJSONArray("purposeContexts").length());
        File dir=new File("build/briefing-review");assertTrue(dir.isDirectory()||dir.mkdirs());try(Writer w=new OutputStreamWriter(new FileOutputStream(new File(dir,"native.json")),StandardCharsets.UTF_8)){w.write(new JSONObject().put("daily",daily).put("weekly",weekly).put("shortUse",analyze(at("2026-11-17T09:00"),at("2026-11-17T09:01"),raw,1)).toString());}
    }
    @Test public void adjacentUnknownIntentSpansKeepTheirDifferentRecordedParents() throws Exception{
        event(1,"2026-11-17T08:00","unlock","처음 목적","","",0);
        event(2,"2026-11-17T08:01","app","첫 앱 목적","browser","browser",1);
        event(3,"2026-11-17T08:02","app","둘째 앱 목적","browser","browser",1);
        event(4,"2026-11-17T08:03","unlock","새 목적","","",0);
        JSONObject b=analyze(at("2026-11-17T09:00"),at("2026-11-17T10:00"),new JSONArray().put(span("2026-11-17T09:00","2026-11-17T09:05","browser",2)).put(span("2026-11-17T09:05","2026-11-17T09:10","browser",3)),1);
        assertEquals(600000L,b.getLong("total"));assertEquals(2,b.getJSONArray("timeline").length());assertEquals(2,b.getJSONArray("intervals").length());assertEquals(2,b.getJSONArray("timeline").getJSONObject(0).getLong("parent"));assertEquals(3,b.getJSONArray("timeline").getJSONObject(1).getLong("parent"));
    }
}
