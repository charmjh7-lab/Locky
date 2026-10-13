package com.locky.app;

import android.content.Context;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.os.Build;
import okhttp3.*;
import org.json.*;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jose.jwk.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class ChatGPT {
    static final String ISSUER="https://auth.openai.com", RESOURCE="https://api.openai.com/v1";
    static final String TOKEN=ISSUER+"/api/accounts/oauth/token";
    final OkHttpClient http;
    private final Vault vault;
    private final Context context;
    volatile Call running;
    volatile LoginCallback listener;
    private long loginGeneration;
    private final Object loginLock=new Object(),stateLock=new Object();
    private volatile String loginUrl;
    private final LoginForeground foreground=new LoginForeground();
    ChatGPT(Context context) {
        this.context=context.getApplicationContext(); vault=new Vault(this.context);
        http=new OkHttpClient.Builder().dns(host->{
            try { List<InetAddress> addresses=Dns.SYSTEM.lookup(host);networkRecord(host,"device_dns","주소 조회 성공");return addresses; }
            catch(UnknownHostException e){networkRecord(host,"device_dns","주소 조회 실패");throw e;}
        })
            .connectTimeout(30,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).callTimeout(10,TimeUnit.MINUTES).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        String phase=connection().optString("phase");
        if(List.of("starting","checking","browser","returning","exchange","identity","saving","models").contains(phase))
            connection("failed","연결이 중단됐습니다.","로그인 도중 앱이 종료됐습니다. Continue with ChatGPT로 다시 연결해 주세요. 저장된 계정과 학습은 유지됩니다.");
    }
    JSONObject connection() { synchronized(stateLock) {
        try { return new JSONObject(context.getSharedPreferences("connection",0).getString("state","{}"))
            .put("network",new JSONObject(context.getSharedPreferences("connection",0).getString("network","{}"))); }
        catch(JSONException ignored) { return new JSONObject(); }
    }}
    private void networkRecord(String host,String route,String result) { synchronized(stateLock) {
        try {
            JSONObject report=new JSONObject(context.getSharedPreferences("connection",0).getString("network","{}"));
            JSONArray steps=report.optJSONArray("steps");if(steps==null)steps=new JSONArray();
            steps.put(new JSONObject().put("time",System.currentTimeMillis()).put("host",host).put("route",route).put("result",result).put("foreground",foreground.isForeground()));
            while(steps.length()>16)steps.remove(0);
            report.put("steps",steps);
            context.getSharedPreferences("connection",0).edit().putString("network",report.toString()).commit();
        }catch(JSONException ignored){}
    }}
    private void startNetworkReport() throws JSONException { synchronized(stateLock) {
        JSONObject report=new JSONObject().put("time",System.currentTimeMillis()).put("steps",new JSONArray());
        try {
            ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active=manager.getActiveNetwork();NetworkCapabilities caps=active==null?null:manager.getNetworkCapabilities(active);
            report.put("active",active!=null).put("foreground",foreground.isForeground()).put("restrictBackgroundStatus",manager.getRestrictBackgroundStatus());
            if(caps!=null)report.put("wifi",caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).put("mobile",caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .put("vpn",caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).put("validated",caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            if(Build.VERSION.SDK_INT>=28&&active!=null){LinkProperties link=manager.getLinkProperties(active);if(link!=null)report.put("privateDns",link.isPrivateDnsActive());}
        }catch(Exception e){report.put("stateError",safeError(e));}
        context.getSharedPreferences("connection",0).edit().putString("network",report.toString()).commit();
    }}
    void checkConnection() throws Exception {
        startNetworkReport();
        // Public metadata only: no account tokens, consent, model inference or quota usage.
        JSONObject discovery=get(http.newBuilder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build(),ISSUER+"/.well-known/openid-configuration",null);
        if(!ISSUER.equals(discovery.optString("issuer")))throw new IOException("로그인 서버의 발급자 정보가 일치하지 않습니다.");
        http.dns().lookup("api.openai.com");
        networkRecord("auth.openai.com","https","공개 인증 서버 응답 확인");
    }
    void connection(String phase,String text,String detail) { synchronized(stateLock) {
        try {
            JSONObject old=connection();JSONArray history=old.optJSONArray("history");if(history==null||phase.equals("starting"))history=new JSONArray();
            history.put(new JSONObject().put("foreground",foreground.isForeground()).put("time",System.currentTimeMillis()).put("phase",phase).put("text",text).put("detail",detail));
            while(history.length()>24)history.remove(0);
            JSONObject next=new JSONObject().put("foreground",foreground.isForeground()).put("phase",phase).put("text",text).put("detail",detail).put("time",System.currentTimeMillis()).put("history",history);
            context.getSharedPreferences("connection",0).edit().putString("state",next.toString()).commit();
        } catch(JSONException ignored) {}
    }}
    interface SignIn { void browser(String url); void progress(); void success(); void error(String message); }
    static String random() { byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    JSONObject get(String url, String token) throws Exception {
        return get(http,url,token);
    }
    private JSONObject get(OkHttpClient client,String url,String token) throws Exception {
        Request.Builder b=new Request.Builder().url(url); if(token!=null) b.header("Authorization","Bearer "+token);
        try(Response r=client.newCall(b.build()).execute()) { String body=r.body().string(); if(!r.isSuccessful()) throw new IOException(httpError("HTTP",r,body)); return new JSONObject(body); }
    }
    JSONObject form(String url, Map<String,String> values) throws Exception {
        if(!url.startsWith(ISSUER+"/")) throw new IOException("Untrusted authorization endpoint");
        FormBody.Builder b=new FormBody.Builder(); for(var e:values.entrySet()) b.add(e.getKey(),e.getValue());
        try(Response r=http.newCall(new Request.Builder().url(url).post(b.build()).build()).execute()) {
            String raw=r.body().string(); if(!r.isSuccessful()) throw new IOException(httpError("OAuth HTTP",r,raw));
            return raw.isBlank()?new JSONObject():new JSONObject(raw);
        }
    }
    synchronized JSONArray accounts() throws Exception {
        JSONObject all=vault.read(); JSONArray p=all.getJSONArray("profiles"), out=new JSONArray();
        for(int i=0;i<p.length();i++) { JSONObject a=p.getJSONObject(i); out.put(new JSONObject().put("client",a.getString("client")).put("email",a.optString("email")).put("active",a.getString("client").equals(all.optString("active"))).put("connected",a.has("access_token")).put("plan",Arrays.asList(a.optString("scope").split("\\s+")).contains("chatgpt.tokens.use.direct"))); }
        return out;
    }
    synchronized void select(String client) throws Exception {
        JSONObject all=vault.read(); boolean found=false; JSONArray ps=all.getJSONArray("profiles");
        for(int i=0;i<ps.length();i++) if(ps.getJSONObject(i).getString("client").equals(client)) found=true;
        if(!found) throw new IOException("Account not found"); all.put("active",client); vault.write(all);
    }
    private JSONObject profile(JSONObject all, String client) throws Exception {
        JSONArray ps=all.getJSONArray("profiles"); for(int i=0;i<ps.length();i++) if(ps.getJSONObject(i).getString("client").equals(client)) return ps.getJSONObject(i);
        return null;
    }
    void signIn(String selected,boolean consent,SignIn callback) {
        final long generation;
        synchronized(loginLock) {
            cancelLogin();generation=++loginGeneration;foreground.begin(generation);
            connection("starting","ChatGPT 연결을 준비하고 있습니다.","");
        }
        callback.progress();
        new Thread(() -> {
            LoginCallback local=null;String stage="로그인 준비";
            try {
                JSONObject all; synchronized(this){all=vault.read();}
                JSONObject old=selected.isBlank()?all.optJSONObject("pending_registration"):profile(all,selected);
                if(!selected.isBlank()&&old==null)throw new IOException("저장된 계정을 찾지 못했습니다.");
                stage="연결 검사";loginProgress(generation,"checking","로그인 서버 연결을 확인하고 있습니다.","",callback);
                checkConnection();ensureLogin(generation);
                String host=context.getSharedPreferences("settings",0).getString("host",null);
                if(host==null) { host="urn:uuid:"+UUID.randomUUID(); context.getSharedPreferences("settings",0).edit().putString("host",host).commit(); }
                local=new LoginCallback();
                synchronized(loginLock){ensureLogin(generation);listener=local;}
                String redirect=local.redirect();
                String state=random(), nonce=random(), verifier=random();
                String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                Uri.Builder uri=Uri.parse(ISSUER+"/api/accounts/authorize").buildUpon();
                Map<String,String> params=new LinkedHashMap<>(Map.of("client_id",old==null?"dynamic_agent_client":old.getString("client"),"ext_agent_host_id",host,"response_type","code","redirect_uri",redirect,"scope","openid profile email offline_access resource.invoke chatgpt.tokens.use.direct","resource",RESOURCE,"state",state,"nonce",nonce,"code_challenge_method","S256","code_challenge",challenge));
                if(old==null) params.put("agent_name_hint","Locky");
                else { if(old.has("id_token")) params.put("id_token_hint",old.getString("id_token")); if(old.has("email")) params.put("login_hint",old.getString("email")); }
                if(consent)params.put("prompt","consent");
                for(var e:params.entrySet()) uri.appendQueryParameter(e.getKey(),e.getValue());
                stage="브라우저 승인";loginProgress(generation,"browser","브라우저에서 로그인을 완료해 주세요.","승인 응답 화면에서 ‘앱으로 돌아가기’를 선택해 주세요. 앱에서 연결을 마무리합니다.",callback);
                synchronized(loginLock){ensureLogin(generation);loginUrl=uri.build().toString();}
                foreground.browser(generation);callback.browser(loginUrl);
                Map<String,String> result=local.await(state);
                ensureLogin(generation);
                if(result.containsKey("error"))throw new IOException("로그인이 승인되지 않았습니다: "+result.get("error"));
                String client=result.get("client_id");
                if(old!=null) { if(client!=null&&!client.equals(old.getString("client"))) throw new IOException("Account registration mismatch"); client=old.getString("client"); }
                if(client==null||client.equals("dynamic_agent_client")||client.isBlank()) throw new IOException("Issued client ID missing");
                String code=result.get("code");if(code==null||code.isBlank())throw new IOException("로그인 승인 코드가 없습니다.");
                // Retain the issued registration even if the user never returns; keep the code only in memory.
                stage="등록 저장";
                if(old==null||!old.has("sub"))synchronized(this){synchronized(loginLock){ensureLogin(generation);JSONObject pending=vault.read();pending.put("pending_registration",new JSONObject().put("client",client));vault.write(pending);}}
                stage="앱 복귀";awaitLoginForeground(generation,callback);
                stage="토큰 교환";loginProgress(generation,"exchange","앱으로 돌아왔습니다. 연결 권한을 확인하고 있습니다.","",callback);
                JSONObject tokens=form(TOKEN,Map.of("grant_type","authorization_code","client_id",client,"code",code,"code_verifier",verifier,"redirect_uri",redirect,"resource",RESOURCE));
                stage="계정 검증";loginProgress(generation,"identity","연결 권한을 받았습니다. 계정을 검증하고 있습니다.","",callback);
                JWTClaimsSet claims=validateIdentity(tokens.getString("id_token"),client,nonce,generation,callback);
                if(old!=null&&old.has("sub")&&!old.getString("sub").equals(claims.getSubject())) throw new IOException("Signed-in account differs from selected account");
                if(!"Bearer".equalsIgnoreCase(tokens.optString("token_type"))||tokens.optString("access_token").isBlank())throw new IOException("유효한 계정 연결 토큰을 받지 못했습니다.");
                JSONObject saved=new JSONObject(tokens.toString()).put("client",client).put("sub",claims.getSubject()).put("email",claims.getStringClaim("email")).put("expires_at",System.currentTimeMillis()+tokens.getLong("expires_in")*1000);
                stage="계정 저장";loginProgress(generation,"saving","검증한 계정을 저장하고 있습니다.","",callback);
                boolean plan=Arrays.asList(tokens.optString("scope").split("\\s+")).contains("chatgpt.tokens.use.direct");
                synchronized(this) { synchronized(loginLock) {
                    ensureLogin(generation);all=vault.read(); JSONArray ps=all.getJSONArray("profiles"); boolean replaced=false;
                    for(int i=0;i<ps.length();i++) if(ps.getJSONObject(i).getString("client").equals(client)) { ps.put(i,saved); replaced=true; }
                    if(!replaced) ps.put(saved); all.remove("pending_registration");all.put("active",client); vault.write(all);
                    connection(plan?"signed_in":"permission",plan?"ChatGPT 계정에 연결됐습니다.":"계정은 연결됐지만 요금제 사용 권한이 없습니다.",plan?"모델 목록을 확인합니다.":"설정에서 ‘요금제 사용 승인’을 선택해 권한을 허용해 주세요.");
                }}
                callback.success();
            } catch(Exception e) {
                String detail=stage+" 단계 · "+safeError(e);boolean current;
                synchronized(loginLock) {
                    current=generation==loginGeneration;
                    if(current)connection("failed","ChatGPT 연결을 마치지 못했습니다.",detail);
                }
                if(current)callback.error(detail);
            } finally { if(local!=null)local.close();synchronized(loginLock){if(listener==local)listener=null;if(generation==loginGeneration)loginUrl=null;} }
        },"locky-sign-in").start();
    }
    private void ensureLogin(long generation) throws IOException { synchronized(loginLock) {
        if(generation!=loginGeneration)throw new IOException("로그인이 취소됐습니다.");
    }}
    private void loginProgress(long generation,String phase,String text,String detail,SignIn callback) throws IOException {
        synchronized(loginLock){ensureLogin(generation);connection(phase,text,detail);}
        callback.progress();
    }
    void foreground(boolean resumed){foreground.setForeground(resumed);}
    private void awaitLoginForeground(long generation,SignIn callback) throws Exception {
        if(!foreground.isForeground())loginProgress(generation,"returning","브라우저 응답을 받았습니다. 앱으로 돌아와 주세요.","앱이 열리면 계정 연결을 마무리합니다.",callback);
        foreground.await(generation,TimeUnit.MINUTES.toMillis(2));ensureLogin(generation);
    }
    JWTClaimsSet validateIdentity(String raw,String client,String nonce,long generation,SignIn callback) throws Exception {
        awaitLoginForeground(generation,callback);
        JSONObject discovery=get(ISSUER+"/.well-known/openid-configuration",null);
        if(!ISSUER.equals(discovery.optString("issuer")))throw new IOException("로그인 서버의 발급자 정보가 일치하지 않습니다.");
        String jwks=discovery.getString("jwks_uri"); if(!jwks.startsWith(ISSUER+"/"))throw new IOException("Untrusted JWKS");
        awaitLoginForeground(generation,callback);
        return IdentityCheck.validate(raw,JWKSet.parse(get(jwks,null).toString()),ISSUER,client,nonce,System.currentTimeMillis());
    }
    synchronized String access() throws Exception {
        JSONObject all=vault.read(), p=profile(all,all.optString("active"));
        if(p==null||!p.has("access_token"))throw new IOException("설정에서 Continue with ChatGPT로 연결해 주세요.");
        if(!Arrays.asList(p.optString("scope").split(" ")).contains("chatgpt.tokens.use.direct"))throw new IOException("ChatGPT 요금제 사용 권한이 필요합니다. 다시 연결해 주세요.");
        if(p.optLong("expires_at")<System.currentTimeMillis()+60000) {
            JSONObject next=form(TOKEN,Map.of("grant_type","refresh_token","client_id",p.getString("client"),"refresh_token",p.getString("refresh_token"),"resource",RESOURCE));
            for(String name:List.of("access_token","refresh_token","id_token","scope","earliest_refresh_at")) if(next.has(name))p.put(name,next.get(name));
            p.put("expires_at",System.currentTimeMillis()+next.getLong("expires_in")*1000); vault.write(all);
        }
        return p.getString("access_token");
    }
    JSONArray models() throws Exception {
        JSONObject res=get(RESOURCE+"/models",access()); JSONArray models=res.getJSONArray("models"), out=new JSONArray();
        for(int i=0;i<models.length();i++) { JSONObject m=models.getJSONObject(i); if("list".equals(m.optString("visibility"))) out.put(new JSONObject().put("slug",m.getString("slug")).put("name",m.getString("display_name"))); }
        return out;
    }
    synchronized String signOut() throws Exception {
        cancel(); JSONObject all=vault.read(), p=profile(all,all.optString("active")); if(p==null)return "연결된 계정이 없습니다.";
        boolean revoked=false;
        try { if(p.has("refresh_token"))form(get(ISSUER+"/.well-known/openid-configuration",null).getString("revocation_endpoint"),Map.of("token",p.getString("refresh_token"),"token_type_hint","refresh_token","client_id",p.getString("client"))); revoked=true; } catch(Exception ignored){}
        for(String key:List.of("access_token","refresh_token","id_token","scope","expires_at"))p.remove(key); vault.write(all);
        return revoked?"로그아웃됐습니다.":"이 기기에서 로그아웃됐습니다. 원격 해제는 확인하지 못했습니다. ChatGPT 설정에서 앱 연결을 해제할 수 있습니다.";
    }
    void cancelLogin() { synchronized(loginLock) {
        ++loginGeneration;foreground.cancel();loginUrl=null;LoginCallback s=listener;listener=null;if(s!=null)s.close();
        String phase=connection().optString("phase");
        if(List.of("starting","checking","browser","returning","exchange","identity","saving").contains(phase))connection("cancelled","로그인이 취소됐습니다.","Continue with ChatGPT로 다시 연결할 수 있습니다.");
    }}
    boolean isLoginUrl(String url){return url!=null&&url.equals(loginUrl);}
    static String httpError(String prefix,Response response,String raw) {
        String request=response.header("x-request-id",response.header("request-id",""));
        String errorCode="";
        try{JSONObject e=new JSONObject(raw).optJSONObject("error");if(e!=null)errorCode=e.optString("code");}catch(JSONException ignored){}
        if(!errorCode.matches("[A-Za-z0-9_.-]{1,100}"))errorCode="";
        String safe=safeError(new IOException(raw));if(safe.length()>16000)safe=safe.substring(0,16000)+" [truncated]";
        return prefix+" "+response.code()+(request.isBlank()?"":" · request_id="+request)+(errorCode.isBlank()?"":" · error_code="+errorCode)+" · "+safe;
    }
    void cancel() { Call c=running;if(c!=null)c.cancel(); }
    static String safeError(Exception e) {
        String message=Objects.toString(e.getMessage(),e.getClass().getSimpleName());
        return message.replaceAll("(?i)(Bearer\\s+)[^\\s\"']+","$1[redacted]")
            .replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+","[token redacted]")
            .replaceAll("(?i)(\"(?:access_token|refresh_token|id_token|code|code_verifier|state|nonce)\"\\s*:\\s*)\"[^\"]*\"","$1\"[redacted]\"");
    }
}
