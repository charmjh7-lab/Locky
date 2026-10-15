package com.locky.app;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.Locale;

/** Views of GuardService's current grant; opening/moving these never creates a session. */
final class FloatingTimer {
    static final int GREEN=Color.rgb(35,67,49), AMBER=Color.rgb(116,77,32);
    private final GuardService service;
    private final WindowManager window;
    private final SharedPreferences positions;
    FrameLayout root;
    LinearLayout panel;
    TextView remaining,sessionTime,dailyTime,purposeText;
    Button stop,close;
    WindowManager.LayoutParams timerLayout,panelLayout;
    Rect safe=new Rect();
    String positionKey="",pkg="",label="",purpose="";
    long event,milliseconds,daily;
    boolean compact,dragging,touchMoved;
    private float downX,downY;
    private int downLeft,downTop,panelDesiredHeight;
    private long touchTime=-1,outsideTime=-2;
    private final Runnable longPress=()->{if(root!=null&&!touchMoved)root.performLongClick();};

    FloatingTimer(GuardService service,WindowManager window){
        this.service=service;this.window=window;positions=service.store.settings;
    }

    private final class Capsule extends FrameLayout {
        Capsule(){super(service);}
        @Override public boolean performClick(){return super.performClick();}
        @Override public boolean onTouchEvent(MotionEvent e){return touch(e)||super.onTouchEvent(e);}
    }
    private final class Details extends LinearLayout {
        Details(){super(service);}
        @Override public boolean performClick(){return super.performClick();}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE){outsideTime=e.getEventTime();collapse();return true;}
            return super.onTouchEvent(e);
        }
    }

    void update(boolean visible,String pkg,long event,String label,String purpose,long milliseconds,long daily){
        if(!visible){hide();return;}
        if(!this.pkg.equals(pkg)||this.event!=event){collapse();cancelTouch();}
        this.pkg=pkg;this.event=event;this.label=label;this.purpose=purpose;
        this.milliseconds=milliseconds;this.daily=daily;
        if(root==null){
            FrameLayout view=new Capsule();
            view.setPadding(dp(4),dp(8),dp(4),dp(8));view.setMinimumHeight(dp(48));
            view.setClickable(true);view.setLongClickable(true);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            TextView time=text(16,Color.WHITE);time.setGravity(Gravity.CENTER);time.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
            time.setPadding(dp(10),dp(3),dp(10),dp(3));time.setMinHeight(dp(32));time.setMinWidth(dp(68));time.setIncludeFontPadding(false);
            view.addView(time,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
            root=view;remaining=time;timerLayout=layout(false);timerLayout.setTitle("Locky floating timer");
            root.setOnClickListener(v->togglePanel());
            root.setOnLongClickListener(v->{collapse();dragging=true;root.setPressed(true);return true;});
            ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
                boolean small=!insets.isVisible(WindowInsetsCompat.Type.statusBars());
                if(compact!=small){compact=small;styleCapsule();}
                // Defer until the delivered insets are also available as root insets.
                v.post(()->{if(root==v){placeTimer();placePanel();}});return insets;
            });
            remaining.setText(format(milliseconds));styleCapsule();placeTimer();
            try{window.addView(root,timerLayout);}catch(RuntimeException failure){root=null;remaining=null;timerLayout=null;return;}
        }
        remaining.setText(format(milliseconds));
        remaining.setBackground(background(milliseconds<=60_000?AMBER:GREEN,24));
        root.setContentDescription("이번 사용 남은 시간 "+format(milliseconds)+". 눌러서 상세 정보, 길게 눌러 위치 이동");
        placeTimer();refreshPanel();
    }

    private void styleCapsule(){
        if(remaining==null)return;
        remaining.setTextSize(compact?14:16);remaining.setMinHeight(dp(compact?26:32));remaining.setMinWidth(dp(compact?60:68));
    }

    private boolean touch(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                touchTime=e.getEventTime();
                downX=e.getRawX();downY=e.getRawY();downLeft=timerLayout.x;downTop=timerLayout.y;
                touchMoved=false;dragging=false;root.setPressed(true);
                service.main.postDelayed(longPress,ViewConfiguration.getLongPressTimeout());return true;
            case MotionEvent.ACTION_MOVE:
                float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                if(dragging){
                    timerLayout.x=clamp(Math.round(downLeft+dx),safe.left,safe.right-timerLayout.width);
                    timerLayout.y=clamp(Math.round(downTop+dy),safe.top,safe.bottom-timerLayout.height);
                    updateLayout(root,timerLayout);
                }else if(Math.hypot(dx,dy)>ViewConfiguration.get(service).getScaledTouchSlop()){
                    touchMoved=true;service.main.removeCallbacks(longPress);root.setPressed(false);
                }
                return true;
            case MotionEvent.ACTION_UP:
                service.main.removeCallbacks(longPress);root.setPressed(false);
                if(dragging){savePosition();dragging=false;}else if(!touchMoved&&touchTime!=outsideTime)root.performClick();return true;
            case MotionEvent.ACTION_CANCEL:
                cancelTouch();placeTimer();return true;
            default:return false;
        }
    }

    private void cancelTouch(){
        service.main.removeCallbacks(longPress);dragging=false;touchMoved=false;if(root!=null)root.setPressed(false);
    }

    private void togglePanel(){
        if(panel!=null){collapse();return;}
        String expectedPkg=pkg;long expectedEvent=event;
        service.checkpoint();service.enforce(); // Fresh values, including a possible expiry since the last tick.
        if(root==null||!pkg.equals(expectedPkg)||event!=expectedEvent)return;
        LinearLayout view=new Details();view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16),dp(8),dp(16),dp(12));view.setBackground(background(UiTheme.surface(service),18));view.setElevation(dp(6));
        LinearLayout header=new LinearLayout(service);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView app=text(17,UiTheme.ink(service));app.setTypeface(Typeface.DEFAULT,Typeface.BOLD);app.setText(label);app.setSingleLine();app.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(app,new LinearLayout.LayoutParams(0,-2,1));
        close=new Button(service);close.setText("×");close.setTextSize(22);close.setTextColor(UiTheme.muted(service));close.setBackgroundColor(Color.TRANSPARENT);
        close.setPadding(0,0,0,0);close.setContentDescription("상세 정보 접기");close.setOnClickListener(v->collapse());header.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));view.addView(header);
        LinearLayout content=new LinearLayout(service);content.setOrientation(LinearLayout.VERTICAL);
        content.addView(textLabel("이번 사용 남은 시간"));sessionTime=text(30,UiTheme.accent(service));sessionTime.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);content.addView(sessionTime);
        TextView today=textLabel("오늘 남은 시간");today.setPadding(0,dp(12),0,0);content.addView(today);
        dailyTime=text(20,UiTheme.ink(service));dailyTime.setTypeface(Typeface.MONOSPACE,Typeface.NORMAL);content.addView(dailyTime);
        TextView reason=textLabel("사용 목적");reason.setPadding(0,dp(12),0,0);content.addView(reason);
        purposeText=text(16,UiTheme.ink(service));purposeText.setPadding(0,dp(4),0,dp(12));content.addView(purposeText);
        ScrollView scroll=new ScrollView(service);scroll.setFillViewport(false);scroll.addView(content);view.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        stop=new Button(service);stop.setText("사용 종료");stop.setTextSize(16);stop.setTextColor(Color.WHITE);stop.setBackground(background(GREEN,12));stop.setMinHeight(dp(48));
        stop.setOnClickListener(v->{
            // A stale/double click must never stop a different app or a later grant.
            if(panel!=view)return;
            service.checkpoint();
            if(expectedPkg.equals(service.foreground)&&expectedPkg.equals(service.sessionPkg)&&expectedEvent==service.sessionEvent)service.stopApp();
            else {collapse();service.enforce();}
        });
        LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(-1,dp(48));action.topMargin=dp(8);view.addView(stop,action);
        panel=view;panelLayout=layout(true);panelLayout.setTitle("Locky timer details");refreshPanel();
        panelLayout.width=Math.min(dp(280),safe.width());
        view.measure(View.MeasureSpec.makeMeasureSpec(panelLayout.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        panelDesiredHeight=view.getMeasuredHeight();panelLayout.height=Math.min(panelDesiredHeight,safe.height());
        // Keep close/end visible on short screens and let only the information scroll.
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1));placePanel();
        try{window.addView(panel,panelLayout);}catch(RuntimeException failure){collapse();}
    }

    private void refreshPanel(){
        if(panel==null)return;
        sessionTime.setText(format(milliseconds));sessionTime.setTextColor(milliseconds<=60_000?(UiTheme.dark(service)?Color.rgb(240,197,129):AMBER):UiTheme.accent(service));
        dailyTime.setText(format(daily));purposeText.setText(purpose.isEmpty()?"선택한 목적 없음":purpose);
    }

    void collapse(){
        if(panel!=null){try{window.removeViewImmediate(panel);}catch(IllegalArgumentException ignored){}panel=null;}
        sessionTime=null;dailyTime=null;purposeText=null;stop=null;close=null;panelLayout=null;
    }

    void hide(){
        cancelTouch();collapse();
        if(root!=null){try{window.removeViewImmediate(root);}catch(IllegalArgumentException ignored){}root=null;remaining=null;}
        timerLayout=null;compact=false;
    }

    @SuppressLint("RtlHardcoded") // Saved x/y are physical screen coordinates, independent of text direction.
    private WindowManager.LayoutParams layout(boolean outside){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                |(outside?WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH:0),PixelFormat.TRANSLUCENT);
        // Absolute screen coordinates; fit safe insets ourselves, including cutouts.
        p.gravity=Gravity.TOP|Gravity.LEFT;
        if(Build.VERSION.SDK_INT>=30)p.setFitInsetsTypes(0);
        if(Build.VERSION.SDK_INT>=28)p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return p;
    }

    private Rect screenBounds(){
        if(Build.VERSION.SDK_INT>=30){WindowMetrics metrics=window.getCurrentWindowMetrics();if(metrics!=null)return new Rect(metrics.getBounds());}
        DisplayMetrics metrics=new DisplayMetrics();
        if(window.getDefaultDisplay()!=null)window.getDefaultDisplay().getRealMetrics(metrics);else metrics.setTo(service.getResources().getDisplayMetrics());
        return new Rect(0,0,Math.max(1,metrics.widthPixels),Math.max(1,metrics.heightPixels));
    }

    private Rect safeBounds(Rect bounds){
        WindowInsetsCompat insets=null;
        if(Build.VERSION.SDK_INT>=30){WindowMetrics metrics=window.getCurrentWindowMetrics();if(metrics!=null)insets=WindowInsetsCompat.toWindowInsetsCompat(metrics.getWindowInsets());}
        WindowInsetsCompat rootInsets=root==null?null:ViewCompat.getRootWindowInsets(root);
        if(insets==null)insets=rootInsets;
        Rect result=new Rect(bounds);
        if(insets!=null){Insets bars=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            int bottom=bars.bottom;if(rootInsets!=null)bottom=Math.max(bottom,rootInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            result.left+=bars.left;result.top+=bars.top;result.right-=bars.right;result.bottom-=bottom;
        }
        result.inset(dp(8),dp(8));
        if(result.width()<dp(48)||result.height()<dp(48))return new Rect(bounds);
        return result;
    }

    void placeTimer(){
        if(root==null)return;
        Rect screen=screenBounds();safe=safeBounds(screen);
        String key="floating.position."+(Math.min(screen.width(),screen.height())/service.getResources().getDisplayMetrics().density>=600?"wide":"compact")
            +"."+(screen.width()>screen.height()?"landscape":"portrait");
        if(!key.equals(positionKey)){cancelTouch();positionKey=key;collapse();}
        root.measure(View.MeasureSpec.makeMeasureSpec(safe.width(),View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(safe.height(),View.MeasureSpec.AT_MOST));
        int width=root.getMeasuredWidth(),height=root.getMeasuredHeight();
        int x=dragging?timerLayout.x:safe.left+Math.round((safe.width()-width)*fraction(positionKey+".x",1));
        int y=dragging?timerLayout.y:safe.top+Math.round((safe.height()-height)*fraction(positionKey+".y",0));
        boolean changed=timerLayout.width!=width||timerLayout.height!=height||timerLayout.x!=x||timerLayout.y!=y;
        timerLayout.width=width;timerLayout.height=height;
        timerLayout.x=clamp(x,safe.left,safe.right-width);timerLayout.y=clamp(y,safe.top,safe.bottom-height);
        if(changed)updateLayout(root,timerLayout);
    }

    private float fraction(String key,float fallback){float v=positions.getFloat(key,fallback);return Float.isFinite(v)?Math.max(0,Math.min(1,v)):fallback;}
    private void savePosition(){
        positions.edit().putFloat(positionKey+".x",(float)(timerLayout.x-safe.left)/Math.max(1,safe.width()-timerLayout.width))
            .putFloat(positionKey+".y",(float)(timerLayout.y-safe.top)/Math.max(1,safe.height()-timerLayout.height)).apply();
    }

    private void placePanel(){
        if(panel==null)return;
        int previousWidth=panelLayout.width,previousHeight=panelLayout.height,previousX=panelLayout.x,previousY=panelLayout.y;
        panelLayout.width=Math.min(dp(280),safe.width());panelLayout.height=Math.min(panelDesiredHeight,safe.height());
        int x=timerLayout.x>safe.centerX()?timerLayout.x+timerLayout.width-panelLayout.width:timerLayout.x;
        int below=timerLayout.y+timerLayout.height+dp(4);
        int y=below+panelLayout.height<=safe.bottom?below:timerLayout.y-panelLayout.height-dp(4);
        panelLayout.x=clamp(x,safe.left,safe.right-panelLayout.width);panelLayout.y=clamp(y,safe.top,safe.bottom-panelLayout.height);
        if(previousWidth!=panelLayout.width||previousHeight!=panelLayout.height||previousX!=panelLayout.x||previousY!=panelLayout.y)updateLayout(panel,panelLayout);
    }

    private void updateLayout(View view,WindowManager.LayoutParams params){
        // Before addView and after removal there is deliberately no attached window.
        if(view==null||!view.isAttachedToWindow())return;
        try{window.updateViewLayout(view,params);}catch(IllegalArgumentException ignored){}
    }
    private TextView text(float size,int color){TextView v=new TextView(service);v.setTextSize(size);v.setTextColor(color);return v;}
    private TextView textLabel(String value){TextView v=text(12,UiTheme.muted(service));v.setText(value);return v;}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    static String format(long value){long seconds=Math.max(0,(value+999)/1000);return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);}
    private int dp(int value){return Math.round(value*service.getResources().getDisplayMetrics().density);}
    private static int clamp(int value,int min,int max){return Math.max(min,Math.min(value,Math.max(min,max)));}
}
