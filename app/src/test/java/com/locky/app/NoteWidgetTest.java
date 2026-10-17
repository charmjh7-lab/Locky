package com.locky.app;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.os.Bundle;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class NoteWidgetTest {
    Application app;Store store;
    @Before public void setup() throws Exception {Store.instance=null;app=RuntimeEnvironment.getApplication();store=Store.get(app);store.note(new JSONObject().put("id","a").put("title","A").put("mode","check").put("checks",new JSONArray().put(new JSONObject().put("text","완료한 할 일").put("done",true))));store.note(new JSONObject().put("id","b").put("title","B").put("body","두 번째 메모"));}
    @After public void cleanup(){store.close();Store.instance=null;}
    @Test public void independentSelectionsUseActualStoreAndDeletionFallsBack() throws Exception {
        store.settings.edit().putString("last-note","a").commit();assertEquals("a",NoteWidget.selected(app,10).getString("id"));
        store.settings.edit().putString(NoteWidget.key(10),"a").putString(NoteWidget.key(11),"b").commit();assertEquals("b",NoteWidget.selected(app,11).getString("id"));
        NoteWidgetService.Factory factory=new NoteWidgetService.Factory(app,10);factory.onCreate();assertEquals(1,factory.getCount());assertTrue(factory.checked.get(0));assertEquals("완료한 할 일",factory.text.get(0));
        store.note(new JSONObject().put("id","a").put("mode","check").put("checks",new JSONArray().put(new JSONObject().put("text","변경").put("done",false))));factory.onDataSetChanged();assertEquals("변경",factory.text.get(0));assertFalse(factory.checked.get(0));
        new NoteWidget().onDeleted(app,new int[]{10});assertFalse(store.settings.contains(NoteWidget.key(10)));assertEquals("b",store.settings.getString(NoteWidget.key(11),""));
        store.settings.edit().putString(NoteWidget.key(10),"a").commit();store.getWritableDatabase().delete("notes","id=?",new String[]{"a"});assertEquals("b",NoteWidget.selected(app,10).getString("id"));store.getWritableDatabase().delete("notes",null,null);assertNull(NoteWidget.selected(app,10));factory.onDataSetChanged();assertEquals(0,factory.getCount());
    }
    @Test public void nativeEntryTargetsTheExactMemoInNewAndExistingActivity(){
        var controller=Robolectric.buildActivity(MainActivity.class,MainActivity.launchIntent(app).putExtra("screen","notes").putExtra("note","a")).create();
        assertEquals("a",controller.get().host.pendingNote);controller.newIntent(MainActivity.launchIntent(app).putExtra("screen","notes").putExtra("note","b"));assertEquals("b",controller.get().host.pendingNote);controller.destroy();
    }
    @Test public void metadataHasTwoAxisResizeNoPeriodicRefreshAndProtectedCollectionService() throws Exception {
        android.content.res.XmlResourceParser xml=app.getResources().getXml(R.xml.note_widget_info);while(xml.next()!=org.xmlpull.v1.XmlPullParser.START_TAG){}
        assertEquals(3,xml.getAttributeIntValue("http://schemas.android.com/apk/res/android","resizeMode",-1));assertEquals(0,xml.getAttributeIntValue("http://schemas.android.com/apk/res/android","updatePeriodMillis",-1));xml.close();
        var service=app.getPackageManager().getServiceInfo(new ComponentName(app,NoteWidgetService.class),0);assertEquals("android.permission.BIND_REMOTEVIEWS",service.permission);assertFalse(service.exported);
    }
}
