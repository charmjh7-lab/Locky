package com.locky.app;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.JSONArray;
import java.util.HashSet;
import java.util.Set;

/**
 * Shows the original, interactive start WebView in an ordinary Activity below
 * popup dictionaries. Repeated searches reuse this host and the same WebView.
 * The accessibility overlay is restored only when the user leaves this host.
 */
public final class DictionaryActivity extends Activity {
    static final int REQUEST=7412;
    private static final long FOREIGN_WINDOW_SETTLE_MS=800;
    GuardService guard;
    boolean launched,returning;
    final Set<String> packages=new HashSet<>();
    private final Runnable foreignWindowSettled=()->{
        if(returning||guard==null||guard.dictionaryHost!=this)return;
        String pkg=guard.foreground;
        if(!pkg.isEmpty()&&!allows(pkg))returnToStart();
    };
    void foregroundChanged(String pkg){
        if(guard==null||returning)return;
        guard.main.removeCallbacks(foreignWindowSettled);
        if(!pkg.isEmpty()&&!allows(pkg))
            guard.main.postDelayed(foreignWindowSettled,FOREIGN_WINDOW_SETTLE_MS);
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setWindowAnimations(0);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        guard=GuardService.live;
        try{
            JSONArray choices=Links.dictionaries(this);
            for(int n=0;n<choices.length();n++){
                ComponentName c=ComponentName.unflattenFromString(choices.getJSONObject(n).getString("component"));
                if(c!=null)packages.add(c.getPackageName());
            }
        }catch(Exception ignored){}
        if(guard==null||!guard.overlayVisible||guard.overlay==null){finish();return;}
        // A prepared native window is present underneath the existing overlay.
        // Do not remove the overlay WebView until this host has resumed.
        FrameLayout surface=new FrameLayout(this);
        surface.setBackgroundColor(android.graphics.Color.rgb(244,243,237));
        setContentView(surface);
    }
    @Override protected void onPostResume(){
        super.onPostResume();
        if(returning||guard==null||launched)return;
        guard.main.post(()->{
            if(returning||isFinishing()||isDestroyed()||launched)return;
            if(!guard.borrowStart(this)){finish();return;}
            launched=true;
            search(getIntent().getStringExtra("word"),getIntent().getBooleanExtra("picker",false));
        });
    }
    boolean allows(String pkg){
        return pkg.equals(getPackageName())||packages.contains(pkg)
            ||pkg.equals("android")||pkg.contains("intentresolver");
    }
    void search(String word,boolean picker){
        if(returning||guard==null||guard.dictionaryHost!=this)return;
        guard.useUtility("사전");
        try{
            if(picker)Links.dictionaryPicker(this,word);
            else Links.dictionary(this,word);
        }catch(Exception e){
            Toast.makeText(this,"사전을 열지 못했습니다. 다시 선택해 주세요.",Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onResume(){
        super.onResume();
        if(launched&&guard!=null&&guard.overlay!=null)guard.overlay.resumeOverlay();
        // Returning from Daum's popup does not end dictionary mode.
    }
    @Override protected void onPause(){
        // A popup may only pause this Activity, leaving it visible behind Daum.
        // Pausing the WebView here would blank that underlying start screen.
        super.onPause();
    }
    void returnToStart(){
        if(returning)return;returning=true;
        if(guard!=null){
            guard.main.removeCallbacks(foreignWindowSettled);
            guard.returnDictionary(this);
        }
        finish();
    }
    @Override public void onBackPressed(){returnToStart();}
    @Override protected void onDestroy(){
        if(guard!=null){
            guard.main.removeCallbacks(foreignWindowSettled);
            guard.returnDictionary(this);
        }
        super.onDestroy();
    }
}
