package com.locky.app;

import android.app.Application;
import android.content.ContentValues;
import android.os.Looper;
import java.util.TimeZone;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class GateCatalogTest {
    Store store;TimeZone originalZone;
    @Before public void setup(){originalZone=TimeZone.getDefault();Store.instance=null;store=Store.get(RuntimeEnvironment.getApplication());store.getWritableDatabase();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @After public void close(){store.close();Store.instance=null;TimeZone.setDefault(originalZone);}
    void rule(String pkg){ContentValues v=new ContentValues();v.put("pkg",pkg);v.put("label",pkg);v.put("mode","limited");v.put("minutes",60);store.put("rules",v);}
    @Test public void aWarmCatalogAndItsScopedOrdersRequireNoFurtherQueries() throws Exception {
        rule("video");Store.GateCatalog catalog=store.gateCatalog();JSONArray order=store.purposes("video");
        assertTrue(catalog.data.getJSONObject("purposeOrders").has("app"));assertTrue(order.length()>0);
        store.getWritableDatabase().execSQL("DROP TABLE purposes");store.getWritableDatabase().execSQL("DROP TABLE rules");
        assertSame(catalog,store.gateCatalog());assertSame(order,store.purposes("video"));assertSame(catalog.data.getJSONArray("allPurposes"),store.allPurposes());
    }
    @Test public void editsRemovalAndRuleChangesReplaceOnlyTheNextSnapshot() throws Exception {
        rule("video");Store.GateCatalog before=store.gateCatalog();int size=store.purposes("video").length();
        JSONObject p=store.purpose(new JSONObject().put("label","영상 목적"),"video");Store.GateCatalog added=store.gateCatalog();
        assertFalse(store.currentGateCatalog(before));assertEquals(size+1,store.purposes("video").length());
        assertEquals(size,before.data.getJSONObject("purposeOrders").getJSONArray("video").length());
        store.removePurpose(p.getString("id"));assertEquals(size,store.purposes("video").length());assertNotSame(added,store.gateCatalog());
        rule("social");assertTrue(store.gateCatalog().data.getJSONObject("purposeOrders").has("social"));
        store.removeRule("social");assertFalse(store.gateCatalog().data.getJSONObject("purposeOrders").has("social"));
    }
    @Test public void scopeSelectionAndDailyOrderMatchTheExistingShuffleRule() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-12:00"));
        store.purpose(new JSONObject().put("id","custom").put("label","앱 전용"),"video");
        JSONArray all=store.allPurposes(),actual=store.purposes("video");java.util.List<String> ids=new java.util.ArrayList<>();
        for(int i=0;i<all.length();i++){JSONObject p=all.getJSONObject(i);if(java.util.Set.of("video","app").contains(p.getString("scope")))ids.add(p.getString("id"));}
        assertEquals(Rules.shuffle(ids,java.time.LocalDate.now().toString(),"video"),java.util.stream.IntStream.range(0,actual.length()).mapToObj(i->actual.optJSONObject(i).optString("id")).toList());
        Store.GateCatalog before=store.gateCatalog();TimeZone.setDefault(TimeZone.getTimeZone("GMT+14:00"));
        Store.GateCatalog next=store.gateCatalog();assertTrue(next.revision>before.revision);assertNotEquals(before.day,next.day);
    }
    @Test public void checkpointPreferencesDoNotRetirePurposePreparationButUiSettingsDo() throws Exception {
        Store.GateCatalog before=store.gateCatalog();
        store.settings.edit().putLong("checkpoint",1234).putString("last-package","video").commit();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(before,store.gateCatalog());
        store.settings.edit().putInt("end",123).commit();Shadows.shadowOf(Looper.getMainLooper()).idle();
        Store.GateCatalog next=store.gateCatalog();assertTrue(next.revision>before.revision);assertEquals(123,next.data.getJSONObject("prefs").getInt("end"));
    }
}
