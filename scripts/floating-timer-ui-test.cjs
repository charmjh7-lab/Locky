const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,args:['--no-sandbox','--no-zygote','--single-process']}:{})});
 try{
  const page=await browser.newPage({viewport:{width:360,height:700}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<html><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8')});
  await page.evaluate(()=>{
   window.fixture={prefs:{enabled:true,start:540,end:1260,reminders:[],weeklyDay:7,floatingTimer:true},rules:[],status:{accessibility:true},gate:{},overlay:false};
   window.calls=[];window.failSave=false;
   window.Native={request:(id,action,raw)=>{const args=JSON.parse(raw);calls.push({action,args});let error=null;
    if(action==='settings'){if(failSave){error='저장 실패';failSave=false;}else Object.assign(fixture.prefs,args);}
    setTimeout(()=>receive(id,action==='state'?JSON.parse(JSON.stringify(fixture)):{},error),0);
   }};
   state=JSON.parse(JSON.stringify(fixture));screen='settings';render();
  });
  const toggle=page.locator('#floatingTimer');assert.equal(await toggle.getAttribute('aria-checked'),'true');
  await toggle.click();await page.waitForFunction(()=>document.querySelector('#floatingTimer').getAttribute('aria-checked')==='false');
  assert.equal(await toggle.innerText(),'꺼짐');assert.equal(await page.evaluate(()=>fixture.prefs.enabled),true);
  await page.evaluate(()=>refresh(true));assert.equal(await toggle.getAttribute('aria-checked'),'false');
  await toggle.click();await page.waitForFunction(()=>document.querySelector('#floatingTimer').getAttribute('aria-checked')==='true');
  await page.evaluate(()=>{failSave=true;});await toggle.click();await page.waitForFunction(()=>document.querySelector('#toast').textContent==='저장 실패');
  assert.equal(await toggle.getAttribute('aria-checked'),'true');assert.equal(await page.evaluate(()=>fixture.prefs.floatingTimer),true);
  const writes=await page.evaluate(()=>calls.filter(c=>c.action==='settings'));
  assert.deepEqual(writes.map(c=>c.args),[{floatingTimer:false},{floatingTimer:true},{floatingTimer:false}]);assert.deepEqual(errors,[]);
  console.log('Floating timer setting: toggle, persistence, unrelated preference preservation and save failure passed.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
