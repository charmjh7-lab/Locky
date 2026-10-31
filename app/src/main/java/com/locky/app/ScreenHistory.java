package com.locky.app;

import android.app.usage.*;
import android.content.Context;
import org.json.*;
import java.util.*;

/** Screen evidence shared by live checkpoints, OS recovery and report reservations.
 * Unknown is distinct from inactive: original rows remain available in raw exports. */
final class ScreenHistory {
    static final int UNKNOWN=-1,OFF=0,ON=1;
    static final class Mark {
        final long time;final int type,value;
        Mark(long time,int type,int value){this.time=time;this.type=type;this.value=value;}
    }
    static final class Span {
        final long start,end;final int state;
        Span(long start,long end,int state){this.start=start;this.end=end;this.state=state;}
    }
    static final class Evidence {
        final List<Span> spans=new ArrayList<>();boolean hasInactive,hasStop;
        List<long[]> active(){List<long[]> out=new ArrayList<>();for(Span s:spans)if(s.state==ON)out.add(new long[]{s.start,s.end});return out;}
    }
    private static long cachedStart=Long.MAX_VALUE,cachedEnd=-1;
    private static final List<Mark> cached=new ArrayList<>();
    private static List<Mark> systemMarks(Context c,long start,long end){
        List<Mark> fresh=new ArrayList<>(),result=new ArrayList<>();
        synchronized(cached){
        long queryFrom=start;
        boolean reuse=start>=cachedStart&&end>=cachedEnd&&end-cachedEnd<60000;
        if(reuse)queryFrom=Math.max(cachedStart,cachedEnd-2000);
        else {cached.clear();cachedStart=start;}
        final long replacing=queryFrom;cached.removeIf(m->m.time>=replacing);
        if(UsageReader.allowed(c))try{
            UsageStatsManager manager=c.getSystemService(UsageStatsManager.class);
            UsageEvents events=manager.queryEvents(queryFrom,end+1);
            if(events!=null){UsageEvents.Event event=new UsageEvents.Event();while(events.hasNextEvent()){
                events.getNextEvent(event);int type=event.getEventType();long at=event.getTimeStamp();
                if(type==UsageEvents.Event.SCREEN_NON_INTERACTIVE||type==UsageEvents.Event.DEVICE_SHUTDOWN)fresh.add(new Mark(at,1,OFF));
                else if(type==UsageEvents.Event.SCREEN_INTERACTIVE)fresh.add(new Mark(at,1,ON));
                else if(type==UsageEvents.Event.KEYGUARD_SHOWN)fresh.add(new Mark(at,2,ON));
                else if(type==UsageEvents.Event.KEYGUARD_HIDDEN)fresh.add(new Mark(at,2,OFF));
                else if(type==UsageEvents.Event.DEVICE_STARTUP)fresh.add(new Mark(at,0,UNKNOWN));
            }}
        }catch(RuntimeException ignored){/* Permission revocation/locked credential storage: unknown stays unknown. */}

        cached.addAll(fresh);cachedEnd=end;
        for(Mark m:cached)if(m.time>=start&&m.time<=end)result.add(m);
        }
        // Never acquire Store's monitor while holding the cache lock: checkpoints
        // and report work can enter these readers from different threads.
        if(!fresh.isEmpty())Store.get(c).screenMarks(fresh);
        return result;
    }
    static Evidence read(Context c,long start,long end,int initial,boolean history){
        List<Mark> marks=new ArrayList<>();Store store=Store.get(c);
        try{
            JSONArray local=store.rows("SELECT time,state,type FROM screen_states WHERE time>=? AND time<=? ORDER BY time,id",""+start,""+end);
            if(history)for(int type=0;type<3;type++){JSONArray seed=store.rows("SELECT time,state,type FROM screen_states WHERE time<? AND type=? ORDER BY time DESC,id DESC LIMIT 1",""+start,""+type);if(seed.length()>0)local.put(seed.getJSONObject(0));}
            for(int n=0;n<local.length();n++){JSONObject row=local.getJSONObject(n);marks.add(new Mark(row.getLong("time"),row.getInt("type"),row.getInt("state")));}
        }catch(JSONException e){throw new IllegalStateException(e);}
        marks.addAll(systemMarks(c,history?Math.max(0,start-86400000L):start,end));
        // For this boot, never carry a pre-reboot active observation into the new process.
        long boot=System.currentTimeMillis()-android.os.SystemClock.elapsedRealtime();if(boot>start&&boot<end)marks.add(new Mark(boot,0,UNKNOWN));
        return build(start,end,initial,marks);
    }
    static Evidence build(long start,long end,int initial,List<Mark> marks){
        marks.sort(Comparator.comparingLong((Mark m)->m.time).thenComparingInt(m->m.type));
        Evidence out=new Evidence();int screen=initial,lock=initial==ON?OFF:UNKNOWN;long cursor=start;
        for(Mark mark:marks){if(mark.time>end)break;if(mark.time>=start&&(((mark.type==0||mark.type==1)&&mark.value==OFF)||(mark.type==2&&mark.value==ON))){out.hasStop=true;if(mark.time<end)out.hasInactive=true;}int state=screen==OFF||lock==ON?OFF:screen==ON?ON:UNKNOWN;
            long at=Math.max(start,mark.time);if(at>cursor){out.spans.add(new Span(cursor,at,state));if(state==OFF)out.hasInactive=true;cursor=at;}
            if(mark.type==0){screen=mark.value;lock=mark.value==ON?OFF:UNKNOWN;}
            else if(mark.type==1)screen=mark.value;
            else {lock=mark.value;if(mark.value==OFF)screen=ON;}
        }
        int state=screen==OFF||lock==ON?OFF:screen==ON?ON:UNKNOWN;
        if(cursor<end){out.spans.add(new Span(cursor,end,state));if(state==OFF)out.hasInactive=true;}return out;
    }
    static JSONArray filter(Context c,JSONArray rows,long start,long end) throws JSONException {
        Evidence evidence=read(c,start,end,UNKNOWN,true);JSONArray out=new JSONArray();
        for(int n=0;n<rows.length();n++){
            JSONObject row=rows.getJSONObject(n);boolean first=c.getPackageName().equals(row.optString("pkg"))&&"첫 화면".equals(row.optString("label"));
            for(Span span:evidence.spans){long a=Math.max(row.getLong("start"),span.start),b=Math.min(row.getLong("end"),span.end);if(b<=a||span.state==OFF||(first&&span.state==UNKNOWN))continue;
                out.put(new JSONObject(row.toString()).put("start",a).put("end",b).put("screenEvidence",span.state==ON?"active":"unknown"));
            }
        }return out;
    }
}
