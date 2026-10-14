const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const assets=path.join(__dirname,'../app/src/main/assets');
const phone=Array.from({length:30},(_,i)=>({id:'p'+i,label:'목적 '+i,scope:'phone',pkg:'',uri:''}));
const app=[{id:'a1',label:'연락 확인',scope:'app',pkg:'',uri:''},{id:'a2',label:'소식 확인',scope:'app',pkg:'',uri:''}];
const fixture={overlay:false,prefs:{enabled:true,start:540,end:1260},status:{accessibility:true,notifications:true},free:false,gate:{mode:'app',pkg:'app.video',label:'Video',purpose:'연락 확인',daily:3600000,session:300000},allPurposes:[...phone.slice(0,3),...app],notes:[{id:'n',title:'메모',body:'기존 메모',mode:'text',checks:'[]'}],chats:[],accounts:[],models:[],connection:{},busy:false,rules:[]};
const briefing={days:1,date:'2026-10-14',start:1,total:660000,unlocks:2,aimless:0,summary:'사용 기록',insight:'목적 기록',usageAccess:true,recovered:0,apps:[{label:'적게 사용',ms:60000},{label:'많이 사용',ms:600000}],events:[{kind:'unlock',time:1,purpose:'이전 목적'},{kind:'unlock',time:2,purpose:'최근 목적'}],timeline:Array.from({length:12},(_,i)=>({label:'기록 '+i,start:i*60000,end:(i+1)*60000,purpose:'확인'}))};
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,args:['--no-sandbox','--no-zygote','--single-process']}:{})});
 try{
 const page=await browser.newPage({viewport:{width:390,height:780}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.setContent('<!doctype html><html lang="ko"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>'+fs.readFileSync(path.join(assets,'style.css'),'utf8')+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
 await page.addScriptTag({content:fs.readFileSync(path.join(assets,'app.js'),'utf8')});
 await page.evaluate(({fixture,briefing})=>{
  window.fixture=fixture;window.briefing=briefing;window.calls=[];window.failGrant=false;
  window.Native={request:(id,action,raw)=>{const args=JSON.parse(raw);calls.push({action,args});let out={},error=null;
   if(action==='state')out=fixture;
   if(action==='tick')out={gate:fixture.gate,free:false};
   if(action==='brief')out=briefing;
   if(action==='purpose'){out={...args,id:'custom'+calls.length};fixture.allPurposes.push(out);}
   if(action==='grant'&&window.failGrant){error='앱이 바뀌었습니다. 다시 열어 주세요.';window.failGrant=false;}
   setTimeout(()=>receive(id,out,error),30);
  }};state=fixture;noteId='n';render();
 },{fixture,briefing});
 // Keep the restored chooser next to the utility editors; spare space remains below it.
 for(const viewport of [{width:320,height:560},{width:360,height:600},{width:390,height:780},{width:700,height:900}]){
  await page.setViewportSize(viewport);
  for(const count of [0,3,9,30]){
   await page.evaluate(({purposes,app})=>{state.allPurposes=[...purposes,...app];screen='home';render();},{purposes:phone.slice(0,count),app});
   await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
   const result=await page.evaluate(()=>{const box=s=>document.querySelector(s).getBoundingClientRect(),tools=box('.quick-tools'),dictionary=box('.dictionary-bar'),area=box('.start-purposes'),footer=box('.start-footer');return {toolsTop:tools.top,toolsHeight:tools.height,dictionaryBottom:dictionary.bottom,areaTop:area.top,areaBottom:area.bottom,footerTop:footer.top,footerBottom:footer.bottom,height:innerHeight,scroll:document.documentElement.scrollHeight};});
   assert(result.areaBottom<=result.footerTop+1,JSON.stringify({viewport,count,...result}));
   assert(result.footerBottom<=result.height,JSON.stringify(result));assert(result.scroll<=result.height+1,JSON.stringify(result));
   assert(result.toolsTop<=55&&result.toolsHeight>=100,JSON.stringify(result));
   assert(result.areaTop-result.dictionaryBottom>=7&&result.areaTop-result.dictionaryBottom<=17,JSON.stringify(result));
  }
 }
 await page.setViewportSize({width:320,height:560});
 await page.evaluate(()=>{state.allPurposes=Array.from({length:30},(_,i)=>({id:'long'+i,label:'긴 목적 '+i,scope:'app'}));screen='app';render();});
 await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
 assert(await page.locator('.more-reasons').isVisible());
 assert((await page.locator('.start-purposes').boundingBox()).y+(await page.locator('.start-purposes').boundingBox()).height<=(await page.locator('.start-footer').boundingBox()).y+1);
 await page.locator('.more-reasons').click();assert.equal(await page.locator('#sheet [data-purpose]').count(),30);await page.locator('[data-act=closeSheet]').click();
 await page.setViewportSize({width:390,height:780});
 await page.evaluate(({phone,app})=>{state.allPurposes=[...phone.slice(0,3),...app];screen='app';minutes=5;render();},{phone,app});
 await page.evaluate(()=>new Promise(r=>requestAnimationFrame(r)));
 assert.equal(await page.locator('#startApp').count(),0);assert.equal(await page.locator('.minutes .selected').innerText(),'5');
 assert((await page.locator('.duration').boundingBox()).y<(await page.locator('.start-purposes').boundingBox()).y);
 await page.locator('[data-min="9"]').click();
 await page.evaluate(()=>Promise.all([choosePurpose('a1'),choosePurpose('a1')]));
 let grants=await page.evaluate(()=>calls.filter(c=>c.action==='grant'));assert.equal(grants.length,1);assert.deepEqual(grants[0].args,{id:'a1',minutes:9});
 await page.evaluate(()=>{window.failGrant=true;});await page.locator('[data-purpose="a2"]').click();
 await page.waitForFunction(()=>document.querySelector('#toast').textContent.includes('앱이 바뀌었습니다'));
 assert.equal(await page.locator('[data-purpose="a2"]').isDisabled(),false);
 await page.locator('[data-purpose="a2"]').click();await page.waitForFunction(()=>calls.filter(c=>c.action==='grant').length===3);
 // Direct input uses the already chosen duration and starts without a second confirmation screen.
 await page.locator('[data-act="direct"]').click();await page.locator('#purposeLabel').fill('직접 입력 목적');await page.locator('#savePurpose').click();
 await page.waitForFunction(()=>calls.some(c=>c.action==='grant'&&c.args.id.startsWith('custom')));
 grants=await page.evaluate(()=>calls.filter(c=>c.action==='grant'));assert.equal(grants.at(-1).args.minutes,9);
 assert.equal(await page.locator('#sheet .sheet').count(),0);
 await page.evaluate(()=>{screen='renew';render();});assert.equal(await page.locator('[data-act=extend]').count(),1);
 await page.locator('[data-act=extend]').click();assert((await page.evaluate(()=>calls)).some(c=>c.action==='extend'));
 // Briefing defaults and independent icon-only sorting work without fetching again.
 await page.evaluate(()=>navigate('brief'));await page.locator('[data-brief-sort=apps]').waitFor();
 assert.equal(await page.locator('.usage-row strong').first().innerText(),'많이 사용');
 assert.equal(await page.locator('.event-row strong').first().innerText(),'최근 목적');
 assert.equal(await page.locator('[data-brief-sort=apps]').innerText(),'');
 const briefCalls=await page.evaluate(()=>calls.filter(c=>c.action==='brief').length);
 await page.locator('[data-brief-sort=apps]').click();assert.equal(await page.locator('.usage-row strong').first().innerText(),'적게 사용');
 assert.equal(await page.locator('.event-row strong').first().innerText(),'최근 목적');
 await page.locator('[data-brief-sort=events]').click();assert.equal(await page.locator('.event-row strong').first().innerText(),'이전 목적');
 await page.locator('.brief-timeline summary>span').click();assert.equal(await page.locator('.brief-timeline[open] .app-row strong').first().innerText(),'기록 11');
 await page.locator('.brief-timeline .more-list summary').click();
 await page.locator('[data-brief-sort=timeline]').click();assert.equal(await page.locator('.brief-timeline[open] .app-row strong').first().innerText(),'기록 0');
 assert.equal(await page.locator('.brief-timeline .more-list[open]').count(),1);
 assert.equal(await page.evaluate(()=>calls.filter(c=>c.action==='brief').length),briefCalls);
 // Screenshot reproduction: a domain link and citation marker refer to one article.
 const url='https://v2.www.edaily.co.kr/news/1',tag='【0†source】',link='[edaily.co.kr]('+url+')';
 const cases=[{text:'문장입니다. ('+link+') '+tag,span:tag},{text:'문장입니다. ('+link+')',span:link},{text:'문장입니다. (edaily.co.kr)',span:'edaily.co.kr'},{text:'문장입니다. ('+link+')',span:'문장입니다. ('+link+')'},{text:'이모지 😀 문장입니다. ('+link+') '+tag,span:tag}];
 for(const [i,sample] of cases.entries()){
  const start=Array.from(sample.text.slice(0,sample.text.indexOf(sample.span))).length;
  const refs=[{url,title:'기사 제목',start_index:start,end_index:start+Array.from(sample.span).length}];
  await page.evaluate(({text,refs,id})=>{clearInterval(poll);screen='chat';document.querySelector('#app').innerHTML='<main class="page"><div class="message assistant chat-markdown">'+chatBody(text,refs,id)+'</div></main>';bind();},{text:sample.text,refs,id:i});
  assert.equal(await page.locator('.chat-md-link').count(),0);
  assert.equal(await page.locator('.inline-reference').count(),1);
  const visible=await page.locator('.message').innerText();assert(!visible.includes('edaily.co.kr')&&!visible.includes('출처')&&!visible.includes('(')&&!visible.includes(')'),visible);
  await page.locator('.inline-reference').click();assert.equal(await page.locator('.chat-source-item:visible').innerText(),'1\n기사 제목');
  await page.locator('.inline-reference').click();assert.equal(await page.locator('.chat-source-item:visible').count(),0);
 }
 // Link and marker can both have metadata; cleanup must not invalidate earlier offsets.
 await page.evaluate(({url,tag,link})=>{
  const text='겹치는 주석. ('+link+') '+tag;
  const refs=[link,tag].map(part=>{const start=Array.from(text.slice(0,text.indexOf(part))).length;return {url,title:'같은 기사',start_index:start,end_index:start+Array.from(part).length};});
  document.querySelector('#app').innerHTML='<div class="message assistant chat-markdown">'+chatBody(text,refs,'overlap')+'</div>';bind();
 },{url,tag,link});
 assert.equal(await page.locator('.inline-reference').count(),1);assert(!(await page.locator('.message').innerText()).includes('LOCKYCITE'));
 assert(!(await page.locator('.message').innerText()).includes(tag));
 assert.equal(await page.evaluate(()=>{const html=chatBody('본문\n\nSources:\n(https://example.net/raw)',[],'bare');const box=document.createElement('div');box.innerHTML=html;return box.querySelector('[data-url]').dataset.url;}),'https://example.net/raw');
 // Keep substantive linked text and ordinary links, merge trailing sources, and ignore unsafe URLs.
 await page.evaluate(({url,tag})=>{
  const text='**근거 문장** '+tag+'\n일반 링크 [홈페이지](https://example.org/)\n\n### Sources:\n- [같은 기사]('+url+')\n- [별도 자료](https://example.net/source)';
  const start=Array.from(text.slice(0,text.indexOf(tag))).length;
  const ref={url,title:'기사 제목',start_index:start,end_index:start+Array.from(tag).length};
  document.querySelector('#app').innerHTML='<main class="page"><div class="message assistant chat-markdown">'+chatBody(text,[ref,ref,{url:'javascript:alert(1)'}],'mixed')+'</div></main>';bind();
 },{url,tag});
 assert.equal(await page.locator('.chat-md-link').innerText(),'홈페이지');assert.equal(await page.locator('.chat-source-toggle').count(),2);
 assert.equal(await page.locator('.chat-source-footer .chat-source-item').count(),2);
 await page.locator('.chat-source-footer .chat-source-toggle').click();assert.equal(await page.locator('.chat-source-item:visible').count(),2);
 await page.locator('.chat-source-item:visible').first().click();assert((await page.evaluate(()=>calls)).some(c=>c.action==='linkTest'&&c.args.uri===url));
 if(process.env.LOCKY_UI_EVIDENCE_DIR){fs.mkdirSync(process.env.LOCKY_UI_EVIDENCE_DIR,{recursive:true});await page.screenshot({path:path.join(process.env.LOCKY_UI_EVIDENCE_DIR,'citations.png'),fullPage:true});}
 await page.evaluate(()=>{document.querySelector('#app').innerHTML=chatBody('<img src=x onerror=alert(1)>',{},'unsafe');});assert.equal(await page.locator('#app img').count(),0);
 if(process.env.LOCKY_UI_EVIDENCE_DIR){
  fs.mkdirSync(process.env.LOCKY_UI_EVIDENCE_DIR,{recursive:true});
  for(const name of ['home','app','brief']){
   await page.evaluate(name=>{document.querySelector('#toast').style.display='none';screen=name;state=fixture;render();if(name==='brief')renderBrief(briefing);},name);
   await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
   await page.screenshot({path:path.join(process.env.LOCKY_UI_EVIDENCE_DIR,name+'.png'),fullPage:true});
  }
 }
 assert.deepEqual(errors,[]);console.log('UI revisions passed: 17 layouts, immediate/default/custom purpose grants, double-tap/error recovery, independent briefing sort, domain/marker deduplication and source disclosure links.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
