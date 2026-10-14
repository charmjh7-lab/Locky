package com.locky.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** A display only: GuardService owns the countdown and all usage accounting. */
final class FloatingTimer {
    private final Context context;
    private final WindowManager window;
    LinearLayout root;
    TextView remaining;
    FloatingTimer(Context context,WindowManager window){this.context=context;this.window=window;}

    void update(boolean visible,long milliseconds){
        if(!visible){hide();return;}
        if(root==null){
            LinearLayout view=new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);view.setGravity(Gravity.CENTER);view.setMinimumWidth(dp(104));
            view.setPadding(dp(12),dp(6),dp(12),dp(6));
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            GradientDrawable background=new GradientDrawable();background.setColor(Color.rgb(31,41,38));background.setCornerRadius(dp(24));view.setBackground(background);
            TextView label=new TextView(context);label.setText("이번 사용");label.setTextSize(10);label.setTextColor(Color.rgb(207,218,212));view.addView(label);
            TextView time=new TextView(context);time.setTextSize(18);time.setTextColor(Color.WHITE);time.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);view.addView(time);
            WindowManager.LayoutParams params=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.TOP|Gravity.RIGHT;params.x=dp(12);params.y=dp(8);params.setTitle("Locky floating timer");
            try{window.addView(view,params);root=view;remaining=time;}catch(RuntimeException failure){return;}
        }
        // Round up exactly as the existing countdown: never show zero before expiry.
        long seconds=Math.max(0,(milliseconds+999)/1000);
        remaining.setText(String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60));
    }
    void hide(){if(root!=null){try{window.removeViewImmediate(root);}catch(IllegalArgumentException ignored){}root=null;remaining=null;}}
    private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
