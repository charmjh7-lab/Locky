package com.locky.app;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import java.util.HashSet;
import java.util.Set;

/** Minimum application layer for dictionaries/notifications below accessibility overlays. */
public final class DictionaryActivity extends Activity {
    static final int REQUEST=7412;
    GuardService guard;WebHost host;boolean launched,returning;
    final Set<String> packages=new HashSet<>();
    private Bitmap cover;
    private final Runnable foreignWindowSettled=()->{
        if(!returning&&guard!=null&&guard.dictionaryHost==this&&!guard.foreground.isEmpty()&&!allows(guard.foreground))returnToStart();
    };
    void foregroundChanged(String pkg){
        if(guard==null||returning)return;
        guard.main.removeCallbacks(foreignWindowSettled);
        if(!pkg.isEmpty()&&!allows(pkg))guard.main.postDelayed(foreignWindowSettled,800);
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().setWindowAnimations(0);getWindow().getDecorView();UiTheme.window(this);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        guard=GuardService.live;
        if(guard==null||!guard.dictionaryOpening||!guard.overlayVisible||guard.overlayWindow==null){finish();return;}
        host=guard.overlay;
        try{
            JSONArray choices=Links.dictionaries(this);
            for(int n=0;n<choices.length();n++){
                ComponentName c=ComponentName.unflattenFromString(choices.getJSONObject(n).getString("component"));
                if(c!=null)packages.add(c.getPackageName());
            }
            ImageView frozen=freezeStart();guard.dictionaryHost=this;guard.overlayWindow.addView(frozen,new android.widget.FrameLayout.LayoutParams(-1,-1));
            guard.overlayWindow.removeView(host.root);setContentView(host.root);
            host.firstFrameCallback=this::readyForDictionary;
            host.resumeOverlay();
        }catch(Exception e){guard.finishDictionaryRequest("사전 화면을 열지 못했습니다.");if(guard.dictionaryHost==this)returnToStart();else{guard.dictionaryOpening=false;guard.revealOverlay();finish();}}
    }
    private ImageView freezeStart(){
        int width=host.root.getWidth(),height=host.root.getHeight();
        ImageView frozen=new ImageView(this);frozen.setBackgroundColor(UiTheme.background(this));
        if(width>0&&height>0){
            Bitmap next=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            host.root.draw(new Canvas(next));clearCover();cover=next;
            frozen.setScaleType(ImageView.ScaleType.FIT_XY);frozen.setImageBitmap(cover);
        }
        return frozen;
    }
    void clearCover(){cover=null;} // Detached ImageViews release their bitmap without recycling an in-flight display list.
    void coverStart(){if(host!=null){ImageView frozen=freezeStart();setContentView(frozen);}}
    @Override protected void onResume(){super.onResume();if(host!=null&&!returning)host.resumeOverlay();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);UiTheme.window(this);if(host!=null)host.applyTheme();}
    void readyForDictionary(){
        if(returning||launched||isFinishing()||guard==null)return;
        if(!guard.dictionaryReady(this)){returnToStart();return;}
        clearCover();launched=true;
        search(guard.dictionaryWord,error->{guard.finishDictionaryRequest(error);if(error!=null)returnToStart();});
    }
    boolean allows(String pkg){return pkg.equals(getPackageName())||packages.contains(pkg)||pkg.equals("android")||pkg.contains("intentresolver");}
    void search(String word,java.util.function.Consumer<String> result){
        if(returning||guard==null||guard.dictionaryHost!=this){result.accept("사전 검색을 다시 시도해 주세요.");return;}
        try{Links.dictionary(this,word);guard.useUtility("사전");result.accept(null);}
        catch(Exception e){result.accept("사전을 열지 못했습니다. 다시 선택해 주세요.");}
    }
    void returnToStart(){
        if(returning)return;returning=true;
        if(guard!=null){guard.main.removeCallbacks(foreignWindowSettled);guard.returnDictionary(this);}
        else finish();
    }
    @Override public void onBackPressed(){returnToStart();}
    @Override protected void onDestroy(){
        if(guard!=null){guard.main.removeCallbacks(foreignWindowSettled);if(guard.dictionaryHost==this){returning=true;guard.returnDictionary(this);}}
        clearCover();super.onDestroy(); // The service owns the WebView; never close it here.
    }
}
