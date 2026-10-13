package com.locky.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Receives the browser response and releases the browser before remote account verification. */
final class LoginCallback implements AutoCloseable {
    private final ServerSocket server;
    private volatile Socket accepted;
    LoginCallback() throws IOException {
        server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(300000);
    }
    String redirect() { return "http://127.0.0.1:"+server.getLocalPort()+"/auth/callback"; }
    Map<String,String> await(String state) throws IOException {
        long deadline=System.currentTimeMillis()+300000;
        while(System.currentTimeMillis()<deadline) {
            server.setSoTimeout((int)Math.max(1,deadline-System.currentTimeMillis()));
            Socket socket=server.accept();
            try {
                socket.setSoTimeout(5000);
                String first=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
                String[] line=first==null?new String[0]:first.split(" ");
                Map<String,String> params=new LinkedHashMap<>();
                if(line.length==3&&"GET".equals(line[0])) {
                    URI u=URI.create(line[1]);
                    if("/auth/callback".equals(u.getPath())&&!u.isAbsolute()&&u.getRawAuthority()==null) {
                        String query=u.getRawQuery();
                        if(query!=null)for(String pair:query.split("&")) {
                            String[] p=pair.split("=",2);
                            String key=URLDecoder.decode(p[0],"UTF-8");
                            if(params.containsKey(key))throw new IllegalArgumentException("Duplicate callback parameter");
                            params.put(key,p.length==2?URLDecoder.decode(p[1],"UTF-8"):"");
                        }
                        if(MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),params.getOrDefault("state","").getBytes(StandardCharsets.UTF_8))) {
                            accepted=socket;
                            reply(socket,200,"로그인 응답을 받았어.\n앱으로 돌아가면 계정 연결을 마무리할게.");
                            return params;
                        }
                    }
                }
                reply(socket,400,"올바르지 않은 로그인 응답이야.");
            } catch(IllegalArgumentException|SocketTimeoutException ignored) {
                try { reply(socket,400,"올바르지 않은 로그인 응답이야."); } catch(IOException ignoredWrite) {}
            } finally { socket.close(); }
        }
        throw new SocketTimeoutException("로그인 시간이 지났어. 다시 연결해줘.");
    }
    private static String escape(String text) {
        return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
    private static void reply(Socket socket,int code,String message) throws IOException {
        String html="<!doctype html><html lang=ko><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Locky 앱으로 돌아가기</title>"
            +"<style>body{font:17px system-ui;background:#f7f7f2;color:#23352b;max-width:600px;margin:12vh auto;padding:24px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;line-height:1.7}a{display:inline-block;background:#52725c;color:white;padding:16px 22px;border-radius:12px;text-decoration:none}</style>"
            +"<h2>Locky</h2><pre>"+escape(message)+"</pre>"
            +"<p><a href='intent://connection#Intent;scheme="+BuildConfig.APP_SCHEME+";package="+BuildConfig.APPLICATION_ID+";end'>앱으로 돌아가기</a></p><p>버튼이 열리지 않으면 이 창을 닫고 앱으로 돌아가줘. 앱에서 연결을 마무리하고 결과를 보여줄게.</p></html>";
        byte[] body=html.getBytes(StandardCharsets.UTF_8);
        OutputStream out=socket.getOutputStream();
        out.write(("HTTP/1.1 "+code+(code==200?" OK":" Bad Request")+"\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);out.flush();
    }
    @Override public void close() {
        try {server.close();}catch(IOException ignored){}
        if(accepted!=null)try {accepted.close();}catch(IOException ignored){}
    }
}
