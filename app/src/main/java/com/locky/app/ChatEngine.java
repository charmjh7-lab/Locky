package com.locky.app;

import android.content.Context;
import org.json.*;
import okhttp3.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class ChatEngine {
    final Store store; final ChatGPT auth; final AtomicBoolean busy=new AtomicBoolean();volatile String active="";volatile String error="";
    ChatEngine(Context c,ChatGPT auth) {store=Store.get(c);this.auth=auth;}
    void send(String id,String text,String model) throws Exception {
        if(text.trim().isEmpty()||text.length()>20000)throw new IOException("메시지를 1~20,000자로 입력해 주세요.");
        if(model.isEmpty())throw new IOException("ChatGPT 연결 후 모델을 선택해 주세요.");
        if(store.rows("SELECT id FROM chats WHERE id=?",id).length()==0)throw new IOException("대화를 찾지 못했습니다.");
        if(!busy.compareAndSet(false,true))throw new IOException("답변을 받고 있습니다. 잠시 기다려 주세요.");active=id;error="";
        store.message(id,"user",text,"complete");long reply=store.message(id,"assistant","","streaming");
        LockyApp.app.work.execute(()->{
            StringBuilder result=new StringBuilder();JSONArray citations=new JSONArray();boolean completed=false;
            try {
                JSONArray input=new JSONArray(),messages=store.rows("SELECT role,body,status FROM messages WHERE chat=? ORDER BY id",id);
                // Keep the complete local history. Only send completed turns; never fabricate a failed response.
                for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if(m.optString("status").equals("complete"))input.put(new JSONObject().put("role",m.getString("role")).put("content",m.getString("body")));}
                JSONObject req=new JSONObject().put("model",model).put("input",input).put("instructions","당신은 Locky의 간단한 대화 도우미입니다. 사용자의 언어로 정확하고 자연스럽게 답하세요. 간단한 질문에는 간결하게 답하고, 더 자세한 설명을 요청하면 충분히 설명하세요. 최신 정보나 확인이 필요한 질문에는 활성화된 웹 검색을 사용하세요. 실제로 조회하지 않은 최신 정보는 확인한 것처럼 말하지 마세요.").put("store",false).put("stream",true).put("tools",new JSONArray().put(new JSONObject().put("type","web_search")));
                Call call=auth.http.newCall(new Request.Builder().url(ChatGPT.RESOURCE+"/responses").header("Authorization","Bearer "+auth.access()).header("Accept","text/event-stream").post(RequestBody.create(req.toString(),MediaType.get("application/json"))).build());auth.running=call;
                try(Response response=call.execute()) {
                    if(!response.isSuccessful())throw new IOException("ChatGPT 요청 실패 ("+response.code()+"). 계정 연결과 요금제 사용량을 확인해 주세요.");
                    BufferedReader reader=new BufferedReader(new InputStreamReader(response.body().byteStream(),java.nio.charset.StandardCharsets.UTF_8));String line;long saved=0;
                    while((line=reader.readLine())!=null)if(line.startsWith("data: ")&&!line.equals("data: [DONE]")) {
                        JSONObject event=new JSONObject(line.substring(6));String type=event.optString("type");
                        if(type.equals("response.output_text.delta")){result.append(event.optString("delta"));if(System.currentTimeMillis()-saved>450){store.updateMessage(reply,result.toString(),"streaming");saved=System.currentTimeMillis();}}
                        if(type.equals("response.output_text.annotation.added")){JSONObject annotation=event.optJSONObject("annotation");if(annotation!=null&&annotation.optString("type").equals("url_citation"))citations.put(annotation);}
                        if(type.equals("response.completed")){completed=true;JSONObject finalResponse=event.optJSONObject("response");if(finalResponse!=null){JSONArray output=finalResponse.optJSONArray("output");if(output!=null)for(int j=0;j<output.length();j++){JSONArray content=output.getJSONObject(j).optJSONArray("content");if(content!=null)for(int k=0;k<content.length();k++){JSONObject part=content.getJSONObject(k);JSONArray refs=part.optJSONArray("annotations");if(refs!=null)for(int n=0;n<refs.length();n++)if(refs.getJSONObject(n).optString("type").equals("url_citation"))citations.put(refs.getJSONObject(n));}}}}
                        if(type.equals("response.failed")||type.equals("response.incomplete")||type.equals("error"))throw new IOException("답변이 완료되지 않았습니다. 요금제 사용량을 확인하거나 다시 시도해 주세요.");
                    }
                    if(!completed)throw new IOException("연결이 끊겨 답변을 마치지 못했습니다.");
                }
            } catch(Exception e){error=ChatGPT.safeError(e);}finally {store.citations(reply,citations);store.updateMessage(reply,result.toString(),completed?"complete":"interrupted");auth.running=null;busy.set(false);WebHost.refreshAll();}
        });
    }
}
