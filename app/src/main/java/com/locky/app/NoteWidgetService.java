package com.locky.app;

import android.appwidget.AppWidgetManager;
import android.content.*;
import android.graphics.Paint;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Bound only by the launcher while the collection is needed. */
public final class NoteWidgetService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent){return new Factory(getApplicationContext(),intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1));}
    static final class Factory implements RemoteViewsFactory {
        final Context context;final int widget;final List<String> text=new ArrayList<>();final List<Boolean> checked=new ArrayList<>();final List<String> ids=new ArrayList<>();String noteId="";boolean checklist;
        Factory(Context c,int id){context=c;widget=id;}
        public void onCreate(){onDataSetChanged();}
        public void onDataSetChanged(){
            text.clear();checked.clear();ids.clear();noteId="";checklist=false;
            try{JSONObject note=NoteWidget.selected(context,widget);if(note==null)return;noteId=note.getString("id");checklist="check".equals(note.optString("mode"));
                if(checklist){JSONArray checks=new JSONArray(note.optString("checks","[]"));for(int n=0;n<checks.length();n++){JSONObject item=checks.getJSONObject(n);text.add(item.optString("text"));checked.add(item.optBoolean("done"));ids.add(item.getString("id"));}}
                else if(!note.optString("body").isEmpty())for(String line:note.optString("body").split("\n",-1)){text.add(line.isEmpty()?" ":line);checked.add(false);}
            }catch(JSONException e){android.util.Log.e("LockyWidget","Cannot read memo rows",e);}
        }
        public RemoteViews getViewAt(int position){
            if(position<0||position>=text.size())return null;
            RemoteViews row=new RemoteViews(context.getPackageName(),R.layout.note_widget_row);
            boolean done=checked.get(position);row.setTextViewText(R.id.widget_row_text,text.get(position));
            row.setViewVisibility(R.id.widget_check_target,checklist?View.VISIBLE:View.GONE);
            row.setImageViewResource(R.id.widget_row_check,done?R.drawable.widget_check_done:R.drawable.widget_check_empty);
            if(checklist){Intent check=new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widget).putExtra("note",noteId).putExtra("index",position).putExtra("item",ids.get(position)).putExtra("text",text.get(position)).putExtra("expected",done);
                row.setOnClickFillInIntent(R.id.widget_check_target,check);row.setContentDescription(R.id.widget_check_target,text.get(position)+(done?" · 완료 해제":" · 완료로 표시"));}
            row.setInt(R.id.widget_row_text,"setPaintFlags",Paint.ANTI_ALIAS_FLAG|(done?Paint.STRIKE_THRU_TEXT_FLAG:0));return row;
        }
        public int getCount(){return text.size();}public long getItemId(int p){return p;}public boolean hasStableIds(){return false;}public int getViewTypeCount(){return 1;}public RemoteViews getLoadingView(){return null;}public void onDestroy(){text.clear();checked.clear();ids.clear();}
    }
}
