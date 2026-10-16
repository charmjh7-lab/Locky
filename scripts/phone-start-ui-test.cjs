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
   await page.evaluate(()=>{state.role='APP_GATE';state.overlay=true;render();});
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
  await page.evaluate(()=>{window.stateReads=calls.filter(c=>c.action==='state').length;prepareGate(10);window.gateData={gate:{pkg:'video',label:'Video',daily:600000,session:0},prefs:{start:540,end:1260},free:false,purposeDate:'2026-10-16',scope:'video',purposes:[{id:'app-p',label:'앱 목적',scope:'app'}],allPurposes:[{id:'app-p',label:'앱 목적',scope:'app'}],rules:[]};presentGate('app',gateData,11);window.minutesNode=document.querySelector('[data-min="5"]');presentGate('app',gateData,12);});
  assert.equal(await page.evaluate(()=>document.querySelector('[data-min="5"]')===minutesNode),true);
  await page.evaluate(()=>{gateData={...gateData,scope:'social',gate:{pkg:'social',label:'Social',daily:300000,session:0}};presentGate('app',gateData,13);});
  assert.equal(await page.locator('.gate-app').textContent(),'Social');assert.equal(await page.locator('#dailyLeft').textContent(),'05:00');
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='state').length),await page.evaluate(()=>stateReads));
  await page.locator('[data-purpose="app-p"]').click();await page.waitForFunction(()=>!purposeStarting);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='grant').at(-1).args.gateNavigation),13);
  assert.deepEqual(errors,[]);console.log('PASS phone owner: existing light/dark geometry, no main nav, retained editors/drafts/daily order, root/sheet/notes Back, dictionary return, separate session snapshot.');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
