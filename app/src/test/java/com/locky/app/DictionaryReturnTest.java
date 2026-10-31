package com.locky.app;

import android.app.Application;
import android.content.*;
import android.content.pm.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class DictionaryReturnTest {
    ActivityController<PhoneStartActivity> controller;PhoneStartActivity phone;
    @Before public void setup(){Store.instance=null;GuardService.live=null;controller=Robolectric.buildActivity(PhoneStartActivity.class,PhoneStartActivity.intent(RuntimeEnvironment.getApplication(),1)).create().start().resume().visible();phone=controller.get();}
    @After public void cleanup(){controller.pause().stop().destroy();Store.instance.close();Store.instance=null;}
    ComponentName register(String pkg){
        ComponentName component=new ComponentName(pkg,pkg+".Lookup");ResolveInfo info=new ResolveInfo();info.activityInfo=new ActivityInfo();info.activityInfo.packageName=pkg;info.activityInfo.name=component.getClassName();info.activityInfo.exported=true;info.activityInfo.applicationInfo=new ApplicationInfo();info.activityInfo.applicationInfo.packageName=pkg;info.nonLocalizedLabel="Dictionary";
        Shadows.shadowOf(phone.getPackageManager()).addResolveInfoForIntent(Links.process(""),info);Store.get(phone).settings.edit().putString("dictionary","process:"+component.flattenToString()).commit();return component;
    }
    @Test public void popupAndRepeatedSearchNeverDetachOrReplaceStartWebView() throws Exception {
        ComponentName target=register("net.daum.android.dictionary");Object web=phone.host.view,parent=phone.host.root.getParent();long nav=phone.host.navigationGeneration;
        phone.dictionary("첫 단어");Intent first=Shadows.shadowOf((Application)phone.getApplication()).getNextStartedActivity();assertEquals(target,first.getComponent());assertEquals("첫 단어",first.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));assertTrue((first.getFlags()&Intent.FLAG_ACTIVITY_MULTIPLE_TASK)!=0);
        controller.pause().resume();phone.dictionary("두 번째 단어");Intent second=Shadows.shadowOf((Application)phone.getApplication()).getNextStartedActivity();assertEquals("두 번째 단어",second.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
        assertSame(parent,phone.host.root.getParent());assertSame(web,phone.host.view);assertEquals(nav,phone.host.navigationGeneration);assertFalse(phone.isFinishing());assertTrue(phone.allowsDictionary(target.getPackageName()));assertFalse(phone.allowsDictionary("other.managed.app"));
    }
    @Test public void ordinaryDictionaryIsLaunchedFromTheExistingActivityWithoutNewTask() throws Exception {
        ComponentName target=register("example.dictionary");phone.dictionary("word");Intent intent=Shadows.shadowOf((Application)phone.getApplication()).getNextStartedActivity();assertEquals(target,intent.getComponent());assertEquals(0,intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK);assertFalse(phone.isFinishing());
    }
    @Test public void missingDictionaryLeavesTheSameStartAvailableForRetry() throws Exception {
        Object web=phone.host.view;try{phone.dictionary("word");fail();}catch(ActivityNotFoundException expected){}
        assertSame(web,phone.host.view);assertFalse(phone.isFinishing());assertFalse(phone.allowsDictionary("example.dictionary"));
    }
}
