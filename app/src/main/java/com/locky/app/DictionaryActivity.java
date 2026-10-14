package com.locky.app;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.widget.Toast;
import org.json.JSONArray;
import java.util.HashSet;
import java.util.Set;

/** Temporarily hosts the same start view below a dictionary's normal/popup Activity. */
public final class DictionaryActivity extends Activity {
    static final int REQUEST=7412;
    GuardService guard;
    boolean launched,away,returning;
    final Set<String> packages=new HashSet<>();
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        guard=GuardService.live;
        try{JSONArray choices=Links.dictionaries(this);for(int n=0;n<choices.length();n++){ComponentName c=ComponentName.unflattenFromString(choices.getJSONObject(n).getString("component"));if(c!=null)packages.add(c.getPackageName());}}
        catch(Exception ignored){}
        if(guard==null||!guard.borrowStart(this)){finish();return;}
        guard.main.post(()->{if(isFinishing()||isDestroyed())return;launched=true;
            try{String word=getIntent().getStringExtra("word");if(getIntent().getBooleanExtra("picker",false))Links.dictionaryPicker(this,word);else Links.dictionary(this,word);}
            catch(Exception e){Toast.makeText(this,"사전을 열지 못했습니다. 다시 선택해 주세요.",Toast.LENGTH_LONG).show();returnToStart();}
        });
    }
    boolean allows(String pkg){return pkg.equals(getPackageName())||packages.contains(pkg)||pkg.equals("android")||pkg.contains("intentresolver");}
    @Override protected void onPause(){if(launched){away=true;if(guard!=null&&guard.overlay!=null)++guard.overlay.visualGeneration;}super.onPause();}
    @Override protected void onResume(){super.onResume();if(launched&&away)returnToStart();else if(guard!=null&&guard.overlay!=null)guard.overlay.resumeOverlay();}
    void returnToStart(){if(returning)return;returning=true;if(guard!=null)guard.returnDictionary(this);finish();}
    @Override public void onBackPressed(){returnToStart();}
    @Override protected void onDestroy(){if(guard!=null)guard.returnDictionary(this);super.onDestroy();}
}
