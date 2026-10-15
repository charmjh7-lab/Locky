package com.locky.app;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class RawExportTest {
    final List<ActivityController<MainActivity>> controllers=new ArrayList<>();
    MainActivity activity;
    @Before public void setup(){Store.instance=null;GuardService.live=null;LockyApp.app=new LockyApp();activity=create(null);}
    MainActivity create(Bundle saved){ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class,MainActivity.launchIntent(RuntimeEnvironment.getApplication())).create(saved);controllers.add(c);return c.get();}
    @After public void cleanup() throws Exception {
        LockyApp.app.work.shutdown();assertTrue(LockyApp.app.work.awaitTermination(5,TimeUnit.SECONDS));
        for(ActivityController<MainActivity> c:controllers)if(!c.get().isDestroyed())c.destroy();
        if(Store.instance!=null)Store.instance.close();Store.instance=null;LockyApp.app=null;
    }
    void awaitStatus(String expected) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do{Shadows.shadowOf(Looper.getMainLooper()).idle();if(expected.equals(ShadowToast.getTextOfLatestToast()))return;Thread.sleep(10);}while(System.nanoTime()<end);
        assertEquals(expected,ShadowToast.getTextOfLatestToast());
    }
    @Test public void largeJsonSurvivesRecreationWithSmallSavedStateAndWritesExactUtf8() throws Exception {
        String json=new JSONObject().put("schemaVersion",1).put("record","한글 😀 기록\n".repeat(150000)).toString(2);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length>1024*1024);
        String token=RawExport.stage(activity,json);activity.saveRawToDocument("Locky-day-raw.json",token);
        org.robolectric.shadows.ShadowActivity.IntentForResult picker=Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(Intent.ACTION_CREATE_DOCUMENT,picker.intent.getAction());assertEquals("application/json",picker.intent.getType());
        Bundle saved=new Bundle();activity.onSaveInstanceState(saved);Parcel parcel=Parcel.obtain();parcel.writeBundle(saved);assertTrue(parcel.dataSize()<65536);parcel.recycle();
        assertEquals(token,saved.getString(RawExport.STATE_TOKEN));assertFalse(saved.containsKey("pending-raw-export"));
        controllers.get(0).destroy();activity=create(saved);
        File target=File.createTempFile("locky-export-",".json",activity.getCacheDir());
        Uri destination=Uri.parse("content://locky-export/document/success");FileOutputStream output=new FileOutputStream(target);
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(destination,output);
        activity.onActivityResult(picker.requestCode,Activity.RESULT_OK,new Intent().setData(destination));
        awaitStatus("기록 JSON 파일을 저장했습니다.");assertEquals(json,new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));assertFalse(output.getChannel().isOpen());
        assertEquals(1,new JSONObject(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8)).getInt("schemaVersion"));
        assertFalse(new File(activity.getFilesDir(),"raw-exports/"+token+".json").exists());target.delete();
    }
    @Test public void diagnosticTextExportSurvivesRecreationAndWritesARealUtf8File() throws Exception {
        String text="Locky 잠금 해제 진단\nalpha requested 1.0->1.0\nwindow focus observed=true\n";
        activity.saveDiagnosticsToDocument("Locky-unlock.txt",RawExport.stage(activity,text));
        var picker=Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertEquals("text/plain",picker.intent.getType());assertEquals("Locky-unlock.txt",picker.intent.getStringExtra(Intent.EXTRA_TITLE));
        Bundle saved=new Bundle();activity.onSaveInstanceState(saved);controllers.get(0).destroy();activity=create(saved);
        File target=File.createTempFile("locky-diagnostic-",".txt",activity.getCacheDir());Uri uri=Uri.parse("content://locky-export/document/diagnostic");
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri,new FileOutputStream(target));
        activity.onActivityResult(picker.requestCode,Activity.RESULT_OK,new Intent().setData(uri));
        awaitStatus("잠금 해제 진단 파일을 저장했습니다.");assertEquals(text,new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));target.delete();
    }
    @Test public void repeatedRequestsDoNotReplacePendingExportAndCancelCleansBothStages() throws Exception {
        String first=RawExport.stage(activity,"{\"first\":1}"),second=RawExport.stage(activity,"{\"second\":2}");
        activity.saveRawToDocument("first.json",first);activity.saveRawToDocument("second.json",second);
        org.robolectric.shadows.ShadowActivity.IntentForResult picker=Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertEquals("first.json",picker.intent.getStringExtra(Intent.EXTRA_TITLE));assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        assertFalse(new File(activity.getFilesDir(),"raw-exports/"+second+".json").exists());
        activity.onActivityResult(picker.requestCode,Activity.RESULT_CANCELED,null);
        assertFalse(new File(activity.getFilesDir(),"raw-exports/"+first+".json").exists());
        String retry=RawExport.stage(activity,"{\"retry\":true}");activity.saveRawToDocument("retry.json",retry);
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());activity.onActivityResult(picker.requestCode,Activity.RESULT_CANCELED,null);
    }
    @Test public void writeFailureReportsFailureAndAllowsAnotherAttempt() throws Exception {
        String token=RawExport.stage(activity,"{\"record\":1}");activity.saveRawToDocument("failed.json",token);
        int request=Shadows.shadowOf(activity).getNextStartedActivityForResult().requestCode;
        Uri unavailable=Uri.parse("content://locky-export/document/failure");
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(unavailable,new OutputStream(){@Override public void write(int b) throws IOException {throw new IOException("Provider write failed");}});
        activity.onActivityResult(request,Activity.RESULT_OK,new Intent().setData(unavailable));
        awaitStatus("기록 파일을 저장하지 못했습니다. 다시 시도해 주세요.");
        assertFalse(new File(activity.getFilesDir(),"raw-exports/"+token+".json").exists());
        String retry=RawExport.stage(activity,"{\"retry\":true}");activity.saveRawToDocument("retry.json",retry);
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());activity.onActivityResult(request,Activity.RESULT_CANCELED,null);
    }
}
