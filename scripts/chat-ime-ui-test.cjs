const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const assets=process.env.LOCKY_ASSETS_DIR||path.join(__dirname,'../app/src/main/assets');
const frame=page=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,args:['--no-sandbox','--no-zygote','--single-process']}:{})});
 try{
  const page=await browser.newPage({viewport:{width:390,height:780}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<!doctype html><html lang="ko"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>'+fs.readFileSync(path.join(assets,'style.css'),'utf8')+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:fs.readFileSync(path.join(assets,'app.js'),'utf8')});
  await page.evaluate(()=>{
   window.fixture={overlay:false,prefs:{enabled:true,start:540,end:1260},status:{accessibility:true},free:false,gate:{mode:'unlock'},allPurposes:Array.from({length:24},(_,i)=>({id:'p'+i,label:'사용 목적 '+i,scope:'phone'})),notes:[{id:'n',title:'메모',body:'입력 중 메모',mode:'text',checks:'[]'}],chats:[{id:'c',title:'대화'}],accounts:[],models:[],connection:{},busy:false,rules:[]};
   window.records=Array.from({length:14},(_,i)=>({id:i+1,role:'assistant',body:('기록 '+i+'의 본문입니다. 읽던 위치를 유지합니다.\n\n').repeat(5),status:'complete',citations:'[]'}));
   const cited=records[5];cited.body+='참고한 문장입니다. 【0†source】';const marker='【0†source】',at=Array.from(cited.body.slice(0,cited.body.indexOf(marker))).length;
   cited.citations=JSON.stringify([{url:'https://example.org/source',title:'참고한 원문',start_index:at,end_index:at+Array.from(marker).length}]);
   window.messageReads=0;window.nextDelay=0;
   window.Native={request:(id,action,raw)=>{
    if(id==='0')return;const args=JSON.parse(raw);let out={};
    if(action==='state')out=fixture;
    if(action==='tick')out={gate:fixture.gate,free:false,busy:false};
    if(action==='messages'){messageReads++;out=JSON.parse(JSON.stringify(records));}
    if(action==='saveNote'){const n=fixture.notes[0];Object.assign(n,args,{checks:JSON.stringify(args.checks)});}
    const delay=action==='messages'?nextDelay:0;if(action==='messages')nextDelay=0;
    setTimeout(()=>receive(id,out,null),delay);
   }};
   state=fixture;screen='chat';chatId='c';render();
  });
  await page.evaluate(()=>renderMessages(true));
  await page.evaluate(()=>{const toggle=document.querySelector('.inline-reference');window.scrollTo(0,toggle.getBoundingClientRect().top+scrollY-240);startPolling();});
  await page.locator('.inline-reference').click();await frame(page);
  const y=await page.evaluate(()=>{
   window.readNode=document.querySelector('.inline-reference').closest('.message');window.sourcePanel=document.querySelector('.inline-reference').nextElementSibling;
   window.pollMutations=0;new MutationObserver(rows=>pollMutations+=rows.length).observe(document.querySelector('#messages'),{childList:true,subtree:true});
   return scrollY;
  });
  if(process.env.LOCKY_UI_EVIDENCE_DIR){fs.mkdirSync(process.env.LOCKY_UI_EVIDENCE_DIR,{recursive:true});await page.screenshot({path:path.join(process.env.LOCKY_UI_EVIDENCE_DIR,'source-inline.png')});}
  const reads=await page.evaluate(()=>messageReads);
  // Keep the real 1-second timer running through the delayed symptom, not just the click.
  for(const ms of [1100,1000,1000]){
   await page.waitForTimeout(ms);
   assert.equal(await page.evaluate(()=>scrollY),y);
   assert(await page.evaluate(()=>document.querySelector('.inline-reference').closest('.message')===readNode&&document.querySelector('.inline-reference').getAttribute('aria-expanded')==='true'&&!sourcePanel.hidden));
  }
  assert((await page.evaluate(()=>messageReads))>=reads+3);
  assert.equal(await page.evaluate(()=>pollMutations),0,'Unchanged polling must not rebuild message DOM');
  assert.equal(await page.locator('.chat-source-list:visible').evaluate(el=>getComputedStyle(el).position),'static');
  assert(await page.locator('.chat-source-list:visible').evaluate(el=>{const r=el.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth;}));
  const readTop=await page.locator('.inline-reference').evaluate(el=>el.getBoundingClientRect().top);
  await page.evaluate(()=>{records.at(-1).body+='새로 도착한 답변\n'.repeat(15);});
  await page.waitForTimeout(1100);
  assert.equal(await page.evaluate(()=>scrollY),y);
  assert(await page.evaluate(()=>document.querySelector('.inline-reference').closest('.message')===readNode));
  // Changes above the viewport preserve the same visible anchor, including an open source.
  await page.evaluate(()=>{records[0].body+='위쪽 대화의 갱신\n'.repeat(30);});
  await page.waitForTimeout(1100);
  assert(Math.abs((await page.locator('.inline-reference').evaluate(el=>el.getBoundingClientRect().top))-readTop)<2);
  await page.evaluate(()=>{records[5].body+='\n추가된 답변 내용';records[5].status='interrupted';});
  await page.waitForTimeout(1100);
  assert.equal(await page.locator('.inline-reference').getAttribute('aria-expanded'),'true');
  assert.equal(await page.locator('.chat-source-list:visible').count(),1);
  assert(Math.abs((await page.locator('.inline-reference').evaluate(el=>el.getBoundingClientRect().top))-readTop)<2);
  await page.evaluate(()=>window.scrollTo(0,document.documentElement.scrollHeight));
  await page.evaluate(()=>{records.push({id:99,role:'assistant',body:'새 메시지\n'.repeat(30),status:'streaming',citations:'[]'});});
  await page.waitForTimeout(1100);
  assert(await page.evaluate(()=>Math.abs(scrollY+innerHeight-document.documentElement.scrollHeight)<2));
  // A slow earlier read cannot replace a more recent response.
  await page.evaluate(async()=>{nextDelay=100;const stale=renderMessages(false);records.at(-1).body='가장 최근의 답변';await renderMessages(false);await stale;});
  assert.equal(await page.locator('.message').last().innerText(),'가장 최근의 답변');
  await page.evaluate(()=>{clearInterval(poll);}); // Chat regression observations above used the live timer.
  for(const viewport of [{width:390,height:780,reduced:300,overlay:true},{width:320,height:560,reduced:260,overlay:false}]){
   await page.setViewportSize({width:viewport.width,height:viewport.height});
   await page.evaluate(overlay=>{document.activeElement?.blur();nativeKeyboard=false;normalHeight=0;normalWidth=0;window.scrollTo(0,0);screen='home';state=fixture;state.overlay=overlay;noteId='n';render();},viewport.overlay);await frame(page);
   const before=await page.evaluate(()=>{
    const box=s=>{const r=document.querySelector(s).getBoundingClientRect();return {top:r.top+scrollY,height:r.height};};
    window.noteNode=document.querySelector('#noteBody');window.questionNode=document.querySelector('#quickQuestion');
    return {tools:box('.quick-tools'),memo:box('.note-content'),purposes:box('.start-purposes'),footer:box('.start-footer'),page:box('.start-page')};
   });
   await page.locator('#noteBody').fill('메모 초안\n두 번째 줄');await page.locator('#noteBody').focus();
   await page.evaluate(()=>insetsChanged(true));await frame(page);
   await page.setViewportSize({width:viewport.width,height:viewport.reduced});await frame(page);
   const after=await page.evaluate(()=>{
    const box=s=>{const r=document.querySelector(s).getBoundingClientRect();return {top:r.top+scrollY,height:r.height};};
    return {tools:box('.quick-tools'),memo:box('.note-content'),purposes:box('.start-purposes'),footer:box('.start-footer'),page:box('.start-page')};
   });
   if(process.env.LOCKY_UI_EVIDENCE_DIR)await page.screenshot({path:path.join(process.env.LOCKY_UI_EVIDENCE_DIR,'keyboard-'+viewport.width+'.png')});
   assert.deepEqual(after,before,'IME must not compress or reflow the phone start');
   assert(after.footer.top>=viewport.reduced,'Dismissal stays behind the keyboard');
   await page.locator('#quickQuestion').fill('질문 초안');
   await page.evaluate(()=>{const input=document.querySelector('#quickQuestion');input.focus();input.setSelectionRange(2,4);});await frame(page);
   assert.deepEqual(await page.locator('#quickQuestion').evaluate(el=>[el.selectionStart,el.selectionEnd]),[2,4]);
   await page.locator('#word').fill('사전 초안');await page.locator('#word').focus();await frame(page);
   assert(await page.locator('#word').evaluate(el=>{const r=el.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight;}));
   await page.setViewportSize({width:viewport.width,height:viewport.height});await page.evaluate(()=>insetsChanged(false));await frame(page);
   assert.equal(await page.evaluate(()=>scrollY),0);
   assert.equal(await page.locator('#noteBody').inputValue(),'메모 초안\n두 번째 줄');assert.equal(await page.locator('#quickQuestion').inputValue(),'질문 초안');assert.equal(await page.locator('#word').inputValue(),'사전 초안');
   assert(await page.evaluate(()=>document.querySelector('#noteBody')===noteNode&&document.querySelector('#quickQuestion')===questionNode));
   assert.deepEqual(await page.locator('.quick-tools').evaluate(el=>({top:el.getBoundingClientRect().top+scrollY,height:el.getBoundingClientRect().height})),before.tools);
  }
  assert.deepEqual(errors,[]);
  console.log('Chat/IME regressions passed: unchanged polling at 1/2/3 seconds, open citations and DOM retention, incremental/late message updates, reading anchor and bottom follow; uncompressed start tools, drafts, cursor and restoration in 2 representative windows.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
