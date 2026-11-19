const {chromium}=require('playwright');
const fs=require('node:fs'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||undefined,args:['--no-sandbox']});
 try{
  const page=await browser.newPage({viewport:{width:360,height:780}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>'+fs.readFileSync('app/src/main/assets/style.css','utf8')+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/app.js','utf8')});
  await page.evaluate(()=>{
   state={overlay:false,prefs:{},gate:{},status:{}};screen='brief';
   document.querySelector('#app').innerHTML=brief();
   const now=Date.now(),start=now-12*3600000;
   window.briefFixture={analysisVersion:2,kind:'day',days:1,date:'2026-10-16',start,end:now,total:5400000,unlocks:3,appStarts:2,cancels:1,extensions:1,aimless:1,complete:false,usageAccess:true,
    focus:{knownMillis:12*3600000,unknownMillis:0,configuredMillis:8*3600000,observedMillis:3600000,freeObservedMillis:1800000,managedAppMillis:3000000,unmanagedAppMillis:600000,unknownAppPolicyMillis:0,choices:4,cancels:1,reliableSince:start-86400000,periods:[{start,end:now,known:true,focus:true}]},
    apps:[{pkg:'chat',label:'ChatGPT',ms:3600000,focus:2400000,free:1200000,unknown:0},{pkg:'video',label:'YouTube',ms:1800000,focus:1200000,free:600000,unknown:0}],
    events:[{id:1,time:start,kind:'unlock',purpose:'개발 문제 해결',target:'chat'}],timeline:[{start,end:start+3600000,pkg:'chat',label:'ChatGPT',purpose:'개발 문제 해결',source:'accessibility',relation:'direct',focusState:'focus'},{start:start+3600000,end:start+5400000,pkg:'video',label:'YouTube',purpose:'개발 문제 해결',source:'accessibility',relation:'other-app',focusState:'focus'}],
    highlights:[{start,end:start+5400000,text:'개발 문제 해결 선택 후 다른 앱 사용이 30분 기록됐어요.'}],purposes:[{purpose:'개발 문제 해결',count:2,directMillis:3600000,otherMillis:1800000}],flows:[],hours:[{start,end:start+3600000,ms:3600000}],quality:{note:'연속 작동 여부 미확인'},focusResults:[{key:'focus:1:10:20',start,end:start+7200000,complete:true}]};

   briefFixture.apps[1].focus=3000000;briefFixture.apps[1].free=60000;
   briefData=briefFixture;renderBrief(briefFixture);
   document.querySelector('#briefDate').textContent=briefFixture.date;
  });
  const order=()=>page.locator('#brief-apps .usage-row strong').allTextContents();
  assert.deepEqual(await order(),['ChatGPT','YouTube']);
  await page.locator('[data-brief-filter=focus]').click();assert.deepEqual(await order(),['YouTube','ChatGPT']);
  await page.locator('[data-brief-sort=apps]').click();assert.deepEqual(await order(),['ChatGPT','YouTube']);
  await page.locator('[data-brief-filter=free]').click();assert.deepEqual(await order(),['YouTube','ChatGPT']);
  await page.locator('[data-brief-sort=apps]').click();assert.deepEqual(await order(),['ChatGPT','YouTube']);
  await page.locator('#brief-purpose-counts>summary').click();
  await page.locator('#briefTimeline>summary').click();
  await page.locator('[data-brief-sort=timeline]').click();
  await page.evaluate(()=>{window.targetTime=null;Element.prototype.scrollIntoView=function(){window.targetTime=Number(this.dataset.timelineStart);};});
  await page.locator('[data-brief-jump]').first().click();
  assert.equal(await page.evaluate(()=>targetTime===briefFixture.start),true);
  await page.evaluate(()=>{window.keptTimeline=document.querySelector('#briefTimeline');briefFixture.total+=60000;renderBrief(briefFixture);});
  assert.equal(await page.evaluate(()=>keptTimeline===document.querySelector('#briefTimeline')),true);
  assert.equal(await page.locator('#brief-purpose-counts').getAttribute('open'),'');
  await page.evaluate(()=>{document.querySelectorAll('details').forEach(d=>d.open=false);window.scrollTo(0,0);});
  if(process.env.LOCKY_CAPTURE_DIR){fs.mkdirSync(process.env.LOCKY_CAPTURE_DIR,{recursive:true});await page.screenshot({path:process.env.LOCKY_CAPTURE_DIR+'/brief-light.png',fullPage:true});}
  for(const width of [320,360,700]){
   await page.setViewportSize({width,height:780});
   for(const dark of [false,true]){
    await page.evaluate(dark=>document.documentElement.dataset.theme=dark?'dark':'light',dark);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true,'horizontal overflow at '+width);
   }
  }
  await page.setViewportSize({width:360,height:780});
  if(process.env.LOCKY_CAPTURE_DIR)await page.screenshot({path:process.env.LOCKY_CAPTURE_DIR+'/brief-dark.png',fullPage:true});
  await page.evaluate(()=>{briefFixture.focus={};briefFixture.highlights=[];renderBrief(briefFixture);});
  assert.equal(await page.locator('[data-brief-filter=all]').getAttribute('aria-pressed'),'true');
  assert.equal(await page.locator('[data-brief-filter=focus]').isDisabled(),true);
  assert.deepEqual(await order(),['ChatGPT','YouTube']);
  assert.equal(await page.locator('#brief-highlights').count(),0);
  assert.deepEqual(errors,[]);
  console.log('PASS: filter-specific sorting both ways, unknown-history fallback, chronological jump, open details/DOM retained, empty highlights and narrow light/dark layouts.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
