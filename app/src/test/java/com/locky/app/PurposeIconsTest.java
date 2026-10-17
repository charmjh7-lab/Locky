package com.locky.app;

import android.app.Application;
import android.content.*;
import android.content.pm.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class PurposeIconsTest {
    Context context;PurposeIcons icons;int encodes;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();icons=new PurposeIcons();install("linked.app",1);}
    void install(String pkg,long update){PackageInfo info=new PackageInfo();info.packageName=pkg;info.lastUpdateTime=update;info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=pkg;info.applicationInfo.enabled=true;Shadows.shadowOf(context.getPackageManager()).installPackage(info);Shadows.shadowOf(context.getPackageManager()).setApplicationIcon(pkg,new android.graphics.drawable.ColorDrawable(android.graphics.Color.GREEN));}
    JSONArray purposes() throws Exception{return new JSONArray().put(new JSONObject().put("id","a").put("pkg","linked.app")).put(new JSONObject().put("id","b").put("pkg","linked.app")).put(new JSONObject().put("id","none")).put(new JSONObject().put("id","web").put("uri","https://example.com")).put(new JSONObject().put("id","missing").put("pkg","uninstalled.app"));}
    JSONObject load(JSONArray ps) throws Exception{return icons.forPurposes(context,ps,d->{encodes++;return "image-"+encodes;});}
    @Test public void onlyInstalledLinksGetIconsAndSharedPackageIsEncodedOnce() throws Exception {
        JSONObject data=load(purposes());assertEquals("image-1",data.getString("a"));assertEquals(data.getString("a"),data.getString("b"));assertFalse(data.has("none"));assertFalse(data.has("web"));assertFalse(data.has("missing"));assertEquals(1,encodes);load(purposes());assertEquals(1,encodes);
    }
    @Test public void packageUpdateRemovalAndPurposeRelinkingCannotKeepOldImages() throws Exception {
        load(purposes());install("linked.app",2);assertEquals("image-2",load(purposes()).getString("a"));
        Shadows.shadowOf(context.getPackageManager()).removePackage("linked.app");assertFalse(load(purposes()).has("a"));
        install("linked.app",2);assertEquals("image-3",load(purposes()).getString("a"));icons.invalidate();load(purposes());assertEquals(4,encodes);
        JSONArray changed=new JSONArray().put(new JSONObject().put("id","a").put("uri","https://example.com"));assertEquals(0,load(changed).length());
    }
    @Test public void conversionFailureIsJustTheExistingLinkFallback() throws Exception {
        assertEquals(0,icons.forPurposes(context,purposes(),d->{throw new IllegalStateException("decode failure");}).length());
    }
    @Test public void onlyUnambiguousNonWebDeepLinksResolveToAppIcons() throws Exception {
        Intent intent=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("linked-app://inbox"));ResolveInfo r=new ResolveInfo();r.activityInfo=new ActivityInfo();r.activityInfo.packageName="linked.app";r.activityInfo.name="linked.app.Main";r.activityInfo.exported=true;r.isDefault=true;Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(intent,r);
        assertEquals("linked.app",PurposeIcons.linkedPackage(context,new JSONObject().put("uri","linked-app://inbox")));
        ResolveInfo other=new ResolveInfo();other.activityInfo=new ActivityInfo();other.activityInfo.packageName="other.app";other.activityInfo.name="other.app.Main";other.activityInfo.exported=true;other.isDefault=true;Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(intent,other);
        assertEquals("",PurposeIcons.linkedPackage(context,new JSONObject().put("uri","linked-app://inbox")));
    }
}
