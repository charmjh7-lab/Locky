package com.locky.app;

import android.app.*;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppGateViewTest {
    Activity owner;AppGateView view;int starts,extensions,stops,edits,chosenMinutes;String chosenPkg,chosenId,chosenLabel;long chosenToken;
    @Before public void setup(){Store.instance=null;owner=Robolectric.buildActivity(Activity.class).setup().get();owner.setTheme(R.style.AppTheme);view=new AppGateView(owner,new AppGateView.Actions(){
        public void choose(long token,String pkg,String id,String label,int minutes){starts++;chosenToken=token;chosenPkg=pkg;chosenId=id;chosenLabel=label;chosenMinutes=minutes;}
        public void extend(long token,String pkg,int minutes){extensions++;chosenMinutes=minutes;}
        public void stop(long token,String pkg){stops++;}
        public void edit(long token,String pkg){edits++;}
    });owner.setContentView(view);}
    @After public void cleanup(){view.close();if(Store.instance!=null)Store.instance.close();Store.instance=null;}
    JSONObject payload() throws Exception {
        JSONArray ps=new JSONArray();for(int i=0;i<24;i++)ps.put(new JSONObject().put("id","p"+i).put("label","아주 긴 사용 목적을 선택하는 항목 "+i));
        return new JSONObject().put("gate",new JSONObject().put("label","관리 앱").put("purpose","원래 정한 목적").put("daily",345000)).put("prefs",new JSONObject().put("start",540).put("end",1260)).put("free",false).put("purposes",ps).put("rules",new JSONArray());
    }
    void bind(long token,String mode) throws Exception {view.bind(token,mode,"video",payload());view.setActive(true);}
    @Test public void fullyBoundNativePurposesStartImmediatelyAndDuplicateTapsCannotStartTwice() throws Exception {
        bind(1,"app");assertEquals("05:45",view.daily.getText());assertEquals(24,view.presetButtons.size());assertEquals(2,view.grid.getColumnCount());
        view.minuteButtons.get(4).performClick();view.presetButtons.get(2).performClick();view.presetButtons.get(3).performClick();
        assertEquals(1,starts);assertEquals(9,chosenMinutes);assertEquals("p2",chosenId);assertEquals("video",chosenPkg);assertEquals(1,chosenToken);
        Object first=view.presetButtons.get(0);bind(2,"app");assertSame(first,view.presetButtons.get(0));view.presetButtons.get(1).performClick();assertEquals(2,starts);assertEquals(5,chosenMinutes);
    }
    @Test public void directEditorValidatesAndProvidesSaveStartAndClose() throws Exception {
        bind(1,"app");view.direct.performClick();assertEquals(View.VISIBLE,view.editor.getVisibility());view.save.performClick();assertEquals(0,starts);
        view.input.setText("친구에게 답장");view.save.performClick();view.save.performClick();assertEquals(1,starts);assertEquals("친구에게 답장",chosenLabel);assertEquals("",chosenId);
        view.setActive(false);assertEquals(View.GONE,view.editor.getVisibility());bind(2,"app");view.direct.performClick();view.cancel.performClick();assertEquals(View.GONE,view.editor.getVisibility());
    }
    @Test public void renewAndBlockedRetainTheirOriginalCopyPurposeAndActions() throws Exception {
        bind(1,"renew");assertEquals("사용 연장",view.heading.getText());assertEquals("원래 정한 목적",view.original.getText());assertEquals(View.GONE,view.purposes.getVisibility());
        view.minuteButtons.get(5).performClick();view.extend.performClick();view.extend.performClick();assertEquals(1,extensions);assertEquals(10,chosenMinutes);
        bind(2,"blocked");assertEquals("오늘의 사용 시간을 모두 썼어요",view.question.getText());assertEquals(View.GONE,view.extend.getVisibility());assertEquals(View.GONE,view.duration.getVisibility());assertTrue(view.available.getText().toString().contains("21:00"));
        view.stop.performClick();assertEquals(1,stops);
        JSONObject data=payload();data.getJSONArray("rules").put(new JSONObject().put("mode","locked"));view.bind(3,"blocked","video",data);assertEquals("집중 시간에는 차단된 앱이에요",view.question.getText());
    }
    @Test public void inactiveViewCannotSubmitAndEditUsesTheSameTransitionIdentity() throws Exception {
        bind(9,"app");view.setActive(false);view.presetButtons.get(0).performClick();view.stop.performClick();assertEquals(0,starts);assertEquals(0,stops);
        view.setActive(true);view.edit.performClick();view.edit.performClick();assertEquals(1,edits);
    }
    @Test public void lateImeSubmitFromPreviousAppCannotStartTheNewApp() throws Exception {
        bind(1,"app");view.direct.performClick();view.input.setText("이전 앱 목적");view.bind(2,"app","social",payload());view.setActive(true);
        view.submitDirect();view.save.performClick();assertEquals(0,starts);assertEquals(View.GONE,view.editor.getVisibility());
    }
    void measure(int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    @Test public void longListsScrollIndependentlyAndFooterStaysInsideSmallAndWideScreens() throws Exception {
        bind(1,"app");
        for(int[] size:new int[][]{{360,640},{320,480},{740,800}}){measure(size[0],size[1]);capture("app-"+size[0]);assertTrue("exit height "+view.stop.getHeight(),view.stop.getHeight()>=48);assertTrue("footer bottom "+view.footer.getBottom()+" page "+view.page.getHeight(),view.footer.getBottom()<=view.page.getHeight());assertTrue("preset viewport "+view.presetScroll.getHeight(),view.presetScroll.getHeight()>0);assertTrue("grid "+view.grid.getHeight()+" viewport "+view.presetScroll.getHeight(),view.grid.getHeight()>view.presetScroll.getHeight());}
        for(String mode:new String[]{"app","renew","blocked"})for(String theme:new String[]{"light","dark"}){Store.get(owner).settings.edit().putString("theme-mode",theme).commit();view.bind(2,mode,"video",payload());view.applyTheme();measure(360,720);capture(mode+"-"+theme);}
        assertFalse(hasWebView(view));
    }
    @Test public void themesAndInsetsRetainViewsAndKeepNativeInputAboveIme() throws Exception {
        bind(1,"app");Object first=view.presetButtons.get(0);Store store=Store.get(owner);store.settings.edit().putString("theme-mode","dark").commit();view.applyTheme();assertEquals(UiTheme.background(owner),((ColorDrawable)view.getBackground()).getColor());assertSame(first,view.presetButtons.get(0));
        var ime=new androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.of(0,24,0,32)).setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(),androidx.core.graphics.Insets.of(0,0,0,260)).build();
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(view,ime);measure(360,640);assertEquals(260,view.getPaddingBottom());view.direct.performClick();measure(360,640);assertTrue(view.editor.getBottom()<=380);assertTrue(view.save.getBottom()<=view.editorPanel.getHeight());
    }
    boolean hasWebView(View v){if(v instanceof android.webkit.WebView)return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(hasWebView(((ViewGroup)v).getChildAt(i)))return true;return false;}
    void capture(String name) throws Exception {
        String directory=System.getProperty("locky.captureDir");if(directory==null)return;
        java.io.File folder=new java.io.File(directory);folder.mkdirs();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
        try(var output=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{bitmap.recycle();}
    }
}
