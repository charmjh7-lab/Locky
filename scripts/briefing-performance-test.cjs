// Focused regression checks for collapsed purpose containment and pending-report navigation.
const fs=require('node:fs'),assert=require('node:assert/strict'),{chromium}=require('playwright');
const native=JSON.parse(fs.readFileSync('app/build/briefing-review/native.json','utf8'));
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||undefined,args:['--no-sandbox']});
 const out=process.env.LOCKY_CAPTURE_DIR;if(out)fs.mkdirSync(out,{recursive:true});const results={};let page;
 try{
  page=await browser.newPage({viewport:{width:390,height:844}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<html lang="ko"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>'+fs.readFileSync('app/src/main/assets/style.css','utf8')+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/app.js','utf8')});
  async function show(b){await page.evaluate(b=>{state={role:'MAIN',overlay:false,prefs:{},gate:{},status:{},purposes:[],allPurposes:[],notes:[],chats:[],rules:[]};screen='brief';briefData=b;briefRecordFilter='all';briefSort.timeline='asc';document.getElementById('app').innerHTML=brief();renderBrief(b);},b);}
  const openId=async id=>{await page.evaluate(id=>{const d=document.getElementById(id);if(!d)throw Error('Missing '+id);d.open=true;briefHydrate(d);},id);};
  await show(native.displayDaily);
  // A compact bridge response must retain all raw observations and purpose totals.
  assert.equal(await page.evaluate(()=>briefModel(briefData).purposes.find(p=>p.purpose==='자료 확인').total),320000);
  assert.equal(await page.evaluate(()=>briefModel(briefData).purposes.find(p=>p.purpose==='메모 확인').total),180000,'phone-purpose statistics must match the same chronological container');
  await openId('briefTimeline');await openId('brief-record-day-2026-11-17');
  const tree=await page.evaluate(()=>briefTimelineModel(briefData).days.find(d=>d.key==='2026-11-17').nodes.filter(n=>n.kind!=='event').map(n=>({kind:n.kind,id:n.event?.id,ms:n.nodes?.reduce((s,v)=>s+(v.ms||0),0),apps:n.nodes?.filter(v=>v.kind==='visit').map(v=>({pkg:v.pkg,purpose:v.choice?.purpose||''}))})));
  assert.deepEqual(tree.map(n=>[n.kind,n.id,n.ms]),[['phone',1,320000],['phone',3,180000]],'every later app, including parent=0 recovery, belongs inside the chronological phone opening');
  assert.equal(tree[1].apps.find(v=>v.pkg==='video').purpose,'');assert.equal(tree[1].apps.find(v=>v.pkg==='chat').purpose,'','an older app choice must not be repeated under a new phone opening');
  assert.equal(await page.locator('#brief-record-day-2026-11-17 > div > .brief-day-records > .brief-visit-unit').count(),0);
  assert.equal(await page.locator('#briefTimeline .brief-visit-unit').count(),0,'collapsed phone groups must not render sibling app lists');
  if(out)await page.screenshot({path:out+'/phone-groups-closed.png',fullPage:true});
  await openId('brief-phone-2026-11-17-3');const child=page.locator('#brief-phone-2026-11-17-3 .brief-visit-unit').first();assert.equal(await child.isVisible(),true);assert.equal(await page.locator('#brief-phone-2026-11-17-3 .brief-visit-unit').count(),4);
  await page.locator('#brief-phone-2026-11-17-3 > summary').click();assert.equal(await child.isVisible(),false,'hydrated app lists must remain hidden when the phone purpose is collapsed');
  await page.locator('#brief-phone-2026-11-17-3 > summary').click();assert.equal(await child.isVisible(),true);
  await page.evaluate(()=>themeChanged(true));if(out)await page.screenshot({path:out+'/phone-group-open-dark.png',fullPage:true});
  await show(native.weekly);const week=await page.evaluate(()=>{const m=briefTimelineModel(briefData);return {total:m.days.reduce((s,d)=>s+d.ms,0),afterMidnight:m.days.find(d=>d.key==='2026-11-18').nodes.filter(n=>n.kind==='phone').map(n=>[n.event.id,n.nodes.reduce((s,v)=>s+(v.ms||0),0)])};});assert.equal(week.total,native.weekly.total);assert.deepEqual(week.afterMidnight,[[3,60000]]);
  // A report and the next state response can both remain pending while home paints.
  await show(native.displayDaily);
  results.navigation=await page.evaluate(async()=>{
   restored=true;renderedRoute='brief';const waiting=[];window.Native={request(id,action){if(action==='brief'||action==='state')waiting.push({id,action});else receive(id,{},null);}};
   const work=refresh();await Promise.resolve();receive(waiting.find(r=>r.action==='state').id,state,null);await work;await Promise.resolve();
   const pending=waiting.find(r=>r.action==='brief');if(!pending)throw Error('Report did not start');if(refreshing)throw Error('Report retained the global refresh lock');
   const before=performance.now();const navigation=navigate('home');await Promise.resolve();await Promise.resolve();
   const ms=performance.now()-before,visible=!!document.querySelector('.start-page');if(!visible)throw Error('Home waited for a pending native response');
   const home=document.querySelector('.start-page');receive(pending.id,briefData,null);await Promise.resolve();await Promise.resolve();if(home!==document.querySelector('.start-page'))throw Error('Stale report changed the home screen');
   const stateRequest=waiting.filter(r=>r.action==='state').at(-1);receive(stateRequest.id,state,null);await navigation;clearInterval(poll);return {paintMs:ms,homeVisible:visible};
  });assert(results.navigation.paintMs<250);
  // Many records stay in the model; folded details keep the document small.
  const large=structuredClone(native.displayDaily);large.timeline=Array.from({length:6000},(_,i)=>({start:large.start+i*12000,end:large.start+i*12000+8000,pkg:'app'+i%17,label:'앱 '+i%17,parent:0,eventId:0,purpose:'목적 미확인',focusState:'free',relation:'unconfirmed'}));large.events=Array.from({length:75},(_,i)=>({id:i+100,time:large.start+i*80*12000,kind:'unlock',purpose:'휴대폰 목적 '+i}));large.purposeContexts=large.events;large.flows=[];large.purposes=[];large.apps=Array.from({length:17},(_,i)=>({pkg:'app'+i,label:'앱 '+i,ms:2800000,focus:0,free:2800000}));large.total=48000000;
  results.scale=await page.evaluate(b=>{screen='brief';briefData=b;document.getElementById('app').innerHTML=brief();const t=performance.now();renderBrief(b);const renderMs=performance.now()-t;const start=performance.now(),m=briefTimelineModel(b),modelMs=performance.now()-start;return {records:b.timeline.length,renderMs,modelMs,domNodes:document.querySelectorAll('#briefContent *').length,outside:m.days.flatMap(d=>d.nodes).filter(n=>n.kind==='visit').length,total:m.days.flatMap(d=>d.nodes).flatMap(n=>n.nodes||[]).reduce((s,v)=>s+(v.ms||0),0)};},large);
  assert.equal(results.scale.outside,0);assert.equal(results.scale.total,large.total);assert(results.scale.domNodes<1600);assert(results.scale.renderMs<1500);assert(results.scale.modelMs<1000);assert.deepEqual(errors,[]);
  results.passed=['closed and reopened purpose containment','unlinked recovery kept without invented app intent','compact payload keeps observed totals','midnight grouping','pending report and pending state do not block home','stale report cannot replace home','6000-record bounded initial DOM'];
  if(out)fs.writeFileSync(out+'/results.json',JSON.stringify(results,null,2));console.log(JSON.stringify(results,null,2));
 }catch(e){if(out&&page)await page.screenshot({path:out+'/failure.png',fullPage:true}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
