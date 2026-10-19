const {chromium}=require('playwright');
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||undefined,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:390,height:780}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 try{
  await page.setContent('<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><div id="app"></div><div id="sheet"></div><div id="toast"></div>');
  await page.addStyleTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/style.css'),'utf8')});
  await page.evaluate(()=>{
   window.calls=[];window.gateData=pkg=>{const purposes=[{id:'app-p',label:'영상 한 편',scope:'app',pkg:'',uri:''}];return {role:'APP_GATE',overlay:true,gate:{mode:'app',pkg,label:pkg,daily:600000,session:0,purpose:'처음 정한 목적'},prefs:{start:540,end:1260},free:false,purposeDate:'2026-10-17',scope:pkg,purposes,allPurposes:purposes,rules:[{pkg,mode:'limited'}],notes:[],chats:[],models:[],accounts:[],connection:{}};};
   window.fixture=gateData('app.video');
   window.Native={darkTheme:()=>false,request(id,action,raw){const args=JSON.parse(raw);calls.push({action,args});let out={};if(action==='state')out=fixture;else if(action==='tick'){out={gate:fixture.gate,free:fixture.free};if(window.holdTick){window.heldTick={id,out:structuredClone(out)};return;}}else if(action==='apps')out=[];else if(action==='purpose'){out={...args,id:'custom',pkg:args.pkg||'',uri:args.uri||''};fixture.purposes.push(out);fixture.allPurposes=fixture.purposes;}
    setTimeout(()=>receive(id,out,null),0);
   }};
  });
  await page.addScriptTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8')});
  await page.evaluate(()=>{prepareGate(0);window.preparedMain=document.querySelector('.gate-page');window.preparedDuration=document.querySelector('.duration');window.idleInert=preparedMain.inert;presentGate('app',fixture,1);clearInterval(poll);window.durationNode=document.querySelector('.duration');window.presetNode=document.querySelector('[data-purpose="app-p"]');});
  assert.equal(await page.evaluate(()=>idleInert&&preparedMain===document.querySelector('.gate-page')&&preparedDuration===document.querySelector('.duration')&&!preparedMain.inert),true);
  await page.locator('[data-min="9"]').click();await page.evaluate(async()=>{presentGate('app',fixture,1);clearInterval(poll);await refresh();await refresh(true);clearInterval(poll);});
  assert.equal(await page.evaluate(()=>durationNode===document.querySelector('.duration')&&presetNode===document.querySelector('[data-purpose="app-p"]')&&minutes===9),true);
  await page.evaluate(()=>Promise.all([choosePurpose('app-p'),choosePurpose('app-p')]));
  assert.deepEqual(await page.evaluate(()=>calls.filter(c=>c.action==='grant').map(c=>c.args)),[{id:'app-p',minutes:9,gateNavigation:1}]);
  await page.evaluate(()=>{window.holdTick=true;window.pendingRefresh=refresh();});await page.waitForFunction(()=>!!heldTick);
  await page.evaluate(async()=>{const old=heldTick;fixture=gateData('app.social');presentGate('app',fixture,2);clearInterval(poll);holdTick=false;receive(old.id,old.out,null);await pendingRefresh;});
  assert.equal(await page.evaluate(()=>preparedMain===document.querySelector('.gate-page')&&durationNode===document.querySelector('.duration')),true);
  assert.equal(await page.evaluate(()=>state.gate.pkg),'app.social');assert.match(await page.locator('.gate-app').textContent(),/app.social/);
  await page.evaluate(()=>{memoSaving=new Promise(resolve=>window.releaseMemo=resolve);window.oldChoice=choosePurpose('app-p');window.oldDismiss=action('dismiss');fixture=gateData('app.video');presentGate('app',fixture,3);clearInterval(poll);});
  await page.evaluate(async()=>{releaseMemo();memoSaving=null;await oldChoice;await oldDismiss;});assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='grant').length),1);assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='dismiss').length),0);
  await page.evaluate(()=>{fixture.gate.mode='renew';presentGate('renew',fixture,4);clearInterval(poll);});assert.match(await page.locator('.gate-note').textContent(),/처음 정한 목적/);
  await page.locator('[data-min="10"]').click();await page.locator('[data-act="extend"]').click();await page.waitForFunction(()=>calls.some(c=>c.action==='extend'));
  assert.deepEqual(await page.evaluate(()=>calls.find(c=>c.action==='extend').args),{gateNavigation:4,minutes:10});
  await page.evaluate(()=>{fixture.gate.mode='blocked';fixture.rules[0].mode='locked';presentGate('blocked',fixture,5);clearInterval(poll);});assert.match(await page.locator('.gate-info').textContent(),/집중 시간에는 차단/);await page.locator('[data-act="dismiss"]').click();await page.waitForFunction(()=>calls.some(c=>c.action==='dismiss'));assert.equal(await page.evaluate(()=>calls.find(c=>c.action==='dismiss').args.gateNavigation),5);
  await page.evaluate(()=>{fixture=gateData('app.video');presentGate('app',fixture,6);clearInterval(poll);});
  assert.equal(await page.evaluate(()=>preparedMain===document.querySelector('.gate-page')&&durationNode===document.querySelector('.duration')),true);
  await page.locator('[data-act="direct"]').click();assert.equal(await page.locator('#purposeSave').isChecked(),false);assert.match(await page.locator('#savePurpose').textContent(),/시작하기/);
  await page.locator('#purposeLabel').fill('일회성 목적');
  await page.evaluate(()=>{window.inputNode=document.querySelector('#purposeLabel');fixture.gate.daily=480000;presentGate('app',fixture,6);clearInterval(poll);});
  assert.equal(await page.evaluate(()=>inputNode===document.querySelector('#purposeLabel')&&inputNode.value==='일회성 목적'),true);
  await page.locator('#savePurpose').click();await page.waitForFunction(()=>calls.some(c=>c.action==='grant'&&c.args.transient));
  assert.deepEqual(await page.evaluate(()=>calls.find(c=>c.action==='grant'&&c.args.transient).args),{gateNavigation:6,label:'일회성 목적',scope:'app.video',pkg:'',uri:'',aimless:false,transient:true,minutes:5});
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='purpose').length),0);
  await page.locator('[data-act="managePurposes"]').click();assert.doesNotMatch(await page.locator('#sheet').textContent(),/일회성 목적/);await page.evaluate(()=>closeSheet());
  await page.locator('[data-act="direct"]').click();assert.equal(await page.locator('#purposeSave').isChecked(),false);await page.locator('#purposeLabel').fill('직접 정한 목적');await page.locator('#purposeSave').check();assert.match(await page.locator('#savePurpose').textContent(),/저장하고 시작/);
  await page.locator('#savePurpose').click();await page.waitForFunction(()=>calls.some(c=>c.action==='grant'&&c.args.id==='custom'));
  assert.equal(await page.evaluate(()=>calls.find(c=>c.action==='purpose').args.scope),'app.video');await page.locator('[data-act="managePurposes"]').click();assert.match(await page.locator('#sheet').textContent(),/직접 정한 목적/);await page.evaluate(()=>closeSheet());
  if(process.env.LOCKY_GATE_SCREEN_DIR){fs.mkdirSync(process.env.LOCKY_GATE_SCREEN_DIR,{recursive:true});await page.screenshot({path:path.join(process.env.LOCKY_GATE_SCREEN_DIR,'app-gate.png')});}
  for(const role of ['PHONE_START','MAIN']){
   await page.evaluate(role=>{clearInterval(poll);const purposes=[{id:'linked',scope:'phone',label:'연결된 목적',pkg:'linked.app'},{id:'web',scope:'phone',label:'웹 페이지',uri:'https://example.com'},{id:'plain',scope:'phone',label:'연결 없음'}];fixture={...gateData(''),role,overlay:false,status:{accessibility:true},phoneSession:{},phoneCycle:1,purposes,allPurposes:purposes,purposeOrders:{phone:purposes},purposeIcons:{linked:'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="32" height="32" fill="green"/></svg>'},dictionary:'',notes:[{id:'note',title:'메모',body:'메모 본문',checks:'[]',mode:'text'}]};state=fixture;screen=role==='MAIN'?'home':'unlock';purposeEntry=null;render();},role);
   await page.locator('[data-act="direct"]').click();await page.locator('#purposeLabel').fill('전화 일회성');await page.locator('#savePurpose').click();await page.waitForFunction(()=>!purposeStarting);
   assert.deepEqual(await page.evaluate(()=>calls.filter(c=>c.action==='choose').at(-1).args),{label:'전화 일회성',pkg:'',uri:'',scope:'phone',aimless:false,transient:true});
   assert.equal(await page.locator('[data-purpose="linked"] .linked-app-icon').count(),1);assert.equal(await page.locator('[data-purpose="web"] svg').count(),1);assert.equal(await page.locator('[data-purpose="plain"]').locator('svg,img').count(),0);
   await page.locator('[data-purpose="linked"] img').click();await page.waitForFunction(()=>!purposeStarting);assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='choose').at(-1).args.id),'linked');
   await page.locator('.linked-app-icon').dispatchEvent('error');assert.equal(await page.locator('.purpose-link-icon.icon-failed svg').isVisible(),true);
   if(process.env.LOCKY_GATE_SCREEN_DIR)await page.screenshot({path:path.join(process.env.LOCKY_GATE_SCREEN_DIR,role.toLowerCase()+'-icons.png')});
  }
  assert.deepEqual(errors,[]);console.log('PASS APP_GATE DOM/duration reuse, app/renew/blocked, transient/optional-save/edit/exit, stale requests, and PHONE_START/MAIN icons/fallback/actions');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
