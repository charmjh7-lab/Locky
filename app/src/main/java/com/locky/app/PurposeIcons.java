package com.locky.app;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.graphics.drawable.Drawable;
import org.json.*;
import java.util.*;
import java.util.function.Function;

/** Only linked phone purposes are resolved. Generic web URLs keep the link glyph. */
final class PurposeIcons {
    private static final class Entry {
        final long version,updated;final String image;
        Entry(PackageInfo info,String image){version=version(info);updated=info.lastUpdateTime;this.image=image;}
    }
    private final Map<String,Entry> cache=new HashMap<>();
    synchronized void invalidate(){cache.clear();}
    synchronized JSONObject forPurposes(Context context,JSONArray purposes,Function<Drawable,String> encode) throws JSONException {
        JSONObject icons=new JSONObject();Set<String> needed=new HashSet<>();PackageManager pm=context.getPackageManager();
        for(int i=0;i<purposes.length();i++){
            JSONObject purpose=purposes.getJSONObject(i);String pkg=linkedPackage(context,purpose);
            if(pkg.isEmpty())continue;needed.add(pkg);
            try{
                PackageInfo info=pm.getPackageInfo(pkg,0);Entry entry=cache.get(pkg);
                if(entry==null||entry.version!=version(info)||entry.updated!=info.lastUpdateTime){entry=new Entry(info,encode.apply(pm.getApplicationIcon(pkg)));cache.put(pkg,entry);}
                icons.put(purpose.getString("id"),entry.image);
            }catch(PackageManager.NameNotFoundException|RuntimeException ignored){cache.remove(pkg);}
        }
        cache.keySet().retainAll(needed);return icons;
    }
    static String linkedPackage(Context c,JSONObject purpose){
        String pkg=purpose.optString("pkg");if(!pkg.isEmpty())return pkg;
        String uri=purpose.optString("uri");if(uri.isEmpty())return "";
        String scheme=Uri.parse(uri).getScheme();
        if(scheme==null||scheme.equalsIgnoreCase("http")||scheme.equalsIgnoreCase("https"))return "";
        Intent intent=Links.intent(c,"",uri);if(intent==null)return "";
        Set<String> packages=new HashSet<>();
        for(ResolveInfo r:c.getPackageManager().queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY))if(r.activityInfo!=null&&r.activityInfo.exported)packages.add(r.activityInfo.packageName);
        return packages.size()==1?packages.iterator().next():"";
    }
    private static long version(PackageInfo info){return android.os.Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
}
