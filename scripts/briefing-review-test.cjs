// Exercises the shipped assets using JSON emitted by the real Java analysis, not a second UI implementation.
const fs=require('node:fs'),assert=require('node:assert/strict'),{chromium}=require('playwright');
const native=JSON.parse(fs.readFileSync('app/build/briefing-review/native.json','utf8'));
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||undefined,args:['--no-sandbox']});
 let page;const results={},out=process.env.LOCKY_CAPTURE_DIR;if(out)fs.mkdirSync(out,{recursive:true});
 try{
  page=await browser.newPage({viewport:{width:390,height:844}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<html lang="ko"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>'+fs.readFileSync('app/src/main/assets/style.css','utf8')+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/app.js','utf8')});
  async function show(data){await page.evaluate(b=>{state={overlay:false,prefs:{},gate:{},status:{}};screen='brief';briefData=b;briefRecordFilter='all';briefAppFilter='all';briefSort.apps='desc';briefSort.timeline='asc';document.querySelector('#app').innerHTML=brief();renderBrief(b);document.querySelector('#briefDate').textContent=b.date;},data);}
  const purpose=()=>page.locator('#brief-purposes .brief-purpose-unit').filter({has:page.locator('summary strong',{hasText:'자료 확인'})});
  const openId=async id=>{await page.evaluate(id=>{const d=document.getElementById(id);if(!d)throw Error('Missing details '+id);d.open=true;briefHydrate(d);},id);};
  await show(native.daily);assert.match(await page.evaluate(()=>briefBoundaryRecords(briefData)[0].text),/기간 시작/);assert.equal(await page.evaluate(()=>briefBoundaryRecords({start:0,focus:{periods:[{start:0,known:true,focus:true},{start:1000,known:true,focus:true}]}}).length),1,'same-state settings must not invent a transition');
  const stats=await page.evaluate(()=>briefModel(briefData).purposes.map(p=>({purpose:p.purpose,total:p.total,apps:p.apps})));
  assert.equal(stats.find(p=>p.purpose==='자료 확인').total,440000,'same phone/app purpose must count every interval only once');
  assert.deepEqual(stats.find(p=>p.purpose==='자료 확인').apps.map(a=>[a.label,a.ms]),[['ChatGPT',360000],['브라우저',80000]]);
  await purpose().locator(':scope > summary').click();
  await purpose().locator('.brief-purpose-apps').waitFor();
  assert.match(await purpose().innerText(),/6분/);assert.match(await purpose().innerText(),/1분 20초/);
  assert.equal(await purpose().locator('.brief-flow-unit').count(),0,'do not nest the same purpose heading again');
  assert.equal(await purpose().locator('summary strong').count(),1);
  assert.equal(await purpose().locator('.brief-purpose-choice').count(),2);
  if(out)await page.screenshot({path:out+'/purpose-open-light.png',fullPage:true});
  await openId('brief-flows');await openId('brief-flow-1');assert.match(await page.locator('#brief-flow-1 .brief-app-totals').innerText(),/6분/);assert.match(await page.locator('#brief-flow-1 .brief-app-totals').innerText(),/1분 20초/);assert(!/목적 (연결 )?미확인/.test(await page.locator('#brief-flow-1').innerText()));
  if(out)await page.screenshot({path:out+'/flow-open-light.png',fullPage:true});
  await page.evaluate(()=>{document.getElementById('brief-flows').open=false;});
  await page.locator('[data-brief-filter=free]').click();assert.deepEqual(await page.locator('#brief-apps .usage-row strong').allTextContents(),['브라우저']);
  await page.locator('[data-brief-filter=focus]').click();assert.deepEqual(await page.locator('#brief-apps .usage-row strong').allTextContents(),['ChatGPT','동영상','브라우저']);
  await page.locator('[data-brief-sort=apps]').click();assert.deepEqual(await page.locator('#brief-apps .usage-row strong').allTextContents(),['브라우저','동영상','ChatGPT']);
  assert.equal(await purpose().getAttribute('open'),'','app controls preserve opened purpose details');
  const nine=native.daily.hours.find(h=>new Date(h.start).toISOString().includes('T00:00:00'));
  assert(nine);await page.locator('.brief-hour-cell[data-brief-hour="'+nine.start+'"]').click();
  assert.match(await page.locator('.brief-hour-selection').innerText(),/7분 20초/);assert.match(await page.locator('.brief-hour-selection').innerText(),/6분/);
  await openId('briefTimeline');await openId('brief-record-day-2026-11-17');await openId('brief-phone-2026-11-17-1');
  const tree=await page.evaluate(()=>{
   const d=briefTimelineModel(briefData).days.find(d=>d.key==='2026-11-17'),phone=d.nodes.find(n=>n.event?.id===1);
   return {outside:d.nodes.filter(n=>n.kind==='visit').map(n=>n.pkg),ms:phone.nodes.filter(n=>n.kind==='visit').reduce((s,v)=>s+v.ms,0),locations:[...briefTimelineModel(briefData).locations].filter(([k])=>k==='e2'||k.startsWith('u')).map(([k,v])=>[k,v])};
  });
  assert.deepEqual(tree.outside,['video']);assert.equal(tree.ms,440000);
  assert.equal(await page.locator('#brief-phone-2026-11-17-1 > div > .brief-phone-children > .brief-visit-unit').count(),4);
  await page.locator('[data-brief-sort=timeline]').click();assert.equal(await page.locator('#briefTimeline').getAttribute('open'),'');await page.locator('[data-brief-sort=timeline]').click();
  const firstApp='brief-visit-2026-11-17-e2';await openId(firstApp);assert.equal(await page.locator('[id="'+firstApp+'"]').count(),1);
  assert.match(await page.locator('[id="'+firstApp+'"] .brief-visit-records').innerText(),/5분/);
  assert(!/목적 (연결 )?미확인/.test(await page.locator('#briefTimeline').innerText()));
  assert.equal(await page.evaluate(()=>{const ids=[...document.querySelectorAll('[id]')].map(n=>n.id);return new Set(ids).size===ids.length;}),true,'duplicate DOM identifiers');
  if(out)await page.screenshot({path:out+'/timeline-open-light.png',fullPage:true});
  await page.evaluate(()=>{window.oldTimeline=document.getElementById('briefTimeline');briefData={...briefData,total:briefData.total+60000};renderBrief(briefData);});
  assert.equal(await page.evaluate(()=>oldTimeline===document.getElementById('briefTimeline')),true,'summary update should retain unchanged timeline DOM');
  assert.equal(await page.locator('[id="'+firstApp+'"]').getAttribute('open'),'');
  await page.locator('[data-brief-record-filter=cancels]').click();assert.match(await page.locator('#briefTimeline').innerText(),/선택한 유형의 기록 없음/);
  await page.locator('[data-brief-record-filter=all]').click();assert.equal(await page.locator('#briefTimeline').getAttribute('open'),'');
  // Jump to an overlapping interval must open every ancestor and the exact leaf.
  await page.evaluate(at=>briefJump(at),nine.start+3*60000);assert.equal(await page.locator('[id="'+firstApp+'"]').getAttribute('open'),'');
  await page.evaluate(()=>{const b=JSON.parse(JSON.stringify(briefData));b.timeline.push({start:b.start+10*3600000,end:b.start+10*3600000+10000,pkg:'video',label:'동영상',parent:0,eventId:0,purpose:'목적 미확인',focusState:'focus',relation:'unconfirmed',source:'usage-access'});b.total+=10000;briefData=b;renderBrief(b);});
  await page.evaluate(()=>{const p=document.getElementById('brief-purpose-'+encodeURIComponent('장보기'));p.open=true;briefHydrate(p);});
  assert.match(await page.locator('[id="brief-purpose-'+encodeURIComponent('장보기')+'"]').innerText(),/관측되지 않았습니다/);
  // Small, wide, dark, and expanded states must remain readable without horizontal page overflow.
  for(const width of [320,390,740]){await page.setViewportSize({width,height:844});for(const dark of [false,true]){await page.evaluate(d=>themeChanged(d),dark);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true,'overflow at '+width);}}
  if(out){await page.setViewportSize({width:390,height:844});await page.screenshot({path:out+'/purpose-timeline-dark.png',fullPage:true});}
  await show(native.shortUse);assert.match(await purpose().locator(':scope>summary').innerText(),/1분 미만/);assert.match(await page.locator('.brief-overview .stat').first().innerText(),/1분 미만/);
  assert.equal(await page.evaluate(()=>briefModel(briefData).purposes.find(p=>p.purpose==='자료 확인').total),20000);
  await show(native.boundaryUse);const boundary=await page.evaluate(()=>briefTimelineModel(briefData).days.flatMap(d=>d.nodes).filter(n=>n.kind==='visit').map(v=>({steps:v.steps.length,ms:v.ms})));assert.deepEqual(boundary,[{steps:2,ms:1200000}],'focus boundary must not create a second app entry');
  await show(native.weekly);const week=await page.evaluate(()=>{const m=briefTimelineModel(briefData);return {total:m.days.reduce((s,d)=>s+d.ms,0),day:m.days.find(d=>d.key==='2026-11-18')?.ms,duplicates:m.markers.filter(t=>t.type==='usage').map(t=>t.recordKey)};});
  assert.equal(week.total,native.weekly.total);assert.equal(week.day,60000);assert.equal(new Set(week.duplicates).size,week.duplicates.length);
  const midnight=native.weekly.hours.find(h=>new Date(h.start).toISOString().startsWith('2026-11-17T15:00:00'));assert(midnight);
  await page.locator('.brief-hour-cell[data-brief-hour="'+midnight.start+'"]').click();assert.match(await page.locator('.brief-hour-selection').innerText(),/브라우저/);assert.match(await page.locator('.brief-hour-selection').innerText(),/1분/);
  await openId('briefTimeline');await openId('brief-record-day-2026-11-18');await openId('brief-phone-2026-11-18-1');
  if(out)await page.screenshot({path:out+'/weekly-midnight.png',fullPage:true});
  await page.evaluate(()=>{briefData={...briefData,days:1,total:7200000,complete:true,quality:{comparisonEligible:true},previous:{available:true,total:3600000},comparison:{records:Array.from({length:5},(_,i)=>({date:'2026-11-'+(10+i),total:3600000}))}};renderBrief(briefData);});
  assert.match(await page.locator('#brief-comparison .brief-change').innerText(),/↑ 60분/);
  await page.evaluate(()=>{briefData.comparison.records.pop();renderBrief(briefData);});assert.equal(await page.locator('.brief-change').count(),0);
  // Older saved snapshots retain explicit contexts without requiring a database migration.
  const legacy=structuredClone(native.daily);delete legacy.purposeContexts;await show(legacy);assert.equal(await page.evaluate(()=>briefModel(briefData).purposes.find(p=>p.purpose==='자료 확인').total),440000);
  await page.evaluate(()=>{briefData={...briefData,focus:{},highlights:[]};renderBrief(briefData);});assert.equal(await page.locator('[data-brief-filter=focus]').isDisabled(),true);
  // Scale check: input controls must not regenerate unrelated timeline or purpose DOM.
  const large=structuredClone(native.daily);large.events=[];large.purposeContexts=[];large.flows=[];large.purposes=[];large.timeline=Array.from({length:3000},(_,i)=>({start:large.start+i*2000,end:large.start+i*2000+1000,pkg:'app'+(i%30),label:'앱 '+(i%30),parent:0,eventId:0,purpose:'목적 미확인',focusState:i%2?'focus':'free',relation:'unconfirmed'}));large.apps=Array.from({length:30},(_,i)=>({pkg:'app'+i,label:'앱 '+i,ms:100000,focus:i%2?100000:0,free:i%2?0:100000}));large.total=3000000;
  await show(large);results.scale=await page.evaluate(()=>{const timeline=document.getElementById('briefTimeline'),purposes=document.getElementById('brief-purposes'),times=[];for(let i=0;i<12;i++){const button=document.querySelector('[data-brief-filter="'+(i%2?'focus':'free')+'"]'),start=performance.now();button.click();times.push(performance.now()-start);}return {records:briefData.timeline.length,maxClickMs:Math.max(...times),medianClickMs:[...times].sort((a,b)=>a-b)[6],retained:timeline===document.getElementById('briefTimeline')&&purposes===document.getElementById('brief-purposes'),domNodes:document.querySelectorAll('#briefContent *').length};});
  assert.equal(results.scale.retained,true);assert(results.scale.domNodes<1500);assert(results.scale.maxClickMs<500,'filter click blocks the browser');
  assert.deepEqual(errors,[]);results.passed=['native analysis to rendered totals','same-purpose ancestor deduplication','direct app details without repeated purpose fold','unknown intent preserved','single recorded parent','filter sorting and partial updates','hour selection','nested jump and refresh','midnight day/hour reconciliation','short duration','comparison sufficiency','legacy snapshot','320–740px light/dark','3000-record controls'];
  if(out)fs.writeFileSync(out+'/results.json',JSON.stringify(results,null,2));console.log(JSON.stringify(results,null,2));
 }catch(error){if(out&&page)await page.screenshot({path:out+'/failure.png',fullPage:true}).catch(()=>{});throw error;}finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
