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
   window.Native={request:(id,action,raw)=>{const args=JSON.parse(raw);calls.push({action,args});if(action==='rendered')return;if(action==='dictionary'){window.pendingLookup=id;return;}if(action==='dictionaryChoice')fixture.dictionary=args.id;
    setTimeout(()=>receive(id,action==='state'?fixture:action==='dictionaries'?[{id:'process:dictionary/Popup',label:'작은 사전'}]:{},null),0);}};
   state=fixture;screen='unlock';render();
  });
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
  assert.equal(await page.evaluate(()=>calls.filter(x=>['dismiss','stop','choose'].includes(x.action)).length),0);assert.deepEqual(errors,[]);
  console.log('PASS: deferred dispatch, success-only clearing, failure/retry, duplicate submit, newer edit, choice/cancel, repeated search, draft/scroll preservation and unlock reuse.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
