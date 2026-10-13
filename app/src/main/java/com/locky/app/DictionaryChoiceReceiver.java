package com.locky.app;
import android.content.*;
import org.json.*;
public final class DictionaryChoiceReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){ComponentName chosen=i.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT);if(chosen==null)return;try{JSONArray options=Links.dictionaries(c);for(int j=0;j<options.length();j++){JSONObject option=options.getJSONObject(j);if(chosen.flattenToString().equals(option.getString("component"))){Store.get(c).settings.edit().putString("dictionary",option.getString("id")).apply();WebHost.refreshAll();return;}}}catch(Exception ignored){}}
}
