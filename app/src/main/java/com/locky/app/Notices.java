package com.locky.app;

import android.app.*;
import android.content.*;
import android.os.*;

final class Notices {
    static void channels(Context c) {NotificationManager n=c.getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel("timer","남은 사용 시간",NotificationManager.IMPORTANCE_LOW));n.createNotificationChannel(new NotificationChannel("brief","사용 브리핑",NotificationManager.IMPORTANCE_DEFAULT));n.createNotificationChannel(new NotificationChannel("reminder","집중 시간 알림",NotificationManager.IMPORTANCE_DEFAULT));}
    static PendingIntent open(Context c,String screen) {return PendingIntent.getActivity(c,screen.hashCode(),new Intent(c,MainActivity.class).putExtra("screen",screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static void timer(Context c,String label,long daily,long session) {
        Notification n=new Notification.Builder(c,"timer").setSmallIcon(R.drawable.icon_mono).setContentTitle(label+" · 오늘 남은 시간").setContentText("이번 사용 "+format(session)+" · 눌러서 자세히 보기").setWhen(System.currentTimeMillis()+daily).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true).setContentIntent(open(c,"timer")).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();c.getSystemService(NotificationManager.class).notify(11,n);
    }
    static void cancelTimer(Context c){c.getSystemService(NotificationManager.class).cancel(11);}
    static void send(Context c,int id,String channel,String title,String body,String screen) {c.getSystemService(NotificationManager.class).notify(id,new Notification.Builder(c,channel).setSmallIcon(R.drawable.icon_mono).setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(open(c,screen)).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());}
    static String format(long ms) {long sec=Math.max(0,ms)/1000;return String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
}
