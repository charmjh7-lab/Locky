package com.locky.app;

import android.app.Application;
import android.content.ContentValues;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.ScrollView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class FloatingTimerInteractionTest {
    GuardService g;FloatingTimer timer;WindowManager window;Rect screen;long session;
    int metricsReads;
    int dp(int n){return Math.round(n*g.getResources().getDisplayMetrics().density);}
    @Before public void setup() throws Exception {
        Store.instance=null;LockyApp.app=new LockyApp();
        g=Robolectric.buildService(GuardService.class).create().get();g.store=Store.get(g);GuardService.live=g;
        g.store.settings.edit().clear().putInt("start",0).putInt("end",0).commit();g.wasManaged=true;
        ContentValues v=new ContentValues();v.put("pkg","user.video");v.put("label","YouTube");v.put("mode","limited");v.put("minutes",60);g.store.put("rules",v);
        g.foreground=g.sessionPkg="user.video";g.purpose="영상 하나 보기";g.interactive=true;g.sessionRemaining=272000;
        session=g.sessionEvent=g.store.event("app",g.purpose,g.sessionPkg,"",0);g.sessionPolicyEnd=g.policyEnd(System.currentTimeMillis());resetClock();Notices.channels(g);
        screen=new Rect(0,0,dp(390),dp(780));
        window=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{
            if(m.getName().equals("getCurrentWindowMetrics")){metricsReads++;return new WindowMetrics(new Rect(screen),new WindowInsets.Builder()
                .setInsetsIgnoringVisibility(WindowInsets.Type.systemBars(),android.graphics.Insets.of(0,dp(24),0,dp(24)))
                .setInsets(WindowInsets.Type.systemBars(),android.graphics.Insets.of(0,dp(24),0,dp(24)))
                .setVisible(WindowInsets.Type.systemBars(),true).build());}
            return null;
        });
        timer=new FloatingTimer(g,window);g.floatingTimer=timer;g.syncFloatingTimer();
    }
    void resetClock(){g.segmentWall=System.currentTimeMillis();g.segmentElapsed=SystemClock.elapsedRealtime();}
    @After public void cleanup(){timer.hide();g.main.removeCallbacksAndMessages(null);g.store.close();Store.instance=null;GuardService.live=null;LockyApp.app.work.shutdownNow();LockyApp.app=null;}
    void motion(View target,int action,float x,float y,long when){MotionEvent e=MotionEvent.obtain(when,when,action,x,y,0);target.dispatchTouchEvent(e);e.recycle();}
    void tap(){long now=SystemClock.uptimeMillis();motion(timer.root,MotionEvent.ACTION_DOWN,30,20,now);motion(timer.root,MotionEvent.ACTION_UP,30,20,now+1);}
    void drag(float dx,float dy,boolean commit){
        long now=SystemClock.uptimeMillis();motion(timer.root,MotionEvent.ACTION_DOWN,40,20,now);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout()+1));assertTrue(timer.dragging);
        motion(timer.root,MotionEvent.ACTION_MOVE,40+dx,20+dy,SystemClock.uptimeMillis());
        motion(timer.root,commit?MotionEvent.ACTION_UP:MotionEvent.ACTION_CANCEL,40+dx,20+dy,SystemClock.uptimeMillis());
    }
    @Test public void realTapOpensLiveDetailsAndOutsideClosesWithoutAnotherGrant() throws Exception {
        tap();assertNotNull(timer.panel);assertEquals("04:32",timer.sessionTime.getText().toString());assertEquals("60:00",timer.dailyTime.getText().toString());assertEquals("영상 하나 보기",timer.purposeText.getText().toString());
        assertEquals(0,timer.panelLayout.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        assertTrue((timer.panelLayout.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0);
        assertTrue((timer.panelLayout.flags&WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)!=0);
        // Touching the panel is still use of the foreground app, with the same event.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        g.segmentWall=System.currentTimeMillis()-2000;g.segmentElapsed=SystemClock.elapsedRealtime()-2000;g.segmentUptime=SystemClock.uptimeMillis()-2000;g.checkpoint();g.syncFloatingTimer();
        assertEquals("04:30",timer.sessionTime.getText().toString());assertEquals("59:58",timer.dailyTime.getText().toString());assertFalse(g.overlayVisible);assertEquals("user.video",g.foreground);
        motion(timer.panel,MotionEvent.ACTION_OUTSIDE,0,0,SystemClock.uptimeMillis());assertNull(timer.panel);assertNotNull(timer.root);
        assertEquals(session,g.sessionEvent);assertEquals(1,g.store.rows("SELECT id FROM events WHERE kind='app'").length());
        ForegroundPolicy.Result observed=ForegroundPolicy.select(Arrays.asList(new ForegroundPolicy.Window("user.video",1,true,false,1,false),new ForegroundPolicy.Window("",4,false,true,10,false)),Collections.emptySet());
        assertEquals("user.video",observed.pkg);assertFalse(observed.paused);
        timer.root.performClick();assertNotNull(timer.panel);timer.close.performClick();assertNull(timer.panel);assertEquals(session,g.sessionEvent);
    }
    @Test public void tappingCapsuleToDismissOutsidePanelDoesNotReopenIt(){
        tap();View panel=timer.panel;long now=SystemClock.uptimeMillis();
        motion(panel,MotionEvent.ACTION_OUTSIDE,30,20,now);motion(timer.root,MotionEvent.ACTION_DOWN,30,20,now);motion(timer.root,MotionEvent.ACTION_UP,30,20,now+1);
        assertNull(timer.panel);assertNotNull(timer.root);
    }
    @Test public void longPressDragPersistsClampedPositionAcrossNewTimer(){
        drag(-10000,10000,true);assertEquals(timer.safe.left,timer.timerLayout.x);assertEquals(timer.safe.bottom-timer.timerLayout.height,timer.timerLayout.y);assertNull(timer.panel);
        String key=timer.positionKey;assertEquals(0,g.store.settings.getFloat(key+".x",-1),0);assertEquals(1,g.store.settings.getFloat(key+".y",-1),0);
        timer.hide();timer=new FloatingTimer(g,window);g.floatingTimer=timer;g.syncFloatingTimer();
        assertEquals(timer.safe.left,timer.timerLayout.x);assertEquals(timer.safe.bottom-timer.timerLayout.height,timer.timerLayout.y);assertEquals(session,g.sessionEvent);
    }
    @Test public void swipeAndCancelledDragDoNotSaveOrOpen(){
        int x=timer.timerLayout.x,y=timer.timerLayout.y;long now=SystemClock.uptimeMillis();
        motion(timer.root,MotionEvent.ACTION_DOWN,40,20,now);motion(timer.root,MotionEvent.ACTION_MOVE,150,120,now+10);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout()+1));assertFalse(timer.dragging);
        motion(timer.root,MotionEvent.ACTION_UP,150,120,SystemClock.uptimeMillis());assertNull(timer.panel);assertEquals(x,timer.timerLayout.x);
        drag(-10000,10000,false);assertEquals(x,timer.timerLayout.x);assertEquals(y,timer.timerLayout.y);assertFalse(g.store.settings.contains(timer.positionKey+".x"));
    }
    @Test public void foldAndRotateUseIndependentPositionsAndResizingStaysOnScreen(){
        drag(-10000,10000,true);String cover=timer.positionKey;
        screen=new Rect(0,0,dp(820),dp(1000));timer.placeTimer();g.syncFloatingTimer();assertNotEquals(cover,timer.positionKey);assertEquals(timer.safe.right-timer.timerLayout.width,timer.timerLayout.x);assertEquals(timer.safe.top,timer.timerLayout.y);
        drag(-10000,0,true);String inner=timer.positionKey;
        screen=new Rect(0,0,dp(1000),dp(820));timer.placeTimer();g.syncFloatingTimer();assertNotEquals(inner,timer.positionKey);assertEquals(timer.safe.right-timer.timerLayout.width,timer.timerLayout.x);
        screen=new Rect(0,0,dp(820),dp(1000));timer.placeTimer();g.syncFloatingTimer();assertEquals(timer.safe.left,timer.timerLayout.x);assertEquals(timer.safe.top,timer.timerLayout.y);
        screen=new Rect(0,0,dp(390),dp(780));timer.placeTimer();g.syncFloatingTimer();assertEquals(cover,timer.positionKey);assertEquals(timer.safe.left,timer.timerLayout.x);assertEquals(timer.safe.bottom-timer.timerLayout.height,timer.timerLayout.y);
        screen=new Rect(0,0,dp(340),dp(620));timer.placeTimer();g.syncFloatingTimer();assertTrue(timer.safe.contains(new Rect(timer.timerLayout.x,timer.timerLayout.y,timer.timerLayout.x+timer.timerLayout.width,timer.timerLayout.y+timer.timerLayout.height)));
    }
    @Test public void panelFitsShortScreenAndKeepsActionsVisibleWithLongPurpose(){
        g.purpose="긴 사용 목적을 화면 크기와 관계없이 확인합니다. ".repeat(30);screen=new Rect(0,0,dp(390),dp(300));timer.placeTimer();g.syncFloatingTimer();tap();
        assertNotNull(timer.panel);WindowManager.LayoutParams p=timer.panelLayout;assertTrue(timer.safe.contains(new Rect(p.x,p.y,p.x+p.width,p.y+p.height)));
        timer.panel.measure(View.MeasureSpec.makeMeasureSpec(p.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(p.height,View.MeasureSpec.EXACTLY));timer.panel.layout(0,0,p.width,p.height);
        assertTrue(timer.stop.getBottom()<=p.height);assertTrue(timer.stop.getTop()>=0);assertTrue(timer.close.getBottom()<=p.height);assertTrue(timer.panel.getChildAt(1) instanceof ScrollView);
        ScrollView scroll=(ScrollView)timer.panel.getChildAt(1);assertTrue(scroll.getChildAt(0).getHeight()>scroll.getHeight());assertEquals(g.purpose,timer.purposeText.getText().toString());
    }
    @Test public void lastMinuteChangesColorAndImmersiveInsetsMakeDigitsSmaller(){
        assertEquals(FloatingTimer.GREEN,((GradientDrawable)timer.remaining.getBackground()).getColor().getDefaultColor());
        g.sessionRemaining=60001;g.syncFloatingTimer();assertEquals(FloatingTimer.GREEN,((GradientDrawable)timer.remaining.getBackground()).getColor().getDefaultColor());
        g.sessionRemaining=60000;g.syncFloatingTimer();assertEquals(FloatingTimer.AMBER,((GradientDrawable)timer.remaining.getBackground()).getColor().getDefaultColor());
        float original=timer.remaining.getTextSize();ViewCompat.dispatchApplyWindowInsets(timer.root,new WindowInsetsCompat.Builder().setVisible(WindowInsetsCompat.Type.statusBars(),false).build());
        assertTrue(timer.compact);assertTrue(timer.remaining.getTextSize()<original);
        ViewCompat.dispatchApplyWindowInsets(timer.root,new WindowInsetsCompat.Builder().setVisible(WindowInsetsCompat.Type.statusBars(),true).build());assertFalse(timer.compact);assertEquals(original,timer.remaining.getTextSize(),0);
    }
    @Test public void endUsesExistingStopAndStaleButtonCannotEndAnotherSession(){
        tap();Button old=timer.stop;old.performClick();assertNull(timer.root);assertNull(timer.panel);assertEquals("",g.sessionPkg);assertEquals("",g.foreground);
        g.sessionPkg=g.foreground="user.video";g.sessionEvent=session+1;g.sessionRemaining=120000;resetClock();g.syncFloatingTimer();tap();Button previous=timer.stop;
        g.sessionEvent=session+2;g.syncFloatingTimer();assertNull(timer.panel);previous.performClick();old.performClick();assertEquals("user.video",g.sessionPkg);assertEquals(session+2,g.sessionEvent);
    }
    @Test public void pauseExpiryAndAppChangeRemoveDetailsAndDoNotKeepStalePurpose(){
        tap();g.systemPaused=true;g.syncFloatingTimer();assertNull(timer.root);assertNull(timer.panel);
        g.systemPaused=false;g.syncFloatingTimer();assertNotNull(timer.root);assertNull(timer.panel);assertEquals(session,g.sessionEvent);
        tap();g.foreground="other.app";g.syncFloatingTimer();assertNull(timer.root);assertNull(timer.panel);
        g.foreground=g.sessionPkg;g.syncFloatingTimer();tap();g.sessionRemaining=0;g.syncFloatingTimer();assertNull(timer.root);assertNull(timer.panel);
    }

    @Test public void countdownReusesDrawableAndSkipsMetricsUntilDisplayOrWidthChanges(){
        Shadows.shadowOf(Looper.getMainLooper()).idle();Object background=timer.remaining.getBackground();int before=metricsReads;
        CharSequence description=timer.root.getContentDescription();
        g.syncFloatingTimer();assertSame(description,timer.root.getContentDescription());
        for(int i=0;i<4;i++){g.sessionRemaining-=1000;g.syncFloatingTimer();Shadows.shadowOf(Looper.getMainLooper()).idle();assertSame(background,timer.remaining.getBackground());}
        assertEquals(before,metricsReads);assertEquals("04:28",timer.remaining.getText().toString());
        g.sessionRemaining=60000;g.syncFloatingTimer();Object warning=timer.remaining.getBackground();assertNotSame(background,warning);
        g.sessionRemaining=59000;g.syncFloatingTimer();assertSame(warning,timer.remaining.getBackground());
        g.sessionRemaining=61000;g.syncFloatingTimer();assertSame(background,timer.remaining.getBackground());
    }
}
