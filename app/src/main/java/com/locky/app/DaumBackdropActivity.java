package com.locky.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

/**
 * A short-lived, noninteractive image of Locky's own start view.
 * The live WebView stays attached to the accessibility overlay, preserving
 * the active dictionary input and notes without a detach/reattach flash.
 */
public final class DaumBackdropActivity extends Activity {
    GuardService guard;
    boolean popupLaunched,away;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setWindowAnimations(0);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        guard=GuardService.live;
        if(guard==null||!guard.daumPopupActive||guard.daumBackdrop==null){finish();return;}
        guard.daumBackdropHost=this;
        ImageView picture=new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.FIT_XY);
        picture.setBackgroundColor(Color.rgb(244,243,237));
        picture.setImageBitmap(guard.daumBackdrop);
        setContentView(picture);
        // Wait until this window has actually drawn under the still-visible
        // accessibility overlay before lowering the overlay and opening Daum.
        picture.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener(){
            boolean scheduled;
            @Override public void onDraw(){
                if(scheduled)return;
                scheduled=true;
                picture.post(()->{
                    if(picture.getViewTreeObserver().isAlive())
                        picture.getViewTreeObserver().removeOnDrawListener(this);
                    picture.postDelayed(()->{
                        if(!isFinishing()&&!isDestroyed()&&guard!=null&&guard.daumPopupActive){
                            popupLaunched=true;guard.showDaumPopup(DaumBackdropActivity.this);
                        }
                    },32);
                });
            }
        });
    }
    @Override protected void onPause(){
        if(popupLaunched)away=true;
        super.onPause();
    }
    @Override protected void onResume(){
        super.onResume();
        if(popupLaunched&&away&&guard!=null)guard.finishDaumPopup();
    }
    @Override public void onBackPressed(){
        if(guard!=null)guard.finishDaumPopup();else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(guard!=null&&guard.daumBackdropHost==this)guard.finishDaumPopup();
        super.onDestroy();
    }
}
