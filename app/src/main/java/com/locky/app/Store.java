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
    final Context context;
    private Store(Context c) { super(c,"locky.db",null,2);context=c; settings=c.getSharedPreferences("locky-settings",0);if(!settings.contains("usage-excluded"))settings.edit().putStringSet("usage-excluded",new HashSet<>(Set.of("com.samsung.android.sidegesturepad"))).apply();setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE purposes(id TEXT PRIMARY KEY,label TEXT NOT NULL,pkg TEXT NOT NULL DEFAULT '',uri TEXT NOT NULL DEFAULT '',aimless INTEGER NOT NULL DEFAULT 0,scope TEXT NOT NULL DEFAULT '*')");
        db.execSQL("CREATE TABLE rules(pkg TEXT PRIMARY KEY,label TEXT NOT NULL,mode TEXT NOT NULL,minutes INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,kind TEXT NOT NULL,purpose TEXT NOT NULL,pkg TEXT NOT NULL,target TEXT NOT NULL,parent INTEGER NOT NULL DEFAULT 0,aimless INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE usage(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER NOT NULL,end INTEGER NOT NULL,pkg TEXT NOT NULL,label TEXT NOT NULL,managed INTEGER NOT NULL,parent INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX usage_time ON usage(start,end)");db.execSQL("CREATE INDEX events_time ON events(time)");
        db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL,checks TEXT NOT NULL,updated INTEGER NOT NULL,mode TEXT NOT NULL DEFAULT 'text')");
        db.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat TEXT NOT NULL,role TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,time INTEGER NOT NULL,citations TEXT NOT NULL DEFAULT '[]')");
        seedPurposes(db);
    }
    private void seedPurposes(SQLiteDatabase db) {
        String[][] phone={{"contact","연락 확인","0"},{"lookup","궁금한 것 찾기","0"},{"task","할 일 처리","0"},{"rest","잠깐 쉬기","0"},{"aimless","그냥 열었어요","1"}};
        for(String[] p:phone)db.execSQL("INSERT OR IGNORE INTO purposes(id,label,aimless,scope) VALUES(?,?,?,'phone')",new Object[]{p[0],p[1],Integer.parseInt(p[2])});
        String[][] apps={{"app-contact","메시지 확인","0"},{"app-watch","보고 싶은 콘텐츠","0"},{"app-read","필요한 정보 찾기","0"},{"app-rest","잠깐 쉬기","0"},{"app-aimless","그냥 열었어요","1"}};
        for(String[] p:apps)db.execSQL("INSERT OR IGNORE INTO purposes(id,label,aimless,scope) VALUES(?,?,?,'app')",new Object[]{p[0],p[1],Integer.parseInt(p[2])});
    }
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next) {
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
    synchronized JSONArray rows(String sql,String...args) throws JSONException {
        JSONArray out=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery(sql,args)) {while(c.moveToNext()){JSONObject o=new JSONObject();for(int i=0;i<c.getColumnCount();i++){int type=c.getType(i);o.put(c.getColumnName(i),type==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):type==Cursor.FIELD_TYPE_NULL?JSONObject.NULL:c.getString(i));}out.put(o);}}return out;
    }
    synchronized void put(String table,ContentValues values) { getWritableDatabase().insertWithOnConflict(table,null,values,SQLiteDatabase.CONFLICT_REPLACE); }
    synchronized long event(String kind,String purpose,String pkg,String target,long parent) {
        return event(kind,purpose,pkg,target,parent,false);
    }
    synchronized long event(String kind,String purpose,String pkg,String target,long parent,boolean aimless) {
        ContentValues v=new ContentValues();v.put("aimless",aimless?1:0);v.put("time",System.currentTimeMillis());v.put("kind",kind);v.put("purpose",purpose);v.put("pkg",pkg);v.put("target",target);v.put("parent",parent);return getWritableDatabase().insertOrThrow("events",null,v);
    }
    synchronized void usage(long from,long to,String pkg,String label,boolean managed,long parent) {
        if(to<=from||pkg.isEmpty()||UsageReader.excluded(context,pkg))return;
        // Accessibility observation owns a span. Recovery fills only uncovered gaps,
        // even when an overlay/system pause was recorded under another package.
        long cursor=from;List<long[]> gaps=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT start,end FROM usage WHERE end>? AND start<? ORDER BY start",new String[]{""+from,""+to})) {
            while(c.moveToNext()){long a=c.getLong(0),b=c.getLong(1);if(a>cursor)gaps.add(new long[]{cursor,Math.min(a,to)});cursor=Math.max(cursor,b);if(cursor>=to)break;}
        }
        if(cursor<to)gaps.add(new long[]{cursor,to});
        for(long[] gap:gaps)appendUsage(gap[0],gap[1],pkg,label,managed,parent);
    }
    private void appendUsage(long from,long to,String pkg,String label,boolean managed,long parent) {
        SQLiteDatabase d=getWritableDatabase();
        try(Cursor c=d.rawQuery("SELECT id,end,pkg,label,managed,parent FROM usage ORDER BY id DESC LIMIT 1",null)) {
            if(c.moveToFirst()&&c.getLong(1)==from&&pkg.equals(c.getString(2))&&label.equals(c.getString(3))&&c.getInt(4)==(managed?1:0)&&c.getLong(5)==parent){ContentValues update=new ContentValues();update.put("end",to);d.update("usage",update,"id=?",new String[]{""+c.getLong(0)});return;}
        }
        ContentValues v=new ContentValues();v.put("start",from);v.put("end",to);v.put("pkg",pkg);v.put("label",label);v.put("managed",managed?1:0);v.put("parent",parent);d.insertOrThrow("usage",null,v);
    }
    synchronized long used(String pkg,long now) {
        if(UsageReader.excluded(context,pkg))return 0;
        long start=Rules.dayStart(now,ZoneId.systemDefault()),total=0,cursor=start;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT start,end FROM usage WHERE pkg=? AND managed=1 AND end>? AND start<? ORDER BY start",new String[]{pkg,""+start,""+now})) {while(c.moveToNext()){long a=Math.max(cursor,c.getLong(0)),b=Math.min(now,c.getLong(1));total+=Math.max(0,b-a);cursor=Math.max(cursor,b);}}return total;
    }
    long remaining(String pkg,long now) {
        JSONObject rule=rule(pkg);if(rule==null)return 0;
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
        JSONArray rows=rows("SELECT * FROM purposes WHERE scope=? OR scope=? ORDER BY rowid",scope,scope.equals("phone")?"phone":"app");Map<String,JSONObject> byId=new LinkedHashMap<>();for(int i=0;i<rows.length();i++)byId.put(rows.getJSONObject(i).getString("id"),rows.getJSONObject(i));
        JSONArray out=new JSONArray();for(String id:Rules.shuffle(new ArrayList<>(byId.keySet()),LocalDate.now().toString(),scope))out.put(byId.get(id));return out;
    }
    synchronized JSONObject purpose(JSONObject input,String scope) throws JSONException {
        String label=input.optString("label").trim();if(label.isEmpty()||label.length()>100)throw new JSONException("목적을 1~100자로 입력해 주세요.");
        String id=input.optString("id",UUID.randomUUID().toString());boolean aimless=input.optBoolean("aimless",id.equals("aimless")||id.equals("app-aimless"));
        ContentValues v=new ContentValues();v.put("id",id);v.put("label",label);v.put("pkg",input.optString("pkg"));v.put("uri",input.optString("uri"));v.put("scope",scope);v.put("aimless",aimless?1:0);put("purposes",v);return rows("SELECT * FROM purposes WHERE id=?",id).getJSONObject(0);
    }
    synchronized void note(JSONObject n) throws JSONException {
        ContentValues v=new ContentValues();v.put("id",n.optString("id",UUID.randomUUID().toString()));v.put("title",n.optString("title","메모"));v.put("body",n.optString("body"));v.put("checks",n.optJSONArray("checks")==null?"[]":n.getJSONArray("checks").toString());v.put("mode",n.optString("mode","text").equals("check")?"check":"text");v.put("updated",System.currentTimeMillis());put("notes",v);
    }
    synchronized String newChat() {String id=UUID.randomUUID().toString();ContentValues v=new ContentValues();v.put("id",id);v.put("title","새 대화");v.put("updated",System.currentTimeMillis());put("chats",v);return id;}
    synchronized long message(String chat,String role,String body,String status) {
        ContentValues v=new ContentValues();v.put("chat",chat);v.put("role",role);v.put("body",body);v.put("status",status);v.put("time",System.currentTimeMillis());long id=getWritableDatabase().insertOrThrow("messages",null,v);
        ContentValues title=new ContentValues();title.put("updated",System.currentTimeMillis());if(role.equals("user")) {try {if(rows("SELECT id FROM messages WHERE chat=? AND role='user'",chat).length()==1)title.put("title",body.substring(0,Math.min(30,body.length())));}catch(JSONException ignored){}}
        getWritableDatabase().update("chats",title,"id=?",new String[]{chat});return id;
    }
    synchronized void updateMessage(long id,String text,String status) {ContentValues v=new ContentValues();v.put("body",text);v.put("status",status);getWritableDatabase().update("messages",v,"id=?",new String[]{""+id});}
    synchronized void citations(long id,JSONArray links){ContentValues v=new ContentValues();v.put("citations",links.toString());getWritableDatabase().update("messages",v,"id=?",new String[]{""+id});}
    synchronized void deleteChat(String id) {SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try {d.delete("messages","chat=?",new String[]{id});d.delete("chats","id=?",new String[]{id});d.setTransactionSuccessful();}finally{d.endTransaction();}}
    boolean managed(long time) {return settings.getBoolean("enabled",true)&&Rules.managed(Rules.minute(time,ZoneId.systemDefault()),settings.getInt("start",540),settings.getInt("end",1260));}
    JSONObject preferences() throws JSONException {return new JSONObject().put("enabled",settings.getBoolean("enabled",true)).put("floatingTimer",settings.getBoolean("floatingTimer",true)).put("start",settings.getInt("start",540)).put("end",settings.getInt("end",1260)).put("reminders",new JSONArray(settings.getString("reminders","[30,20,10,5,1]"))).put("weeklyDay",settings.getInt("weeklyDay",7)).put("model",settings.getString("model",""));}
    synchronized void cleanOldUsage() {long cutoff=System.currentTimeMillis()-180L*86400000;getWritableDatabase().delete("usage","end<?",new String[]{""+cutoff});getWritableDatabase().delete("events","time<?",new String[]{""+cutoff});}
}
