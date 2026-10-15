package com.locky.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Window;
import androidx.core.view.WindowInsetsControllerCompat;

/** Same semantic colors as the WebView; never reload a document to change theme. */
final class UiTheme {
    private UiTheme() {}
    static String mode(Context c){
        String value=c.getSharedPreferences("locky-settings",0).getString("theme-mode","system");
        return "light".equals(value)||"dark".equals(value)?value:"system";
    }
    static boolean dark(Context c){
        String value=mode(c);
        if(!"system".equals(value))return "dark".equals(value);
        return (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
    }
    // A user-forced mode overrides night-qualified Android resources as well as CSS.
    static int background(Context c){return Color.parseColor(dark(c)?"#101714":"#F5F6F2");}
    static int surface(Context c){return Color.parseColor(dark(c)?"#19231E":"#FFFFFF");}
    static int ink(Context c){return Color.parseColor(dark(c)?"#F0F5EE":"#192B25");}
    static int muted(Context c){return Color.parseColor(dark(c)?"#AFBEB3":"#637069");}
    static int accent(Context c){return Color.parseColor(dark(c)?"#A2D6B6":"#285C46");}
    static void window(Activity a){
        Window w=a.getWindow();int color=background(a);w.setBackgroundDrawable(new ColorDrawable(color));
        w.setStatusBarColor(color);w.setNavigationBarColor(color);
        if(Build.VERSION.SDK_INT>=29){w.setStatusBarContrastEnforced(false);w.setNavigationBarContrastEnforced(false);}
        // Before attach, Android 11+ can have no WindowInsetsController yet.
        // Set initial decor flags now; subsequent calls use the attached controller.
        if(Build.VERSION.SDK_INT>=30&&w.getInsetsController()==null){
            int mask=android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            android.view.View decor=w.getDecorView();
            decor.setSystemUiVisibility((decor.getSystemUiVisibility()&~mask)|(dark(a)?0:mask));
        }else{
            WindowInsetsControllerCompat bars=new WindowInsetsControllerCompat(w,w.getDecorView());
            bars.setAppearanceLightStatusBars(!dark(a));bars.setAppearanceLightNavigationBars(!dark(a));
        }
    }
}
