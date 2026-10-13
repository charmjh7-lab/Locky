package com.locky.app;
import android.content.*;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class IntegrationTest {
    @Test public void storedPurposeMemoChatAndBudgetSurviveActivityRestart() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();Store s=Store.get(c);
        JSONObject purpose=s.purpose(new JSONObject().put("id","test-purpose").put("label","테스트 연락 확인"),"*");assertEquals("테스트 연락 확인",purpose.getString("label"));
        s.note(new JSONObject().put("id","test-note").put("title","오늘의 할 일").put("body","메모 저장 확인").put("checks",new JSONArray().put(new JSONObject().put("text","책 읽기").put("done",true))));
        String chat=s.newChat();s.message(chat,"user","기억할 메시지","complete");assertEquals("기억할 메시지",s.rows("SELECT body FROM messages WHERE chat=?",chat).getJSONObject(0).getString("body"));
        long now=System.currentTimeMillis();s.usage(now-60000,now,"test.pkg","테스트 앱",true,0);assertEquals(60000,s.used("test.pkg",now));
        UiDevice d=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));assertTrue(d.wait(Until.hasObject(By.textContains("Locky")),10000));d.pressHome();c.startActivity(new Intent(c,MainActivity.class).putExtra("screen","home").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));assertTrue(d.wait(Until.hasObject(By.textContains("무엇을")),10000));
        assertEquals("메모 저장 확인",s.rows("SELECT body FROM notes WHERE id='test-note'").getJSONObject(0).getString("body"));
        s.deleteChat(chat);assertEquals(0,s.rows("SELECT id FROM messages WHERE chat=?",chat).length());
    }
}
