package com.locky.app;
import android.content.*;
import org.json.*;
public final class DictionaryChoiceReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){ComponentName chosen=i.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT);if(chosen==null)return;try{JSONArray options=Links.dictionaries(c);for(int j=0;j<options.length();j++){JSONObject option=options.getJSONObject(j);if(chosen.flattenToString().equals(option.getString("component"))){String id=option.getString("id");
                        if("net.daum.android.dictionary".equals(chosen.getPackageName())){
                            for(int k=0;k<options.length();k++){String p=options.getJSONObject(k).getString("id");
                                if(p.startsWith("process:")&&p.contains("net.daum.android.dictionary")){id=p;break;}}
                        }
                        Store.get(c).settings.edit().putString("dictionary",id).apply();WebHost.refreshAll();return;}}}catch(Exception ignored){}}
}
