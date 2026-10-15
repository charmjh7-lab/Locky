package com.locky.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import org.json.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Opaque ordinary application window, always behind the secure Android keyguard. */
public final class PhoneStartActivity extends Activity {
    private static WeakReference<PhoneStartActivity> live=new WeakReference<>(null);
    WebHost host;
    long cycle;
    boolean resumed;volatile boolean chosen;
    private boolean dictionaryJourney;
    private final Set<String> dictionaryPackages=new HashSet<>();
    private android.window.OnBackInvokedCallback backCallback;
    static PhoneStartActivity current(){return live.get();}
    static Intent intent(Context context,long cycle){return new Intent(context,PhoneStartActivity.class)
        .putExtra("cycle",cycle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);live=new WeakReference<>(this);
        if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(false);setTurnScreenOn(false);}
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(UiTheme.background(this)));
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        cycle=saved==null?getIntent().getLongExtra("cycle",0):saved.getLong("cycle");
        chosen=saved!=null&&saved.getBoolean("chosen");
        host=new WebHost(this,WebHost.Role.PHONE_START);setContentView(host.root);
        JSONObject session=host.phoneSession();
        String screen=session.optString("screen","unlock");
        if(!Set.of("unlock","notes","chats","chat").contains(screen))screen="unlock";
        if("chat".equals(screen)&&!session.optString("chat").isEmpty())host.openChat(session.optString("chat"));else host.screen(screen);
        if(Build.VERSION.SDK_INT>=33){backCallback=this::handleBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}
        trace("onCreate webview=new restored="+(saved!=null));
    }
    void acceptCycle(long next){if(next<=cycle)return;cycle=next;chosen=false;if(host!=null)host.ensurePurposeDay();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);acceptCycle(intent.getLongExtra("cycle",cycle));trace("onNewIntent webview=reused");}
    @Override protected void onStart(){super.onStart();trace("onStart");}
    @Override protected void onResume(){
        super.onResume();resumed=true;dictionaryJourney=false;
        trace("onResume webview=reused");host.resume();host.ensurePurposeDay();
        GuardService g=GuardService.live;if(g!=null){g.pauseForActivity();g.useUtility("notes".equals(host.pendingScreen)?"메모":Set.of("chat","chats").contains(host.pendingScreen)?"ChatGPT":"첫 화면");}
        // No navigation/full refresh here. The retained document updates its own data.
    }
    @Override protected void onPause(){resumed=false;trace("onPause");host.evaluate("flushMemo();remember()");host.pause();super.onPause();}
    @Override protected void onStop(){trace("onStop");super.onStop();}
    @Override protected void onSaveInstanceState(Bundle state){state.putLong("cycle",cycle);state.putBoolean("chosen",chosen);host.evaluate("flushMemo();remember()");super.onSaveInstanceState(state);}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);host.applyTheme();}
    private void handleBack(){
        androidx.core.view.WindowInsetsCompat insets=androidx.core.view.ViewCompat.getRootWindowInsets(host.root);
        if(insets!=null&&insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())){
            androidx.core.view.WindowCompat.getInsetsController(getWindow(),host.root).hide(androidx.core.view.WindowInsetsCompat.Type.ime());return;
        }
        host.evaluate("back()");
    }
    @Override public void onBackPressed(){handleBack();}
    @Override protected void onDestroy(){trace("onDestroy finishing="+isFinishing());if(current()==this)live.clear();if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);if(host!=null)host.close();super.onDestroy();}
    void choose(JSONObject purpose) throws Exception {
        if(chosen)return;
        // Validate the explicit link before committing the purpose, so failure can retry.
        Intent linked=null;
        if(!purpose.optString("pkg").isEmpty()||!purpose.optString("uri").isEmpty()){
            linked=Links.intent(this,purpose.optString("pkg"),purpose.optString("uri"));
            if(linked==null)throw new IllegalStateException("연결한 앱이나 동작을 열 수 없습니다. 목적 연결을 확인해 주세요.");
        }
        chosen=true;
        GuardService guard=GuardService.live;
        try{if(guard!=null)guard.choosePhone(purpose);else Store.get(this).event("unlock",purpose.getString("label"),"",purpose.optString("pkg"),0,purpose.optInt("aimless",0)==1);}
        catch(Exception error){chosen=false;throw error;}
        trace("exit reason=purpose");
        host.clearPhoneSession();
        if(linked!=null){try{startActivity(linked.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(RuntimeException error){android.widget.Toast.makeText(this,"목적은 기록했지만 연결한 앱을 열지 못했습니다.",android.widget.Toast.LENGTH_LONG).show();}}
        else startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
    void dismiss(){GuardService g=GuardService.live;if(g==null)throw new IllegalStateException("화면을 잠그려면 접근성 서비스를 켜 주세요.");trace("lock requested by user");g.dismissStart();}
    void dictionary(String word) throws Exception {
        dictionaryPackages.clear();JSONArray choices=Links.dictionaries(this);
        for(int n=0;n<choices.length();n++){ComponentName component=ComponentName.unflattenFromString(choices.getJSONObject(n).getString("component"));if(component!=null)dictionaryPackages.add(component.getPackageName());}
        dictionaryJourney=true;
        try{Links.dictionary(this,word);if(GuardService.live!=null)GuardService.live.useUtility("사전");}
        catch(Exception error){dictionaryJourney=false;throw error;}
    }
    boolean allowsDictionary(String pkg){return dictionaryJourney&&(dictionaryPackages.contains(pkg)||pkg.equals("android")||pkg.contains("intentresolver"));}
    private void trace(String event){WebHost.trace("phone-activity "+event);}
}
