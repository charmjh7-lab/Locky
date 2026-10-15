package com.locky.app;

import java.time.*;
import org.json.*;

/** Historical schedule only: never claims attention, service uptime or task completion. */
final class FocusPeriods {
    static JSONArray between(JSONArray history,long from,long to) throws JSONException {
        JSONArray out=new JSONArray();long cursor=from;
        for(int i=0;i<history.length();i++){
            JSONObject h=history.getJSONObject(i);long effective=h.getLong("effective");
            long until=i+1<history.length()?history.getJSONObject(i+1).getLong("effective"):Long.MAX_VALUE;
            long a=Math.max(from,effective),b=Math.min(to,until);if(b<=a)continue;
            if(cursor<a)out.put(new JSONObject().put("start",cursor).put("end",a).put("known",false));
            ZoneId zone=ZoneId.of(h.getString("zone"));boolean enabled=h.getInt("enabled")==1;int start=h.getInt("start"),end=h.getInt("end");
            while(a<b){boolean focus=enabled&&Rules.managed(Rules.minute(a,zone),start,end);
                long boundary=enabled&&start!=end?Rules.nextBoundary(a,start,end,zone):b;long next=Math.min(b,boundary);
                out.put(new JSONObject().put("start",a).put("end",next).put("known",true).put("focus",focus).put("enabled",enabled).put("configId",h.getLong("id")).put("zone",zone.getId()));a=next;
            }cursor=b;
        }
        if(cursor<to)out.put(new JSONObject().put("start",cursor).put("end",to).put("known",false));return out;
    }
    static JSONArray history(Store s) throws JSONException {return s.rows("SELECT * FROM focus_config_history ORDER BY effective,id");}
    static JSONObject data(Store s,long start,long end) throws JSONException {
        JSONArray h=history(s),periods=between(h,start,end),changes=new JSONArray();long focus=0,known=0;
        for(int i=0;i<h.length();i++){JSONObject v=h.getJSONObject(i);if(v.getLong("effective")>=start&&v.getLong("effective")<end)changes.put(v);}
        for(int i=0;i<periods.length();i++){JSONObject p=periods.getJSONObject(i);long ms=p.getLong("end")-p.getLong("start");if(p.optBoolean("known"))known+=ms;if(p.optBoolean("focus"))focus+=ms;}
        return new JSONObject().put("periods",periods).put("changes",changes).put("reliableSince",h.length()==0?JSONObject.NULL:h.getJSONObject(0).getLong("effective"))
            .put("observations",s.rows("SELECT time,kind,interactive FROM service_observations WHERE time>=? AND time<? ORDER BY time,id",""+start,""+end)).put("configuredMillis",focus).put("knownMillis",known).put("unknownMillis",end-start-known);
    }
    /** Include the true beginning of overnight windows; edits terminate the old window. */
    static JSONArray windows(Store s,long from,long now) throws JSONException {
        JSONArray h=history(s),spans=between(h,Math.max(0,from-2*86400000L),now+2*86400000L),out=new JSONArray();
        for(int i=0;i<spans.length();i++){JSONObject p=spans.getJSONObject(i);if(!p.optBoolean("focus"))continue;
            long a=p.getLong("start"),b=p.getLong("end");if(b<=from||a>now)continue;
            // The expansion is longer than a recurring window, except the legacy 24h policy.
            p.put("key","focus:"+p.getLong("configId")+":"+a+":"+b).put("complete",b<=now);out.put(p);
        }return out;
    }
}
