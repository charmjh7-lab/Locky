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
            origin=SystemClock.elapsedRealtime();until=origin+30_000;
            current=new JSONObject().put("start",System.currentTimeMillis()).put("originElapsed",origin).put("events",new JSONArray()).put("dropped",0).put("app",BuildConfig.VERSION_NAME).put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT).put("model",Build.MANUFACTURER+" "+Build.MODEL);
            cycles.add(current);trim();record("screen-off observed");
        }catch(JSONException ignored){}
    }
    synchronized void resumeCapture(){if(enabled&&current!=null){until=SystemClock.elapsedRealtime()+30_000;current.remove("captureEnd");current.remove("captureEndReason");}}
    synchronized void confirmed(int type){
        long now=System.currentTimeMillis();
        prefs.edit().putString("unlock-last-window",windowName(type)).putLong("unlock-last-at",now).apply();
        if(enabled&&current!=null)try{current.put("unlock",now).put("window",windowName(type));record("unlock confirmed observed type="+type);}catch(JSONException ignored){}
    }
    synchronized void transitionEnded(String reason){
        if(enabled&&current!=null)try{current.put("transitionEnd",System.currentTimeMillis()).put("transitionEndReason",reason);}catch(JSONException ignored){}
        if(enabled&&current!=null)schedule();
    }
    void failure(String operation,Exception error){
        // Exception messages can include app/window information. Keep only controlled category and class.
        String value=operation+":"+error.getClass().getSimpleName();
        prefs.edit().putString("unlock-last-failure",value).putLong("unlock-last-failure-at",System.currentTimeMillis()).apply();
        record("window failure "+value);
    }
    static String windowName(int type){return type==android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY?"application":type==android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY?"accessibility":"none";}
    synchronized void record(String event){
        if(!enabled||current==null)return;
        if(until>0&&SystemClock.elapsedRealtime()>until){
            if(!current.has("captureEnd")){try{current.put("captureEnd",System.currentTimeMillis()).put("captureEndReason","bounded-capture-ended");}catch(JSONException ignored){}schedule();}
            return;
        }
        try{
            String text=event.substring(0,Math.min(event.length(),500));
            JSONObject row=new JSONObject().put("at",System.currentTimeMillis()).put("elapsed",SystemClock.elapsedRealtime()-origin).put("event",text);
            String counter=text.startsWith("window add ")?"adds":text.startsWith("window remove ")?"removes":text.startsWith("window update requested")?"updates":text.startsWith("alpha ")?"alphas":null;
            if(counter!=null)current.put(counter,current.optInt(counter)+1);
            boolean stateChanged=false;
            if(text.startsWith("state ")){JSONArray states=current.optJSONArray("states");if(states==null){states=new JSONArray();current.put("states",states);}if(states.length()<40){states.put(text.substring(6));stateChanged=true;}}
            current.getJSONArray("events").put(row);bytes+=row.toString().getBytes(StandardCharsets.UTF_8).length+1;
            JSONArray events=current.getJSONArray("events");
            if(events.length()>MAX_EVENTS){bytes-=events.getJSONObject(20).toString().getBytes(StandardCharsets.UTF_8).length+1;events.remove(20);current.put("dropped",current.optInt("dropped")+1);}
            if(stateChanged)recount();
            trim();schedule();
        }catch(JSONException ignored){}
    }
    private void recount(){bytes=0;for(JSONObject cycle:cycles)bytes+=cycle.toString().getBytes(StandardCharsets.UTF_8).length+1;}
    private void trim(){
        while(cycles.size()>MAX_CYCLES){cycles.remove(0);recount();}
        while(bytes>MAX_BYTES-4096&&cycles.size()>1){cycles.remove(0);recount();}
    }
    private synchronized String encode(){
        try{
            String data=new JSONObject().put("schema",1).put("cycles",new JSONArray(cycles)).toString();
            while(data.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES&&!cycles.isEmpty()){
                if(cycles.size()>1)cycles.remove(0);
                else {JSONObject c=cycles.get(0);JSONArray events=c.getJSONArray("events");if(events.length()==0)break;events.remove(events.length()>20?20:0);c.put("dropped",c.optInt("dropped")+1);}
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
        synchronized(this){cycles.clear();current=null;bytes=0;if(pending!=null){pending.cancel(false);pending=null;}}
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
        out.append("요청(requested)과 앱/Android 콜백(observed)을 구분합니다. update accepted는 호출 성공이며 실제 화면 합성 완료를 뜻하지 않습니다. View draw/visual-ready도 합성 완료 증거가 아닙니다.\n메모·질문·답변·검색어·화면 텍스트는 수집하지 않습니다.\n");
        out.append("진단 기록: ").append(enabled?"켜짐":"꺼짐").append(" · 보관 ").append(cycles.size()).append("회 (최대 5회/약 200KB)\n");
        if(!storageError.isEmpty())out.append(storageError).append('\n');
        if(cycles.isEmpty())out.append("기록이 없습니다. 진단을 켠 뒤 화면을 끄고 잠금을 해제해 주세요.\n");
        int n=0;
        for(JSONObject c:cycles){
            out.append("\n=== 주기 ").append(++n).append(" · ").append(timestamp(c.optLong("start"))).append(" ===\n");
            out.append("환경: Locky ").append(c.optString("app")).append(" / Android ").append(c.optString("android")).append(" SDK ").append(c.optInt("sdk")).append(" / ").append(c.optString("model")).append('\n');
            out.append("잠금 해제 확인: ").append(stamp(c,"unlock")).append(" · 창: ").append(c.optString("window","아직 확인되지 않음")).append('\n');
            out.append("전환 종료: ").append(stamp(c,"transitionEnd")).append(" · 이유: ").append(c.optString("transitionEndReason","미확인")).append('\n');
            out.append("기록 종료: ").append(stamp(c,"captureEnd")).append(" · 이유: ").append(c.optString("captureEndReason","기록 중/마지막 관찰 이후 대기")).append('\n');
            JSONArray rows=c.optJSONArray("events");if(rows==null)continue;
            out.append("창 생성 ").append(c.optInt("adds")).append(" / 제거 ").append(c.optInt("removes")).append(" / 갱신 요청 ").append(c.optInt("updates")).append(" / alpha 변경 요청 ").append(c.optInt("alphas")).append(" / 생략된 과다 이벤트 ").append(c.optInt("dropped")).append('\n');
            out.append("상태 순서: ").append(c.optJSONArray("states")==null?"[]":c.optJSONArray("states").toString()).append('\n');
            for(int i=0;i<rows.length();i++){JSONObject r=rows.optJSONObject(i);out.append(timestamp(r.optLong("at"))).append(" +").append(r.optLong("elapsed")).append("ms ").append(r.optString("event")).append('\n');}
        }
        return out.toString();
    }
    private static String timestamp(long value){return Instant.ofEpochMilli(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);}
    private static String stamp(JSONObject c,String key){return c.has(key)?timestamp(c.optLong(key)):"미확인";}
}
