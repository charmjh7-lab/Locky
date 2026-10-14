package com.locky.app;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import org.json.*;
import java.util.*;

final class Links {
    static Intent intent(Context c,String pkg,String uri) {
        if(pkg.equals(c.getPackageName()))return null;
        Intent i;if(uri.isEmpty())i=c.getPackageManager().getLaunchIntentForPackage(pkg);
        else {Uri u=Uri.parse(uri);String scheme=u.getScheme();if(scheme==null||scheme.equals("file")||scheme.equals("content")||scheme.equals("javascript")||scheme.equals("intent"))return null;i=new Intent(Intent.ACTION_VIEW,u);if(!pkg.isEmpty())i.setPackage(pkg);}
        return i!=null&&i.resolveActivity(c.getPackageManager())!=null?i:null;
    }
    static Intent process(String word){return new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT,word).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,true);}
    static Intent web(String word){return new Intent(Intent.ACTION_VIEW,Uri.parse("https://dict.naver.com/search?query="+Uri.encode(word)));}
    static Intent search(String word){return new Intent(Intent.ACTION_SEARCH).setPackage("com.diotek.sec.lookup.dictionary").putExtra(SearchManager.QUERY,word);}
    static JSONArray dictionaries(Context c) throws JSONException {
        JSONArray out=new JSONArray();Set<String> seen=new HashSet<>();
        for(Intent base:List.of(process(""),search(""),web(""))){
            String type=base.getAction().equals(Intent.ACTION_PROCESS_TEXT)?"process:":base.getAction().equals(Intent.ACTION_SEARCH)?"search:":"web:";
            for(ResolveInfo app:c.getPackageManager().queryIntentActivities(base,PackageManager.MATCH_DEFAULT_ONLY)){
                if(!app.activityInfo.exported||app.activityInfo.packageName.equals(c.getPackageName()))continue;
                ComponentName component=new ComponentName(app.activityInfo.packageName,app.activityInfo.name);String id=type+component.flattenToString();
                if(seen.add(component.flattenToString()))out.put(new JSONObject().put("id",id).put("component",component.flattenToString()).put("label",app.loadLabel(c.getPackageManager()).toString()));
            }
        }return out;
    }
    static Intent dictionaryIntent(Context c,String choice,String word) throws Exception {
        JSONArray choices=dictionaries(c);String id=choice;
        if(choice.startsWith("launch:")){String pkg=choice.substring(7);id="";for(int j=0;j<choices.length();j++){String candidate=choices.getJSONObject(j).getString("id");if(!candidate.startsWith("web:")&&ComponentName.unflattenFromString(choices.getJSONObject(j).getString("component")).getPackageName().equals(pkg)){id=candidate;break;}}}
        if(!id.startsWith("process:")&&!id.startsWith("search:")&&!id.startsWith("web:"))id="process:"+id;
        // Daum's miniature dictionary is registered as PROCESS_TEXT.
        // Prefer it over an older selection pointing at the full dictionary.
        ComponentName requested=ComponentName.unflattenFromString(id.substring(id.indexOf(':')+1));
        if(requested!=null&&"net.daum.android.dictionary".equals(requested.getPackageName()))
            for(int j=0;j<choices.length();j++){String candidate=choices.getJSONObject(j).getString("id");
                if(candidate.startsWith("process:")&&candidate.contains("net.daum.android.dictionary")){id=candidate;break;}}
        boolean valid=false;for(int j=0;j<choices.length();j++)if(choices.getJSONObject(j).getString("id").equals(id))valid=true;
        if(!valid)return null;
        Intent i=id.startsWith("process:")?process(word):id.startsWith("search:")?search(word):web(word);
        i.setComponent(ComponentName.unflattenFromString(id.substring(id.indexOf(':')+1)));return i;
    }
    static void dictionaryPicker(Context c,String word) throws Exception {
        JSONArray choices=dictionaries(c);if(choices.length()==0)throw new ActivityNotFoundException("검색어를 전달할 수 있는 사전이나 브라우저가 없습니다.");
        ArrayList<Intent> types=new ArrayList<>();boolean hasProcess=false,hasSearch=false,hasWeb=false;
        for(int j=0;j<choices.length();j++){String id=choices.getJSONObject(j).getString("id");hasProcess|=id.startsWith("process:");hasSearch|=id.startsWith("search:");hasWeb|=id.startsWith("web:");}
        if(hasProcess)types.add(process(word));if(hasSearch)types.add(search(word));if(hasWeb)types.add(web(word));Intent primary=types.remove(0);
        PendingIntent picked=PendingIntent.getBroadcast(c,52,new Intent(c,DictionaryChoiceReceiver.class).setAction("com.locky.app.DICTIONARY_CHOSEN"),PendingIntent.FLAG_UPDATE_CURRENT|(android.os.Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));
        Intent chooser=Intent.createChooser(primary,"기본 사전 선택 · 다음부터 바로 검색",picked.getIntentSender());if(!types.isEmpty())chooser.putExtra(Intent.EXTRA_ALTERNATE_INTENTS,types.toArray(new Intent[0]));
        c.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    static int dictionaryFlags(Intent i) {
        int flags=Intent.FLAG_ACTIVITY_NEW_TASK;
        if(Intent.ACTION_PROCESS_TEXT.equals(i.getAction())&&i.getComponent()!=null&&"net.daum.android.dictionary".equals(i.getComponent().getPackageName())){
            // Daum's popup should not restore the dictionary home screen.
            flags|=Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
        }else if(!Intent.ACTION_VIEW.equals(i.getAction()))flags|=Intent.FLAG_ACTIVITY_CLEAR_TOP;
        return flags;
    }
    static void dictionary(Context c,String word) throws Exception {
        String choice=Store.get(c).settings.getString("dictionary","");Intent i=choice.isEmpty()?null:dictionaryIntent(c,choice,word);
        if(i==null){Store.get(c).settings.edit().remove("dictionary").apply();dictionaryPicker(c,word);return;}
        c.startActivity(i.addFlags(dictionaryFlags(i)));
    }
}
