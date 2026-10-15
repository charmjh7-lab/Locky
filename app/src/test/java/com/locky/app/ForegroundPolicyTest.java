package com.locky.app;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ForegroundPolicyTest {
    @Test public void outgoingKeyguardAdditionBelongsToTheCurrentUnlockOnly(){
        assertFalse(ForegroundPolicy.newSystemInterruption(true,Set.of(51),51));
        assertTrue(ForegroundPolicy.newSystemInterruption(true,Set.of(51),52));
        assertTrue(ForegroundPolicy.newSystemInterruption(false,Set.of(51),51));
    }
    private ForegroundPolicy.Window w(String p,int t,boolean focus,boolean active,int layer){return new ForegroundPolicy.Window(p,t,focus,active,layer,false);}
    @Test public void gestureServiceAndKeyboardDoNotBecomeForeground(){assertEquals("video",ForegroundPolicy.select(Arrays.asList(w("gesture.service",4,true,true,9),w("keyboard",2,true,true,8),w("video",1,false,false,1)),Collections.emptySet()).pkg);}
    @Test public void actualHomeIsChosenEvenWithoutLauncherIntent(){assertEquals("home",ForegroundPolicy.select(Arrays.asList(w("video",1,false,false,0),w("home",1,true,true,1)),Collections.emptySet()).pkg);}
    @Test public void notificationShadePausesWithoutReplacingApp(){ForegroundPolicy.Result r=ForegroundPolicy.select(Arrays.asList(w("video",1,false,false,0),w("",3,true,true,4)),Collections.emptySet());assertTrue(r.paused);assertEquals("",r.pkg);assertEquals("video",ForegroundPolicy.select(Collections.singletonList(w("video",1,true,true,1)),Collections.emptySet()).pkg);}
    @Test public void nonFocusedFullScreenShadeAlsoPauses(){assertTrue(ForegroundPolicy.select(Arrays.asList(w("video",1,true,true,0),new ForegroundPolicy.Window("",3,false,false,2,false,true)),Collections.emptySet()).paused);}
    @Test public void focusedSplitWindowWinsOverUnfocusedPopup(){assertEquals("editor",ForegroundPolicy.select(Arrays.asList(w("video",1,false,false,5),w("editor",1,true,true,1)),Collections.emptySet()).pkg);}
}
