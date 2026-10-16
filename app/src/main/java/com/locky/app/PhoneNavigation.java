package com.locky.app;

import android.content.SharedPreferences;

/** Monotonic navigation decision, scoped only to one phone-unlock cycle. */
final class PhoneNavigation {
    enum State { PURPOSE_PENDING, NAVIGATION_RELEASED }
    private final SharedPreferences prefs;
    PhoneNavigation(SharedPreferences prefs){this.prefs=prefs;}
    State state(long cycle){return prefs.getLong("phone-navigation-cycle",-1)==cycle&&prefs.getBoolean("phone-navigation-released",false)?State.NAVIGATION_RELEASED:State.PURPOSE_PENDING;}
    void begin(long cycle){if(prefs.getLong("phone-navigation-cycle",-1)<cycle)prefs.edit().putLong("phone-navigation-cycle",cycle).putBoolean("phone-navigation-released",false).apply();}
    boolean release(long cycle){
        if(prefs.getLong("phone-navigation-cycle",-1)!=cycle||state(cycle)==State.NAVIGATION_RELEASED)return false;
        prefs.edit().putBoolean("phone-navigation-released",true).apply();return true;
    }
}
