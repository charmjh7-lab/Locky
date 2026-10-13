package com.locky.app;

import android.content.*;
import android.net.Uri;

final class Links {
    static Intent intent(Context c,String pkg,String uri) {
        if(pkg.equals(c.getPackageName()))return null;
        Intent i;if(uri.isEmpty())i=c.getPackageManager().getLaunchIntentForPackage(pkg);
        else {Uri u=Uri.parse(uri);String scheme=u.getScheme();if(scheme==null||scheme.equals("file")||scheme.equals("content")||scheme.equals("javascript")||scheme.equals("intent"))return null;i=new Intent(Intent.ACTION_VIEW,u);if(!pkg.isEmpty())i.setPackage(pkg);}
        return i!=null&&i.resolveActivity(c.getPackageManager())!=null?i:null;
    }
    static org.json.JSONArray dictionaries(Context c) throws org.json.JSONException {
        org.json.JSONArray out=new org.json.JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();
        Intent process=new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain");
        for(android.content.pm.ResolveInfo app:c.getPackageManager().queryIntentActivities(process,0)) {
            ComponentName component=new ComponentName(app.activityInfo.packageName,app.activityInfo.name);String id=component.flattenToString();
            if(seen.add(id))out.put(new org.json.JSONObject().put("id",id).put("label",app.loadLabel(c.getPackageManager()).toString()));
        }
        String samsung="com.diotek.sec.lookup.dictionary";
        if(c.getPackageManager().getLaunchIntentForPackage(samsung)!=null)out.put(new org.json.JSONObject().put("id","launch:"+samsung).put("label","삼성 사전"));
        out.put(new org.json.JSONObject().put("id","naver").put("label","네이버 사전"));return out;
    }
    static void dictionary(Context c,String word) throws Exception {
        String choice=Store.get(c).settings.getString("dictionary","");
        if(choice.isEmpty())throw new IllegalStateException("사전을 한 번 선택해 주세요.");
        boolean valid=false;org.json.JSONArray choices=dictionaries(c);for(int j=0;j<choices.length();j++)if(choices.getJSONObject(j).getString("id").equals(choice))valid=true;
        if(!valid){Store.get(c).settings.edit().remove("dictionary").apply();throw new IllegalStateException("선택한 사전이 없어졌어요. 사전을 다시 선택해 주세요.");}
        Intent intent;
        if(choice.equals("naver")){
            intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://dict.naver.com/search?query="+Uri.encode(word)));
            android.content.pm.ResolveInfo handler=c.getPackageManager().resolveActivity(intent,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if(handler!=null&&!handler.activityInfo.packageName.equals("android"))intent.setPackage(handler.activityInfo.packageName);
            else {java.util.List<android.content.pm.ResolveInfo> list=c.getPackageManager().queryIntentActivities(intent,0);if(!list.isEmpty())intent.setPackage(list.get(0).activityInfo.packageName);}
        }else if(choice.startsWith("launch:")){intent=c.getPackageManager().getLaunchIntentForPackage(choice.substring(7));if(intent==null)throw new IllegalStateException("사전을 열 수 없어요.");intent.putExtra(Intent.EXTRA_TEXT,word);}
        else intent=new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setComponent(ComponentName.unflattenFromString(choice)).putExtra(Intent.EXTRA_PROCESS_TEXT,word).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,true);
        c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
