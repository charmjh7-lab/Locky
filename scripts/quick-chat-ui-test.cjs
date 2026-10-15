const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,args:['--no-sandbox','--no-zygote','--single-process']}:{})});
 try{
  const page=await browser.newPage({viewport:{width:390,height:780}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<html><head><style>'+fs.readFileSync(path.join(__dirname,'../app/src/main/assets/style.css'),'utf8')+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8')});
  await page.evaluate(()=>{
   window.fixture={overlay:true,prefs:{model:'test',enabled:true,start:540,end:1260},status:{accessibility:true},gate:{mode:'unlock'},allPurposes:[{id:'p',label:'목적',scope:'phone'}],notes:[{id:'n',title:'메모',body:'보존할 메모',mode:'text'}],chats:[],accounts:[{active:true,connected:true}],models:[],connection:{},rules:[],busy:false};
   window.calls=[];window.pending=[];window.rows={};window.holdMemo=true;
   window.Native={request:(id,action,raw)=>{const a=JSON.parse(raw);calls.push({id,action,args:a});if(id==='0')return;
    if(action==='quickSend'){pending.push({id,args:a});return;}if(action==='saveNote'&&holdMemo){window.memoRequest=id;return;}
    if(action==='send'){rows[a.id].push({id:30,role:'user',body:a.text,status:'complete',citations:'[]'});}
    const value=action==='state'?fixture:action==='tick'?{busy:fixture.busy,gate:fixture.gate}:action==='messages'?(rows[a.id]||[]):{};
    setTimeout(()=>receive(id,value,null),0);
   }};state=fixture;screen='unlock';render();
   document.querySelector('#noteBody').value='저장 대기 중 메모';scheduleMemo();
   document.querySelector('#quickQuestion').value='첫 번째 질문 <그대로>';
   document.querySelector('[data-act=quickSend]').click();
   window.sentChat=chatId;window.firstQuestion=document.querySelector('.message.user');window.firstText=firstQuestion.firstElementChild;
   window.instant={screen,count:document.querySelectorAll('.message').length,text:firstQuestion.textContent};
  });
  assert.deepEqual(await page.evaluate(()=>instant),{screen:'chat',count:2,text:'첫 번째 질문 <그대로>'});
  assert.equal(await page.evaluate(()=>!!memoSaving),true);
  assert.equal(await page.evaluate(()=>calls.filter(c=>['open','newChat','send'].includes(c.action)).length),0);
  assert.equal(await page.evaluate(()=>calls.findIndex(c=>c.action==='rendered'&&c.args.screen==='chat')<calls.findIndex(c=>c.action==='quickSend')),true);
  assert.equal(await page.evaluate(()=>calls.find(c=>c.action==='present').args.navigation===calls.find(c=>c.action==='rendered'&&c.args.screen==='chat').args.navigation),true);
  await page.locator('#chatInput').fill('이어질 질문 초안');
  // Database acceptance can arrive through polling before the original API acknowledgment.
  await page.evaluate(async()=>{rows[sentChat]=[{id:11,role:'user',body:'첫 번째 질문 <그대로>',status:'complete',citations:'[]'},{id:12,role:'assistant',body:'',status:'streaming',citations:'[]',quickRequest:sentChat}];await renderMessages(false);});
  assert.equal(await page.evaluate(()=>document.querySelector('.message.user')===firstQuestion&&firstQuestion.firstElementChild===firstText),true);
  await page.evaluate(async()=>{rows[sentChat][1].body='먼저 도착한 최신 답변';rows[sentChat][1].status='complete';fixture.busy=false;await refresh();});
  await page.evaluate(()=>{const q=pending.shift();receive(q.id,{id:sentChat,user:11,reply:12,busy:true,messages:[{id:11,role:'user',body:'첫 번째 질문 <그대로>',status:'complete'},{id:12,role:'assistant',body:'',status:'streaming',quickRequest:sentChat}]},null);});
  await page.waitForFunction(()=>!quickTurns.get(sentChat).inFlight);
  assert.match(await page.locator('.message.assistant').textContent(),/먼저 도착한 최신 답변/);assert.equal(await page.evaluate(()=>state.busy),false);
  assert.equal(await page.locator('.message.user').count(),1);assert.equal(await page.locator('#chatInput').inputValue(),'이어질 질문 초안');
  assert.equal(await page.evaluate(()=>quickQuestionDraft),'');
  await page.evaluate(async()=>{fixture.busy=false;rows[sentChat][1].status='complete';rows[sentChat][1].body='**답변**';await refresh();});
  assert.equal(await page.locator('.message.assistant strong').textContent(),'답변');
  await page.locator('[data-act=send]').click();await page.waitForFunction(()=>calls.some(c=>c.action==='send'));
  assert.equal(await page.evaluate(()=>calls.find(c=>c.action==='send').args.id),await page.evaluate(()=>sentChat));
  // Save failure stays in this chat, preserving the question; retry reuses the request ID.
  await page.evaluate(async()=>{receive(memoRequest,{},null);holdMemo=false;await flushMemo();await navigate('unlock');});
  await page.locator('#quickQuestion').fill('실패 후 재시도');await page.locator('[data-act=quickSend]').click();
  const failed=await page.evaluate(()=>{const p=pending.shift();window.failedId=p.args.request;receive(p.id,null,'저장 실패');return failedId;});
  await page.waitForSelector('[data-act=retryQuick]');assert.equal(await page.evaluate(()=>screen),'chat');
  assert.equal(await page.locator('.message.user').textContent(),'실패 후 재시도');
  await page.locator('[data-act=retryQuick]').evaluate(el=>{el.click();el.click();});
  assert.equal(await page.evaluate(()=>pending.length),1);assert.equal(await page.evaluate(()=>pending[0].args.request),failed);
  // An acknowledgment after screen-off navigation must not pull the user back into chat.
  await page.evaluate(async()=>{await navigate('unlock',nativeNavigation+1);const q=pending.shift();rows[failedId]=[{id:21,role:'user',body:'실패 후 재시도',status:'complete'},{id:22,role:'assistant',body:'',status:'streaming',quickRequest:failedId}];receive(q.id,{id:failedId,user:21,reply:22,busy:true,messages:rows[failedId]},null);});
  await page.waitForFunction(()=>!quickTurns.get(failedId).inFlight);assert.equal(await page.evaluate(()=>screen),'unlock');
  await page.evaluate(async()=>{await navigate('chats');await openChatById(failedId);});
  assert.equal(await page.locator('.message.user').textContent(),'실패 후 재시도');
  assert.equal(await page.evaluate(()=>chatId),failed);assert.deepEqual(errors,[]);
  console.log('PASS: same-task immediate chat, no Activity API/newChat/send before entry, deferred memo/DB, exact ID, optimistic node adoption, follow-up draft, retry deduplication, late ACK after lock and chat-list reopen.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
