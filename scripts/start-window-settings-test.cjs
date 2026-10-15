const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage({viewport:{width:390,height:780}});
  await page.setContent('<html><head><style>'+fs.readFileSync(path.join(__dirname,'../app/src/main/assets/style.css'),'utf8')+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8')});
  await page.evaluate(()=>{
   window.calls=[];window.fixture={overlay:false,prefs:{startUnderKeyguard:true,enabled:true,start:540,end:1260,reminders:[],weeklyDay:7},status:{accessibility:true,drawOverlays:false,unlockDiagnostics:{enabled:false,cycles:0,unlocks:0,lastAt:0},startWindow:{status:'permission-required',actual:'accessibility'}},gate:{},rules:[],allPurposes:[],chats:[],notes:[],accounts:[],models:[],connection:{}};
   window.Native={request:(id,action,raw)=>{const a=JSON.parse(raw);calls.push({action,args:a});if(id==='0')return;
    if(action==='settings'&&'startUnderKeyguard' in a){fixture.prefs.startUnderKeyguard=a.startUnderKeyguard;fixture.status.startWindow.status=a.startUnderKeyguard?'permission-required':'disabled';}
    if(action==='settings'&&'unlockDiagnostics' in a)fixture.status.unlockDiagnostics.enabled=a.unlockDiagnostics;
    if(action==='clearDiagnostics')Object.assign(fixture.status.unlockDiagnostics,{cycles:0,unlocks:0,lastAt:0});
    setTimeout(()=>receive(id,action==='state'?fixture:action==='diagnostics'?{text:'Locky 진단\nwindow update requested\n<script>window.injected=true</script>',status:fixture.status.unlockDiagnostics}:{},null),0);
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
  assert.equal(await page.locator('#unlockDiagnostics').getAttribute('aria-checked'),'false');
  await page.locator('#unlockDiagnostics').click();await page.waitForFunction(()=>document.querySelector('#unlockDiagnostics').getAttribute('aria-checked')==='true');
  await page.evaluate(()=>{Object.assign(fixture.status.unlockDiagnostics,{cycles:3,unlocks:3,lastAt:Date.now()});state=fixture;render();});
  assert.match(await page.locator('#unlockDiagnosticStatus').textContent(),/잠금 해제: 3회/);
  await page.locator('[data-act=viewDiagnostics]').click();await page.waitForSelector('.diagnostic-log');
  assert.match(await page.locator('.diagnostic-log').textContent(),/<script>/);assert.equal(await page.evaluate(()=>!!window.injected),false);
  await page.locator('.sheet [data-act=saveDiagnostics]').click();await page.waitForFunction(()=>calls.some(c=>c.action==='saveDiagnostics'));
  await page.locator('.sheet [data-act=closeSheet]').first().click();
  await page.locator('[data-act=deleteDiagnostics]').click();await page.locator('.sheet [data-act=closeSheet]').last().click();
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='clearDiagnostics').length),0);
  await page.locator('[data-act=deleteDiagnostics]').click();await page.locator('[data-act=confirmDeleteDiagnostics]').click();
  await page.waitForFunction(()=>document.querySelector('[data-act=deleteDiagnostics]').disabled);
  assert.match(await page.locator('#unlockDiagnosticStatus').textContent(),/아직 없음/);
  await page.evaluate(()=>{state.overlay=true;screen='unlock';render();});await page.locator('#quickQuestion').focus();
  assert.equal(await page.evaluate(()=>calls.some(c=>c.action==='editorFocus')),true);
  console.log('PASS: requested/applied window status; opt-in diagnostics, escaped viewer, export, cancel/delete and editor-focus bridge.');
 }finally{await browser.close();}
})();
