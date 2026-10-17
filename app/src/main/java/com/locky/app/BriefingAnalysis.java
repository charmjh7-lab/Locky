package com.locky.app;

import java.time.*;
import java.util.*;
import org.json.*;

/** One fact-based analysis contract for screens, exports and notifications. No model calls. */
final class BriefingAnalysis {
    static void addComparison(android.content.Context c,JSONObject result,int days) throws Exception {
        addComparison(c,result,days,new Briefing.Request(c,System.currentTimeMillis(),ZoneId.systemDefault()));
    }
    static void addComparison(android.content.Context c,JSONObject result,int days,Briefing.Request request) throws Exception {
        if(!result.getBoolean("complete"))return;long start=result.getLong("start"),end=result.getLong("end");ZoneId z=ZoneId.systemDefault();
        LocalDate first=Instant.ofEpochMilli(start).atZone(z).toLocalDate();long prior=first.minusDays(days).atStartOfDay(z).toInstant().toEpochMilli();
        // Fix all seven local-date ranges before fetching any comparisons (including DST days).
        List<LocalDate> dates=new ArrayList<>();List<long[]> ranges=new ArrayList<>();
        if(days==1)for(int d=1;d<=7;d++){LocalDate date=first.minusDays(d);dates.add(date);ranges.add(new long[]{date.atStartOfDay(z).toInstant().toEpochMilli(),date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()});}
        JSONObject previous=request.range(prior,start);result.put("previous",new JSONObject().put("available",previous.getJSONArray("timeline").length()>0).put("total",previous.getLong("total")).put("start",prior).put("end",start));
        if(days!=1)return;
        JSONArray dayTotals=new JSONArray();Map<String,Long> sums=new HashMap<>();int comparable=0;long recentTotal=0,recentEvening=0;
        for(int d=0;d<ranges.size();d++){
            LocalDate date=dates.get(d);long[] range=ranges.get(d);
            JSONObject p=request.range(range[0],range[1]);
            // Full schedule history and positive observation on at least five days; no zero-filled missing days.
            if(!p.getJSONObject("quality").optBoolean("comparisonEligible"))continue;
            comparable++;recentTotal+=p.getLong("total");recentEvening+=p.optLong("eveningMillis");dayTotals.put(new JSONObject().put("date",date.toString()).put("total",p.getLong("total")));
            JSONArray apps=p.getJSONArray("apps");for(int i=0;i<apps.length();i++){JSONObject app=apps.getJSONObject(i);sums.merge(app.getString("pkg"),app.getLong("ms"),Long::sum);}
        }
        result.put("comparison",new JSONObject().put("days",comparable).put("records",dayTotals).put("basis","관측 기록이 있는 날끼리 비교 · 서비스 연속 작동을 보장하지 않음"));
        if(comparable<5||!result.getJSONObject("quality").optBoolean("comparisonEligible"))return;
        JSONArray highlights=result.getJSONArray("highlights"),apps=result.getJSONArray("apps");
        for(int i=0;i<apps.length();i++){JSONObject a=apps.getJSONObject(i);long average=sums.getOrDefault(a.getString("pkg"),0L)/comparable,diff=a.getLong("ms")-average;
            if(Math.abs(diff)>=30*60000L&&Math.abs(diff)>=Math.max(average/2,15*60000L)){
                highlights.put(new JSONObject().put("type","change").put("priority",4).put("start",start).put("end",end).put("ms",a.getLong("ms")).put("pkg",a.getString("pkg")).put("evidence","comparable-days:"+comparable)
                    .put("text",a.getString("label")+" 사용이 최근 기록 "+comparable+"일 평균보다 "+Briefing.minutes(Math.abs(diff))+"분 "+(diff>0?"늘었어요.":"줄었어요.")));break;
            }
        }
        if(recentTotal>0&&result.getLong("total")>0){double oldShare=(double)recentEvening/recentTotal,newShare=(double)result.optLong("eveningMillis")/result.getLong("total");
            if(Math.abs(newShare-oldShare)>=.20&&result.optLong("eveningMillis")>=30*60000L)highlights.put(new JSONObject().put("type","time-change").put("priority",4).put("start",start).put("end",end).put("ms",result.optLong("eveningMillis")).put("evidence","comparable-days:"+comparable).put("text","18시 이후 사용 비중이 최근 기록 "+comparable+"일보다 "+Math.round(Math.abs(newShare-oldShare)*100)+"%p "+(newShare>oldShare?"높았어요.":"낮았어요.")));}
        while(highlights.length()>3)highlights.remove(highlights.length()-1);
    }
    private static long duration(JSONObject o){return o.optLong("end")-o.optLong("start");}
    private static JSONObject parent(Store s,Map<Long,JSONObject> cache,long id) throws JSONException {
        if(id==0)return null;if(cache.containsKey(id))return cache.get(id);JSONArray rows=s.rows("SELECT * FROM events WHERE id=?",""+id);JSONObject e=rows.length()==0?null:rows.getJSONObject(0);cache.put(id,e);return e;
    }
    static JSONObject analyze(Store s,long start,long end,JSONArray usage,JSONArray events,JSONObject focus) throws JSONException {
        JSONArray periods=focus.getJSONArray("periods"),timeline=new JSONArray(),highlights=new JSONArray(),flows=new JSONArray(),unobserved=new JSONArray(),purposes=new JSONArray(),hours=new JSONArray();
        Map<Long,JSONObject> contexts=new HashMap<>();Map<String,JSONObject> apps=new LinkedHashMap<>(),purposeRows=new LinkedHashMap<>();Map<Long,JSONObject> flowRows=new LinkedHashMap<>();
        int unlocks=0,appStarts=0,aimless=0,cancels=0,extensions=0;long total=0,focusMs=0,freeMs=0,unknownMs=0,directMs=0,otherMs=0,eveningMs=0,managedMs=0,unmanagedMs=0,unknownRuleMs=0;
        int focusChoices=0,focusCancels=0;List<JSONObject> selections=new ArrayList<>(),sorted=new ArrayList<>();
        for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);contexts.put(e.getLong("id"),e);String kind=e.getString("kind");
            if(kind.equals("unlock")||kind.equals("app")){
                selections.add(e);if(kind.equals("unlock"))unlocks++;else appStarts++;if(e.optInt("aimless")==1)aimless++;
                String name=e.getString("purpose");JSONObject row=purposeRows.get(name);if(row==null){row=new JSONObject().put("purpose",name).put("count",0).put("directMillis",0).put("otherMillis",0);purposeRows.put(name,row);}row.put("count",row.getInt("count")+1);
                if(atFocus(periods,e.getLong("time")))focusChoices++;
            }
            if(kind.equals("dismiss")||kind.equals("cancel")){cancels++;if(atFocus(periods,e.getLong("time")))focusCancels++;}
            if(kind.equals("extend"))extensions++;
        }
        JSONArray preceding=s.rows("SELECT * FROM events WHERE kind IN ('unlock','app') AND time<? ORDER BY time DESC,id DESC LIMIT 1",""+start);
        if(preceding.length()>0)selections.add(0,preceding.getJSONObject(0));
        for(int i=0;i<usage.length();i++)sorted.add(usage.getJSONObject(i));sorted.sort(Comparator.comparingLong(o->o.optLong("start")));
        for(JSONObject u:sorted){
            long a=u.getLong("start"),b=u.getLong("end");JSONObject context=parent(s,contexts,u.optLong("parent")),initial=context;Set<Long> seen=new HashSet<>();
            while(initial!=null&&initial.optLong("parent")>0&&seen.add(initial.optLong("id"))){JSONObject ancestor=parent(s,contexts,initial.optLong("parent"));if(ancestor==null)break;initial=ancestor;}
            List<Long> cuts=new ArrayList<>(List.of(a,b));for(JSONObject e:selections)if(e.getLong("time")>a&&e.getLong("time")<b)cuts.add(e.getLong("time"));
            for(int i=0;i<periods.length();i++){JSONObject p=periods.getJSONObject(i);for(String k:new String[]{"start","end"}){long t=p.getLong(k);if(t>a&&t<b)cuts.add(t);}}
            cuts=new ArrayList<>(new TreeSet<>(cuts));
            for(int i=0;i+1<cuts.size();i++){
                long from=cuts.get(i),to=cuts.get(i+1),ms=to-from;JSONObject active=context;
                // New explicit choices terminate the prior intent. Adjacency alone never reassigns it.
                if(active!=null)for(JSONObject e:selections)if((e.getLong("time")>active.getLong("time")||(e.getLong("time")==active.getLong("time")&&e.getLong("id")>active.getLong("id")))&&e.getLong("time")<=from){active=null;break;}
                String pkg=u.getString("pkg"),target=active==null?"":active.optString("target");if(target.isEmpty()&&active!=null&&active.optString("kind").equals("app"))target=active.optString("pkg");
                boolean linked=active!=null&&!target.isEmpty(),direct=linked&&target.equals(pkg);String category=category(periods,from);
                JSONObject t=new JSONObject().put("start",from).put("end",to).put("pkg",pkg).put("label",u.getString("label")).put("source",u.optString("source","legacy"))
                    .put("technicalResume",u.optBoolean("technicalResume")&&from==a).put("parent",u.optLong("parent")).put("eventId",active==null?0:active.getLong("id")).put("purpose",active==null?"목적 미확인":active.optString("purpose"))
                    .put("initialPurpose",initial==null?"":initial.optString("purpose")).put("relation",direct?"direct":linked?"other-app":"unconfirmed").put("focusState",category).put("ruleMode",u.optString("rule_mode","unknown"));
                // Only exactly adjacent same-app spans with the same explicit context can merge.
                JSONObject previous=timeline.length()==0?null:timeline.getJSONObject(timeline.length()-1);
                if(previous!=null&&previous.getLong("end")==from&&same(previous,t,"pkg","source","eventId","focusState","ruleMode","relation"))previous.put("end",to);else timeline.put(t);
                total+=ms;if(category.equals("focus")){focusMs+=ms;String mode=u.optString("rule_mode","unknown");if(mode.equals("unknown"))unknownRuleMs+=ms;else if(mode.equals("none"))unmanagedMs+=ms;else managedMs+=ms;}else if(category.equals("free"))freeMs+=ms;else unknownMs+=ms;
                if(direct)directMs+=ms;else if(linked)otherMs+=ms;
                JSONObject app=apps.get(pkg);if(app==null){app=new JSONObject().put("pkg",pkg).put("label",u.getString("label")).put("ms",0).put("focus",0).put("free",0).put("unknown",0);apps.put(pkg,app);}app.put("ms",app.getLong("ms")+ms).put(category,app.getLong(category)+ms);
                if(active!=null){JSONObject row=purposeRows.get(active.optString("purpose"));if(row==null){row=new JSONObject().put("purpose",active.optString("purpose")).put("count",0).put("priorSelection",true).put("directMillis",0).put("otherMillis",0);purposeRows.put(active.optString("purpose"),row);}if(row!=null){String field=direct?"directMillis":linked?"otherMillis":"";if(!field.isEmpty())row.put(field,row.getLong(field)+ms);}
                    long id=active.getLong("id");JSONObject flow=flowRows.get(id);if(flow==null){flow=new JSONObject().put("event",active).put("start",active.getLong("time")).put("end",to).put("directMillis",0).put("otherMillis",0).put("steps",new JSONArray());flowRows.put(id,flow);}flow.put("end",to);if(linked){String key=direct?"directMillis":"otherMillis";flow.put(key,flow.getLong(key)+ms);}flow.getJSONArray("steps").put(t);
                }
            }
        }
        List<JSONObject> ranked=new ArrayList<>(apps.values());ranked.sort((a,b)->Long.compare(b.optLong("ms"),a.optLong("ms")));JSONArray appRows=new JSONArray();for(JSONObject app:ranked)appRows.put(app);
        int repeated=0;for(JSONObject p:purposeRows.values()){purposes.put(p);repeated+=Math.max(0,p.getInt("count")-1);}
        long cursor=start;for(JSONObject u:sorted){long a=u.getLong("start"),b=u.getLong("end");if(a>cursor)unobserved.put(new JSONObject().put("start",cursor).put("end",a).put("reason","외부 앱 사용 관측 없음 · 화면 꺼짐/내부 화면/누락 여부는 이 자료만으로 확정 불가"));cursor=Math.max(cursor,b);}if(cursor<end)unobserved.put(new JSONObject().put("start",cursor).put("end",end).put("reason","외부 앱 사용 관측 없음"));
        // Actual local-hour boundaries, including DST overlap/gaps and non-24-hour dates.
        ZoneId zone=ZoneId.systemDefault();long hour=start;while(hour<end){ZonedDateTime local=Instant.ofEpochMilli(hour).atZone(zone);long next=Math.min(end,local.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli());long ms=0;for(int i=0;i<timeline.length();i++){JSONObject t=timeline.getJSONObject(i);ms+=Math.max(0,Math.min(next,t.getLong("end"))-Math.max(hour,t.getLong("start")));}hours.put(new JSONObject().put("start",hour).put("end",next).put("ms",ms));if(local.getHour()>=18)eveningMs+=ms;hour=next;}
        if(focusMs>=30*60000L){JSONObject top=null;for(JSONObject app:ranked)if(top==null||app.optLong("focus")>top.optLong("focus"))top=app;
            if(top!=null&&top.getLong("focus")>=15*60000L&&top.getLong("focus")*2>=focusMs){long a=end,b=start;for(int i=0;i<timeline.length();i++){JSONObject t=timeline.getJSONObject(i);if(t.getString("pkg").equals(top.getString("pkg"))&&t.getString("focusState").equals("focus")){a=Math.min(a,t.getLong("start"));b=Math.max(b,t.getLong("end"));}}
                highlights.put(new JSONObject().put("type","focus-app").put("priority",1).put("start",a).put("end",b).put("ms",top.getLong("focus")).put("pkg",top.getString("pkg")).put("evidence","focus-period-intersection").put("text","집중시간 중 "+top.getString("label")+" 사용이 "+Briefing.minutes(top.getLong("focus"))+"분 기록됐어요."));}
        }
        for(int n=1;n<periods.length();n++){
            JSONObject beforePeriod=periods.getJSONObject(n-1),afterPeriod=periods.getJSONObject(n);long boundary=afterPeriod.getLong("start"),window=30*60000L;
            if(!beforePeriod.optBoolean("focus")||!afterPeriod.optBoolean("known")||afterPeriod.optBoolean("focus")||boundary-window<start||boundary+window>end)continue;
            Map<String,Long> before=new HashMap<>(),after=new HashMap<>();long beforeTotal=0,afterTotal=0;
            for(int j=0;j<timeline.length();j++){JSONObject t=timeline.getJSONObject(j);String pkg=t.getString("pkg");long a=t.getLong("start"),b=t.getLong("end"),left=Math.max(0,Math.min(boundary,b)-Math.max(boundary-window,a)),right=Math.max(0,Math.min(boundary+window,b)-Math.max(boundary,a));before.merge(pkg,left,Long::sum);after.merge(pkg,right,Long::sum);beforeTotal+=left;afterTotal+=right;}
            if(beforeTotal<10*60000L||afterTotal<10*60000L)continue;
            String oldTop=before.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey(),newTop=after.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();long afterMs=after.get(newTop);
            if(afterMs<15*60000L||oldTop.equals(newTop)||(double)afterMs/afterTotal-(double)before.getOrDefault(newTop,0L)/beforeTotal<.4)continue;
            highlights.put(new JSONObject().put("type","focus-boundary-change").put("priority",1).put("start",boundary-window).put("end",boundary+window).put("ms",beforeTotal+afterTotal).put("pkg",newTop)
                .put("evidence",new JSONObject().put("focusEnd",boundary).put("beforePkg",oldTop).put("beforeMillis",before.get(oldTop)).put("afterPkg",newTop).put("afterMillis",afterMs))
                .put("text",clock(boundary)+" 집중시간 종료 전 30분에는 "+apps.get(oldTop).getString("label")+" "+Briefing.minutes(before.get(oldTop))+"분, 이후 30분에는 "+apps.get(newTop).getString("label")+" "+Briefing.minutes(afterMs)+"분이 기록됐어요."));break;
        }
        for(JSONObject flow:flowRows.values()){
            JSONArray steps=flow.getJSONArray("steps"),gaps=new JSONArray(),choices=new JSONArray();long last=flow.getLong("start");for(int i=0;i<steps.length();i++){JSONObject t=steps.getJSONObject(i);if(t.getLong("start")>last)gaps.put(new JSONObject().put("start",last).put("end",t.getLong("start")));last=t.getLong("end");}
            for(JSONObject e:selections)if(e.getLong("time")>flow.getLong("start")&&e.getLong("time")<flow.getLong("end"))choices.put(e);
            flow.put("blocks",blocks(steps)).put("gaps",gaps).put("newChoices",choices);flows.put(flow);
            long other=flow.getLong("otherMillis"),direct=flow.getLong("directMillis");if(other>=15*60000L&&other*3>=other+direct){JSONObject e=flow.getJSONObject("event");highlights.put(new JSONObject().put("type","other-app").put("priority",2).put("start",flow.getLong("start")).put("end",flow.getLong("end")).put("ms",other).put("eventId",e.getLong("id")).put("target",e.optString("target")).put("evidence","usage.parent=events.id").put("text",clock(e.getLong("time"))+"에 ‘"+e.getString("purpose")+"’ 선택 후, 같은 선택에 연결된 다른 앱 사용이 "+Briefing.minutes(other)+"분 기록됐어요."));}
        }
        for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);if(!Set.of("dismiss","cancel").contains(e.getString("kind")))continue;long at=e.getLong("time"),next=Long.MAX_VALUE;for(JSONObject choice:selections)if(choice.getLong("time")>at)next=Math.min(next,choice.getLong("time"));long observed=0;for(JSONObject u:sorted)observed+=Math.max(0,Math.min(Math.min(end,at+5*60000L),u.getLong("end"))-Math.max(at,u.getLong("start")));
            if(next-at<=5*60000L||observed==0){String text=next-at<=5*60000L?clock(at)+"에 열지 않기를 선택한 뒤 "+Math.max(1,(next-at)/1000)+"초 후 다시 시작했어요.":clock(at)+"에 열지 않기를 선택한 뒤 "+Math.min(5,Math.max(0,(end-at)/60000))+"분 동안 외부 앱 사용 기록이 없어요.";
                if(next==Long.MAX_VALUE&&end-at<60000L)continue;
                highlights.put(new JSONObject().put("type","cancel").put("priority",3).put("start",at).put("end",Math.min(end,next==Long.MAX_VALUE?at+5*60000L:next)).put("ms",observed).put("eventId",e.getLong("id")).put("restartAfterMillis",next==Long.MAX_VALUE?JSONObject.NULL:next-at).put("evidence","cancel-event;observed-usage;explicit-next-choice").put("text",text));}
        }
        List<JSONObject> selected=new ArrayList<>();for(int i=0;i<highlights.length();i++)selected.add(highlights.getJSONObject(i));selected.sort(Comparator.comparingInt(o->o.optInt("priority")));highlights=new JSONArray();for(int i=0;i<Math.min(3,selected.size());i++)highlights.put(selected.get(i));
        JSONArray observations=focus.optJSONArray("observations");boolean comparable=focus.getLong("unknownMillis")==0&&total>=30*60000L&&observations!=null&&observations.length()>=3&&observations.getJSONObject(observations.length()-1).getLong("time")-observations.getJSONObject(0).getLong("time")>=6*3600000L;
        focus.put("observedMillis",focusMs).put("freeObservedMillis",freeMs).put("unknownObservedMillis",unknownMs).put("managedAppMillis",managedMs).put("unmanagedAppMillis",unmanagedMs).put("unknownAppPolicyMillis",unknownRuleMs).put("choices",focusChoices).put("cancels",focusCancels);
        String summary="외부 앱 사용 "+Briefing.minutes(total)+"분 · 목적 선택 "+(unlocks+appStarts)+"회 · 사용 취소 "+cancels+"회";
        String insight=focus.getLong("knownMillis")==0?"집중시간 기록 시작 이전입니다.":"집중시간 중 외부 앱 사용이 "+Briefing.minutes(focusMs)+"분 기록됐어요.";
        return new JSONObject().put("analysisVersion",2).put("start",start).put("end",end).put("total",total).put("unlocks",unlocks).put("appStarts",appStarts).put("aimless",aimless).put("cancels",cancels).put("extensions",extensions).put("newPurposeChoices",unlocks+appStarts).put("repeatedPurposeChoices",repeated).put("directMillis",directMs).put("otherMillis",otherMs)
            .put("summary",summary).put("insight",insight).put("apps",appRows).put("events",events).put("timeline",timeline).put("intervals",blocks(timeline)).put("flows",flows).put("purposes",purposes).put("hours",hours).put("eveningMillis",eveningMs).put("focus",focus).put("highlights",highlights)
            .put("quality",new JSONObject().put("comparisonEligible",comparable).put("unobserved",unobserved).put("incomplete",true).put("continuousServiceCoverageKnown",false).put("note","사용 기록이 없는 구간을 집중한 시간으로 계산하지 않으며, 접근성 서비스의 연속 작동 여부는 확인되지 않습니다."));
    }
    /** Presentation groups preserve observed milliseconds and explicitly expose tiny UI gaps. */
    private static JSONArray blocks(JSONArray spans) throws JSONException {
        JSONArray out=new JSONArray();for(int i=0;i<spans.length();i++){
            JSONObject t=spans.getJSONObject(i),last=out.length()==0?null:out.getJSONObject(out.length()-1);long ms=duration(t);
            if(last!=null&&t.optBoolean("technicalResume")&&t.getLong("start")>last.getLong("end")&&t.getLong("start")-last.getLong("end")<=1500&&same(last,t,"pkg","eventId","focusState","source","ruleMode","relation")){
                last.put("excludedTransitionMillis",last.optLong("excludedTransitionMillis")+t.getLong("start")-last.getLong("end")).put("end",t.getLong("end")).put("ms",last.getLong("ms")+ms);
            }else out.put(new JSONObject(t.toString()).put("ms",ms).put("excludedTransitionMillis",0));
        }return out;
    }
    private static boolean same(JSONObject a,JSONObject b,String... keys){for(String key:keys)if(!Objects.equals(a.opt(key),b.opt(key)))return false;return true;}
    private static String category(JSONArray periods,long at) throws JSONException {for(int i=0;i<periods.length();i++){JSONObject p=periods.getJSONObject(i);if(p.getLong("start")<=at&&at<p.getLong("end"))return !p.optBoolean("known")?"unknown":p.optBoolean("focus")?"focus":"free";}return "unknown";}
    private static boolean atFocus(JSONArray periods,long at) throws JSONException {return category(periods,at).equals("focus");}
    private static String clock(long at){return Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString();}
}
