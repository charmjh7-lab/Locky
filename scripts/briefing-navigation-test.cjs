// Manual-only focused check; see README.md → Manual focused verification.
const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,args:['--no-sandbox']}:{})});
 try {
  const page=await browser.newPage({viewport:{width:390,height:780}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>'+fs.readFileSync(path.join(__dirname,'../app/src/main/assets/style.css'),'utf8')+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  if(process.env.LOCKY_KOREAN_FONT){const font=fs.readFileSync(process.env.LOCKY_KOREAN_FONT).toString('base64');await page.addStyleTag({content:'@font-face{font-family:LockyQA;src:url(data:font/otf;base64,'+font+')} :root{font-family:LockyQA,system-ui!important}'});await page.evaluate(()=>document.fonts.ready);}
  await page.addScriptTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8')});
  await page.evaluate(()=>{
   window.calls=[];window.pending=[];
   const ps=['c','a','b'].map(id=>({id,label:'목적 '+id,scope:'phone'}));
   window.fixture={overlay:true,purposeDate:'2026-10-16',purposeOrders:{phone:ps,app:[]},purposes:ps,allPurposes:ps,prefs:{model:'test',enabled:true,start:540,end:1260},status:{accessibility:true},gate:{mode:'unlock'},notes:[{id:'n',title:'메모',body:'보존할 내용',mode:'text'}],chats:[{id:'existing',title:'기존 대화'}],accounts:[{active:true,connected:true}],models:[],connection:{},rules:[],busy:false};
   window.Native={request:(id,action,raw)=>{const args=JSON.parse(raw);calls.push({action,args});if(id==='0')return;
    if(action==='quickSend'){pending.push({id,args});return;}
    const value=action==='state'?fixture:action==='tick'?{busy:fixture.busy,gate:fixture.gate}:action==='messages'?[]:action==='brief'?window.briefFixture:{};
    setTimeout(()=>receive(id,value,null),0);
   }};state=fixture;screen='unlock';restored=true;render();
  });
  const order=()=>page.locator('[data-purpose]').evaluateAll(nodes=>nodes.map(n=>n.dataset.purpose));
  assert.deepEqual(await order(),['c','a','b']);await page.evaluate(()=>prepareStart({date:'2026-10-16',scope:'phone',purposes:fixture.purposes}));assert.deepEqual(await order(),['c','a','b']);
  await page.evaluate(()=>{fixture.purposeOrders.phone=[fixture.purposes[1],fixture.purposes[2],fixture.purposes[0]];fixture.purposeDate='2026-10-17';updateStart();});assert.deepEqual(await order(),['c','a','b']);
  await page.evaluate(()=>prepareStart({date:'2026-10-17',scope:'phone',purposes:fixture.purposeOrders.phone}));assert.deepEqual(await order(),['a','b','c']);
  await page.locator('#quickQuestion').fill('질문');await page.locator('[data-act=quickSend]').click();assert.equal(await page.evaluate(()=>screen),'chat');await page.evaluate(()=>back());await page.waitForFunction(()=>screen==='unlock'&&!!document.querySelector('#quickQuestion'));assert.equal(await page.locator('#noteBody').inputValue(),'보존할 내용');
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='quickSend').length),1);assert.equal(await page.evaluate(()=>calls.some(c=>['open','stop','cancelResponse'].includes(c.action))),false);
  await page.locator('[data-act=openChat]').first().click();await page.waitForFunction(()=>screen==='chats'&&!!document.querySelector('[data-chat]'));await page.locator('[data-chat=existing]').click();await page.waitForFunction(()=>screen==='chat');await page.evaluate(()=>back());await page.waitForFunction(()=>screen==='chats');await page.evaluate(()=>back());await page.waitForFunction(()=>screen==='unlock');
  await page.evaluate(async()=>{fixture.overlay=false;await navigate('home');await navigate('chats');});await page.locator('[data-chat=existing]').click();await page.waitForFunction(()=>screen==='chat');await page.evaluate(()=>back());await page.waitForFunction(()=>screen==='chats');
  await page.evaluate(async()=>{
   const now=Date.now(),start=now-12*3600000;
   window.briefFixture={analysisVersion:2,kind:'day',days:1,date:'2026-10-16',start,end:now,total:5400000,unlocks:3,appStarts:2,cancels:1,extensions:1,aimless:1,complete:false,usageAccess:true,
    focus:{knownMillis:12*3600000,unknownMillis:0,configuredMillis:8*3600000,observedMillis:3600000,freeObservedMillis:1800000,managedAppMillis:3000000,unmanagedAppMillis:600000,unknownAppPolicyMillis:0,choices:4,cancels:1,reliableSince:start-86400000,periods:[{start,end:now,known:true,focus:true}]},
    apps:[{pkg:'chat',label:'ChatGPT',ms:3600000,focus:2400000,free:1200000,unknown:0},{pkg:'video',label:'YouTube',ms:1800000,focus:1200000,free:600000,unknown:0}],
    events:[{id:1,time:start,kind:'unlock',purpose:'개발 문제 해결',target:'chat'}],timeline:[{start,end:start+3600000,pkg:'chat',label:'ChatGPT',purpose:'개발 문제 해결',source:'accessibility',relation:'direct',focusState:'focus'},{start:start+3600000,end:start+5400000,pkg:'video',label:'YouTube',purpose:'개발 문제 해결',source:'accessibility',relation:'other-app',focusState:'focus'}],
    highlights:[{start,end:start+5400000,text:'개발 문제 해결 선택 후 다른 앱 사용이 30분 기록됐어요.'}],purposes:[{purpose:'개발 문제 해결',count:2,directMillis:3600000,otherMillis:1800000}],flows:[],hours:[{start,end:start+3600000,ms:3600000}],quality:{note:'연속 작동 여부 미확인'},focusResults:[{key:'focus:1:10:20',start,end:start+7200000,complete:true}]};
   await navigate('brief');
  });
  await page.waitForFunction(()=>!!document.querySelector('#briefTimeline'));
  await page.locator('#briefTimeline > summary').click();await page.evaluate(()=>window.scrollTo(0,700));const position=await page.evaluate(()=>window.scrollY);
  await page.evaluate(()=>window.keptTimeline=document.querySelector('#briefTimeline'));await page.evaluate(async()=>{briefFixture.total+=60000;await loadBrief();});assert.equal(await page.evaluate(()=>window.keptTimeline===document.querySelector('#briefTimeline')),true);assert.equal(await page.locator('#briefTimeline').getAttribute('open'),'');assert.equal(await page.evaluate(()=>window.scrollY),position);
  await page.locator('[data-brief-filter=focus]').click();assert.equal(await page.locator('[data-brief-filter=focus]').getAttribute('class'),'selected');
  await page.evaluate(()=>window.scrollTo(0,0));await page.locator('[data-brief-jump]').first().click();assert.equal(await page.locator('#briefTimeline').getAttribute('open'),'');
  await page.evaluate(async()=>{await openBriefRoute('brief|day|2026-10-12');});assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='brief').at(-1).args.date),'2026-10-12');
  await page.evaluate(async()=>{briefFixture.kind='focus';briefFixture.complete=true;await openBriefRoute('brief|focus|focus:1:10:20');});assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='brief').at(-1).args.key),'focus:1:10:20');
  assert.equal(await page.locator('h1').textContent(),'집중시간 결과');
  await page.evaluate(()=>{briefFixture.kind='day';briefFixture.complete=false;renderBrief(briefFixture);window.scrollTo(0,0);});
  if(process.env.LOCKY_CAPTURE_DIR){fs.mkdirSync(process.env.LOCKY_CAPTURE_DIR,{recursive:true});fs.writeFileSync(path.join(process.env.LOCKY_CAPTURE_DIR,'fixture.json'),JSON.stringify(await page.evaluate(()=>({state:fixture,data:briefFixture}))));await page.screenshot({path:path.join(process.env.LOCKY_CAPTURE_DIR,'brief-light.png'),fullPage:true});await page.evaluate(()=>themeChanged(true));await page.screenshot({path:path.join(process.env.LOCKY_CAPTURE_DIR,'brief-dark.png'),fullPage:true});}
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true);assert.deepEqual(errors,[]);
  console.log('PASS: entry-aware chat back, no duplicate send/open Activity, native daily order pinned until entry, fixed briefing date/key routes, details/scroll retained, filters and timeline jump.');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
