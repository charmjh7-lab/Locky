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
@Config(application=Application.class,sdk=35)
public class PhoneStartActivityTest {
    Application app;Store store;ActivityController<PhoneStartActivity> activity;
    @Before public void setup(){app=RuntimeEnvironment.getApplication();Store.instance=null;GuardService.live=null;store=Store.get(app);store.settings.edit().clear().commit();}
    @After public void cleanup(){if(activity!=null)activity.pause().stop().destroy();GuardService.live=null;store.close();Store.instance=null;}
    PhoneStartActivity open(long cycle){activity=Robolectric.buildActivity(PhoneStartActivity.class,PhoneStartActivity.intent(app,cycle)).create().start().resume().visible();return activity.get();}
    @Test public void pauseResumeNewCycleAndConfigurationKeepTheOnlyWebView(){
        PhoneStartActivity phone=open(1);WebHost host=phone.host;Object web=host.view;long nav=host.navigationGeneration;
        activity.pause().stop().start().resume();
        activity.newIntent(PhoneStartActivity.intent(app,2));
        phone.onConfigurationChanged(new android.content.res.Configuration(phone.getResources().getConfiguration()));
        assertSame(host,phone.host);assertSame(web,host.view);assertEquals(nav,host.navigationGeneration);assertEquals(2,phone.cycle);
        assertNull(host.loadingView);assertEquals(WebHost.Role.PHONE_START,host.role);assertFalse(host.isOverlay);assertFalse(phone.isFinishing());
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
}
