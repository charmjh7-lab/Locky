package com.locky.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import org.json.*;

final class Notices {
    static void channels(Context c) {NotificationManager n=c.getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel("timer","남은 사용 시간",NotificationManager.IMPORTANCE_LOW));n.createNotificationChannel(new NotificationChannel("brief","사용 브리핑",NotificationManager.IMPORTANCE_DEFAULT));n.createNotificationChannel(new NotificationChannel("reminder","집중 시간 알림",NotificationManager.IMPORTANCE_DEFAULT));}
    static PendingIntent open(Context c,String screen) {return PendingIntent.getActivity(c,screen.hashCode(),MainActivity.launchIntent(c).putExtra("screen",screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static boolean allowed(Context c){return Build.VERSION.SDK_INT<33||c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    static void timer(Context c,String label,long daily,long session) {
        if(!allowed(c))return;
        if(session<=0||daily<=0){cancelTimer(c);return;}
        c.getSystemService(NotificationManager.class).notify(11,timerNotification(c,label,daily,session,false));
    }
    static Notification timerNotification(Context c,String label,long daily,long session,boolean paused){
        NotificationCompat.Builder b=new NotificationCompat.Builder(c,"timer")
            .setSmallIcon(R.drawable.icon_mono).setContentTitle(label+(paused?" · 일시 정지":" · 이번 사용"))
            .setContentText("이번 사용 "+format(session)+" · 오늘 남은 "+format(daily))
            .setWhen(paused?0:System.currentTimeMillis()+Math.max(0,session)).setShowWhen(!paused)
            .setUsesChronometer(!paused).setChronometerCountDown(!paused)
            .setContentIntent(open(c,"timer")).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setRequestPromotedOngoing(Build.VERSION.SDK_INT>=36);
        if(paused)b.setShortCriticalText("일시 정지");
        Notification n=b.build();
        // Android 16's original contract requires colorized; newer framework builds forbid it.
        // Ask the device which contract it implements, rather than guessing from One UI names.
        if(Build.VERSION.SDK_INT==36&&!n.hasPromotableCharacteristics()){
            Notification legacy=b.setColorized(true).build();
            if(legacy.hasPromotableCharacteristics())return legacy;
        }
        return n;
    }
    static void paused(Context c,String label,long daily,long session){
        if(!allowed(c))return;
        if(session<=0||daily<=0){cancelTimer(c);return;}
        c.getSystemService(NotificationManager.class).notify(11,timerNotification(c,label,daily,session,true));
    }
    static JSONObject timerStatus(Context c) throws JSONException {
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        NotificationChannel channel=manager.getNotificationChannel("timer");
        Notification posted=null;for(var entry:manager.getActiveNotifications())if(entry.getId()==11){posted=entry.getNotification();break;}
        JSONObject status=new JSONObject().put("supported",Build.VERSION.SDK_INT>=36)
            .put("notifications",allowed(c)&&manager.areNotificationsEnabled())
            .put("channelImportance",channel==null?NotificationManager.IMPORTANCE_LOW:channel.getImportance())
            .put("posted",posted!=null);
        if(Build.VERSION.SDK_INT>=36){
            // Permission, eligibility, and system promotion are separate observations.
            status.put("permitted",manager.canPostPromotedNotifications())
                .put("eligible",timerNotification(c,"Locky",300000,300000,false).hasPromotableCharacteristics())
                .put("promoted",posted!=null&&(posted.flags&Notification.FLAG_PROMOTED_ONGOING)!=0);
        }
        return status;
    }
    static Intent timerSettings(Context c,boolean promotion){
        if(promotion&&Build.VERSION.SDK_INT>=36){
            Intent i=new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.getPackageName());
            if(i.resolveActivity(c.getPackageManager())!=null)return i;
        }
        Intent channel=new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,"timer");
        if(channel.resolveActivity(c.getPackageManager())!=null)return channel;
        return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.getPackageName());
    }
    static void cancelTimer(Context c){c.getSystemService(NotificationManager.class).cancel(11);}
    static void send(Context c,int id,String channel,String title,String body,String screen) {if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;c.getSystemService(NotificationManager.class).notify(id,new Notification.Builder(c,channel).setSmallIcon(R.drawable.icon_mono).setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(open(c,screen)).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());}
    static String format(long ms) {long sec=Math.max(0,ms)/1000;return String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
}

