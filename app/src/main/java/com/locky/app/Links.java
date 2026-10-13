package com.locky.app;

import android.content.*;
import android.net.Uri;

final class Links {
    static Intent intent(Context c,String pkg,String uri) {
        if(pkg.equals(c.getPackageName()))return null;
        Intent i;if(uri.isEmpty())i=c.getPackageManager().getLaunchIntentForPackage(pkg);
        else {Uri u=Uri.parse(uri);String scheme=u.getScheme();if(scheme==null||scheme.equals("file")||scheme.equals("content")||scheme.equals("javascript")||scheme.equals("intent"))return null;i=new Intent(Intent.ACTION_VIEW,u);if(!pkg.isEmpty())i.setPackage(pkg);}
        return i!=null&&i.resolveActivity(c.getPackageManager())!=null?i:null;
    }
    static void dictionary(Context c,String word) {
        Intent samsung=c.getPackageManager().getLaunchIntentForPackage("com.diotek.sec.lookup.dictionary");
        if(samsung!=null){samsung.putExtra("android.intent.extra.TEXT",word);c.startActivity(samsung.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
        Intent process=new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT,word).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,true);
        if(process.resolveActivity(c.getPackageManager())!=null){c.startActivity(Intent.createChooser(process,"사전으로 찾기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
        c.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://dict.naver.com/search?query="+Uri.encode(word))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
