const { chromium }=require('playwright');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.join(__dirname,'../app/src/main/assets');
const phone=Array.from({length:9},(_,i)=>({id:'phone'+i,label:i===8?'그냥 열었어요':'휴대폰 목적 '+i,scope:'phone',pkg:'',uri:'',aimless:i===8?1:0}));
const appPurposes=[{id:'app-rest',label:'콘텐츠 보기',scope:'app',pkg:'',uri:'',aimless:0},{id:'app-aimless',label:'그냥 열었어요',scope:'app',pkg:'',uri:'',aimless:1}];
const demo={overlay:false,prefs:{enabled:true,start:540,end:1260,reminders:[30,20,10,5,1],weeklyDay:7,model:'test-model'},status:{accessibility:true,usage:true,notifications:true,exact:true},free:false,gate:{mode:'app',pkg:'app.video',label:'Video',purpose:'콘텐츠 보기',daily:3600000,session:300000,activePkg:'app.video'},purposes:phone,allPurposes:[...phone,...appPurposes],notes:[],chats:[],rules:[],ruleSeen:[],dictionary:'',models:[{slug:'test-model',name:'Test model'}],accounts:[{active:true,connected:true,email:'test',plan:true}],connection:{},busy:false,activeChat:'',chatError:''};
const calls=[],messages=[],errors=[];
const apps=Array.from({length:8},(_,i)=>({pkg:i===0?'app.video':'app.'+i,label:i===0?'Video':'App '+i,icon:'data:image/svg+xml;base64,'+Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48"><rect width="48" height="48" rx="12" fill="#416a56"/></svg>').toString('base64')}));
const server=http.createServer((req,res)=>{const name=req.url==='/'?'index.html':req.url.slice(1);if(!['index.html','app.js','style.css'].includes(name)){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(path.join(root,name)));});
(async()=>{await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,args:['--no-sandbox','--no-zygote','--single-process']}:{})});try {
 const page=await browser.newPage({viewport:{width:390,height:780}});page.on('pageerror',e=>errors.push(e.message));
 await page.exposeFunction('nativeRequest',async(id,action,raw)=>{const a=JSON.parse(raw);calls.push({action,a});let out={};switch(action){
 case 'state':out=demo;break;case 'tick':out={gate:demo.gate,free:demo.free,busy:false,connection:{}};break;case 'apps':out=apps;break;case 'usageApps':out=apps.map((a,i)=>({...a,excluded:i===0,system:i===0}));break;
 case 'saveNote':{let n={...a,checks:JSON.stringify(a.checks),updated:Date.now()};demo.notes=demo.notes.filter(n=>n.id!==a.id);demo.notes.unshift(n);break;}
 case 'deleteNote':demo.notes=demo.notes.filter(n=>n.id!==a.id);break;
 case 'purpose':out={...a,id:a.id||'custom'};demo.allPurposes=demo.allPurposes.filter(p=>p.id!==out.id);demo.allPurposes.push(out);demo.purposes=demo.allPurposes.filter(p=>p.scope==='phone');break;
 case 'removePurpose':demo.allPurposes=demo.allPurposes.filter(p=>p.id!==a.id);demo.purposes=demo.purposes.filter(p=>p.id!==a.id);break;
 case 'newChat':out={id:'chat'+(demo.chats.length+1)};demo.chats.push({id:out.id,title:'새 대화',updated:Date.now()});break;
 case 'messages':out=messages.filter(m=>m.chat===a.id);break;
  case 'send':{
   const body='**웹 검색 테스트 답변**입니다. 【0†source】\n\n### Sources:\n- [원문](https://example.com/source)';
   const tag='【0†source】',start=Array.from(body.slice(0,body.indexOf(tag))).length;
   messages.push({id:messages.length+1,chat:a.id,role:'user',body:a.text,status:'complete'},
     {id:messages.length+2,chat:a.id,role:'assistant',body,status:'complete',citations:JSON.stringify([{url:'https://example.com/source',title:'검증된 출처',start_index:start,end_index:start+Array.from(tag).length}])});
   break;
  }
  case 'copyDay':case 'saveDay':out={ok:true};break;
 case 'rule':demo.rules=demo.rules.filter(r=>r.pkg!==a.pkg);if(a.mode!=='none')demo.rules.push({...a,mode:a.mode==='restore'?'limited':a.mode,label:apps.find(p=>p.pkg===a.pkg).label,minutes:a.minutes||60});if(!demo.ruleSeen.includes(a.pkg))demo.ruleSeen.push(a.pkg);break;
 case 'dictionaries':out=[{id:'naver',label:'네이버 사전'},{id:'dictionary/test',label:'기기 사전'}];break;
 case 'dictionaryChoice':demo.dictionary=a.id;break;case 'dictionaryPicker':demo.dictionary='process:dictionary/test';break;
 case 'settings':Object.assign(demo.prefs,a);break;
 case 'brief':out={days:a.days,date:'2026-10-14',start:Date.now()-86400000,end:Date.now(),total:1200000,unlocks:9,aimless:1,deviation:0,linked:900000,summary:'오늘 앱을 20분 사용했어요.',insight:'새로 선택한 목적을 기록했어요.',apps:apps.map((a,i)=>({...a,ms:(8-i)*60000})),events:phone.map((p,i)=>({kind:'unlock',purpose:p.label,time:Date.now()+(8-i)*60000})),timeline:[],drift:[],usageAccess:true,recovered:0};break;
 }await page.evaluate(({id,out})=>receive(id,out,null),{id,out});});
 await page.addInitScript(()=>{window.Native={request:(...args)=>window.nativeRequest(...args)};window.clickLog=[];document.addEventListener('click',e=>window.clickLog.push({id:e.target.closest('button')?.id,act:e.target.closest('button')?.dataset.act,target:e.target.tagName}));});
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.evaluate(()=>navigate('home'));await page.locator('#noteBody').waitFor();
 const fit=async()=>{await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));await page.waitForFunction(()=>Math.abs(parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--viewport'))-innerHeight)<1);const r=await page.evaluate(()=>{const red=document.querySelector('.dismiss').getBoundingClientRect(),p=document.querySelector('.start-purposes'),last=p?.lastElementChild?.getBoundingClientRect(),footer=document.querySelector('.start-footer').getBoundingClientRect();return {scroll:document.documentElement.scrollHeight,width:document.documentElement.scrollWidth,h:innerHeight,w:innerWidth,redBottom:red.bottom,redHeight:red.height,footerTop:footer.top,purposesBottom:last?.bottom};});assert(r.scroll<=r.h+1,JSON.stringify(r));assert(r.width<=r.w,JSON.stringify(r));assert(r.redBottom<=r.h,JSON.stringify(r));assert(r.redHeight>=50);assert(!r.purposesBottom||r.purposesBottom<=r.footerTop+1,JSON.stringify(r));};
 for(const viewport of [{width:360,height:600},{width:390,height:780},{width:700,height:900}]){await page.setViewportSize(viewport);await page.waitForTimeout(70);await fit();}
 await page.setViewportSize({width:390,height:780});await fit();
 // The compact chooser must remain actionable even when the display is short.
 for(const viewport of [{width:320,height:560},{width:360,height:600},{width:390,height:780}]){
   await page.setViewportSize(viewport);await page.waitForTimeout(80);await fit();
   const measurement=await page.evaluate(()=>{
     const chooser=document.querySelector('.start-purposes').getBoundingClientRect();
     const tools=document.querySelector('.quick-tools').getBoundingClientRect();
     const dictionary=document.querySelector('.dictionary-bar').getBoundingClientRect();
     const footer=document.querySelector('.start-footer').getBoundingClientRect();
     const intro=document.querySelector('.purpose-intro').getBoundingClientRect();
     const direct=document.querySelector('[data-act=direct]').getBoundingClientRect();
     return {chooserTop:chooser.top,toolsBottom:tools.bottom,dictionaryBottom:dictionary.bottom,footerTop:footer.top,introBottom:intro.bottom,directTop:direct.top,directBottom:direct.bottom};
   });
   assert(measurement.toolsBottom<=measurement.dictionaryBottom&&measurement.dictionaryBottom<=measurement.chooserTop+1,JSON.stringify(measurement));
   assert(measurement.introBottom<=measurement.directTop+1,JSON.stringify(measurement));
   assert(measurement.directBottom<=measurement.footerTop+1,JSON.stringify(measurement));
 }
 await page.setViewportSize({width:390,height:780});await fit();

 const orderBefore=await page.locator('.start-purposes [data-purpose]').evaluateAll(els=>els.map(e=>e.dataset.purpose));
 await page.evaluate(()=>render());
 assert.deepEqual(await page.locator('.start-purposes [data-purpose]').evaluateAll(els=>els.map(e=>e.dataset.purpose)),orderBefore);
 await page.evaluate(()=>navigate('home'));
 assert.notDeepEqual(await page.locator('.start-purposes [data-purpose]').evaluateAll(els=>els.map(e=>e.dataset.purpose)),orderBefore,'A new start must reshuffle presets');

 // With only a few purposes, keep the chooser near the tools without stretching editors.
 const balanced=await page.evaluate(async()=>{
   const previous=state.allPurposes;
   let phoneCount=0;
   state.allPurposes=previous.filter(p=>p.scope!=='phone'||++phoneCount<=3);
   render();await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
   const header=document.querySelector('.start-page .top').getBoundingClientRect();
   const tools=document.querySelector('.quick-tools').getBoundingClientRect();
   const dictionary=document.querySelector('.dictionary-bar').getBoundingClientRect();
   const purposes=document.querySelector('.start-purposes').getBoundingClientRect();
   const footer=document.querySelector('.start-footer').getBoundingClientRect();
   const panel=document.querySelector('.start-purposes');
   const result={toolHeight:tools.height,topGap:tools.top-header.bottom,toolsToDictionary:dictionary.top-tools.bottom,dictionaryToPurposes:purposes.top-dictionary.bottom,bottomGap:footer.top-purposes.bottom,heading:panel.querySelector('#purposeQuestion')?.textContent,helper:panel.querySelector('.purpose-helper')?.textContent,panelBackground:getComputedStyle(panel).backgroundColor,panelBorder:getComputedStyle(panel).borderTopWidth};
   state.allPurposes=previous;render();return result;
 });
 assert(balanced.toolHeight<=254,JSON.stringify(balanced));
 assert(balanced.topGap>=0&&balanced.topGap<=16,JSON.stringify(balanced)); // Utilities stay directly below the header.
 assert(balanced.toolsToDictionary>=0&&balanced.toolsToDictionary<=16,JSON.stringify(balanced));
 assert(balanced.dictionaryToPurposes>=8,JSON.stringify(balanced));
 assert(Math.abs(balanced.bottomGap-balanced.dictionaryToPurposes)<=10,JSON.stringify(balanced)); // Center the decision independently of the utility anchors.
 assert.equal(balanced.heading,'무엇을 하려고 열었나요?');
 assert.equal(balanced.helper,'목적을 선택하면 휴대폰 사용을 시작합니다.');
 assert.equal(balanced.panelBorder,'0px');
 assert.notEqual(balanced.panelBackground,'rgba(0, 0, 0, 0)');

 await fit();const initial=await page.locator('.quick-tools').boundingBox();await page.locator('#noteBody').fill('길어도 고정 크기\n'.repeat(80));await page.waitForTimeout(450);assert(demo.notes.some(n=>n.body.includes('길어도')));await fit();const editedHeight=(await page.locator('.quick-tools').boundingBox()).height;assert(editedHeight>=130&&Math.abs(editedHeight-initial.height)<2,JSON.stringify({before:initial.height,after:editedHeight}));
 await page.locator('.note-heading').click();await page.locator('#noteTitle').fill('내 메모');await page.waitForTimeout(400);assert.equal(demo.notes[0].title,'내 메모');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollHeight<=innerHeight),true);assert(await page.locator('.note-content').evaluate(el=>el.scrollHeight>el.clientHeight));
 await page.locator('#noteMode').selectOption('check');await page.locator('[data-checktext]').first().waitFor();assert.equal(await page.locator('#noteBody').count(),0);await page.locator('[data-act=addCheck]').click();await page.locator('[data-checktext]').last().fill('책 읽기');await page.locator('[data-check]').last().check();await page.waitForTimeout(500);assert(JSON.parse(demo.notes[0].checks).at(-1).done);await page.locator('[data-checktext]').last().press('Enter');assert((await page.locator('[data-checktext]').count())>=3);
 await page.locator('[data-act=newNote]').click();await page.locator('[data-newnote=text]').click();
 await page.evaluate(()=>navigate('home'));
 const displayedTitle=await page.locator('.compact-note .note-heading').innerText();
 await page.locator('[data-note-step="1"]').click();
 assert.notEqual(await page.locator('.compact-note .note-heading').innerText(),displayedTitle);
 await page.evaluate(()=>navigate('home'));await page.setViewportSize({width:360,height:600});await page.waitForTimeout(80);await page.locator('[data-act=morePurposes]').click();assert.equal(await page.locator('#sheet [data-purpose]').count(),9);await page.locator('[data-act=closeSheet]').click();await page.setViewportSize({width:390,height:780});await page.waitForTimeout(80);assert((await page.locator('[data-purpose]:visible').count())>=3);
 await page.locator('[data-act=managePurposes]').click();await page.locator('#sheet [data-editpurpose=phone0]').click();await page.locator('#purposeLink').check();await page.locator('#purposeAppSearch').fill('Video');assert.equal(await page.locator('.purpose-app-row:visible').count(),1);assert.equal(await page.locator('.purpose-app-row img').count(),1);await page.locator('[data-pickapp="app.video"]').click();assert.equal(await page.locator('#purposeApp').inputValue(),'app.video');await page.locator('#savePurpose').click();await page.waitForTimeout(100);assert.equal(demo.allPurposes.find(p=>p.id==='phone0').pkg,'app.video');await page.locator('[data-act=managePurposes]').click();await page.locator('#sheet [data-editpurpose=phone0]').click();await page.locator('#purposeLink').uncheck();await page.locator('#savePurpose').click();await page.waitForTimeout(100);assert.equal(demo.allPurposes.find(p=>p.id==='phone0').pkg,'');await page.locator('[data-act=managePurposes]').click();await page.locator('#sheet [data-editpurpose=phone0]').click();await page.locator('[data-act=deletePurpose]').click();await page.waitForTimeout(100);assert(!demo.allPurposes.some(p=>p.id==='phone0'));
 await page.evaluate(()=>{state.overlay=true;render();});
 const previousPositions=await page.locator(".start-purposes [data-purpose]").evaluateAll(nodes=>nodes.map(x=>x.dataset.purpose));
 await page.evaluate(()=>reshufflePurposesForStart());
 assert.notDeepEqual(await page.locator(".start-purposes [data-purpose]").evaluateAll(nodes=>nodes.map(x=>x.dataset.purpose)),previousPositions);
 await page.locator('[data-act=managePurposes]').click();
 await page.locator('#sheet [data-editpurpose="phone1"]').click();
 await page.locator('#purposeLink').check();await page.locator('#purposeApp').selectOption('app.video');
 await page.locator('#savePurpose').click();
 assert.equal(demo.allPurposes.find(p=>p.id==='phone1').pkg,'app.video');
 await page.locator('[data-act=managePurposes]').click();await page.locator('#sheet [data-editpurpose="phone1"]').click();
 assert.equal(await page.locator('#purposeApp').inputValue(),'app.video');
 await page.locator('#purposeApp').selectOption('app.2');await page.locator('#savePurpose').click();
 assert.equal(demo.allPurposes.find(p=>p.id==='phone1').pkg,'app.2');
 await page.evaluate(()=>navigate('app'));await page.locator('[data-act=managePurposes]').click();
 await page.locator('#sheet [data-editpurpose="app-rest"]').click();await page.locator('#purposeLink').check();
 await page.locator('#purposeApp').selectOption('app.3');await page.locator('#savePurpose').click();
 assert.equal(demo.allPurposes.find(p=>p.id==='app-rest').pkg,'app.3');
 await page.evaluate(()=>{state.overlay=false;render();});await page.evaluate(()=>navigate('home'));
 await page.locator('[data-act=managePurposes]').click();await page.locator('[data-switchscope=app]').click();assert.equal(await page.locator('#sheet [data-remove]').count(),2);await page.locator('[data-remove=app-aimless]').click();await page.waitForTimeout(100);assert(!demo.allPurposes.some(p=>p.id==='app-aimless'));await page.locator('[data-act=closeSheet]').click();
 const priorAppFetch=calls.filter(c=>c.action==='apps').length;await page.locator('[data-act=direct]').click();await page.locator('#purposeLabel').waitFor();assert.equal(calls.filter(c=>c.action==='apps').length,priorAppFetch);await page.locator('#purposeLabel').fill('친구에게 답장');await page.locator('#savePurpose').click();await page.waitForTimeout(500);assert(calls.some(c=>c.action==='choose'&&c.a.id==='custom'),JSON.stringify({calls:calls.slice(-12).map(c=>({action:c.action,scope:c.a.scope})),toast:await page.locator('#toast').textContent(),clicks:await page.evaluate(()=>clickLog.slice(-8)),button:await page.locator('#savePurpose').evaluate(e=>({html:e.outerHTML,bound:!!e.onclick,rect:e.getBoundingClientRect().toJSON()})).catch(()=>null)}));assert.equal(demo.allPurposes.find(p=>p.id==='custom').scope,'phone');
 await page.locator('#word').fill('습관');await page.locator('#dictionaryForm button[type=submit]').click();await page.waitForTimeout(100);await page.evaluate(()=>refresh(true));await page.locator('#word').fill('선택');await page.locator('#dictionaryForm button[type=submit]').click();await page.waitForTimeout(100);assert.equal(calls.filter(c=>c.action==='dictionaryPicker').length,1);assert.equal(calls.filter(c=>c.action==='dictionary').length,1);
 await page.locator('#quickQuestion').fill('오늘 날씨는?');await page.locator('[data-act=quickSend]').click();
 await page.locator('.message.assistant .chat-markdown strong').waitFor();assert.equal(await page.locator('.message.assistant .chat-markdown strong').innerText(),'웹 검색 테스트 답변');
 assert.equal(await page.locator('.message.assistant').innerText().then(s=>s.includes('**웹')),false);
 assert.equal(await page.locator('.chat-source-toggle').count(),2);
 assert.equal(await page.locator('.chat-source-list[hidden]').count(),await page.locator('.chat-source-list').count());
 await page.locator('.chat-source-toggle').first().click();assert.equal(await page.locator('.chat-source-list:not([hidden])').count(),1);
 await page.locator('.chat-source-item:visible').first().click();assert(calls.some(c=>c.action==='linkTest'&&c.a.uri==='https://example.com/source'));
 await page.locator('.chat-source-toggle').first().click();assert.equal(await page.locator('.chat-source-list:not([hidden])').count(),0);
 await page.locator('.chat-source-toggle').last().click();assert.equal(await page.locator('.chat-source-item:visible').count(),1);
 await page.evaluate(()=>navigate('home'));await page.evaluate(()=>{state.overlay=true;render();});
 await page.locator('#quickQuestion').fill('오버레이에서 보낸 질문');await page.locator('[data-act=quickSend]').click();
 await page.waitForTimeout(300);
 const recentOpen=calls.filter(c=>c.action==='open').at(-1),recentSend=calls.filter(c=>c.action==='send').at(-1);
 assert.equal(recentOpen?.a.screen,'chat');assert.equal(recentOpen?.a.chat,recentSend?.a.id);
 assert.equal(demo.chats.length,2);assert(messages.some(m=>m.chat===recentOpen.a.chat&&m.body==='오버레이에서 보낸 질문'));
 await page.evaluate(id=>{state.overlay=false;return openChatById(id);},recentOpen.a.chat);
 await page.locator('.message.assistant .chat-markdown strong').waitFor();
 assert.equal(await page.locator('.chat-plain').last().innerText(),'오버레이에서 보낸 질문');
 await page.evaluate(()=>navigate('app'));assert.equal(await page.locator('[data-purpose=phone1]').count(),0);await page.locator('[data-min="10"]').click();await page.locator('[data-purpose=app-rest]').click();assert.equal(await page.locator('#startApp').count(),0);assert(calls.some(c=>c.action==='grant'&&c.a.minutes===10));
 for(const name of ['app','renew','blocked']){await page.evaluate(n=>navigate(n),name);await page.setViewportSize({width:360,height:600});await fit();}await page.locator('[data-act=dismiss]').click();assert(calls.some(c=>c.action==='dismiss'));
 await page.setViewportSize({width:390,height:780});await page.evaluate(()=>navigate('settings'));assert.equal(await page.locator('#appList').count(),0);await page.locator('#startTime').fill('22:00');await page.locator('#endTime').fill('07:00');await page.locator('#endTime').blur();await page.waitForTimeout(100);assert.equal(demo.prefs.start,1320);assert.equal(demo.prefs.end,420);
 await page.locator('[data-reminder="30"]').click();assert(!demo.prefs.reminders.includes(30));await page.locator('[data-nav=apps]').click();await page.locator('[data-manage="app.video"]').check();await page.waitForTimeout(100);assert.equal(await page.locator('[data-manage]').first().getAttribute('data-manage'),'app.video');await page.locator('[data-rule="app.video"]').click();await page.locator('#ruleMode').selectOption('locked');await page.waitForTimeout(100);assert.equal(demo.rules[0].mode,'locked');await page.locator('[data-act=closeSheet]').click();await page.locator('[data-manage="app.video"]').uncheck();await page.waitForTimeout(100);assert.equal(await page.locator('[data-manage]').first().getAttribute('data-manage'),'app.video');
 await page.evaluate(()=>navigate('brief'));await page.locator('#briefContent .stat').first().waitFor();assert.equal(await page.locator('.more-list').count(),2);await page.locator('[data-days="7"]').click();await page.waitForTimeout(100);assert(calls.some(c=>c.action==='brief'&&c.a.days===7));
 assert((await page.locator('.usage-row').first().innerText()).includes('Video'));
 assert((await page.locator('.event-row').first().innerText()).includes('휴대폰 목적 0'));
 await page.locator('[data-act=copyDay]').click();await page.locator('[data-act=saveDay]').click();
 assert(calls.some(c=>c.action==='copyDay'&&c.a.offset===0)&&calls.some(c=>c.action==='saveDay'&&c.a.offset===0));
 await page.evaluate(()=>navigate('home'));await fit();const beforeKeyboard=await page.locator('.quick-tools').boundingBox();await page.locator('#word').focus();await page.evaluate(()=>insetsChanged(true));await page.waitForFunction(()=>document.body.classList.contains('keyboard'));assert.equal(await page.locator('.start-footer').isVisible(),false);assert.equal(Math.round((await page.locator('.quick-tools').boundingBox()).height),Math.round(beforeKeyboard.height));assert.equal(await page.locator('.start-purposes').isVisible(),true);await page.evaluate(()=>insetsChanged(false));await page.waitForFunction(()=>!document.body.classList.contains('keyboard'));await page.locator('#word').blur();await fit();
 assert.equal(await page.locator('#noteBody,[data-checktext]').first().evaluate(el=>parseFloat(getComputedStyle(el).fontSize)),14);assert((await page.locator('[data-purpose]:visible').count())>=3);await page.locator('[data-act=managePurposes]').click();await page.locator('#sheet [data-act=closeSheet]').click();assert.equal(await page.locator('#sheet .sheet').count(),0);await page.evaluate(()=>navigate('usageApps'));await page.locator('[data-exclude]').first().waitFor();assert.equal(await page.locator('[data-exclude="app.video"]').isDisabled(),true);await page.locator('[data-exclude="app.1"]').check();assert(calls.some(c=>c.action==='excludeUsage'&&c.a.pkg==='app.1'&&c.a.excluded));await page.evaluate(()=>navigate('home'));await page.screenshot({path:'home.png'});await page.evaluate(()=>navigate('apps'));await page.locator('[data-manage]').first().waitFor();await page.screenshot({path:'settings.png'});
 for(const name of (process.env.LOCKY_PRINT_SCREENSHOTS==='1'?['home','settings']:[])){const data=fs.readFileSync(name+'.png').toString('base64');console.log('LOCKY_IMAGE_BEGIN '+name);for(let i=0;i<data.length;i+=6000)console.log('LOCKY_IMAGE_DATA '+data.slice(i,i+6000));console.log('LOCKY_IMAGE_END '+name);}
 assert.deepEqual(errors,[]);console.log('UI checks passed: no-scroll starts, fixed dismissal, independent presets/deletion, bounded memo, single editor scrolling, quick questions/citations, remembered dictionary, immediate settings and stable checked-app ordering.');
 }finally{await browser.close();server.close();}})().catch(e=>{console.error(e);process.exit(1);});

