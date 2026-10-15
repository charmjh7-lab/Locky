package com.locky.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Only controlled window/render metadata enters here. No node text, drafts or package names. */
final class UnlockDiagnostics {
    static final int MAX_CYCLES=5,MAX_BYTES=190_000,MAX_EVENTS=260;
    static volatile UnlockDiagnostics live;
    static synchronized UnlockDiagnostics get(Context c){
        Context app=c.getApplicationContext();
        if(live==null||live.context!=app)live=new UnlockDiagnostics(app);
        return live;
    }
    final Context context;final SharedPreferences prefs;final AtomicFile file;
    private final ScheduledExecutorService disk=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"LockyUnlockStore");t.setDaemon(true);return t;});
    private final List<JSONObject> cycles=new ArrayList<>();
    private final Map<String,JSONObject> observations=new HashMap<>();
    private JSONObject current;private long origin,until;private int bytes;
    private boolean enabled;private ScheduledFuture<?> pending;private String storageError="";
    UnlockDiagnostics(Context c){
        context=c;prefs=c.getSharedPreferences("locky-settings",0);enabled=prefs.getBoolean("unlock-diagnostics",false);
        file=new AtomicFile(new File(c.getFilesDir(),"unlock-diagnostics.json"));
        if(file.getBaseFile().exists())try{
            byte[] saved=file.readFully();if(saved.length>MAX_BYTES)throw new IOException("size");
            JSONArray rows=new JSONObject(new String(saved,StandardCharsets.UTF_8)).optJSONArray("cycles");
            if(rows!=null)for(int i=Math.max(0,rows.length()-MAX_CYCLES);i<rows.length();i++)cycles.add(rows.getJSONObject(i));
            recount();
        }catch(Exception e){storageError="기존 진단 기록을 읽지 못했습니다.";}
    }
    synchronized boolean enabled(){return enabled;}
    synchronized void setEnabled(boolean value){
        if(enabled==value)return;
        if(!value&&current!=null){record("recording stopped by user");try{current.put("captureEnd",System.currentTimeMillis()).put("captureEndReason","disabled");}catch(JSONException ignored){}current=null;}
        enabled=value;prefs.edit().putBoolean("unlock-diagnostics",value).apply();schedule();
    }
    synchronized void begin(){
        if(!enabled)return;
        try{
            if(current!=null){current.put("captureEnd",System.currentTimeMillis());current.put("captureEndReason","next-screen-off");}
            observations.clear();origin=SystemClock.elapsedRealtime();until=origin+30_000;
            current=new JSONObject().put("start",System.currentTimeMillis()).put("originElapsed",origin).put("events",new JSONArray()).put("dropped",0).put("app",BuildConfig.VERSION_NAME).put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT).put("model",Build.MANUFACTURER+" "+Build.MODEL);
            cycles.add(current);trim();record("screen-off observed");
        }catch(JSONException ignored){}
    }
    synchronized void resumeCapture(){if(enabled&&current!=null){until=SystemClock.elapsedRealtime()+30_000;current.remove("captureEnd");current.remove("captureEndReason");}}
    synchronized void confirmedActivity(){
        long now=System.currentTimeMillis();
        prefs.edit().putString("unlock-last-window","activity").putLong("unlock-last-at",now).apply();
        if(enabled&&current!=null)try{current.put("unlock",now).put("window","activity");record("unlock confirmed Activity");}catch(JSONException ignored){}
    }
    void failure(String operation,Exception error){
        // Exception messages can include app/window information. Keep only controlled category and class.
        String value=operation+":"+error.getClass().getSimpleName();
        prefs.edit().putString("unlock-last-failure",value).putLong("unlock-last-failure-at",System.currentTimeMillis()).apply();
        record("activity failure "+value);
    }

    synchronized void record(String event){
        if(!enabled||current==null)return;
        if(until>0&&SystemClock.elapsedRealtime()>until){
            if(!current.has("captureEnd")){try{current.put("captureEnd",System.currentTimeMillis()).put("captureEndReason","bounded-capture-ended");}catch(JSONException ignored){}schedule();}
            return;
        }
        try{
            String text=event.substring(0,Math.min(event.length(),500));
            JSONObject row=new JSONObject().put("at",System.currentTimeMillis()).put("elapsed",SystemClock.elapsedRealtime()-origin).put("event",text);
            // Compress repeated observations by source (not only adjacent rows).
            // Changes remain chronological.
            String key=observationKey(text);JSONObject prior=key==null?null:observations.get(key);
            if(prior!=null&&text.equals(prior.optString("event"))){
                bytes-=rowBytes(prior);prior.put("repeats",prior.optInt("repeats")+1).put("lastAt",row.getLong("at")).put("lastElapsed",row.getLong("elapsed"));bytes+=rowBytes(prior);
                current.put("compressed",current.optInt("compressed")+1);trim();schedule();return;
            }
            if(key!=null){if(observations.size()>=64)observations.clear();observations.put(key,row);}
            current.getJSONArray("events").put(row);bytes+=rowBytes(row);
            JSONArray events=current.getJSONArray("events");
            if(events.length()>MAX_EVENTS)evict(current);
            trim();schedule();
        }catch(JSONException ignored){}
    }
    private static String observationKey(String text){
        if(text.startsWith("accessibility observed"))return text;
        return null;
    }
    private static int priority(String text){
        if(observationKey(text)!=null)return 0;
        if(text.startsWith("web=")&&(text.contains("dom ")||text.contains("Draw observed")||text.contains("view-draw")||text.contains("frame-commit")||text.contains("visual ")||text.contains("visual-ready")||text.contains("frameReady=")||text.contains("visibility")||text.contains("attach")||text.contains("detach")||text.contains("recovery")))return 2;
        if(text.startsWith("phone-activity ")||text.startsWith("unlock")||text.startsWith("keyguard observed")||text.contains("failure"))return 2;
        return 1;
    }
    private static int rowBytes(JSONObject row){return row.toString().getBytes(StandardCharsets.UTF_8).length+1;}
    private void evict(JSONObject cycle) throws JSONException {
        JSONArray events=cycle.getJSONArray("events");if(events.length()==0)return;
        int index=-1,best=3;
        for(int i=0;i<events.length();i++){
            int rank=priority(events.getJSONObject(i).optString("event"));
            // Keep the first preparation evidence even if critical-only overflow occurs.
            if(rank<best&&(i>=12||rank<2)){best=rank;index=i;if(rank==0)break;}
        }
        if(index<0)index=events.length()>12?12:0;
        JSONObject removed=events.getJSONObject(index);bytes-=rowBytes(removed);events.remove(index);
        observations.values().removeIf(value->value==removed);
        cycle.put("dropped",cycle.optInt("dropped")+1);
        if(best==2)cycle.put("criticalDropped",cycle.optInt("criticalDropped")+1);
    }
    private void recount(){bytes=0;for(JSONObject cycle:cycles)bytes+=cycle.toString().getBytes(StandardCharsets.UTF_8).length+1;}
    private void trim(){
        while(cycles.size()>MAX_CYCLES){cycles.remove(0);recount();}
        while(bytes>MAX_BYTES-4096&&cycles.size()>1){cycles.remove(0);recount();}
        if(bytes>MAX_BYTES-4096&&!cycles.isEmpty())try{
            JSONObject cycle=cycles.get(0);
            while(bytes>MAX_BYTES-4096&&cycle.getJSONArray("events").length()>0)evict(cycle);
        }catch(JSONException ignored){}
    }
    private synchronized String encode(){
        try{
            String data=new JSONObject().put("schema",1).put("cycles",new JSONArray(cycles)).toString();
            while(data.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES&&!cycles.isEmpty()){
                if(cycles.size()>1)cycles.remove(0);
                else {JSONObject c=cycles.get(0);if(c.getJSONArray("events").length()==0)break;evict(c);}
                recount();data=new JSONObject().put("schema",1).put("cycles",new JSONArray(cycles)).toString();
            }
            return data;
        }catch(JSONException e){throw new IllegalStateException(e);}
    }
    private synchronized void schedule(){if(pending==null)pending=disk.schedule(this::persist,3000,TimeUnit.MILLISECONDS);}
    private void persist(){
        String data;synchronized(this){pending=null;data=encode();}
        FileOutputStream out=null;
        try{out=file.startWrite();out.write(data.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);synchronized(this){storageError="";}}
        catch(IOException e){if(out!=null)file.failWrite(out);synchronized(this){storageError="진단 기록을 저장하지 못했습니다. 다시 내보내기를 시도해 주세요.";}}
    }
    void clear() throws Exception {
        synchronized(this){cycles.clear();observations.clear();current=null;bytes=0;if(pending!=null){pending.cancel(false);pending=null;}}
        // Runs on the bridge worker, never on the display thread. Serial ordering prevents old writes returning after deletion.
        disk.submit(()->{file.delete();synchronized(this){storageError="";}}).get(5,TimeUnit.SECONDS);
    }
    void flush() throws Exception {synchronized(this){if(pending!=null){pending.cancel(false);pending=null;}}disk.submit(this::persist).get(5,TimeUnit.SECONDS);}
    synchronized JSONObject status() throws JSONException {
        long last=cycles.isEmpty()?0:cycles.get(cycles.size()-1).optLong("start");int unlocks=0;
        for(JSONObject c:cycles)if(c.has("unlock"))unlocks++;
        return new JSONObject().put("enabled",enabled).put("cycles",cycles.size()).put("unlocks",unlocks).put("lastAt",last).put("error",storageError);
    }
    JSONObject windowStatus() throws JSONException {
        return new JSONObject().put("lastUnlockWindow",prefs.getString("unlock-last-window","none")).put("lastUnlockAt",prefs.getLong("unlock-last-at",0))
            .put("lastFailure",prefs.getString("unlock-last-failure","")).put("lastFailureAt",prefs.getLong("unlock-last-failure-at",0));
    }
    synchronized String text(){
        StringBuilder out=new StringBuilder("Locky 잠금 해제 진단 · "+BuildConfig.VERSION_NAME+"\nAndroid "+Build.VERSION.RELEASE+" (SDK "+Build.VERSION.SDK_INT+") · "+Build.MANUFACTURER+" "+Build.MODEL+"\n내보낸 시각: "+timestamp(System.currentTimeMillis())+"\n");
        out.append("Activity 실행 요청과 실제 생명주기 콜백을 구분합니다. onCreate/onResume은 실제 삼성 화면 전환의 자연스러움을 보증하지 않습니다.\n메모·질문·답변·검색어·화면 텍스트는 수집하지 않습니다.\n");
        out.append("진단 기록: ").append(enabled?"켜짐":"꺼짐").append(" · 보관 ").append(cycles.size()).append("회 (최대 5회/약 200KB)\n");
        if(!storageError.isEmpty())out.append(storageError).append('\n');
        if(cycles.isEmpty())out.append("기록이 없습니다. 진단을 켠 뒤 화면을 끄고 잠금을 해제해 주세요.\n");
        int n=0;
        for(JSONObject c:cycles){
            out.append("\n=== 주기 ").append(++n).append(" · ").append(timestamp(c.optLong("start"))).append(" ===\n");
            out.append("환경: Locky ").append(c.optString("app")).append(" / Android ").append(c.optString("android")).append(" SDK ").append(c.optInt("sdk")).append(" / ").append(c.optString("model")).append('\n');
            out.append("잠금 해제 확인: ").append(stamp(c,"unlock")).append(" · 창: ").append(c.optString("window","아직 확인되지 않음")).append('\n');
            out.append("기록 종료: ").append(stamp(c,"captureEnd")).append(" · 이유: ").append(c.optString("captureEndReason","기록 중/마지막 관찰 이후 대기")).append('\n');
            JSONArray rows=c.optJSONArray("events");if(rows==null)continue;
            out.append("중복 관찰 압축 ").append(c.optInt("compressed")).append(" / 용량 초과 핵심 이벤트 생략 ").append(c.optInt("criticalDropped")).append('\n');
            for(int i=0;i<rows.length();i++){JSONObject r=rows.optJSONObject(i);out.append(timestamp(r.optLong("at"))).append(" +").append(r.optLong("elapsed")).append("ms ").append(r.optString("event"));if(r.optInt("repeats")>0)out.append(" [동일 관찰 추가 ").append(r.optInt("repeats")).append("회 · 마지막 +").append(r.optLong("lastElapsed")).append("ms]");out.append('\n');}
        }
        return out.toString();
    }
    private static String timestamp(long value){return Instant.ofEpochMilli(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);}
    private static String stamp(JSONObject c,String key){return c.has(key)?timestamp(c.optLong(key)):"미확인";}
}
