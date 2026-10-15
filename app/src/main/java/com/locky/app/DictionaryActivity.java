package com.locky.app;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.widget.Toast;
import org.json.JSONArray;
import java.util.HashSet;
import java.util.Set;

/** Interactive dictionary host rendered behind the still-attached start overlay. */
public final class DictionaryActivity extends Activity {
    static final int REQUEST=7412;
    private static final long FOREIGN_WINDOW_SETTLE_MS=800;
    GuardService guard;
    WebHost host;
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
        if(guard==null||!guard.dictionaryOpening||!guard.overlayVisible){finish();return;}
        try{
            JSONArray choices=Links.dictionaries(this);
            for(int n=0;n<choices.length();n++){
                ComponentName c=ComponentName.unflattenFromString(choices.getJSONObject(n).getString("component"));
                if(c!=null)packages.add(c.getPackageName());
            }
        }catch(Exception ignored){}
        host=new WebHost(this,true);
        host.handoffState=getIntent().getStringExtra("handoff");
        host.firstFrameCallback=this::readyForDictionary;
        setContentView(host.root);
        host.screen(guard.gate);
    }
    @Override protected void onResume(){
        super.onResume();
        if(host!=null)host.resumeOverlay();
    }
    void readyForDictionary(){
        if(returning||launched||isFinishing()||isDestroyed()||guard==null)return;
        if(!guard.dictionaryReady(this)){returnToStart();return;}
        launched=true;
        search(getIntent().getStringExtra("word"),getIntent().getBooleanExtra("picker",false));
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
    void returnToStart(){
        if(returning)return;
        returning=true;
        if(guard!=null){
            guard.main.removeCallbacks(foreignWindowSettled);
            if(host!=null&&host.ready&&host.view!=null)host.view.evaluateJavascript(
                "captureDictionaryStartState()",value->{
                    try{guard.restoreDictionaryDraft(new JSONArray("["+value+"]").getString(0));}
                    catch(Exception ignored){}
                });
            guard.returnDictionary(this);
        }
        finish();
    }
    @Override public void onBackPressed(){returnToStart();}
    @Override protected void onDestroy(){
        if(guard!=null){
            guard.main.removeCallbacks(foreignWindowSettled);
            guard.dictionaryHostDestroyed(this);
        }
        if(host!=null)host.close();
        super.onDestroy();
    }
}
