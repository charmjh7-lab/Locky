const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,args:['--no-sandbox']}:{})});
 try{
  const page=await browser.newPage({viewport:{width:390,height:780}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<html><head><style>'+fs.readFileSync(path.join(__dirname,'../app/src/main/assets/style.css'),'utf8')+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8')});
  await page.evaluate(()=>{
   const purposes=Array.from({length:12},(_,i)=>({id:'p'+i,label:'목적 '+i,scope:'phone'}));
   window.fixture={role:'PHONE_START',overlay:false,purposeDate:'2026-10-16',purposeOrders:{phone:purposes},purposes,allPurposes:purposes,prefs:{enabled:true,start:540,end:1260},status:{accessibility:true},gate:{mode:'app',pkg:'background.video'},notes:[{id:'n',title:'메모',body:'유지되는 메모',mode:'text'}],chats:[],accounts:[],models:[],connection:{},phoneSession:{},dictionary:'process:dictionary/Popup',rules:[]};window.calls=[];
   window.Native={request:(id,action,raw)=>{const a=JSON.parse(raw);calls.push({action,args:a});if(id==='0')return;if(action==='remember')fixture.phoneSession=a;if(action==='saveNote')Object.assign(fixture.notes[0],a);const value=action==='state'?fixture:action==='purposeOrder'?{date:fixture.purposeDate,scope:'phone',purposes:fixture.purposes}:action==='dictionary'?{launched:true}:action==='messages'?[]:{};setTimeout(()=>receive(id,value,null),0);}};state=fixture;restored=true;screen='unlock';render();
  });
  // Layout identity with the previous phone presentation, both themes, no nav.
  for(const dark of [false,true]){
   await page.evaluate(dark=>{themeChanged(dark);state.role='PHONE_START';state.overlay=false;render();},dark);
   await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
   const phone=await page.locator('.start-page').boundingBox();const controls=await page.locator('.quick-tools,.dictionary-bar,.start-purposes,.start-footer').evaluateAll(xs=>xs.map(x=>{const r=x.getBoundingClientRect();return [r.x,r.y,r.width,r.height];}));
   assert.equal(await page.locator('.bottom').count(),0);assert.match(await page.locator('.top').textContent(),/휴대폰 시작/);
   await page.evaluate(()=>{state.role='LEGACY_OVERLAY';state.overlay=true;render();});
   await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
   assert.deepEqual(await page.locator('.start-page').boundingBox(),phone);assert.deepEqual(await page.locator('.quick-tools,.dictionary-bar,.start-purposes,.start-footer').evaluateAll(xs=>xs.map(x=>{const r=x.getBoundingClientRect();return [r.x,r.y,r.width,r.height];})),controls);
  }
  await page.evaluate(()=>{state.role='PHONE_START';state.overlay=false;render();window.nodes=['#noteBody','#quickQuestion','#word','.preset-scroll'].map(s=>document.querySelector(s));});
  await page.locator('#quickQuestion').fill('질문 초안');await page.locator('#word').fill('dictionary');
  await page.evaluate(async()=>{await refresh();window.dispatchEvent(new Event('visibilitychange'));prepareStart({date:state.purposeDate,scope:'phone',purposes:state.purposes});});
  assert.equal(await page.evaluate(()=>nodes.every(n=>n.isConnected)),true);assert.equal(await page.locator('#quickQuestion').inputValue(),'질문 초안');
  await page.evaluate(()=>back());assert.equal(await page.evaluate(()=>screen),'unlock');assert.equal(await page.evaluate(()=>calls.some(x=>x.action==='exit'||x.action==='stop')),false);
  await page.evaluate(()=>sheet('<p>목적 편집</p>'));await page.evaluate(()=>back());assert.equal(await page.locator('#sheet').textContent(),'');
  await page.evaluate(()=>navigate('notes'));await page.evaluate(()=>back());await page.waitForFunction(()=>screen==='unlock');
  await page.locator('#dictionaryForm').evaluate(form=>form.requestSubmit());await page.waitForFunction(()=>!dictionarySearching);assert.equal(await page.locator('#word').inputValue(),'');assert.equal(await page.locator('#quickQuestion').inputValue(),'질문 초안');
  await page.evaluate(()=>remember());await page.waitForFunction(()=>fixture.phoneSession.quickQuestion==='질문 초안');
  await page.evaluate(async()=>{fixture.phoneSession={screen:'chat',chat:'saved-chat',chatReturn:'chats',chatsReturn:'unlock'};fixture.chatReturn='chats';fixture.chatsReturn='unlock';restored=false;chatId='saved-chat';screen='chat';await refresh(true);});
  await page.evaluate(()=>back());await page.waitForFunction(()=>screen==='chats');await page.evaluate(()=>back());await page.waitForFunction(()=>screen==='unlock');
  await page.evaluate(()=>{state.phoneCycle=1;navigationReleasedCycle=-1;window.releaseBefore=calls.filter(c=>c.action==='releaseNavigation').length;});
  await page.locator('#purposeQuestion').click();assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='releaseNavigation').length),await page.evaluate(()=>releaseBefore));
  await page.locator('#noteBody').click();await page.locator('#quickQuestion').click();await page.locator('#word').click();
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='releaseNavigation').length),await page.evaluate(()=>releaseBefore+1));
  await page.evaluate(()=>{state.phoneCycle=2;render();});await page.locator('[data-act="openChat"]').first().click();await page.waitForFunction(()=>renderedRoute==='chats'&&!refreshing);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='releaseNavigation').at(-1).args.cycle),2);
  // Actual app icons supplement phone purposes without replacing button actions or geometry.
  await page.evaluate(async()=>{await navigate('unlock');state.purposes[0].pkg='linked.app';state.purposes[1].uri='https://example.com';state.purposes[2].pkg='missing.app';state.purposeIcons={[state.purposes[0].id]:'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="32" height="32" fill="green"/></svg>'};render();window.iconNodes=['#noteBody','#word','.preset-scroll'].map(s=>document.querySelector(s));});
  const before=await page.locator('.start-purposes').boundingBox();
  assert.equal(await page.locator('.linked-app-icon').count(),1);
  const iconButton=await page.locator('[data-purpose="p0"]').boundingBox();assert(iconButton.height<=60);
  await page.locator('.linked-app-icon').click();await page.waitForFunction(()=>!purposeStarting);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='choose').at(-1).args.id),'p0');
  assert.equal(await page.locator('[data-purpose="p1"] svg').count(),1);assert.equal(await page.locator('[data-purpose="p2"] svg').count(),1);
  assert.equal(await page.locator('[data-purpose="p3"] img,[data-purpose="p3"] svg').count(),0);
  await page.evaluate(()=>{document.querySelector('.linked-app-icon').dispatchEvent(new Event('error'));});
  assert.equal(await page.locator('.purpose-link-icon.icon-failed svg').isVisible(),true);
  assert.deepEqual(await page.locator('.start-purposes').boundingBox(),before);
  assert.equal(await page.evaluate(()=>iconNodes.every(n=>n.isConnected)),true);
  await page.evaluate(async()=>{state.role=fixture.role='MAIN';state.purposeEditor=fixture.purposeEditor='linked.app';managePurposes('app');});
  await page.locator('[data-act="finishPurposeEditing"]').click();await page.waitForFunction(()=>calls.some(c=>c.action==='finishPurposeEditing'));
  assert.deepEqual(errors,[]);console.log('PASS phone owner: existing light/dark geometry, no main nav, retained editors/drafts/daily order, root/sheet/notes Back, dictionary return, separate session snapshot.');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
