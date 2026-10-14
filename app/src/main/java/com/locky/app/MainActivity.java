package com.locky.app;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import androidx.browser.customtabs.*;

public final class MainActivity extends Activity {
    WebHost host;
    private CustomTabsServiceConnection browserConnection;
    private boolean browserBound;
    private int browserGeneration;
    private static final int RAW_DOCUMENT_REQUEST=5604;
    private String pendingRawExport;
    static Intent launchIntent(Context c){return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(c,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);}
    static boolean connectionReturn(Intent i){return i!=null&&Intent.ACTION_VIEW.equals(i.getAction())&&i.getData()!=null&&"locky".equals(i.getData().getScheme())&&"connection".equals(i.getData().getHost());}
    @Override public void onCreate(Bundle state){super.onCreate(state);androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);Store s=Store.get(this);if(!s.settings.contains("installed"))s.settings.edit().putLong("installed",System.currentTimeMillis()).apply();Intent incoming=getIntent();setIntent(launchIntent(this));host=new WebHost(this,false);setContentView(host.root);String screen=connectionReturn(incoming)?"chats":incoming.getStringExtra("screen");routeScreen(screen==null?s.settings.getString("last-screen",GuardService.live==null?"setup":"home"):screen,incoming.getStringExtra("chat"));if(state!=null)pendingRawExport=state.getString("pending-raw-export");}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(launchIntent(this));if(connectionReturn(i))routeScreen("chats",null);else if(i.hasExtra("screen"))routeScreen(i.getStringExtra("screen"),i.getStringExtra("chat"));host.resume();android.util.Log.i("LockyLifecycle","new-intent task="+getTaskId());}
    private void routeScreen(String screen,String chatId){if("chat".equals(screen)&&chatId!=null&&!chatId.isBlank())host.openChat(chatId);else host.screen(screen);}
    void saveRawToDocument(String filename,String json){
        if(isFinishing()||isDestroyed())return;
        pendingRawExport=json;
        Intent file=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,filename);
        try{startActivityForResult(file,RAW_DOCUMENT_REQUEST);}catch(Exception e){pendingRawExport=null;host.evaluate("toast("+JSONObject.quote("파일 저장 화면을 열지 못했습니다.")+")");}
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putString("pending-raw-export",pendingRawExport);super.onSaveInstanceState(state);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=RAW_DOCUMENT_REQUEST)return;
        final String payload=pendingRawExport;pendingRawExport=null;
        if(result!=RESULT_OK||data==null||data.getData()==null||payload==null)return;
        final Uri destination=data.getData();
        LockyApp.app.work.execute(()->{
            String status="기록 JSON 파일을 저장했습니다.";
            try(OutputStream stream=getContentResolver().openOutputStream(destination,"wt")){
                if(stream==null)throw new java.io.IOException("파일을 열 수 없습니다.");
                stream.write(payload.getBytes(StandardCharsets.UTF_8));stream.flush();
            }catch(Exception e){status="기록 파일을 저장하지 못했습니다."; }
            final String resultMessage=status;
            runOnUiThread(()->{if(host!=null)host.evaluate("toast("+JSONObject.quote(resultMessage)+")");});
        });
    }
    @Override protected void onResume(){super.onResume();android.util.Log.i("LockyLifecycle","resume task="+getTaskId());host.resume();LockyApp.app.auth.foreground(true);Scheduler.schedule(this);if(GuardService.live!=null)GuardService.live.pauseForActivity();WebHost.refreshAll();if(!java.util.List.of("starting","checking","browser","returning","exchange","identity","saving").contains(LockyApp.app.auth.connection().optString("phase")))releaseLoginBrowser();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&host!=null)host.resume();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(host!=null)host.resume();}
    @Override protected void onPause(){LockyApp.app.auth.foreground(false);host.evaluate("remember()");host.pause();super.onPause();}
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
