const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage({viewport:{width:390,height:780}});
  await page.setContent('<html><head><style>'+fs.readFileSync(path.join(__dirname,'../app/src/main/assets/style.css'),'utf8')+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8')});
  await page.evaluate(()=>{
   window.calls=[];window.fixture={overlay:false,prefs:{startUnderKeyguard:true,enabled:true,start:540,end:1260,reminders:[],weeklyDay:7},status:{accessibility:true,drawOverlays:false,startWindow:{status:'permission-required',actual:'accessibility'}},gate:{},rules:[],allPurposes:[],chats:[],notes:[],accounts:[],models:[],connection:{}};
   window.Native={request:(id,action,raw)=>{const a=JSON.parse(raw);calls.push({action,args:a});if(id==='0')return;
    if(action==='settings'){fixture.prefs.startUnderKeyguard=a.startUnderKeyguard;fixture.status.startWindow.status=a.startUnderKeyguard?'permission-required':'disabled';}
    setTimeout(()=>receive(id,action==='state'?fixture:{},null),0);
   }};state=fixture;screen='settings';render();
  });
  assert.match(await page.locator('[data-start-window-status]').textContent(),/권한이 없어 기존 접근성/);
  assert.match(await page.locator('#startUnderKeyguard').textContent(),/사용 요청 켜짐/);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='permission').length),0);
  await page.locator('#startUnderKeyguard').click();await page.waitForFunction(()=>document.querySelector('#startUnderKeyguard').getAttribute('aria-checked')==='false');
  assert.match(await page.locator('[data-start-window-status]').textContent(),/꺼져 있습니다/);
  await page.locator('#startUnderKeyguard').click();await page.waitForFunction(()=>document.querySelector('#startUnderKeyguard').getAttribute('aria-checked')==='true');
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='permission').length),0);
  for(const [status,actual,text] of [['failed','accessibility','생성에 실패'],['pending','accessibility','다음 화면 꺼짐'],['applied','application','일반 오버레이 방식이 적용'],['app-gate','accessibility','현재 앱 차단 창'],['service-off','none','접근성 서비스를 켜면']]){
   await page.evaluate(({status,actual})=>{state.status.startWindow={status,actual};render();},{status,actual});
   assert.ok((await page.locator('[data-start-window-status]').textContent()).includes(text));
  }
  await page.locator('[data-permission="drawOverlays"]').click();await page.waitForFunction(()=>calls.some(c=>c.action==='permission'));
  assert.deepEqual(await page.evaluate(()=>calls.find(c=>c.action==='permission').args),{name:'drawOverlays'});
  console.log('PASS: requested preference, missing permission, actual role, creation failure, deferred activation, off/service state and explicit-only permission navigation.');
 }finally{await browser.close();}
})();
