package com.locky.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

/** Lifecycle/dispatch contracts, not a Samsung compositor or background-launch simulator. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,shadows=PhoneStartActivityTest.TaskMoves.class)
public class PhoneStartActivityTest {
    @org.robolectric.annotation.Implements(ActivityManager.class)
    public static class TaskMoves extends org.robolectric.shadows.ShadowActivityManager {
        static int moves,lastTask;
        @org.robolectric.annotation.Implementation public void moveTaskToFront(int task,int flags,Bundle options){moves++;lastTask=task;assertNotNull(options);}
    }
    Application app;Store store;ActivityController<PhoneStartActivity> activity;
    @Before public void setup(){TaskMoves.moves=0;app=RuntimeEnvironment.getApplication();Store.instance=null;GuardService.live=null;store=Store.get(app);store.settings.edit().clear().commit();}
    @After public void cleanup(){if(activity!=null)activity.pause().stop().destroy();GuardService.live=null;store.close();Store.instance=null;}
    PhoneStartActivity open(long cycle){activity=Robolectric.buildActivity(PhoneStartActivity.class,PhoneStartActivity.intent(app,cycle)).create().start().resume().visible();return activity.get();}
    @Test public void pauseResumeNewCycleAndConfigurationKeepTheOnlyWebView(){
        PhoneStartActivity phone=open(1);WebHost host=phone.host;Object web=host.view;long nav=host.navigationGeneration;
        activity.pause().stop().start().resume();
        activity.newIntent(PhoneStartActivity.intent(app,2));
        phone.onConfigurationChanged(new android.content.res.Configuration(phone.getResources().getConfiguration()));
        assertSame(host,phone.host);assertSame(web,host.view);assertEquals(nav,host.navigationGeneration);assertEquals(2,phone.cycle);
        assertEquals(WebHost.Role.PHONE_START,host.role);assertFalse(phone.isFinishing());
        phone.chosen=true;activity.newIntent(PhoneStartActivity.intent(app,2));assertTrue(phone.chosen); // late same-cycle intent cannot repeat a selection
    }
    @Test public void manifestSeparatesTasksAndDoesNotCoverOrWakeKeyguard() throws Exception {
        ActivityInfo info=app.getPackageManager().getActivityInfo(new ComponentName(app,PhoneStartActivity.class),0);
        ActivityInfo main=app.getPackageManager().getActivityInfo(new ComponentName(app,MainActivity.class),0);
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK,info.launchMode);assertNotEquals(main.taskAffinity,info.taskAffinity);
        assertFalse(info.exported);assertTrue((info.flags&ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS)!=0);
        PhoneStartActivity phone=open(1);assertEquals(0,phone.getWindow().getAttributes().flags&(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON));
    }
    @Test public void oneRecoveryForSilentlyDeniedPreparationAndNoHomeResurrection(){
        PhoneStartController coordinator=new PhoneStartController(app);coordinator.screenOff();
        assertEquals(PhoneStartActivity.class.getName(),Shadows.shadowOf(app).getNextStartedActivity().getComponent().getClassName());
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(true);
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        assertFalse(coordinator.unlocked());assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        assertTrue(coordinator.unlocked());assertNotNull(Shadows.shadowOf(app).getNextStartedActivity());
        assertFalse(coordinator.unlocked());assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        PhoneStartActivity phone=open(coordinator.cycle());activity.pause().stop();
        assertFalse(coordinator.unlocked());assertNull(Shadows.shadowOf(app).getNextStartedActivity());assertFalse(phone.isFinishing());
    }
    @Test public void acknowledgedPreparationAndRepeatedScreenOffReuseOwnerWithoutUnlockLaunch(){
        PhoneStartActivity phone=open(1);PhoneStartController coordinator=new PhoneStartController(app);
        coordinator.screenOff();assertEquals(coordinator.cycle(),phone.cycle);assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(true);
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        assertTrue(coordinator.unlocked());assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        Object view=phone.host.view;coordinator.screenOff();assertSame(view,phone.host.view);assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void savedNavigationAndDraftMetadataDoNotChangeMainNavigationOrUserRecords() throws Exception {
        store.note(new JSONObject().put("id","kept").put("title","메모").put("body","기존 내용").put("mode","text").put("checks",new JSONArray()));
        store.settings.edit().putString("last-screen","brief").putString("phone-start-session",new JSONObject().put("screen","chat").put("chat","actual-chat").put("quickQuestion","초안").put("chatReturn","unlock").toString()).commit();
        PhoneStartActivity phone=open(1);assertEquals("actual-chat",phone.host.pendingChat);assertEquals("chat",phone.host.pendingScreen);
        phone.host.handle("test","remember",new JSONObject().put("screen","chat").put("chat","other-saved-chat").put("chatReturn","chats").put("chatsReturn","unlock").put("quickQuestion","초안").toString());
        assertEquals("other-saved-chat",phone.host.pendingChat);
        assertEquals("brief",store.settings.getString("last-screen",""));assertEquals("초안",phone.host.phoneSession().getString("quickQuestion"));assertEquals("기존 내용",store.rows("SELECT * FROM notes WHERE id='kept'").getJSONObject(0).getString("body"));
    }
    @Test public void navigationReleaseSurvivesResumeAndRecreationButResetsOnlyOnNewCycle(){
        PhoneStartActivity phone=open(1);assertTrue(phone.purposePending());Object web=phone.host.view;
        for(String tool:new String[]{"memo","chat","dictionary"}){
            phone.releaseNavigation(tool);assertFalse(phone.purposePending());activity.pause().stop().start().resume();assertFalse(phone.purposePending());
        }
        PhoneNavigation recreated=new PhoneNavigation(store.settings);assertEquals(PhoneNavigation.State.NAVIGATION_RELEASED,recreated.state(1));recreated.begin(1);assertEquals(PhoneNavigation.State.NAVIGATION_RELEASED,recreated.state(1));
        phone.acceptCycle(2);assertTrue(phone.purposePending());assertSame(web,phone.host.view);assertFalse(recreated.release(1));
        recreated.begin(1);assertTrue(phone.purposePending()); // stale restore cannot rewind a new cycle
    }
    @Test public void onlyConfirmedFocusedHomeReturnsTheExistingTaskOnce(){
        PhoneStartActivity phone=open(1);Object web=phone.host.view;long nav=phone.host.navigationGeneration;activity.pause().stop();
        GuardService guard=Robolectric.buildService(GuardService.class).create().get();guard.store=store;guard.interactive=true;guard.homePackage="launcher";guard.homeClass=guard.launcherClass="launcher.Home";
        var home=new ForegroundPolicy.Result("launcher",false);
        var windows=java.util.List.of(new ForegroundPolicy.Window("launcher",1,true,true,1,false,true));
        guard.overview=true;assertFalse(guard.restorePhoneFromForeground(home,windows,"",false,true));guard.overview=false;
        assertFalse(guard.restorePhoneFromForeground(new ForegroundPolicy.Result("",true),windows,"",false,true));
        assertFalse(guard.restorePhoneFromForeground(new ForegroundPolicy.Result("settings",false),windows,"",false,true));
        guard.launcherClass="launcher.RecentsActivity";assertFalse(guard.restorePhoneFromForeground(home,windows,"",false,true));guard.launcherClass=guard.homeClass;
        ActivityInfo alias=new ActivityInfo();alias.name="launcher.HomeAlias";alias.targetActivity=guard.homeClass;guard.rememberHome(alias);
        guard.launcherClass=alias.name;assertTrue(guard.isHomeClass(alias.name));
        assertFalse(guard.restorePhoneFromForeground(home,java.util.List.of(new ForegroundPolicy.Window("launcher",1,true,true,1,false,false)),"",false,true));
        assertTrue(guard.restorePhoneFromForeground(home,windows,"",false,true));assertEquals(1,TaskMoves.moves);assertEquals(phone.getTaskId(),TaskMoves.lastTask);
        assertTrue(guard.homeObserved);
        assertTrue(guard.restorePhoneFromForeground(home,windows,"",false,true));assertEquals(1,TaskMoves.moves);assertSame(web,phone.host.view);assertEquals(nav,phone.host.navigationGeneration);
        activity.start().resume();phone.releaseNavigation("memo");activity.pause().stop();assertFalse(guard.restorePhoneFromForeground(home,windows,"",false,true));assertEquals(1,TaskMoves.moves);
        guard.main.removeCallbacksAndMessages(null);
    }

    GuardService guard(){
        GuardService guard=Robolectric.buildService(GuardService.class).create().get();guard.store=store;guard.interactive=true;
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);return guard;
    }
    @Test public void externalEntryReusesTaskAndSelectionFinishesWithoutRelaunchOrHome() throws Exception {
        PhoneStartActivity phone=open(11);Object web=phone.host.view;activity.pause().stop();GuardService guard=guard();
        var visible=new ForegroundPolicy.Result("messages",false);
        var windows=java.util.List.of(new ForegroundPolicy.Window("messages",1,true,true,1,false,true));
        assertTrue(guard.restorePhoneFromForeground(visible,windows,"messages",true,false));assertEquals(1,TaskMoves.moves);
        assertTrue(guard.restorePhoneFromForeground(visible,windows,"messages",true,false));assertEquals(1,TaskMoves.moves);
        assertSame(web,phone.host.view);activity.start().resume();
        phone.choose(new JSONObject().put("label","답장하기"));
        assertTrue(phone.isFinishing());assertNull(Shadows.shadowOf(phone).getNextStartedActivity());
        assertEquals("답장하기",store.rows("SELECT * FROM events WHERE kind='unlock'").getJSONObject(0).getString("purpose"));
    }
    @Test public void externalGuardRejectsSystemOverviewUnfocusedAndReleasedNavigation(){
        PhoneStartActivity phone=open(11);activity.pause().stop();GuardService guard=guard();
        var visible=new ForegroundPolicy.Result("messages",false);
        var windows=java.util.List.of(new ForegroundPolicy.Window("messages",1,true,true,1,false,true));
        guard.overview=true;assertFalse(guard.restorePhoneFromForeground(visible,windows,"messages",true,false));guard.overview=false;
        guard.systemPackages.add("messages");assertFalse(guard.restorePhoneFromForeground(visible,windows,"messages",true,false));guard.systemPackages.clear();
        assertFalse(guard.restorePhoneFromForeground(visible,java.util.List.of(new ForegroundPolicy.Window("messages",1,false,true,1,false,true)),"messages",true,false));
        assertTrue(guard.restorePhoneFromForeground(visible,windows,"",false,false));assertEquals(0,TaskMoves.moves);assertTrue(guard.foregroundUncertain);
        assertTrue(guard.restorePhoneFromForeground(visible,windows,"",false,true));assertEquals(1,TaskMoves.moves);
        activity.start().resume();phone.releaseNavigation("ChatGPT");activity.pause().stop();
        assertFalse(guard.restorePhoneFromForeground(visible,windows,"messages",true,false));assertEquals(1,TaskMoves.moves);
    }
    @Test public void newCycleClearsExternalReturnAndNormalSelectionStillOpensHome() throws Exception {
        PhoneStartActivity phone=open(11);activity.pause().stop();phone.returnFromForeground(true);phone.acceptCycle(12);activity.start().resume();
        phone.choose(new JSONObject().put("label","일반 시작"));assertTrue(Shadows.shadowOf(phone).getNextStartedActivity().hasCategory(Intent.CATEGORY_HOME));
    }

    android.view.accessibility.AccessibilityWindowInfo window(String pkg,int type,int layer){
        var node=android.view.accessibility.AccessibilityNodeInfo.obtain();node.setPackageName(pkg);
        var window=android.view.accessibility.AccessibilityWindowInfo.obtain();var shadow=Shadows.shadowOf(window);
        shadow.setRoot(node);shadow.setType(type);shadow.setFocused(true);shadow.setActive(true);shadow.setLayer(layer);shadow.setBoundsInScreen(new android.graphics.Rect(0,0,1000,2000));return window;
    }
    @Test public void notificationAndSideGestureObserveActualAppRatherThanGestureWindow(){
        PhoneStartActivity phone=open(11);activity.pause().stop();GuardService guard=guard();
        Shadows.shadowOf(guard).setWindows(java.util.List.of(window("messages",1,1),window("com.samsung.android.sidegesturepad",1,10)));
        var event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("messages");event.setClassName("messages.Conversation");guard.observeForeground(event);
        assertEquals(1,TaskMoves.moves);assertEquals("",guard.sessionPkg);assertFalse(guard.overlayVisible);
        guard.observeForeground(event);assertEquals(1,TaskMoves.moves);guard.main.removeCallbacksAndMessages(null);event.recycle();
    }

}
