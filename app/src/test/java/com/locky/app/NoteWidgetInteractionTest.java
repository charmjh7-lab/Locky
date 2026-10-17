package com.locky.app;

import android.app.Application;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class NoteWidgetInteractionTest {
    Application app;Store store;
    @Before public void setup() throws Exception {
        Store.instance=null;app=RuntimeEnvironment.getApplication();store=Store.get(app);
        store.note(new JSONObject().put("id","a").put("title","유지할 제목").put("body","유지할 본문").put("mode","check").put("checks",new JSONArray().put(new JSONObject().put("text","첫 항목").put("done",false)).put(new JSONObject().put("text","둘째 항목").put("done",false))));
        store.note(new JSONObject().put("id","b").put("title","다른 메모").put("body","본문"));
    }
    @After public void cleanup(){store.close();Store.instance=null;}
    JSONObject note() throws Exception {return store.rows("SELECT * FROM notes WHERE id='a'").getJSONObject(0);}
    JSONArray checks() throws Exception {return new JSONArray(note().getString("checks"));}
    JSONObject editor(JSONObject base) throws Exception {JSONObject edit=new JSONObject(base.toString());edit.put("checks",new JSONArray(base.getString("checks")));JSONObject original=new JSONObject(base.toString());original.put("checks",new JSONArray(base.getString("checks")));return edit.put("base",original);}
    boolean toggle(int index,JSONObject item) throws Exception {return store.toggleNoteCheck("a",index,item.getString("id"),item.getString("text"),item.optBoolean("done"));}
    @Test public void compactAndWideHeadersKeepTitleAndOpenButOnlyWideHasNavigation() throws Exception {
        JSONObject note=note();
        View small=NoteWidget.layout(app,10,note,"a",120).apply(app,new FrameLayout(app));
        assertEquals(View.GONE,small.findViewById(R.id.widget_previous).getVisibility());assertEquals(View.GONE,small.findViewById(R.id.widget_next).getVisibility());assertEquals(View.VISIBLE,small.findViewById(R.id.widget_title).getVisibility());assertEquals(View.VISIBLE,small.findViewById(R.id.widget_expand).getVisibility());
        View wide=NoteWidget.layout(app,10,note,"a",240).apply(app,new FrameLayout(app));assertEquals(View.VISIBLE,wide.findViewById(R.id.widget_previous).getVisibility());assertEquals(View.VISIBLE,wide.findViewById(R.id.widget_next).getVisibility());
        var xml=app.getResources().getXml(R.xml.note_widget_info);while(xml.next()!=org.xmlpull.v1.XmlPullParser.START_TAG){}assertEquals(2,xml.getAttributeIntValue("http://schemas.android.com/apk/res/android","targetCellWidth",-1));xml.close();
    }
    @Test public void toggleChangesOnlyDoneAndBothFactoriesReloadWithoutLaunchingActivity() throws Exception {
        store.settings.edit().putString(NoteWidget.key(10),"a").putString(NoteWidget.key(11),"a").putString(NoteWidget.key(12),"b").commit();
        var first=new NoteWidgetService.Factory(app,10);var second=new NoteWidgetService.Factory(app,11);first.onCreate();second.onCreate();JSONObject target=checks().getJSONObject(0);
        assertTrue(toggle(0,target));assertFalse(toggle(0,target)); // stale duplicate cannot toggle twice
        first.onDataSetChanged();second.onDataSetChanged();assertTrue(first.checked.get(0));assertTrue(second.checked.get(0));assertEquals("b",NoteWidget.selected(app,12).getString("id"));
        View row=first.getViewAt(0).apply(app,new FrameLayout(app));assertTrue(row.findViewById(R.id.widget_row_check) instanceof ImageView);assertFalse(row.findViewById(R.id.widget_row_text).isClickable());assertTrue((((TextView)row.findViewById(R.id.widget_row_text)).getPaintFlags()&android.graphics.Paint.STRIKE_THRU_TEXT_FLAG)!=0);
        assertEquals("유지할 제목",note().getString("title"));assertEquals("유지할 본문",note().getString("body"));assertEquals("둘째 항목",checks().getJSONObject(1).getString("text"));assertFalse(checks().getJSONObject(1).optBoolean("done"));
        assertTrue(toggle(0,checks().getJSONObject(0)));assertFalse(checks().getJSONObject(0).optBoolean("done"));assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void reorderedDeletedAndChangedRowsRejectOldPayload() throws Exception {
        JSONObject base=note(),target=checks().getJSONObject(0);JSONObject edit=editor(base);JSONArray rows=edit.getJSONArray("checks");edit.put("checks",new JSONArray().put(rows.getJSONObject(1)).put(rows.getJSONObject(0)));store.note(edit);assertFalse(toggle(0,target));
        edit=editor(note());edit.getJSONArray("checks").getJSONObject(1).put("text","바뀐 내용");store.note(edit);assertFalse(toggle(1,target));
        edit=editor(note());edit.put("checks",new JSONArray());store.note(edit);assertFalse(toggle(0,target));
    }
    @Test public void staleEditorTextAndWidgetDoneMergeInEitherOrder() throws Exception {
        JSONObject base=note(),target=checks().getJSONObject(0);JSONObject typing=editor(base);typing.getJSONArray("checks").getJSONObject(0).put("text","입력 중인 내용");
        assertTrue(toggle(0,target));store.note(typing);assertEquals("입력 중인 내용",checks().getJSONObject(0).getString("text"));assertTrue(checks().getJSONObject(0).optBoolean("done"));
        base=note();typing=editor(base);typing.getJSONArray("checks").getJSONObject(1).put("text","다른 줄 입력");store.note(typing);assertTrue(toggle(0,new JSONArray(base.getString("checks")).getJSONObject(0)));assertEquals("다른 줄 입력",checks().getJSONObject(1).getString("text"));assertFalse(checks().getJSONObject(0).optBoolean("done"));
    }
    @Test public void simultaneousWidgetsDoNotLoseIndependentCheckChanges() throws Exception {
        JSONArray before=checks();ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{Future<Boolean> a=threads.submit(()->{start.await();return toggle(0,before.getJSONObject(0));});Future<Boolean> b=threads.submit(()->{start.await();return toggle(1,before.getJSONObject(1));});start.countDown();assertTrue(a.get(5,TimeUnit.SECONDS));assertTrue(b.get(5,TimeUnit.SECONDS));assertTrue(checks().getJSONObject(0).optBoolean("done"));assertTrue(checks().getJSONObject(1).optBoolean("done"));}finally{threads.shutdownNow();}
    }
    @Test public void legacyJsonGetsStableIdentitiesWithoutChangingContentsOrUpdateTime() throws Exception {
        store.getWritableDatabase().execSQL("UPDATE notes SET checks='[{\"text\":\"같은 글\",\"done\":false},{\"text\":\"같은 글\",\"done\":false}]' WHERE id='a'");long updated=note().getLong("updated");store.notes();JSONArray ids=checks();assertNotEquals(ids.getJSONObject(0).getString("id"),ids.getJSONObject(1).getString("id"));store.notes();assertEquals(ids.toString(),checks().toString());assertEquals(updated,note().getLong("updated"));
    }
}
