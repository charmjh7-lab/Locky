package com.locky.app;

import android.app.Application;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Durable acceptance tests. Queued response work is counted, never sent to the network. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class QuickQuestionTest {
    Store store;ChatEngine engine;List<Runnable> responses=new ArrayList<>();
    @Before public void setup(){Store.instance=null;store=Store.get(RuntimeEnvironment.getApplication());engine=new ChatEngine(RuntimeEnvironment.getApplication(),null,responses::add);}
    @After public void cleanup(){store.close();Store.instance=null;}
    @Test public void duplicateAndLostAcknowledgmentReuseTheSameChatAndTurn() throws Exception {
        String request=UUID.randomUUID().toString();JSONObject first=engine.quickSend(request,"같은 질문","test",false);
        assertEquals(request,first.getString("id"));assertEquals(2,store.messages(request).length());assertEquals(1,responses.size());
        JSONObject duplicate=engine.quickSend(request,"같은 질문","",false);
        assertEquals(first.getLong("user"),duplicate.getLong("user"));assertEquals(first.getLong("reply"),duplicate.getLong("reply"));
        ChatEngine restarted=new ChatEngine(RuntimeEnvironment.getApplication(),null,responses::add);
        restarted.quickSend(request,"같은 질문","test",false);
        assertEquals(1,store.rows("SELECT * FROM chats").length());assertEquals(2,store.messages(request).length());assertEquals(1,responses.size());
        try{engine.quickSend(request,"다른 질문","test",false);fail();}catch(java.io.IOException expected){}
        assertEquals(2,store.messages(request).length());
    }
    @Test public void failedAcceptanceRollsBackTheEmptyChatAndCanRetryTheSameRequest() throws Exception {
        String request=UUID.randomUUID().toString();
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_reply BEFORE INSERT ON messages WHEN NEW.role='assistant' BEGIN SELECT RAISE(ABORT,'test failure'); END");
        try{engine.quickSend(request,"저장 실패","test",false);fail();}catch(android.database.sqlite.SQLiteException expected){}
        assertEquals(0,store.rows("SELECT * FROM chats").length());assertEquals(0,store.rows("SELECT * FROM messages").length());assertEquals(0,store.rows("SELECT * FROM quick_requests").length());assertFalse(engine.busy.get());assertTrue(responses.isEmpty());
        store.getWritableDatabase().execSQL("DROP TRIGGER fail_reply");engine.quickSend(request,"저장 실패","test",false);
        assertEquals(2,store.messages(request).length());assertEquals(1,responses.size());
    }
    @Test public void explicitResponseRetryReusesTheSavedUserAndAssistantRows() throws Exception {
        String request=UUID.randomUUID().toString();JSONObject first=engine.quickSend(request,"재시도 질문","test",false);
        store.updateMessage(first.getLong("reply"),"부분 답변","interrupted");engine.busy.set(false);
        JSONObject retry=engine.quickSend(request,"재시도 질문","test",true);
        assertEquals(first.getLong("user"),retry.getLong("user"));assertEquals(first.getLong("reply"),retry.getLong("reply"));assertEquals(2,store.messages(request).length());assertEquals(2,responses.size());
        engine.quickSend(request,"재시도 질문","test",true);assertEquals(2,responses.size());
        engine.busy.set(false);store.updateMessage(first.getLong("reply"),"완료","complete");engine.send(request,"추가 질문","test");
        assertEquals(4,store.messages(request).length());assertEquals(3,responses.size());
    }
    @Test public void upgradingVersionTwoRetainsExistingHistoryAndDeletedChatsCannotResurrect() throws Exception {
        String old=store.newChat();store.appendTurn(old,"기존 대화");
        // Recreate the real v2 schema, including removal of post-v2 screen evidence.
        for(String table:new String[]{"focus_config_history","briefing_results","briefing_delivery","service_observations","quick_requests","screen_states"})store.getWritableDatabase().execSQL("DROP TABLE "+table);
        store.getWritableDatabase().execSQL("ALTER TABLE usage DROP COLUMN source");store.getWritableDatabase().execSQL("ALTER TABLE usage DROP COLUMN rule_mode");store.getWritableDatabase().execSQL("ALTER TABLE usage DROP COLUMN surface");store.getWritableDatabase().setVersion(2);store.close();Store.instance=null;
        store=Store.get(RuntimeEnvironment.getApplication());assertEquals(2,store.messages(old).length());assertEquals(0,store.rows("SELECT * FROM quick_requests").length());
        engine=new ChatEngine(RuntimeEnvironment.getApplication(),null,responses::add);
        String request=UUID.randomUUID().toString();engine.quickSend(request,"삭제한 질문","test",false);store.deleteChat(request);
        try{engine.quickSend(request,"삭제한 질문","test",false);fail();}catch(java.io.IOException expected){}
        assertEquals(0,store.rows("SELECT * FROM chats WHERE id=?",request).length());
    }

    @Test public void deletingChatRemovesItsRequestAndCleanupKeepsLiveRetries() throws Exception {
        Store s=Store.get(RuntimeEnvironment.getApplication());
        String deleted=java.util.UUID.randomUUID().toString(),live=java.util.UUID.randomUUID().toString();
        s.acceptQuickQuestion(deleted,"delete");s.acceptQuickQuestion(live,"keep");s.deleteChat(deleted);
        assertEquals(0,s.rows("SELECT * FROM quick_requests WHERE chat=?",deleted).length());
        assertEquals(0,s.rows("SELECT * FROM messages WHERE chat=?",deleted).length());
        s.getWritableDatabase().execSQL("INSERT INTO quick_requests VALUES('orphan','missing',0,0)");s.cleanOldUsage();
        assertEquals(1,s.rows("SELECT * FROM quick_requests").length());assertNotNull(s.quickRequest(live,"keep"));
    }

}
