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
    private Store(Context c) { super(c,"locky.db",null,1); settings=c.getSharedPreferences("locky-settings",0);setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE purposes(id TEXT PRIMARY KEY,label TEXT NOT NULL,pkg TEXT NOT NULL DEFAULT '',uri TEXT NOT NULL DEFAULT '',aimless INTEGER NOT NULL DEFAULT 0,scope TEXT NOT NULL DEFAULT '*')");
        db.execSQL("CREATE TABLE rules(pkg TEXT PRIMARY KEY,label TEXT NOT NULL,mode TEXT NOT NULL,minutes INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,kind TEXT NOT NULL,purpose TEXT NOT NULL,pkg TEXT NOT NULL,target TEXT NOT NULL,parent INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE usage(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER NOT NULL,end INTEGER NOT NULL,pkg TEXT NOT NULL,label TEXT NOT NULL,managed INTEGER NOT NULL,parent INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX usage_time ON usage(start,end)");db.execSQL("CREATE INDEX events_time ON events(time)");
        db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL,checks TEXT NOT NULL,updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat TEXT NOT NULL,role TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,time INTEGER NOT NULL)");
        String[][] defaults={{"contact","연락 확인","0"},{"lookup","궁금한 것 찾기","0"},{"task","할 일 처리","0"},{"rest","잠깐 쉬기","0"},{"aimless","그냥 열었어요","1"}};
        for(String[] p:defaults)db.execSQL("INSERT INTO purposes(id,label,aimless) VALUES(?,?,?)",new Object[]{p[0],p[1],Integer.parseInt(p[2])});
    }
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next) { throw new IllegalStateException("Missing migration"); }
    synchronized JSONArray rows(String sql,String...args) throws JSONException {
        JSONArray out=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery(sql,args)) {while(c.moveToNext()){JSONObject o=new JSONObject();for(int i=0;i<c.getColumnCount();i++){int type=c.getType(i);o.put(c.getColumnName(i),type==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):type==Cursor.FIELD_TYPE_NULL?JSONObject.NULL:c.getString(i));}out.put(o);}}return out;
    }
    synchronized void put(String table,ContentValues values) { getWritableDatabase().insertWithOnConflict(table,null,values,SQLiteDatabase.CONFLICT_REPLACE); }
    synchronized long event(String kind,String purpose,String pkg,String target,long parent) {
        ContentValues v=new ContentValues();v.put("time",System.currentTimeMillis());v.put("kind",kind);v.put("purpose",purpose);v.put("pkg",pkg);v.put("target",target);v.put("parent",parent);return getWritableDatabase().insertOrThrow("events",null,v);
    }
    synchronized void usage(long from,long to,String pkg,String label,boolean managed,long parent) {
        if(to<=from||pkg.isEmpty())return;
        SQLiteDatabase d=getWritableDatabase();
        try(Cursor c=d.rawQuery("SELECT id,end,pkg,label,managed,parent FROM usage ORDER BY id DESC LIMIT 1",null)) {
            if(c.moveToFirst()&&c.getLong(1)==from&&pkg.equals(c.getString(2))&&label.equals(c.getString(3))&&c.getInt(4)==(managed?1:0)&&c.getLong(5)==parent){ContentValues update=new ContentValues();update.put("end",to);d.update("usage",update,"id=?",new String[]{""+c.getLong(0)});return;}
        }
        ContentValues v=new ContentValues();v.put("start",from);v.put("end",to);v.put("pkg",pkg);v.put("label",label);v.put("managed",managed?1:0);v.put("parent",parent);d.insertOrThrow("usage",null,v);
    }
    synchronized long used(String pkg,long now) {
        long start=Rules.dayStart(now,ZoneId.systemDefault()),total=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT start,end FROM usage WHERE pkg=? AND managed=1 AND end>? AND start<?",new String[]{pkg,""+start,""+now})) {while(c.moveToNext())total+=Math.max(0,Math.min(now,c.getLong(1))-Math.max(start,c.getLong(0)));}return total;
    }
    JSONObject rule(String pkg) {try{JSONArray a=rows("SELECT * FROM rules WHERE pkg=?",pkg);return a.length()==0?null:a.getJSONObject(0);}catch(JSONException e){throw new IllegalStateException(e);} }
    synchronized JSONArray purposes(String scope) throws JSONException {
        JSONArray rows=rows("SELECT * FROM purposes WHERE scope='*' OR scope=? ORDER BY rowid",scope);Map<String,JSONObject> byId=new LinkedHashMap<>();for(int i=0;i<rows.length();i++)byId.put(rows.getJSONObject(i).getString("id"),rows.getJSONObject(i));
        JSONArray out=new JSONArray();for(String id:Rules.shuffle(new ArrayList<>(byId.keySet()),LocalDate.now().toString(),scope))out.put(byId.get(id));return out;
    }
    synchronized JSONObject purpose(JSONObject input,String scope) throws JSONException {
        String label=input.optString("label").trim();if(label.isEmpty()||label.length()>100)throw new JSONException("목적을 1~100자로 입력해 주세요.");
        String id=input.optString("id",UUID.randomUUID().toString());if(id.equals("aimless")){label="그냥 열었어요";scope="*";}
        ContentValues v=new ContentValues();v.put("id",id);v.put("label",label);v.put("pkg",input.optString("pkg"));v.put("uri",input.optString("uri"));v.put("scope",scope);v.put("aimless",id.equals("aimless")?1:0);put("purposes",v);return rows("SELECT * FROM purposes WHERE id=?",id).getJSONObject(0);
    }
    synchronized void note(JSONObject n) throws JSONException {
        ContentValues v=new ContentValues();v.put("id",n.optString("id",UUID.randomUUID().toString()));v.put("title",n.optString("title","메모"));v.put("body",n.optString("body"));v.put("checks",n.optJSONArray("checks")==null?"[]":n.getJSONArray("checks").toString());v.put("updated",System.currentTimeMillis());put("notes",v);
    }
    synchronized String newChat() {String id=UUID.randomUUID().toString();ContentValues v=new ContentValues();v.put("id",id);v.put("title","새 대화");v.put("updated",System.currentTimeMillis());put("chats",v);return id;}
    synchronized long message(String chat,String role,String body,String status) {
        ContentValues v=new ContentValues();v.put("chat",chat);v.put("role",role);v.put("body",body);v.put("status",status);v.put("time",System.currentTimeMillis());long id=getWritableDatabase().insertOrThrow("messages",null,v);
        ContentValues title=new ContentValues();title.put("updated",System.currentTimeMillis());if(role.equals("user")) {try {if(rows("SELECT id FROM messages WHERE chat=? AND role='user'",chat).length()==1)title.put("title",body.substring(0,Math.min(30,body.length())));}catch(JSONException ignored){}}
        getWritableDatabase().update("chats",title,"id=?",new String[]{chat});return id;
    }
    synchronized void updateMessage(long id,String text,String status) {ContentValues v=new ContentValues();v.put("body",text);v.put("status",status);getWritableDatabase().update("messages",v,"id=?",new String[]{""+id});}
    synchronized void deleteChat(String id) {SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try {d.delete("messages","chat=?",new String[]{id});d.delete("chats","id=?",new String[]{id});d.setTransactionSuccessful();}finally{d.endTransaction();}}
    boolean managed(long time) {return settings.getBoolean("enabled",true)&&Rules.managed(Rules.minute(time,ZoneId.systemDefault()),settings.getInt("start",540),settings.getInt("end",1260));}
    JSONObject preferences() throws JSONException {return new JSONObject().put("enabled",settings.getBoolean("enabled",true)).put("start",settings.getInt("start",540)).put("end",settings.getInt("end",1260)).put("reminders",new JSONArray(settings.getString("reminders","[30,20,10,5,1]"))).put("weeklyDay",settings.getInt("weeklyDay",7)).put("model",settings.getString("model",""));}
    synchronized void cleanOldUsage() {long cutoff=System.currentTimeMillis()-180L*86400000;getWritableDatabase().delete("usage","end<?",new String[]{""+cutoff});getWritableDatabase().delete("events","time<?",new String[]{""+cutoff});}
}
