package com.locky.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.time.*;
import java.util.*;

final class Store extends SQLiteOpenHelper {
    static volatile Store instance;
    static Store get(Context c) { if(instance==null)synchronized(Store.class){if(instance==null)instance=new Store(c.getApplicationContext());}return instance; }
    final android.content.SharedPreferences settings;
    final Context context;private long lastObservation;
    // Budget inputs change on charged usage and screen evidence; uncharged gate checkpoints cannot spend time.
    private volatile long usageRevision;
    private JSONArray purposeRows;
    private String purposeDay="";
    private final Map<String,JSONArray> purposeOrders=new HashMap<>();
    private long catalogRevision;
    private String catalogDay="";
    private long recordedUsageEnd=-1;
    private GateCatalog cachedGateCatalog;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener gatePreferences=(prefs,key)->{
        if(key==null||Set.of("enabled","floatingTimer","start","end","reminders","dailyBrief","dailyBriefTime","focusBrief","weeklyDay","model","theme-mode").contains(key))invalidateGateCatalog();
    };
    static final class GateCatalog {
        final long revision;final String day,json;final JSONObject data;
        GateCatalog(long revision,String day,JSONObject data){this.revision=revision;this.day=day;this.data=data;json=data.toString();}
    }
    synchronized void invalidateGateCatalog(){++catalogRevision;cachedGateCatalog=null;}
    private void invalidatePurposes(){purposeRows=null;purposeOrders.clear();invalidateGateCatalog();}
    synchronized boolean currentGateCatalog(GateCatalog catalog){return catalog!=null&&catalog.revision==catalogRevision&&catalog.day.equals(LocalDate.now().toString());}
    synchronized GateCatalog gateCatalog() throws JSONException {
        if(currentGateCatalog(cachedGateCatalog))return cachedGateCatalog;
        String day=LocalDate.now().toString();if(!day.equals(catalogDay)){catalogDay=day;++catalogRevision;}
        JSONArray all=allPurposes(),rules=rows("SELECT * FROM rules ORDER BY pkg");
        Set<String> scopes=new LinkedHashSet<>(Set.of("app"));
        for(int i=0;i<rules.length();i++)scopes.add(rules.getJSONObject(i).getString("pkg"));
        for(int i=0;i<all.length();i++){String scope=all.getJSONObject(i).getString("scope");if(!Set.of("*","phone").contains(scope))scopes.add(scope);}
        JSONObject orders=new JSONObject();for(String scope:scopes)orders.put(scope,purposes(scope));
        JSONObject data=new JSONObject().put("catalogVersion",catalogRevision).put("purposeDate",day)
            .put("prefs",preferences()).put("allPurposes",all).put("purposeOrders",orders).put("rules",rules);
        return cachedGateCatalog=new GateCatalog(catalogRevision,day,data);
    }
    synchronized JSONArray allPurposes() throws JSONException {
        if(purposeRows==null)purposeRows=rows("SELECT * FROM purposes ORDER BY rowid");return purposeRows;
    }
    synchronized void removePurpose(String id){getWritableDatabase().delete("purposes","id=?",new String[]{id});invalidatePurposes();}
    synchronized void removeRule(String pkg){getWritableDatabase().delete("rules","pkg=?",new String[]{pkg});invalidateGateCatalog();}
    long usageRevision(){return usageRevision;}
    private Store(Context c) { super(c,"locky.db",null,5);context=c; settings=c.getSharedPreferences("locky-settings",0);if(!settings.contains("usage-excluded"))settings.edit().putStringSet("usage-excluded",new HashSet<>(Set.of("com.samsung.android.sidegesturepad"))).apply();settings.registerOnSharedPreferenceChangeListener(gatePreferences);setWriteAheadLoggingEnabled(true); }
    @Override public synchronized void close(){settings.unregisterOnSharedPreferenceChangeListener(gatePreferences);super.close();}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE purposes(id TEXT PRIMARY KEY,label TEXT NOT NULL,pkg TEXT NOT NULL DEFAULT '',uri TEXT NOT NULL DEFAULT '',aimless INTEGER NOT NULL DEFAULT 0,scope TEXT NOT NULL DEFAULT '*')");
        db.execSQL("CREATE TABLE rules(pkg TEXT PRIMARY KEY,label TEXT NOT NULL,mode TEXT NOT NULL,minutes INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,kind TEXT NOT NULL,purpose TEXT NOT NULL,pkg TEXT NOT NULL,target TEXT NOT NULL,parent INTEGER NOT NULL DEFAULT 0,aimless INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE usage(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER NOT NULL,end INTEGER NOT NULL,pkg TEXT NOT NULL,label TEXT NOT NULL,managed INTEGER NOT NULL,parent INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX usage_time ON usage(start,end)");db.execSQL("CREATE INDEX events_time ON events(time)");
        db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL,checks TEXT NOT NULL,updated INTEGER NOT NULL,mode TEXT NOT NULL DEFAULT 'text')");
        db.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat TEXT NOT NULL,role TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,time INTEGER NOT NULL,citations TEXT NOT NULL DEFAULT '[]')");
        createQuickRequests(db);
        createBriefingTables(db);
        db.execSQL("ALTER TABLE usage ADD COLUMN source TEXT NOT NULL DEFAULT 'accessibility'");
        db.execSQL("ALTER TABLE usage ADD COLUMN rule_mode TEXT NOT NULL DEFAULT 'unknown'");
        db.execSQL("ALTER TABLE usage ADD COLUMN surface TEXT NOT NULL DEFAULT 'unknown'");
        createScreenStates(db);
        seedPurposes(db);
    }
    private void seedPurposes(SQLiteDatabase db) {
        String[][] phone={{"contact","연락 확인","0"},{"lookup","궁금한 것 찾기","0"},{"task","할 일 처리","0"},{"rest","잠깐 쉬기","0"},{"aimless","그냥 열었어요","1"}};
        for(String[] p:phone)db.execSQL("INSERT OR IGNORE INTO purposes(id,label,aimless,scope) VALUES(?,?,?,'phone')",new Object[]{p[0],p[1],Integer.parseInt(p[2])});
        String[][] apps={{"app-contact","메시지 확인","0"},{"app-watch","보고 싶은 콘텐츠","0"},{"app-read","필요한 정보 찾기","0"},{"app-rest","잠깐 쉬기","0"},{"app-aimless","그냥 열었어요","1"}};
        for(String[] p:apps)db.execSQL("INSERT OR IGNORE INTO purposes(id,label,aimless,scope) VALUES(?,?,?,'app')",new Object[]{p[0],p[1],Integer.parseInt(p[2])});
    }
    private void createScreenStates(SQLiteDatabase db){db.execSQL("CREATE TABLE screen_states(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,state INTEGER NOT NULL,type INTEGER NOT NULL DEFAULT 0)");db.execSQL("CREATE UNIQUE INDEX screen_state_time ON screen_states(time,type,state)");}
    synchronized void screenMarks(List<ScreenHistory.Mark> marks){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(ScreenHistory.Mark mark:marks){ContentValues v=new ContentValues();v.put("time",mark.time);v.put("type",mark.type);v.put("state",mark.value);if(db.insertWithOnConflict("screen_states",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1)++usageRevision;}db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    synchronized void screenState(int state){ContentValues v=new ContentValues();v.put("time",System.currentTimeMillis());v.put("state",state);if(getWritableDatabase().insertWithOnConflict("screen_states",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1)++usageRevision;}
    JSONArray screenUsage(long from,long to) throws JSONException {return ScreenHistory.filter(context,rows("SELECT * FROM usage WHERE end>? AND start<? ORDER BY start,id",""+from,""+to),from,to);}
    JSONArray screenUsage(String pkg,long from,long to) throws JSONException {return ScreenHistory.filter(context,rows("SELECT * FROM usage WHERE pkg=? AND managed=1 AND end>? AND start<? ORDER BY start,id",pkg,""+from,""+to),from,to);}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next) {
        if(old<5)createScreenStates(db);
        if(old<4&&next>=4){createBriefingTables(db);db.execSQL("ALTER TABLE usage ADD COLUMN surface TEXT NOT NULL DEFAULT 'unknown'");db.execSQL("ALTER TABLE usage ADD COLUMN rule_mode TEXT NOT NULL DEFAULT 'unknown'");db.execSQL("ALTER TABLE usage ADD COLUMN source TEXT NOT NULL DEFAULT 'legacy'");}
        if(old<3)createQuickRequests(db);
        if(old<2) {
            db.execSQL("UPDATE purposes SET scope='phone' WHERE scope='*'");
            // Only seed the new app scope: never restore deleted phone presets.
            String[][] apps={{"app-contact","메시지 확인","0"},{"app-watch","보고 싶은 콘텐츠","0"},{"app-read","필요한 정보 찾기","0"},{"app-rest","잠깐 쉬기","0"},{"app-aimless","그냥 열었어요","1"}};
            for(String[] p:apps)db.execSQL("INSERT OR IGNORE INTO purposes(id,label,aimless,scope) VALUES(?,?,?,'app')",new Object[]{p[0],p[1],Integer.parseInt(p[2])});
            db.execSQL("ALTER TABLE events ADD COLUMN aimless INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE notes ADD COLUMN mode TEXT NOT NULL DEFAULT 'text'");
            db.execSQL("ALTER TABLE messages ADD COLUMN citations TEXT NOT NULL DEFAULT '[]'");
            try(Cursor c=db.rawQuery("SELECT id,body,checks FROM notes",null)) {while(c.moveToNext()) {
                try {JSONArray checks=new JSONArray(c.getString(2));if(checks.length()>0){JSONArray migrated=new JSONArray();if(!c.getString(1).trim().isEmpty())migrated.put(new JSONObject().put("text",c.getString(1)).put("done",false));for(int i=0;i<checks.length();i++)migrated.put(checks.get(i));ContentValues v=new ContentValues();v.put("mode","check");v.put("body","");v.put("checks",migrated.toString());db.update("notes",v,"id=?",new String[]{c.getString(0)});}}catch(JSONException ignored){}
            }}
        }
    }
    private void createBriefingTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE focus_config_history(id INTEGER PRIMARY KEY AUTOINCREMENT,effective INTEGER NOT NULL,enabled INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,zone TEXT NOT NULL,reason TEXT NOT NULL)");
        db.execSQL("CREATE TABLE service_observations(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,kind TEXT NOT NULL,interactive INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX observation_time ON service_observations(time)");
        db.execSQL("CREATE INDEX focus_effective ON focus_config_history(effective,id)");
        db.execSQL("CREATE TABLE briefing_results(key TEXT PRIMARY KEY,start INTEGER NOT NULL,end INTEGER NOT NULL,body TEXT NOT NULL)");
        db.execSQL("CREATE TABLE briefing_delivery(key TEXT PRIMARY KEY,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL)");
    }
    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // SQLite is authoritative. Preferences are a projection for existing enforcement code.
        // A crash after the DB commit is repaired here before any policy is read.
        try(Cursor c=db.rawQuery("SELECT enabled,start,end FROM focus_config_history ORDER BY effective DESC,id DESC LIMIT 1",null)) {
            if(c.moveToFirst())settings.edit().putBoolean("enabled",c.getInt(0)==1).putInt("start",c.getInt(1)).putInt("end",c.getInt(2)).commit();
            else insertFocus(db,System.currentTimeMillis(),settings.getBoolean("enabled",true),settings.getInt("start",540),settings.getInt("end",1260),ZoneId.systemDefault().getId(),"initial");
        }
    }
    private long insertFocus(SQLiteDatabase db,long at,boolean enabled,int start,int end,String zone,String reason){
        ContentValues v=new ContentValues();v.put("effective",at);v.put("enabled",enabled?1:0);v.put("start",start);v.put("end",end);v.put("zone",zone);v.put("reason",reason);
        return db.insertOrThrow("focus_config_history",null,v);
    }
    synchronized void saveFocus(boolean enabled,int start,int end,String reason) throws JSONException {
        if(start<0||start>1439||end<0||end>1439||start==end)throw new IllegalArgumentException("서로 다른 시작·종료 시간을 선택해 주세요.");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            JSONObject last=rows("SELECT * FROM focus_config_history ORDER BY effective DESC,id DESC LIMIT 1").getJSONObject(0);
            String zone=ZoneId.systemDefault().getId();
            if(last.optInt("enabled")== (enabled?1:0)&&last.optInt("start")==start&&last.optInt("end")==end&&zone.equals(last.optString("zone"))){db.setTransactionSuccessful();return;}
            long at=System.currentTimeMillis();
            // A backwards clock cannot silently overwrite an already-effective configuration.
            if(at<last.getLong("effective"))throw new IllegalStateException("기기 시각을 확인한 뒤 집중시간을 변경해 주세요.");
            insertFocus(db,at,enabled,start,end,zone,reason);db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        settings.edit().putBoolean("enabled",enabled).putInt("start",start).putInt("end",end).commit();
    }
    synchronized void syncFocusZone() {
        try {getWritableDatabase();saveFocus(settings.getBoolean("enabled",true),settings.getInt("start",540),settings.getInt("end",1260),"timezone");}
        catch(JSONException e){throw new IllegalStateException(e);}
    }
    synchronized void observe(String kind,boolean interactive){
        long now=System.currentTimeMillis();if(kind.equals("checkpoint")&&now>=lastObservation&&now-lastObservation<60000)return;lastObservation=now;
        ContentValues v=new ContentValues();v.put("time",now);v.put("kind",kind);v.put("interactive",interactive?1:0);getWritableDatabase().insertOrThrow("service_observations",null,v);
    }
    synchronized JSONArray rows(String sql,String...args) throws JSONException {
        JSONArray out=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery(sql,args)) {while(c.moveToNext()){JSONObject o=new JSONObject();for(int i=0;i<c.getColumnCount();i++){int type=c.getType(i);o.put(c.getColumnName(i),type==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):type==Cursor.FIELD_TYPE_NULL?JSONObject.NULL:c.getString(i));}out.put(o);}}return out;
    }
    synchronized void put(String table,ContentValues values) { getWritableDatabase().insertWithOnConflict(table,null,values,SQLiteDatabase.CONFLICT_REPLACE);if(table.equals("purposes"))invalidatePurposes();else if(table.equals("rules"))invalidateGateCatalog();else if(table.equals("usage")){recordedUsageEnd=-1;++usageRevision;}else if(table.equals("screen_states"))++usageRevision; }
    synchronized long event(String kind,String purpose,String pkg,String target,long parent) {
        return event(kind,purpose,pkg,target,parent,false);
    }
    synchronized long event(String kind,String purpose,String pkg,String target,long parent,boolean aimless) {
        ContentValues v=new ContentValues();v.put("aimless",aimless?1:0);v.put("time",System.currentTimeMillis());v.put("kind",kind);v.put("purpose",purpose);v.put("pkg",pkg);v.put("target",target);v.put("parent",parent);return getWritableDatabase().insertOrThrow("events",null,v);
    }
    synchronized void usage(long from,long to,String pkg,String label,boolean managed,long parent) {usage(from,to,pkg,label,managed,parent,"accessibility");}
    synchronized void usage(long from,long to,String pkg,String label,boolean managed,long parent,String source) {usage(from,to,pkg,label,managed,parent,source,false);}
    // GuardService has already intersected this interval with ON evidence.
    synchronized void observedUsage(long from,long to,String pkg,String label,boolean managed,long parent){usage(from,to,pkg,label,managed,parent,"accessibility",true);}
    private void usage(long from,long to,String pkg,String label,boolean managed,long parent,String source,boolean observedActive) {
        if(to<=from||pkg.isEmpty()||UsageReader.excluded(context,pkg))return;
        // Accessibility observation owns a span. Recovery fills only uncovered gaps,
        // even when an overlay/system pause was recorded under another package.
        long cursor=from;List<long[]> gaps=new ArrayList<>();
        try {
            if(recordedUsageEnd<0)try(Cursor last=getReadableDatabase().rawQuery("SELECT MAX(end) FROM usage",null)){recordedUsageEnd=last.moveToFirst()?last.getLong(0):0;}
            // A chronological append cannot overlap any recorded interval.
            // Recovery/backdated writes still use the full overlap path.
            if(from<recordedUsageEnd){JSONArray occupied=screenUsage(from,to);
                for(int n=0;n<occupied.length();n++){JSONObject row=occupied.getJSONObject(n);long a=row.getLong("start"),b=row.getLong("end");if(a>cursor)gaps.add(new long[]{cursor,Math.min(a,to)});cursor=Math.max(cursor,b);if(cursor>=to)break;}}
        }catch(JSONException e){throw new IllegalStateException(e);}
        if(cursor<to)gaps.add(new long[]{cursor,to});
        String surface=pkg.equals(context.getPackageName())?"internal":"external";
        if(source.equals("accessibility")&&PhoneStartActivity.current()!=null&&PhoneStartActivity.current().allowsDictionary(pkg))surface="dictionary-tool";
        if(observedActive){for(long[] gap:gaps)appendUsage(gap[0],gap[1],pkg,label,managed,parent,source,surface);}
        else {ScreenHistory.Evidence evidence=ScreenHistory.read(context,from,to,ScreenHistory.UNKNOWN,true);
            for(long[] gap:gaps)for(ScreenHistory.Span span:evidence.spans){long a=Math.max(gap[0],span.start),b=Math.min(gap[1],span.end);if(b>a&&span.state!=ScreenHistory.OFF)appendUsage(a,b,pkg,label,managed,parent,source,surface);}}
    }
    private void appendUsage(long from,long to,String pkg,String label,boolean managed,long parent,String source,String surface) {
        recordedUsageEnd=Math.max(recordedUsageEnd,to);
        SQLiteDatabase d=getWritableDatabase();JSONObject policy=source.equals("accessibility")?rule(pkg):null;String mode=source.equals("accessibility")?(policy==null?"none":policy.optString("mode")):"unknown";
        try(Cursor c=d.rawQuery("SELECT id,end,pkg,label,managed,parent,source,rule_mode,surface FROM usage ORDER BY id DESC LIMIT 1",null)) {
            if(c.moveToFirst()&&c.getLong(1)==from&&pkg.equals(c.getString(2))&&label.equals(c.getString(3))&&c.getInt(4)==(managed?1:0)&&c.getLong(5)==parent&&source.equals(c.getString(6))&&mode.equals(c.getString(7))&&surface.equals(c.getString(8))){ContentValues update=new ContentValues();update.put("end",to);d.update("usage",update,"id=?",new String[]{""+c.getLong(0)});if(managed)++usageRevision;return;}
        }
        ContentValues v=new ContentValues();v.put("surface",surface);v.put("rule_mode",mode);v.put("source",source);v.put("start",from);v.put("end",to);v.put("pkg",pkg);v.put("label",label);v.put("managed",managed?1:0);v.put("parent",parent);d.insertOrThrow("usage",null,v);if(managed)++usageRevision;
    }
    synchronized long used(String pkg,long now) {
        if(UsageReader.excluded(context,pkg))return 0;
        long start=Rules.dayStart(now,ZoneId.systemDefault()),total=0,cursor=start;
        try{JSONArray rows=screenUsage(pkg,start,now);for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);long a=Math.max(cursor,row.getLong("start")),b=Math.min(now,row.getLong("end"));total+=Math.max(0,b-a);cursor=Math.max(cursor,b);}}catch(JSONException e){throw new IllegalStateException(e);}return total;
    }
    long remaining(String pkg,long now) {return remaining(pkg,now,rule(pkg));}
    long remaining(String pkg,long now,JSONObject rule) {
        if(rule==null)return 0;
        if(managed(now)&&settings.getLong("blocked-until:"+pkg,0)>now)return 0;
        return Math.max(0,rule.optInt("minutes",60)*60000L-used(pkg,now));
    }
    void blockUntilFree(String pkg,long now) {
        if(settings.getLong("blocked-until:"+pkg,0)>now)return;
        long until=Rules.nextBoundary(now,settings.getInt("start",540),settings.getInt("end",1260),ZoneId.systemDefault());
        settings.edit().putLong("blocked-until:"+pkg,until).apply();
    }
    JSONObject rule(String pkg) {try{JSONArray a=rows("SELECT * FROM rules WHERE pkg=?",pkg);return a.length()==0?null:a.getJSONObject(0);}catch(JSONException e){throw new IllegalStateException(e);} }
    synchronized JSONArray purposes(String scope) throws JSONException {
        String day=LocalDate.now().toString();if(!day.equals(purposeDay)){purposeDay=day;purposeOrders.clear();}
        JSONArray cached=purposeOrders.get(scope);if(cached!=null)return cached;
        JSONArray all=allPurposes();Map<String,JSONObject> byId=new LinkedHashMap<>();
        for(int i=0;i<all.length();i++){JSONObject p=all.getJSONObject(i);String owner=p.optString("scope");if(owner.equals(scope)||owner.equals(scope.equals("phone")?"phone":"app")||(owner.equals("*")&&scope.equals("phone")))byId.put(p.getString("id"),p);}
        JSONArray out=new JSONArray();for(String id:Rules.shuffle(new ArrayList<>(byId.keySet()),day,scope))out.put(byId.get(id));purposeOrders.put(scope,out);return out;
    }
    static JSONObject purposeData(JSONObject input,String scope) throws JSONException {
        String label=input.optString("label").trim();if(label.isEmpty()||label.length()>100)throw new JSONException("목적을 1~100자로 입력해 주세요.");
        String id=input.optString("id",UUID.randomUUID().toString());boolean aimless=input.optBoolean("aimless",id.equals("aimless")||id.equals("app-aimless"));
        return new JSONObject().put("id",id).put("label",label).put("pkg",input.optString("pkg")).put("uri",input.optString("uri")).put("scope",scope).put("aimless",aimless?1:0);
    }
    synchronized JSONObject purpose(JSONObject input,String scope) throws JSONException {
        JSONObject purpose=purposeData(input,scope);ContentValues values=new ContentValues();
        for(String key:new String[]{"id","label","pkg","uri","scope"})values.put(key,purpose.getString(key));values.put("aimless",purpose.getInt("aimless"));
        put("purposes",values);return rows("SELECT * FROM purposes WHERE id=?",purpose.getString("id")).getJSONObject(0);
    }
    private static JSONArray checkIds(JSONArray input,JSONArray previous) throws JSONException {
        JSONArray out=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<input.length();i++){
            JSONObject item=new JSONObject(input.getJSONObject(i).toString());String id=item.optString("id");
            if(id.isEmpty()&&i<previous.length()&&item.optString("text").equals(previous.getJSONObject(i).optString("text")))id=previous.getJSONObject(i).optString("id");
            if(id.isEmpty()||!seen.add(id)){id=UUID.randomUUID().toString();seen.add(id);}item.put("id",id);out.put(item);
        }return out;
    }
    /** Stable check identities live in the existing JSON, without a schema migration. */
    synchronized JSONArray notes() throws JSONException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            JSONArray notes=rows("SELECT * FROM notes ORDER BY updated DESC,id");
            for(int i=0;i<notes.length();i++){JSONObject note=notes.getJSONObject(i);JSONArray checks=checkIds(new JSONArray(note.getString("checks")),new JSONArray());String normalized=checks.toString();
                if(!normalized.equals(note.getString("checks"))){ContentValues v=new ContentValues();v.put("checks",normalized);db.update("notes",v,"id=?",new String[]{note.getString("id")});note.put("checks",normalized);}
            }db.setTransactionSuccessful();return notes;
        }finally{db.endTransaction();}
    }
    private static Map<String,JSONObject> checksById(JSONArray checks) throws JSONException {Map<String,JSONObject> map=new LinkedHashMap<>();for(int i=0;i<checks.length();i++){JSONObject item=checks.getJSONObject(i);map.put(item.optString("id"),item);}return map;}
    private static JSONArray mergeChecks(JSONArray desired,JSONArray base,JSONArray current) throws JSONException {
        Map<String,JSONObject> before=checksById(base),latest=checksById(current);JSONArray merged=new JSONArray();Set<String> included=new HashSet<>();
        for(int i=0;i<desired.length();i++){
            JSONObject edit=desired.getJSONObject(i),old=before.get(edit.getString("id")),now=latest.get(edit.getString("id"));
            if(old!=null&&now==null)continue; // a stale editor cannot resurrect a deleted item
            JSONObject item=new JSONObject((now==null?edit:now).toString());
            if(old==null||!edit.optString("text").equals(old.optString("text")))item.put("text",edit.optString("text"));
            if(old==null||edit.optBoolean("done")!=old.optBoolean("done"))item.put("done",edit.optBoolean("done"));
            merged.put(item);included.add(edit.getString("id"));
        }
        for(int i=0;i<current.length();i++){JSONObject item=current.getJSONObject(i);String id=item.getString("id");if(!before.containsKey(id)&&!included.contains(id))merged.put(item);}
        return merged;
    }
    synchronized JSONObject note(JSONObject n) throws JSONException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            String id=n.optString("id",UUID.randomUUID().toString());JSONArray found=rows("SELECT * FROM notes WHERE id=?",id);JSONObject current=found.length()==0?null:found.getJSONObject(0),base=n.optJSONObject("base");
            if(base!=null&&!base.optString("id").isEmpty()&&current==null)throw new IllegalStateException("삭제된 메모입니다. 새 메모로 작성해 주세요.");
            JSONArray latest=current==null?new JSONArray():new JSONArray(current.getString("checks"));JSONArray checks=checkIds(n.optJSONArray("checks")==null?new JSONArray():n.getJSONArray("checks"),latest);
            ContentValues v=new ContentValues();v.put("id",id);
            for(String field:new String[]{"title","body","mode"}){
                String value=n.optString(field,field.equals("title")?"메모":field.equals("mode")?"text":"");
                if(base!=null&&current!=null&&value.equals(base.optString(field)))value=current.optString(field);
                if(field.equals("mode"))value=value.equals("check")?"check":"text";
                v.put(field,value);
            }
            if(base!=null&&current!=null){
                if(!current.optString("mode").equals(base.optString("mode")))throw new IllegalStateException("메모 종류가 변경되었습니다. 메모를 다시 열어 주세요.");
                JSONArray before=base.optJSONArray("checks");if(before!=null&&n.optString("mode").equals(base.optString("mode")))checks=mergeChecks(checks,before,latest);
            }
            v.put("checks",checks.toString());v.put("updated",System.currentTimeMillis());put("notes",v);db.setTransactionSuccessful();
            return rows("SELECT * FROM notes WHERE id=?",id).getJSONObject(0);
        }finally{db.endTransaction();}
    }
    /** Compare-and-set only this done bit, against the latest authoritative row. */
    synchronized boolean toggleNoteCheck(String noteId,int index,String itemId,String text,boolean expected) throws JSONException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            JSONArray found=rows("SELECT * FROM notes WHERE id=?",noteId);if(found.length()==0)return false;JSONObject note=found.getJSONObject(0);
            if(!"check".equals(note.optString("mode")))return false;JSONArray checks=new JSONArray(note.getString("checks"));if(index<0||index>=checks.length()||itemId==null||text==null||itemId.isEmpty())return false;
            JSONObject item=checks.getJSONObject(index);if(!itemId.equals(item.optString("id"))||!text.equals(item.optString("text"))||item.optBoolean("done")!=expected)return false;
            item.put("done",!expected);ContentValues v=new ContentValues();v.put("checks",checks.toString());v.put("updated",System.currentTimeMillis());db.update("notes",v,"id=?",new String[]{noteId});db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    synchronized String newChat() {String id=UUID.randomUUID().toString();ContentValues v=new ContentValues();v.put("id",id);v.put("title","새 대화");v.put("updated",System.currentTimeMillis());put("chats",v);return id;}
    private void createQuickRequests(SQLiteDatabase db){db.execSQL("CREATE TABLE quick_requests(request TEXT PRIMARY KEY,chat TEXT NOT NULL,user INTEGER NOT NULL,reply INTEGER NOT NULL)");}
    synchronized JSONObject quickRequest(String request,String text) throws Exception {
        JSONArray found=rows("SELECT * FROM quick_requests WHERE request=?",request);if(found.length()==0)return null;
        JSONObject result=found.getJSONObject(0);JSONArray user=rows("SELECT body FROM messages WHERE id=? AND chat=?",""+result.getLong("user"),result.getString("chat"));
        if(user.length()==0)throw new java.io.IOException("삭제된 대화입니다. 새 질문을 시작해 주세요.");
        if(!text.equals(user.getJSONObject(0).getString("body")))throw new java.io.IOException("다른 질문에 같은 전송 번호를 사용할 수 없습니다.");
        return new JSONObject().put("id",result.getString("chat")).put("user",result.getLong("user")).put("reply",result.getLong("reply"));
    }
    synchronized JSONObject acceptQuickQuestion(String request,String text) throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            JSONObject existing=quickRequest(request,text);if(existing!=null){db.setTransactionSuccessful();return existing;}
            ContentValues chat=new ContentValues();chat.put("id",request);chat.put("title","새 대화");chat.put("updated",System.currentTimeMillis());db.insertOrThrow("chats",null,chat);
            long user=message(request,"user",text,"complete"),reply=message(request,"assistant","","streaming");
            ContentValues entry=new ContentValues();entry.put("request",request);entry.put("chat",request);entry.put("user",user);entry.put("reply",reply);db.insertOrThrow("quick_requests",null,entry);
            JSONObject result=new JSONObject().put("id",request).put("user",user).put("reply",reply);db.setTransactionSuccessful();return result;
        }finally{db.endTransaction();}
    }
    synchronized long appendTurn(String chat,String text){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{message(chat,"user",text,"complete");long reply=message(chat,"assistant","","streaming");db.setTransactionSuccessful();return reply;}
        finally{db.endTransaction();}
    }
    synchronized JSONArray messages(String chat) throws JSONException {return rows("SELECT m.*,q.request AS quickRequest FROM messages m LEFT JOIN quick_requests q ON q.reply=m.id WHERE m.chat=? ORDER BY m.id",chat);}
    synchronized long message(String chat,String role,String body,String status) {
        ContentValues v=new ContentValues();v.put("chat",chat);v.put("role",role);v.put("body",body);v.put("status",status);v.put("time",System.currentTimeMillis());long id=getWritableDatabase().insertOrThrow("messages",null,v);
        ContentValues title=new ContentValues();title.put("updated",System.currentTimeMillis());if(role.equals("user")) {try {if(rows("SELECT id FROM messages WHERE chat=? AND role='user'",chat).length()==1)title.put("title",body.substring(0,Math.min(30,body.length())));}catch(JSONException ignored){}}
        getWritableDatabase().update("chats",title,"id=?",new String[]{chat});return id;
    }
    synchronized void updateMessage(long id,String text,String status) {ContentValues v=new ContentValues();v.put("body",text);v.put("status",status);getWritableDatabase().update("messages",v,"id=?",new String[]{""+id});}
    synchronized void citations(long id,JSONArray links){ContentValues v=new ContentValues();v.put("citations",links.toString());getWritableDatabase().update("messages",v,"id=?",new String[]{""+id});}
    synchronized void deleteChat(String id) {SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try {d.delete("quick_requests","chat=?",new String[]{id});d.delete("messages","chat=?",new String[]{id});d.delete("chats","id=?",new String[]{id});d.setTransactionSuccessful();}finally{d.endTransaction();}}
    boolean managed(long time) {return settings.getBoolean("enabled",true)&&Rules.managed(Rules.minute(time,ZoneId.systemDefault()),settings.getInt("start",540),settings.getInt("end",1260));}
    JSONObject preferences() throws JSONException {return new JSONObject().put("enabled",settings.getBoolean("enabled",true)).put("floatingTimer",settings.getBoolean("floatingTimer",true)).put("start",settings.getInt("start",540)).put("end",settings.getInt("end",1260)).put("reminders",new JSONArray(settings.getString("reminders","[30,20,10,5,1]"))).put("dailyBrief",settings.getBoolean("dailyBrief",true)).put("dailyBriefTime",settings.getInt("dailyBriefTime",540)).put("focusBrief",settings.getBoolean("focusBrief",true)).put("weeklyDay",settings.getInt("weeklyDay",7)).put("model",settings.getString("model","")).put("themeMode",UiTheme.mode(context));}
    synchronized void cleanOldUsage() {
        long cutoff=System.currentTimeMillis()-180L*86400000;SQLiteDatabase db=getWritableDatabase();
        db.delete("service_observations","time<?",new String[]{""+cutoff});
        if(db.delete("usage","end<?",new String[]{""+cutoff})>0)++usageRevision;
        db.delete("events","time<?",new String[]{""+cutoff});
        // Keep the last pre-retention mark for each evidence type, including UNKNOWN.
        db.execSQL("DELETE FROM screen_states WHERE time<? AND id NOT IN (SELECT (SELECT latest.id FROM screen_states latest WHERE latest.type=types.type AND latest.time<? ORDER BY latest.time DESC,latest.id DESC LIMIT 1) FROM (SELECT DISTINCT type FROM screen_states WHERE time<?) types)",new Object[]{cutoff,cutoff,cutoff});
        db.execSQL("DELETE FROM quick_requests WHERE NOT EXISTS (SELECT 1 FROM chats WHERE chats.id=quick_requests.chat)");
    }
}
