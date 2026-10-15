package com.locky.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Window;
import androidx.core.view.WindowInsetsControllerCompat;

/** Same semantic colors as the WebView; never reload a document to change theme. */
final class UiTheme {
    private UiTheme() {}
    static boolean dark(Context c){return (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;}
    static int background(Context c){return c.getColor(R.color.window_background);}
    static int surface(Context c){return c.getColor(R.color.surface);}
    static int ink(Context c){return c.getColor(R.color.ink);}
    static int muted(Context c){return c.getColor(R.color.muted);}
    static int accent(Context c){return c.getColor(R.color.accent);}
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
