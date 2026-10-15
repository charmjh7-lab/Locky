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
    static boolean isolatedDaumPopup(Intent i) {
        return Intent.ACTION_PROCESS_TEXT.equals(i.getAction())
            && i.getComponent()!=null
            && "net.daum.android.dictionary".equals(i.getComponent().getPackageName());
    }
    static int dictionaryFlags(Intent i) {
        int flags=Intent.FLAG_ACTIVITY_NEW_TASK;
        if(isolatedDaumPopup(i)){
            // Daum's popup should not restore the dictionary home screen.
            flags|=Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
        }else if(!Intent.ACTION_VIEW.equals(i.getAction()))flags|=Intent.FLAG_ACTIVITY_CLEAR_TOP;
        return flags;
    }
    static void dictionary(Context c,String word) throws Exception {
        String choice=Store.get(c).settings.getString("dictionary","");Intent i=choice.isEmpty()?null:dictionaryIntent(c,choice,word);
        if(i==null){Store.get(c).settings.edit().remove("dictionary").apply();throw new ActivityNotFoundException("사전을 다시 선택해 주세요.");}
        // Daum's popup must retain its isolated task flags; startActivityForResult
        // cannot be combined with NEW_TASK (Android immediately cancels its result).
        if(c instanceof DictionaryActivity && isolatedDaumPopup(i)) {
            // The service launch path is the one that displayed Daum's mini
            // dictionary correctly before the intermediate Activity regression.
            GuardService guard=((DictionaryActivity)c).guard;
            if(guard==null)throw new ActivityNotFoundException("로키 접근성 서비스를 확인해 주세요.");
            guard.startActivity(i.addFlags(dictionaryFlags(i)));
        } else if(c instanceof DictionaryActivity) {
            ((Activity)c).startActivityForResult(i,DictionaryActivity.REQUEST);
        } else c.startActivity(i.addFlags(dictionaryFlags(i)));
    }
}

