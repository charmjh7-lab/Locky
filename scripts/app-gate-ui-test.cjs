const {chromium}=require('playwright');
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||undefined,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:390,height:780}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 try{
  await page.setContent('<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><div id="app"></div><div id="sheet"></div><div id="toast"></div>');
  await page.addStyleTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/style.css'),'utf8')});
  await page.evaluate(()=>{
   window.calls=[];window.gateSequence=0;window.gateData=pkg=>{const purposes=[{id:'app-p',label:'영상 한 편',scope:'app',pkg:'',uri:''}];return {role:'APP_GATE',overlay:true,gateTransition:++gateSequence,gateNavigation:0,gateActive:true,gateExposure:'INTERACTIVE',scope:pkg,gate:{mode:'app',pkg,label:pkg,daily:600000,session:0,purpose:'처음 정한 목적'},prefs:{start:540,end:1260},free:false,purposeDate:'2026-10-17',scope:pkg,purposes,allPurposes:purposes,rules:[{pkg,mode:'limited'}],notes:[],chats:[],models:[],accounts:[],connection:{}};};
   window.fixture=gateData('app.video');
   window.Native={darkTheme:()=>false,request(id,action,raw){const args=JSON.parse(raw);calls.push({action,args});let out={};if(action==='state'){out=structuredClone(fixture);if(window.holdState){window.heldState={id,out};return;}}else if(action==='tick'){out=structuredClone({gate:fixture.gate,free:fixture.free,gateNavigation:fixture.gateNavigation,gateTransition:fixture.gateTransition,gateActive:fixture.gateActive,gateExposure:fixture.gateExposure});if(window.holdTick){window.heldTick={id,out:structuredClone(out)};return;}}else if(action==='apps')out=[];else if(action==='purpose'){out={...args,id:args.id||'custom',pkg:args.pkg||'',uri:args.uri||''};fixture.purposes=fixture.purposes.filter(p=>p.id!==out.id).concat(out);fixture.allPurposes=fixture.purposes;if(window.holdPurpose){window.heldPurpose={id,out};return;}}else if(action==='removePurpose'){fixture.purposes=fixture.purposes.filter(p=>p.id!==args.id);fixture.allPurposes=fixture.purposes;}
    setTimeout(()=>receive(id,structuredClone(out),null),0);
   }};
  });
  await page.addScriptTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8')});
  await page.evaluate(()=>{const original=presentGate;window.presentGate=(name,payload,navigation)=>{payload.gateNavigation=navigation;original(name,payload,navigation);};});
  const preparedCatalog=await page.evaluate(()=>{
   let builds=0;const original=purposeGridMarkup;window.purposeGridMarkup=(...args)=>{builds++;return original(...args);};
   const shared={catalogVersion:1,purposeDate:fixture.purposeDate,prefs:fixture.prefs,allPurposes:fixture.allPurposes,rules:fixture.rules,purposeOrders:{app:fixture.purposes,'app.video':fixture.purposes,'app.social':fixture.purposes}};
   installGateCatalog(shared);prepareGate(0);const button=document.querySelector('[data-purpose="app-p"]'),before=builds;
   const compact=pkg=>({catalogVersion:1,scope:pkg,purposeDate:shared.purposeDate,gateTransition:99,gateExposure:'WAITING',gate:{...fixture.gate,pkg,label:pkg},free:false});
   presentGate('app',compact('app.video'),1);presentGate('app',compact('app.social'),2);clearInterval(poll);
   const result={prewarmed:!!button,same:button===document.querySelector('[data-purpose="app-p"]'),builds:builds-before};
   window.purposeGridMarkup=original;gateCatalog=null;gatePurposeTemplates.clear();gateFallbackTemplates.clear();nativeNavigation=0;return result;
  });
  assert.deepEqual(preparedCatalog,{prewarmed:true,same:true,builds:0});
  await page.evaluate(()=>{prepareGate(0);window.preparedMain=document.querySelector('.gate-page');window.preparedDuration=document.querySelector('.duration');window.idleInert=preparedMain.inert;presentGate('app',fixture,1);clearInterval(poll);window.durationNode=document.querySelector('.duration');window.presetNode=document.querySelector('[data-purpose="app-p"]');});
  assert.equal(await page.evaluate(()=>idleInert&&preparedMain===document.querySelector('.gate-page')&&preparedDuration===document.querySelector('.duration')&&!preparedMain.inert),true);
  await page.locator('[data-min="9"]').click();await page.evaluate(async()=>{presentGate('app',fixture,1);clearInterval(poll);await refresh();await refresh(true);clearInterval(poll);});
  assert.equal(await page.evaluate(()=>durationNode===document.querySelector('.duration')&&presetNode===document.querySelector('[data-purpose="app-p"]')&&minutes===9),true);
  await page.evaluate(()=>Promise.all([choosePurpose('app-p'),choosePurpose('app-p')]));
  assert.deepEqual(await page.evaluate(()=>calls.filter(c=>c.action==='grant').map(c=>c.args)),[{id:'app-p',minutes:9,gateNavigation:1,gateTransition:1}]);
  await page.evaluate(()=>{window.holdTick=true;window.pendingRefresh=refresh();});await page.waitForFunction(()=>!!heldTick);
  await page.evaluate(async()=>{const old=heldTick;fixture=gateData('app.social');presentGate('app',fixture,2);clearInterval(poll);holdTick=false;receive(old.id,old.out,null);await pendingRefresh;});
  assert.equal(await page.evaluate(()=>preparedMain===document.querySelector('.gate-page')&&durationNode===document.querySelector('.duration')),true);
  assert.equal(await page.evaluate(()=>state.gate.pkg),'app.social');assert.match(await page.locator('.gate-app').textContent(),/app.social/);
  await page.evaluate(()=>{memoSaving=new Promise(resolve=>window.releaseMemo=resolve);window.oldChoice=choosePurpose('app-p');window.oldDismiss=action('dismiss');fixture=gateData('app.video');presentGate('app',fixture,3);clearInterval(poll);});
  await page.evaluate(async()=>{releaseMemo();memoSaving=null;await oldChoice;await oldDismiss;});assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='grant').length),1);assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='dismiss').length),0);
  // Pointer down on one app, then up/click after its shared nodes have been patched.
  await page.locator('[data-purpose="app-p"]').dispatchEvent('pointerdown');
  await page.evaluate(()=>{fixture=gateData('app.social');presentGate('app',fixture,4);clearInterval(poll);});
  const grantsBefore=await page.evaluate(()=>calls.filter(c=>c.action==='grant').length);
  await page.locator('[data-purpose="app-p"]').dispatchEvent('click');
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='grant').length),grantsBefore);
  await page.evaluate(()=>{fixture.gate.mode='renew';presentGate('renew',fixture,5);clearInterval(poll);});assert.match(await page.locator('.gate-note').textContent(),/처음 정한 목적/);
  await page.locator('[data-min="10"]').click();await page.locator('[data-act="extend"]').click();await page.waitForFunction(()=>calls.some(c=>c.action==='extend'));
  assert.deepEqual(await page.evaluate(()=>calls.find(c=>c.action==='extend').args),{gateNavigation:5,gateTransition:4,minutes:10});
  await page.evaluate(()=>{fixture.gate.mode='blocked';fixture.rules[0].mode='locked';presentGate('blocked',fixture,6);clearInterval(poll);});assert.match(await page.locator('.gate-info').textContent(),/집중 시간에는 차단/);await page.locator('[data-act="dismiss"]').click();await page.waitForFunction(()=>calls.some(c=>c.action==='dismiss'));assert.equal(await page.evaluate(()=>calls.find(c=>c.action==='dismiss').args.gateNavigation),6);
  await page.evaluate(()=>{fixture=gateData('app.video');presentGate('app',fixture,7);clearInterval(poll);});
  assert.equal(await page.evaluate(()=>preparedMain===document.querySelector('.gate-page')&&durationNode===document.querySelector('.duration')),true);
  await page.locator('[data-act="direct"]').click();assert.equal(await page.locator('#purposeSave').isChecked(),false);assert.match(await page.locator('#savePurpose').textContent(),/시작하기/);
  await page.locator('#purposeLabel').fill('일회성 목적');
  await page.evaluate(()=>{window.inputNode=document.querySelector('#purposeLabel');fixture.gate.daily=480000;presentGate('app',fixture,7);clearInterval(poll);});
  assert.equal(await page.evaluate(()=>inputNode===document.querySelector('#purposeLabel')&&inputNode.value==='일회성 목적'),true);
  await page.locator('#savePurpose').click();await page.waitForFunction(()=>calls.some(c=>c.action==='grant'&&c.args.transient));
  assert.deepEqual(await page.evaluate(()=>calls.find(c=>c.action==='grant'&&c.args.transient).args),{gateNavigation:7,gateTransition:5,label:'일회성 목적',scope:'app.video',pkg:'',uri:'',aimless:false,transient:true,minutes:5});
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='purpose').length),0);
  await page.locator('[data-act="managePurposes"]').click();assert.doesNotMatch(await page.locator('#sheet').textContent(),/일회성 목적/);await page.evaluate(()=>closeSheet());
  await page.locator('[data-act="direct"]').click();assert.equal(await page.locator('#purposeSave').isChecked(),false);await page.locator('#purposeLabel').fill('직접 정한 목적');await page.locator('#purposeSave').check();assert.match(await page.locator('#savePurpose').textContent(),/저장하고 시작/);
  await page.locator('#savePurpose').click();await page.waitForFunction(()=>calls.some(c=>c.action==='grant'&&c.args.id==='custom'));
  assert.equal(await page.evaluate(()=>calls.find(c=>c.action==='purpose').args.scope),'app.video');await page.locator('[data-act="managePurposes"]').click();assert.match(await page.locator('#sheet').textContent(),/직접 정한 목적/);await page.evaluate(()=>closeSheet());
  // Same-entry updates retain the common root, handlers, editor, and selected duration.
  await page.evaluate(()=>{
   fixture=gateData('app.video');presentGate('app',fixture,8);clearInterval(poll);
   window.stableGate={main:document.querySelector('.gate-page'),info:document.querySelector('.gate-info'),duration:document.querySelector('.duration'),footer:document.querySelector('.start-footer'),button:document.querySelector('[data-purpose="app-p"]'),click:document.querySelector('[data-purpose="app-p"]').onclick};
   window.factoryCalls=0;const factory=gate;window.gate=()=>{factoryCalls++;return factory();};
  });
  await page.locator('[data-min="9"]').click();
  const unchanged=await page.evaluate(async()=>{const revision=domRevision,acks=calls.filter(c=>c.action==='rendered').length;await refresh(true);await refresh();presentGate('app',fixture,8);clearInterval(poll);return {revision:domRevision-revision,acks:calls.filter(c=>c.action==='rendered').length-acks,factoryCalls};});
  assert.deepEqual(unchanged,{revision:0,acks:0,factoryCalls:0});
  await page.evaluate(()=>purposeSheet('app-p'));await page.locator('#purposeLabel').fill('바꾼 목적');await page.locator('#savePurpose').click();await page.waitForFunction(()=>!document.querySelector('#sheet').innerHTML);
  assert.equal(await page.evaluate(()=>minutes===9&&stableGate.button===document.querySelector('[data-purpose="app-p"]')&&stableGate.click===stableGate.button.onclick),true);
  assert.match(await page.locator('[data-purpose="app-p"]').textContent(),/바꾼 목적/);
  await page.evaluate(()=>managePurposes('app'));await page.locator('[data-act="addPurpose"]').click();await page.locator('#purposeLabel').fill('추가 목적');await page.locator('#savePurpose').click();await page.waitForFunction(()=>document.querySelector('[data-purpose="custom"]'));
  await page.evaluate(async()=>{const custom=fixture.purposes.find(p=>p.id==='custom');fixture.purposes=[custom,...fixture.purposes.filter(p=>p.id!=='custom')];fixture.allPurposes=fixture.purposes;await refresh(true);clearInterval(poll);});
  assert.equal(await page.evaluate(()=>document.querySelector('.purpose-item [data-purpose]').dataset.purpose),'custom');
  await page.evaluate(()=>managePurposes('app'));await page.locator('[data-remove="app-p"]').click();await page.waitForFunction(()=>!document.querySelector('[data-purpose="app-p"]'));
  assert.equal(await page.evaluate(()=>minutes===9&&stableGate.main===document.querySelector('.gate-page')&&stableGate.info===document.querySelector('.gate-info')&&stableGate.duration===document.querySelector('.duration')&&stableGate.footer===document.querySelector('.start-footer')&&factoryCalls===0),true);
  assert.equal(await page.locator('#sheet [data-remove="custom"]').count(),1);await page.evaluate(()=>closeSheet());
  // Periodic requests share one in-flight tick; time-only edits do not run layout.
  await page.evaluate(()=>{window.layouts=0;const original=layoutStart;window.layoutStart=()=>{layouts++;original();};holdTick=true;heldTick=null;window.tickBefore=calls.filter(c=>c.action==='tick').length;window.tickOne=refresh();window.tickTwo=refresh();});
  await page.waitForFunction(()=>!!heldTick);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='tick').length-tickBefore),1);
  await page.evaluate(async()=>{heldTick.out.gate.daily=120000;holdTick=false;receive(heldTick.id,heldTick.out,null);await Promise.all([tickOne,tickTwo]);await new Promise(requestAnimationFrame);});
  assert.equal(await page.locator('#dailyLeft').textContent(),'02:00');assert.equal(await page.evaluate(()=>layouts),0);
  await page.evaluate(()=>{holdTick=true;heldTick=null;window.wrongIdentityTick=refresh();});await page.waitForFunction(()=>!!heldTick);
  await page.evaluate(async()=>{heldTick.out.gateTransition++;heldTick.out.gate.daily=0;holdTick=false;receive(heldTick.id,heldTick.out,null);await wrongIdentityTick;});
  assert.equal(await page.locator('#dailyLeft').textContent(),'02:00');
  // A suspended/hidden entry cannot consume late replies or stale exposure messages.
  await page.evaluate(()=>{purposeSheet(null,true);document.querySelector('#purposeLabel').value='유지할 입력';window.keptInput=document.querySelector('#purposeLabel');holdTick=true;heldTick=null;window.pausedTick=refresh();});
  await page.waitForFunction(()=>!!heldTick);
  await page.evaluate(async()=>{gateExposureChanged('SUSPENDED',nativeNavigation,state.gateTransition,state.gate.pkg);holdTick=false;receive(heldTick.id,heldTick.out,null);await pausedTick;});
  assert.equal(await page.evaluate(()=>poll===null&&keptInput===document.querySelector('#purposeLabel')&&keptInput.value==='유지할 입력'),true);
  await page.evaluate(()=>{window.resumedReads=calls.filter(c=>c.action==='state').length;window.resumedTicks=calls.filter(c=>c.action==='tick').length;gateExposureChanged('INTERACTIVE',nativeNavigation,state.gateTransition,state.gate.pkg);});
  await page.waitForFunction(()=>gateRefresh===null);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='state').length-resumedReads),0);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='tick').length-resumedTicks),1);
  assert.equal(await page.evaluate(()=>poll!==null&&keptInput===document.querySelector('#purposeLabel')&&keptInput.value==='유지할 입력'),true);
  await page.evaluate(()=>{clearInterval(poll);gateExposureChanged('HIDDEN',nativeNavigation-1,state.gateTransition,state.gate.pkg);});assert.equal(await page.evaluate(()=>gateAlive),true);
  await page.evaluate(()=>{holdState=true;heldState=null;window.oldState=refresh(true);});await page.waitForFunction(()=>!!heldState);
  await page.evaluate(async()=>{const old=heldState;fixture=gateData('app.social');presentGate('app',fixture,9);clearInterval(poll);holdState=false;await refresh(true);old.out.gate.pkg='app.social';receive(old.id,old.out,null);await oldState;});
  assert.equal(await page.evaluate(()=>state.gateTransition===fixture.gateTransition&&state.gate.pkg==='app.social'),true);
  // A new navigation must acknowledge an unchanged DOM revision, without rebuilding it.
  const newAck=await page.evaluate(()=>{const before=calls.filter(c=>c.action==='rendered').length,revision=domRevision;presentGate('app',fixture,10);clearInterval(poll);return {acks:calls.filter(c=>c.action==='rendered').length-before,revision:domRevision-revision,factoryCalls};});
  assert.deepEqual(newAck,{acks:1,revision:0,factoryCalls:0});
  await page.evaluate(()=>{purposeSheet();document.querySelector('#purposeLabel').value='늦게 저장';holdPurpose=true;heldPurpose=null;window.lateSave=action('savePurpose',document.querySelector('#savePurpose'));});await page.waitForFunction(()=>!!heldPurpose);
  await page.evaluate(async()=>{gateExposureChanged('HIDDEN',nativeNavigation,state.gateTransition,state.gate.pkg);holdPurpose=false;receive(heldPurpose.id,heldPurpose.out,null);await lateSave;});
  assert.equal(await page.evaluate(()=>!gateAlive&&poll===null&&!document.querySelector('#sheet').innerHTML),true);
  // APP_GATE layout requests in a single frame coalesce, including IME notifications.
  await page.evaluate(()=>{fixture=gateData('app.video');presentGate('app',fixture,11);clearInterval(poll);});
  await page.evaluate(()=>new Promise(requestAnimationFrame));
  const coalesced=await page.evaluate(async()=>{layouts=0;viewportChanged();viewportChanged();insetsChanged(true);viewportChanged(true);await new Promise(requestAnimationFrame);insetsChanged(false);return layouts;});
  assert.equal(coalesced,1);
  await page.evaluate(()=>{fixture.gateExposure='WAITING';presentGate('app',fixture,12);});
  assert.equal(await page.evaluate(()=>gateExposure==='WAITING'&&poll===null),true);
  await page.evaluate(()=>gateExposureChanged('INTERACTIVE',nativeNavigation,state.gateTransition,state.gate.pkg));await page.waitForFunction(()=>gateRefresh===null);
  assert.equal(await page.evaluate(()=>poll!==null&&gateExposure==='INTERACTIVE'),true);await page.evaluate(()=>{clearInterval(poll);poll=null;});
  const utility=await page.evaluate(async()=>{const navigation=nativeNavigation,node=document.querySelector('.gate-page');await navigate('notes');return {same:node===document.querySelector('.gate-page')&&navigation===nativeNavigation&&screen==='app',target:calls.filter(c=>c.action==='open').at(-1)?.args.screen};});
  assert.deepEqual(utility,{same:true,target:'notes'});
  // Mutations outside presentGate (editor/control/theme) must retire pixel reuse.
  const tracked=await page.evaluate(async()=>{
   const revision=domRevision,control=document.querySelector('[data-purpose]');
   control.disabled=true;await Promise.resolve();const disabled=domRevision>revision;
   const next=domRevision;purposeSheet();await Promise.resolve();const editor=domRevision>next;
   closeSheet();control.disabled=false;await new Promise(requestAnimationFrame);
   return {disabled,editor};
  });
  assert.deepEqual(tracked,{disabled:true,editor:true});
  await page.evaluate(()=>new Promise(requestAnimationFrame));
  const reusedLayout=await page.evaluate(async()=>{
   layouts=0;const revision=domRevision;presentGate('app',fixture,nativeNavigation+1);clearInterval(poll);
   await new Promise(requestAnimationFrame);return {layouts,unchanged:revision===domRevision};
  });
  assert.deepEqual(reusedLayout,{layouts:0,unchanged:true});
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
  assert.deepEqual(errors,[]);console.log('PASS APP_GATE keyed DOM/edit reuse, tick single-flight, stale entry responses, exposure polling, IME/layout coalescing, grants/exits and preserved PHONE_START/MAIN behavior');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
