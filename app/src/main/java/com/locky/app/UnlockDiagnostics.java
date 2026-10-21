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

/**
 * Bounded, opt-in APP_GATE timing diagnostics. No app package, purpose, node text,
 * messages or credentials. The old unlock-cycle recording API is inert; the
 * PhoneStartActivity lifecycle and navigation are not changed by this recorder.
 */
final class UnlockDiagnostics {
    static final int MAX_GATES=24, MAX_GATE_EVENTS=72, MAX_GATE_BYTES=96_000;
    static volatile UnlockDiagnostics live;
    static synchronized UnlockDiagnostics get(Context c) {
        Context app=c.getApplicationContext();
        if(live==null||live.context!=app)live=new UnlockDiagnostics(app);
        return live;
    }
    final Context context;
    final SharedPreferences prefs;
    final AtomicFile file;
    private final ScheduledExecutorService disk=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"LockyGateDiagnostics");t.setDaemon(true);return t;
    });
    private final List<JSONObject> gates=new ArrayList<>();
    private JSONObject currentGate;
    private long currentGateToken=-1,gateOrigin,lastGateEndUptime=-1;
    private boolean enabled;
    private ScheduledFuture<?> pending;
    private String storageError="";

    UnlockDiagnostics(Context c) {
        context=c;
        prefs=c.getSharedPreferences("locky-settings",0);
        enabled=prefs.getBoolean("app-gate-diagnostics",false);
        file=new AtomicFile(new File(c.getFilesDir(),"app-gate-diagnostics.json"));
        // Unlock tracing has been retired. Preserve the unrelated phone start status.
        new File(c.getFilesDir(),"unlock-diagnostics.json").delete();
        prefs.edit().remove("unlock-diagnostics").apply();
        if(file.getBaseFile().exists())try{
            byte[] bytes=file.readFully();
            if(bytes.length>MAX_GATE_BYTES)throw new IOException("size");
            JSONArray rows=new JSONObject(new String(bytes,StandardCharsets.UTF_8)).optJSONArray("appGates");
            if(rows!=null)for(int i=Math.max(0,rows.length()-MAX_GATES);i<rows.length();i++)
                gates.add(rows.getJSONObject(i));
            trim();
        }catch(Exception e){storageError="기존 앱 시작 진단 기록을 읽지 못했습니다.";}
    }
    synchronized boolean enabled(){return enabled;}
    synchronized void setEnabled(boolean value){
        if(enabled==value)return;
        enabled=value;
        if(!value)currentGate=null;
        prefs.edit().putBoolean("app-gate-diagnostics",value).apply();
        schedule();
    }
    synchronized void beginGate(long token,long detectedUptime){
        if(!enabled)return;
        try{
            final long now=SystemClock.uptimeMillis();
            if(currentGate!=null&&!currentGate.has("end")){
                currentGate.put("end",System.currentTimeMillis());
                currentGate.put("outcome","interrupted-by-new-entry");
            }
            gateOrigin=Math.min(now,Math.max(0,detectedUptime));
            long gap=lastGateEndUptime<0?-1:Math.max(0,gateOrigin-lastGateEndUptime);
            currentGateToken=token;
            currentGate=new JSONObject()
                .put("start",System.currentTimeMillis()-(now-gateOrigin))
                .put("transition",token)
                .put("idleGapMs",gap)
                .put("detectionToGateMs",Math.max(0,now-gateOrigin))
                .put("events",new JSONArray().put(new JSONObject().put("elapsed",0).put("event","foreground detected")));
            gates.add(currentGate);
            while(gates.size()>MAX_GATES)gates.remove(0);
            // Bound the serialized size only on the disk thread, never during entry.
            schedule();
        }catch(JSONException ignored){}
    }
    synchronized void gateRecord(long token,String event){
        if(!enabled||currentGate==null||token!=currentGateToken)return;
        long elapsed=SystemClock.uptimeMillis()-gateOrigin;
        if(elapsed>60_000)return;
        try{
            JSONArray rows=currentGate.getJSONArray("events");
            if(rows.length()>=MAX_GATE_EVENTS){currentGate.put("dropped",currentGate.optInt("dropped")+1);return;}
            rows.put(new JSONObject().put("elapsed",Math.max(0,elapsed))
                .put("event",event.substring(0,Math.min(240,event.length()))));
            schedule();
        }catch(JSONException ignored){}
    }
    synchronized void finishGate(long token,String outcome){
        if(!enabled||currentGate==null||token!=currentGateToken||currentGate.has("end"))return;
        try{
            long now=SystemClock.uptimeMillis();
            currentGate.put("end",System.currentTimeMillis())
                .put("durationMs",Math.max(0,now-gateOrigin))
                .put("outcome",outcome);
            lastGateEndUptime=now;
            schedule();
        }catch(JSONException ignored){}
    }
    private void trim(){
        while(gates.size()>MAX_GATES||new JSONArray(gates).toString().getBytes(StandardCharsets.UTF_8).length>MAX_GATE_BYTES-1024){
            if(gates.isEmpty())break;
            JSONObject first=gates.get(0);
            if(first==currentGate&&gates.size()==1) {
                JSONArray events=first.optJSONArray("events");
                if(events==null||events.length()<2)break;
                events.remove(1);
            } else {
                gates.remove(0);
                if(first==currentGate)currentGate=null;
            }
        }
    }
    private synchronized void schedule(){
        if(pending==null)pending=disk.schedule(this::persist,1500,TimeUnit.MILLISECONDS);
    }
    private synchronized String encode(){
        trim();
        return new JSONObject(Collections.singletonMap("appGates",new JSONArray(gates))).toString();
    }
    private void persist(){
        String data;
        synchronized(this){pending=null;data=encode();}
        FileOutputStream out=null;
        try{
            out=file.startWrite();
            out.write(data.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
            synchronized(this){storageError="";}
        }catch(IOException e){
            if(out!=null)file.failWrite(out);
            synchronized(this){storageError="앱 시작 진단 기록을 저장하지 못했습니다.";}
        }
    }
    void clear() throws Exception {
        synchronized(this){
            gates.clear();currentGate=null;currentGateToken=-1;
            lastGateEndUptime=-1;
            if(pending!=null){pending.cancel(false);pending=null;}
        }
        disk.submit(()->{file.delete();synchronized(this){storageError="";}}).get(5,TimeUnit.SECONDS);
    }
    void flush() throws Exception {
        synchronized(this){if(pending!=null){pending.cancel(false);pending=null;}}
        disk.submit(this::persist).get(5,TimeUnit.SECONDS);
    }
    synchronized JSONObject status() throws JSONException{
        long last=gates.isEmpty()?0:gates.get(gates.size()-1).optLong("start");
        return new JSONObject().put("enabled",enabled).put("appGates",gates.size())
            .put("lastAt",last).put("error",storageError);
    }
    // Keep the existing PhoneStartActivity status indicator, not unlock tracing.
    synchronized void confirmedActivity(){
        prefs.edit().putString("unlock-last-window","activity")
            .putLong("unlock-last-at",System.currentTimeMillis()).apply();
    }
    JSONObject windowStatus() throws JSONException {
        return new JSONObject().put("lastUnlockWindow",prefs.getString("unlock-last-window","none"))
            .put("lastUnlockAt",prefs.getLong("unlock-last-at",0))
            .put("lastFailure","").put("lastFailureAt",0);
    }
    void failure(String operation,Exception error){
        if(operation.startsWith("app-gate"))gateRecord(currentGateToken,operation+":"+error.getClass().getSimpleName());
    }
    synchronized String text(){
        StringBuilder out=new StringBuilder("Locky 관리 앱 시작화면(APP_GATE) 진단 · "+BuildConfig.VERSION_NAME+
            "\nAndroid "+Build.VERSION.RELEASE+" (SDK "+Build.VERSION.SDK_INT+") · "+Build.MANUFACTURER+" "+Build.MODEL+
            "\n내보낸 시각: "+timestamp(System.currentTimeMillis())+"\n");
        out.append("시간: foreground 감지 기준 uptime 밀리초. Chromium visual-ready는 실제 시스템 화면 합성 완료를 의미하지 않습니다.\n");
        out.append("앱 이름·패키지·목적·입력 내용은 기록하지 않습니다.\n");
        out.append("진단: ").append(enabled?"켜짐":"꺼짐").append(" · 최근 ").append(gates.size()).append("회 (최대 24회/96KB)\n");
        if(!storageError.isEmpty())out.append(storageError).append('\n');
        if(gates.isEmpty())out.append("기록이 없습니다. 진단을 켠 뒤 관리 앱에 진입해 주세요.\n");
        for(JSONObject entry:gates){
            out.append("\n=== APP_GATE ").append(timestamp(entry.optLong("start")))
                .append(" · transition ").append(entry.optLong("transition")).append(" ===\n");
            out.append("직전 진입 종료 후 쉬었던 시간: ")
                .append(entry.optLong("idleGapMs",-1)<0?"미확인":entry.optLong("idleGapMs")+"ms")
                .append(" · 감지→진입 처리: ").append(entry.optLong("detectionToGateMs")).append("ms\n");
            out.append("결과: ").append(entry.optString("outcome","기록 중/중단"))
                .append(" · 총 소요: ").append(entry.has("durationMs")?entry.optLong("durationMs")+"ms":"미확인").append('\n');
            JSONArray rows=entry.optJSONArray("events");if(rows==null)continue;
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);
                out.append('+').append(row.optLong("elapsed")).append("ms ")
                    .append(row.optString("event")).append('\n');
            }
            if(entry.optInt("dropped")>0)out.append("생략된 이벤트: ").append(entry.optInt("dropped")).append('\n');
        }
        return out.toString();
    }
    private static String timestamp(long value){
        return Instant.ofEpochMilli(value).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
    // Retired unlock recording entry points. These no longer allocate cycles or write logs.
    void begin(){}
    void resumeCapture(){}
    void record(String ignored){}
}
