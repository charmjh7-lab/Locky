package com.locky.app;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import org.json.*;

/** Read-only, independently selected views of Store.notes; no copied note data. */
public final class NoteWidget extends AppWidgetProvider {
    static final String PREVIOUS="com.locky.app.NOTE_PREVIOUS",NEXT="com.locky.app.NOTE_NEXT";
    static String key(int id){return "note-widget:"+id;}
    static JSONArray notes(Context c) throws JSONException {return Store.get(c).rows("SELECT * FROM notes ORDER BY updated DESC,id");}
    static JSONObject selected(Context c,int id) throws JSONException {
        Store s=Store.get(c);JSONArray notes=notes(c);String selected=s.settings.getString(key(id),"");
        if(selected.isEmpty()){
            JSONObject phone=new JSONObject(s.settings.getString("phone-start-session","{}"));
            selected=s.settings.getString("recent-note",phone.optString("note",s.settings.getString("last-note","")));
        }
        for(int n=0;n<notes.length();n++)if(selected.equals(notes.getJSONObject(n).optString("id")))return notes.getJSONObject(n);
        return notes.length()==0?null:notes.getJSONObject(0);
    }
    static void refreshAll(Context c){AppWidgetManager m=AppWidgetManager.getInstance(c);for(int id:m.getAppWidgetIds(new ComponentName(c,NoteWidget.class)))update(c,m,id);}
    @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids){for(int id:ids)update(c,m,id);}
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager m,int id,Bundle options){update(c,m,id);}
    @Override public void onDeleted(Context c,int[] ids){SharedPreferences.Editor e=Store.get(c).settings.edit();for(int id:ids)e.remove(key(id));e.apply();}
    @Override public void onRestored(Context c,int[] oldIds,int[] newIds){SharedPreferences p=Store.get(c).settings;SharedPreferences.Editor e=p.edit();for(int n=0;n<oldIds.length;n++){String note=p.getString(key(oldIds[n]),"");e.remove(key(oldIds[n])).putString(key(newIds[n]),note);}e.apply();AppWidgetManager m=AppWidgetManager.getInstance(c);for(int id:newIds){Bundle options=new Bundle();options.putBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED,true);m.updateAppWidgetOptions(id,options);}onUpdate(c,m,newIds);}
    @Override public void onReceive(Context c,Intent i){
        super.onReceive(c,i);if(!PREVIOUS.equals(i.getAction())&&!NEXT.equals(i.getAction()))return;
        int id=i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetManager m=AppWidgetManager.getInstance(c);AppWidgetProviderInfo info=m.getAppWidgetInfo(id);
        if(info==null||!new ComponentName(c,NoteWidget.class).equals(info.provider))return;
        try{JSONArray notes=notes(c);JSONObject current=selected(c,id);if(current!=null){int at=0;for(int n=0;n<notes.length();n++)if(notes.getJSONObject(n).getString("id").equals(current.getString("id")))at=n;int next=(at+(NEXT.equals(i.getAction())?1:-1)+notes.length())%notes.length();Store.get(c).settings.edit().putString(key(id),notes.getJSONObject(next).getString("id")).apply();}update(c,m,id);}catch(JSONException e){android.util.Log.e("LockyWidget","Cannot select memo",e);}
    }
    static void update(Context c,AppWidgetManager m,int id){
        try{
            JSONObject note=selected(c,id);String noteId=note==null?"":note.getString("id");Store.get(c).settings.edit().putString(key(id),noteId).apply();
            RemoteViews views=new RemoteViews(c.getPackageName(),R.layout.note_widget);
            views.setTextViewText(R.id.widget_title,note==null?"Locky 메모":note.optString("title","메모"));
            views.setTextViewText(R.id.widget_empty,note==null?"아직 메모가 없어요. 눌러서 메모를 만들어 보세요.":"빈 메모");
            Intent open=MainActivity.launchIntent(c).putExtra("screen","notes").putExtra("note",noteId).setData(Uri.parse("locky://memo-widget/"+id+"/"+Uri.encode(noteId)));
            PendingIntent edit=PendingIntent.getActivity(c,id,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            for(int target:new int[]{R.id.widget_title,R.id.widget_expand,R.id.widget_empty})views.setOnClickPendingIntent(target,edit);
            for(int target:new int[]{R.id.widget_previous,R.id.widget_next}){
                String action=target==R.id.widget_previous?PREVIOUS:NEXT;
                Intent move=new Intent(c,NoteWidget.class).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).setData(Uri.parse("locky://memo-widget/"+id+"/"+action));
                views.setOnClickPendingIntent(target,PendingIntent.getBroadcast(c,id,move,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
                views.setViewVisibility(target,notes(c).length()>1?View.VISIBLE:View.GONE);
            }
            // Adapter identity includes both widget and note. Resize uses the host's real
            // available bounds; the weighted ListView gains both width and height.
            Intent adapter=new Intent(c,NoteWidgetService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).setData(Uri.parse("locky://memo-list/"+id+"/"+Uri.encode(noteId)));
            views.setRemoteAdapter(R.id.widget_list,adapter);views.setEmptyView(R.id.widget_list,R.id.widget_empty);
            m.updateAppWidget(id,views);m.notifyAppWidgetViewDataChanged(id,R.id.widget_list);
        }catch(JSONException e){android.util.Log.e("LockyWidget","Cannot load memo",e);}
    }
}
