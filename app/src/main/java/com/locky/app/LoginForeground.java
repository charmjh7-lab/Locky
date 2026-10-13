package com.locky.app;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** Waits for the actual Activity return, never for a DNS workaround or a browser timer. */
final class LoginForeground {
    private boolean foreground,active;
    private long generation;
    synchronized boolean isForeground(){return foreground;}
    synchronized void setForeground(boolean resumed){foreground=resumed;notifyAll();}
    synchronized void begin(long attempt){generation=attempt;active=true;notifyAll();}
    synchronized void browser(long attempt) throws IOException {check(attempt);foreground=false;}
    synchronized void cancel(){active=false;notifyAll();}
    private void check(long attempt) throws IOException {
        if(!active||attempt!=generation)throw new IOException("로그인을 취소했어.");
    }
    synchronized void await(long attempt,long timeoutMillis) throws IOException {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(true){
            check(attempt);
            if(foreground)return;
            long left=deadline-System.nanoTime();
            if(left<=0)throw new SocketTimeoutException("앱 복귀 시간이 지났어. Continue with ChatGPT로 다시 연결해줘.");
            try{TimeUnit.NANOSECONDS.timedWait(this,left);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("로그인을 취소했어.",e);}
        }
    }
}
