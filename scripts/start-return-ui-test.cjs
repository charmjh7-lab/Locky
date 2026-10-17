const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,args:['--no-sandbox','--no-zygote','--single-process']}:{})});
 try{
  const page=await browser.newPage({viewport:{width:390,height:780}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<html><head><style>'+fs.readFileSync(path.join(__dirname,'../app/src/main/assets/style.css'),'utf8')+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8')});
  await page.evaluate(()=>{
   window.fixture={overlay:true,prefs:{enabled:true,start:540,end:1260},status:{accessibility:true},gate:{mode:'unlock'},allPurposes:Array.from({length:20},(_,i)=>({id:'p'+i,label:'목적 '+i,scope:'phone'})),notes:[{id:'n',title:'메모',body:'보존할 메모',mode:'text'}],chats:[],accounts:[],models:[],connection:{},dictionary:'process:dictionary/Popup'};window.calls=[];window.pendingLookup=null;
   window.Native={request:(id,action,raw)=>{const args=JSON.parse(raw);calls.push({action,args});if(action==='rendered')return;if(action==='saveNote'&&window.holdMemo){window.pendingMemo=id;return;}if(action==='dictionary'){window.pendingLookup=id;return;}if(action==='dictionaryChoice')fixture.dictionary=args.id;
    setTimeout(()=>receive(id,action==='state'?fixture:action==='dictionaries'?[{id:'process:dictionary/Popup',label:'작은 사전'}]:{},null),0);}};
   state=fixture;screen='unlock';render();
  });
  // Screen-off preparation reorders existing presets, even while a memo save is pending.
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  await page.evaluate(()=>{
   window.editors=['#noteBody','#quickQuestion','#word'].map(s=>document.querySelector(s));
   window.fixed=['.start-page','.reason-picker > .direct','.start-footer'].map(s=>document.querySelector(s));
   window.buttons=new Map([...document.querySelectorAll('[data-purpose]')].map(el=>[el.dataset.purpose,el]));
   window.orderBefore=[...buttons.keys()];window.frameBefore=domRevision;
   window.signalsBefore=calls.filter(c=>c.action==='rendered').length;
   editors[0].value='보존할 메모';scheduleMemo();window.holdMemo=true;
   editors[1].value='목적 섞기 중 질문';editors[2].value='검색 초안';
   editors[1].focus();editors[1].setSelectionRange(2,5);
   document.querySelector('.preset-scroll').scrollTop=80;window.scrollBefore=document.querySelector('.preset-scroll').scrollTop;
   window.preparedRevision=prepareStart();
  });
  assert.equal(await page.evaluate(()=>preparedRevision),await page.evaluate(()=>frameBefore+1));
  assert.equal(await page.evaluate(()=>!!memoSaving&&pendingMemo!=null),true);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='rendered').length),await page.evaluate(()=>signalsBefore+1));
  assert.equal(await page.evaluate(()=>editors.every((el,i)=>el===document.querySelector(['#noteBody','#quickQuestion','#word'][i]))),true);
  assert.equal(await page.evaluate(()=>fixed.every(el=>el.isConnected)),true);
  assert.equal(await page.evaluate(()=>[...document.querySelectorAll('[data-purpose]')].every(el=>buttons.get(el.dataset.purpose)===el&&typeof el.onclick==='function')),true);
  assert.notDeepEqual(await page.locator('[data-purpose]').evaluateAll(els=>els.map(el=>el.dataset.purpose)),await page.evaluate(()=>orderBefore));
  assert.deepEqual(await page.evaluate(()=>[document.activeElement===editors[1],editors[1].selectionStart,editors[1].selectionEnd,editors[1].value,editors[2].value]),[true,2,5,'목적 섞기 중 질문','검색 초안']);
  assert.equal(await page.locator('.preset-scroll').evaluate(el=>el.scrollTop),await page.evaluate(()=>scrollBefore));
  await page.evaluate(()=>{receive(pendingMemo,{},null);window.holdMemo=false;nativeRestore();});await page.waitForFunction(()=>!memoSaving);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='rendered').at(-1).args.revision),await page.evaluate(()=>preparedRevision));
  // Unlock visibility and Activity-originated refreshes reuse the submitted DOM.
  await page.evaluate(()=>{
   window.stableRevision=domRevision;window.stableSignals=calls.filter(c=>c.action==='rendered').length;
   window.stableNodes=['.start-page','#noteBody','#quickQuestion','#word','.start-footer'].map(s=>document.querySelector(s));
   window.stateCalls=calls.filter(c=>c.action==='state').length;
   window.mutations=[];window.observer=new MutationObserver(rows=>mutations.push(...rows));observer.observe(document.querySelector('#app'),{childList:true,subtree:true,characterData:true});
   window.dispatchEvent(new Event('visibilitychange'));
  });
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='state').length),await page.evaluate(()=>stateCalls));
  await page.evaluate(()=>refresh());await page.evaluate(()=>refresh(true));
  assert.equal(await page.evaluate(()=>domRevision),await page.evaluate(()=>stableRevision));
  assert.equal(await page.evaluate(()=>mutations.length),0);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='rendered').length),await page.evaluate(()=>stableSignals));
  // Real data updates touch only changed regions and keep unrelated editors/buttons.
  await page.evaluate(()=>{fixture=structuredClone(fixture);fixture.free=true;fixture.allPurposes[0].label='변경된 목적';fixture.allPurposes.push({id:'added',label:'새 목적',scope:'phone'});});
  await page.evaluate(()=>refresh());
  assert.equal(await page.evaluate(()=>stableNodes.every(el=>el.isConnected)),true);
  assert.equal(await page.locator('[data-purpose="p0"]').textContent(),'변경된 목적');
  assert.equal(await page.locator('[data-purpose="added"]').count(),1);
  assert.equal(await page.evaluate(()=>document.querySelector('[data-purpose="p1"]')===buttons.get('p1')),true);
  // Remote memo data arriving while focused is applied after editing, without losing the input.
  await page.evaluate(()=>{document.querySelector('#noteBody').focus();fixture=structuredClone(fixture);fixture.notes[0].body='다른 화면에서 수정';});
  await page.evaluate(()=>refresh());assert.equal(await page.locator('#noteBody').inputValue(),'보존할 메모');
  await page.locator('#noteBody').blur();await page.evaluate(()=>refresh());
  assert.equal(await page.locator('#noteBody').inputValue(),'다른 화면에서 수정');
  assert.equal(await page.evaluate(()=>stableNodes[1]===document.querySelector('#noteBody')),true);
  await page.evaluate(()=>{fixture=structuredClone(fixture);fixture.notes[0].body='보존할 메모';fixture.allPurposes=fixture.allPurposes.filter(p=>p.id!=='added');observer.disconnect();});await page.evaluate(()=>refresh());
  // A sheet and a single-purpose list are unchanged preparations, not new revisions.
  await page.evaluate(()=>{sheet('<p>열린 선택 화면</p>');window.noopBefore=domRevision;prepareStart();});
  assert.equal(await page.evaluate(()=>domRevision),await page.evaluate(()=>noopBefore));
  await page.evaluate(()=>{closeSheet();window.all=state.allPurposes;state.allPurposes=all.slice(0,1);render();window.noopBefore=domRevision;prepareStart();});
  assert.equal(await page.evaluate(()=>domRevision),await page.evaluate(()=>noopBefore));
  await page.evaluate(()=>{window.beforeNavigation=domRevision;window.beforeSignals=calls.filter(c=>c.action==='rendered').length;return navigate('unlock',nativeNavigation+1);});
  assert.equal(await page.evaluate(()=>domRevision),await page.evaluate(()=>beforeNavigation));
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='rendered').length),await page.evaluate(()=>beforeSignals+1));
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='rendered').at(-1).args.navigation),await page.evaluate(()=>nativeNavigation));
  await page.evaluate(()=>{state.allPurposes=all;render();});
  const word='첫 단어 "<뜻>"',question='입력 중인 질문';await page.locator('#word').fill(word);await page.locator('#quickQuestion').fill(question);
  await page.locator('.preset-scroll').evaluate(el=>el.scrollTop=80);const scroll=await page.locator('.preset-scroll').evaluate(el=>el.scrollTop);
  await page.locator('#dictionaryForm').evaluate(form=>form.requestSubmit());await page.waitForFunction(()=>pendingLookup!==null);
  assert.equal(await page.locator('#word').inputValue(),word); // No premature clearing while Activity/dispatch is pending.
  await page.locator('#dictionaryForm').evaluate(form=>form.requestSubmit());assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='dictionary').length),1);
  await page.evaluate(()=>{receive(pendingLookup,{launched:true},null);pendingLookup=null;});await page.waitForFunction(()=>!dictionarySearching);
  assert.equal(await page.locator('#word').inputValue(),'');assert.equal(await page.evaluate(()=>dictionaryWordDraft),'');
  await page.locator('#word').blur();await page.evaluate(()=>refresh(true));
  assert.equal(await page.locator('#word').inputValue(),'');assert.equal(await page.locator('#quickQuestion').inputValue(),question);assert.equal(await page.locator('#noteBody').inputValue(),'보존할 메모');assert.equal(await page.locator('.preset-scroll').evaluate(el=>el.scrollTop),scroll);
  // Execution failure preserves both visible input and the draft across refresh/retry.
  await page.locator('#word').fill('실패한 단어');await page.locator('#dictionaryForm').evaluate(form=>form.requestSubmit());await page.waitForFunction(()=>pendingLookup!==null);
  await page.evaluate(()=>{receive(pendingLookup,null,'실행 실패');pendingLookup=null;});await page.waitForFunction(()=>!dictionarySearching);await page.evaluate(()=>refresh(true));
  assert.equal(await page.locator('#word').inputValue(),'실패한 단어');
  // A new word typed during asynchronous dispatch is not erased by an older success.
  await page.locator('#dictionaryForm').evaluate(form=>form.requestSubmit());await page.waitForFunction(()=>pendingLookup!==null);await page.locator('#word').fill('다음에 찾을 단어');
  await page.evaluate(()=>{receive(pendingLookup,{launched:true},null);pendingLookup=null;});await page.waitForFunction(()=>!dictionarySearching);
  assert.equal(await page.locator('#word').inputValue(),'다음에 찾을 단어');
  // First-time choice can cancel; choosing explicitly dispatches once and only success clears.
  await page.evaluate(()=>{fixture.dictionary='';state.dictionary='';});await page.locator('#dictionaryForm').evaluate(form=>form.requestSubmit());await page.waitForSelector('[data-dictionary]');
  assert.equal(await page.locator('#word').inputValue(),'다음에 찾을 단어');await page.locator('[data-act=closeSheet]').click();assert.equal(await page.locator('#word').inputValue(),'다음에 찾을 단어');
  await page.locator('#dictionaryForm').evaluate(form=>form.requestSubmit());await page.locator('[data-dictionary]').click();await page.waitForFunction(()=>pendingLookup!==null);
  assert.equal(await page.locator('#word').inputValue(),'다음에 찾을 단어');await page.evaluate(()=>{receive(pendingLookup,{launched:true},null);pendingLookup=null;});await page.waitForFunction(()=>!dictionarySearching);
  await page.evaluate(()=>refresh(true));assert.equal(await page.locator('#word').inputValue(),'');
  await page.evaluate(()=>navigate('unlock',nativeNavigation+1));assert.equal(await page.locator('#word').inputValue(),'');assert.equal(await page.locator('#quickQuestion').inputValue(),question);
  const before=await page.evaluate(()=>calls.filter(c=>c.action==='dictionary').length);await page.locator('#dictionaryForm').evaluate(form=>form.requestSubmit());await page.waitForFunction(()=>document.querySelector('#toast').textContent==='찾을 단어를 입력해 주세요.');assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='dictionary').length),before);
  // App entry and per-app purpose ordering are covered by the native gate tests.
  assert.equal(await page.evaluate(()=>calls.filter(x=>['dismiss','stop','choose'].includes(x.action)).length),0);assert.deepEqual(errors,[]);
  console.log('PASS: unchanged unlock visibility/refresh keeps DOM/revision; changed data patches regions; explicit navigation re-acknowledges retained DOM; partial preset reorder, editor/button identity, focus/selection/scroll, nonblocking memo save, revision/no-op/sheet handling; deferred dispatch, success-only clearing, failure/retry, duplicate submit, newer edit, choice/cancel, repeated search, draft/scroll preservation and unlock reuse.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
