package com.locky.app;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.browser.customtabs.*;

public final class MainActivity extends Activity {
    WebHost host;
    private CustomTabsServiceConnection browserConnection;
    private boolean browserBound;
    private int browserGeneration;
    static Intent launchIntent(Context c){return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(c,MainActivity.class));}
    static boolean connectionReturn(Intent i){return i!=null&&Intent.ACTION_VIEW.equals(i.getAction())&&i.getData()!=null&&"locky".equals(i.getData().getScheme())&&"connection".equals(i.getData().getHost());}
    @Override public void onCreate(Bundle state){super.onCreate(state);androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);Store s=Store.get(this);if(!s.settings.contains("installed"))s.settings.edit().putLong("installed",System.currentTimeMillis()).apply();Intent incoming=getIntent();setIntent(launchIntent(this));host=new WebHost(this,false);setContentView(host.root);String screen=connectionReturn(incoming)?"chats":incoming.getStringExtra("screen");host.screen(screen==null?s.settings.getString("last-screen",GuardService.live==null?"setup":"home"):screen);}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(launchIntent(this));if(connectionReturn(i))host.screen("chats");else if(i.hasExtra("screen"))host.screen(i.getStringExtra("screen"));}
    @Override protected void onResume(){super.onResume();LockyApp.app.auth.foreground(true);Scheduler.schedule(this);if(GuardService.live!=null)GuardService.live.pauseForActivity();WebHost.refreshAll();if(!java.util.List.of("starting","checking","browser","returning","exchange","identity","saving").contains(LockyApp.app.auth.connection().optString("phase")))releaseLoginBrowser();}
    @Override protected void onPause(){LockyApp.app.auth.foreground(false);host.evaluate("remember()");super.onPause();}
    @Override public void onBackPressed(){host.evaluate("back()");}
    @Override protected void onDestroy(){releaseLoginBrowser();host.close();super.onDestroy();}
    void loginBrowser(String url){runOnUiThread(()->{
        if(!LockyApp.app.auth.isLoginUrl(url)||isDestroyed())return;
        releaseLoginBrowser();
        final int generation=browserGeneration;
        final boolean[] launched={false};
        java.util.function.Consumer<CustomTabsSession> launch=session->{
            if(launched[0]||generation!=browserGeneration||isDestroyed()||!LockyApp.app.auth.isLoginUrl(url)||!"browser".equals(LockyApp.app.auth.connection().optString("phase")))return;
            launched[0]=true;
            try {
                CustomTabsIntent tab=new CustomTabsIntent.Builder(session).setShowTitle(true).setSendToExternalDefaultHandlerEnabled(true).build();
                if(browserPackage!=null)tab.intent.setPackage(browserPackage);
                tab.launchUrl(this,Uri.parse(url));
            } catch(Exception e){LockyApp.app.auth.cancelLogin();LockyApp.app.auth.connection("failed","로그인 브라우저를 열지 못했어.",ChatGPT.safeError(e));releaseLoginBrowser();WebHost.refreshAll();}
        };
        try {
            browserPackage=CustomTabsClient.getPackageName(this,null);
            LockyApp.app.authBrowser=browserPackage==null?"":browserPackage;
            if(browserPackage==null){launch.accept(null);return;}
            browserConnection=new CustomTabsServiceConnection(){
                @Override public void onCustomTabsServiceConnected(ComponentName name,CustomTabsClient client){client.warmup(0);launch.accept(client.newSession(new CustomTabsCallback()));}
                @Override public void onServiceDisconnected(ComponentName name){}
            };
            browserBound=CustomTabsClient.bindCustomTabsService(this,browserPackage,browserConnection);
            if(!browserBound)launch.accept(null);
            else host.view.postDelayed(()->launch.accept(null),1500);
        } catch(Exception e){launch.accept(null);}
    });}
    private String browserPackage;
    void releaseLoginBrowser(){++browserGeneration;if(browserBound&&browserConnection!=null)try{unbindService(browserConnection);}catch(IllegalArgumentException ignored){}browserBound=false;browserConnection=null;browserPackage=null;LockyApp.app.authBrowser="";if(GuardService.live!=null)GuardService.live.policyChanged();}

}
