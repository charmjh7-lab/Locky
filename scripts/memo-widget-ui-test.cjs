const {chromium}=require('playwright');
const fs=require('node:fs'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,args:['--no-sandbox']});
 try{
  const page=await browser.newPage({viewport:{width:390,height:780}});
  await page.setContent('<style>'+fs.readFileSync('app/src/main/assets/style.css','utf8')+'</style><div id="app"></div><div id="sheet"></div><div id="toast"></div>');
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/app.js','utf8')});
  await page.evaluate(()=>{
   const notes=[{id:'a',title:'할 일',mode:'check',body:'',checks:JSON.stringify(Array.from({length:7},(_,i)=>({text:'할 일 '+i,done:false})))},{id:'b',title:'다른 메모',mode:'text',body:'다른 내용',checks:'[]'}];
   window.fixture={role:'PHONE_START',overlay:false,prefs:{enabled:true,start:540,end:1260},purposeDate:'2026-10-17',status:{accessibility:true},notes,chats:[],accounts:[],models:[],connection:{},rules:[],allPurposes:[],purposes:[],purposeOrders:{},phoneSession:{},savedNote:'a'};window.saved=[];
   window.Native={request:(id,action,raw)=>{const a=JSON.parse(raw);if(action==='saveNote'){saved.push(a);Object.assign(fixture.notes.find(n=>n.id===a.id),a,{checks:JSON.stringify(a.checks)});}const result=action==='state'?fixture:{};setTimeout(()=>receive(id,result,null),0);}};
   state=fixture;restored=true;screen='unlock';render();
  });
  const geometry=()=>page.locator('.quick-tools,.dictionary-bar,.start-purposes,.start-footer').evaluateAll(xs=>xs.map(x=>{const r=x.getBoundingClientRect();return [r.x,r.y,r.width,r.height];}));
  const revised=await geometry();
  await page.evaluate(()=>{window.style=document.querySelector('style');window.after=style.textContent;style.textContent=after.split('/* Compact memo density only;')[0];render();});
  assert.deepEqual(await geometry(),revised,'outer layout preserved');
  await page.evaluate(()=>{style.textContent=after;render();});
  assert(await page.locator('.checkrow').first().evaluate(e=>e.getBoundingClientRect().height<=44));
  assert(await page.locator('.note-content').evaluate(e=>e.clientHeight>=4*41));
  const editor=page.locator('[data-checktext]').nth(1);await editor.fill('길게 작성하는 할 일입니다. '.repeat(20));
  assert(await editor.evaluate(e=>e.clientHeight>=e.scrollHeight&&e.clientHeight>100));
  await page.setViewportSize({width:760,height:780});await page.waitForTimeout(30);
  assert(await editor.evaluate(e=>e.clientHeight>=e.scrollHeight));
  await page.setViewportSize({width:390,height:780});
  await editor.fill('');await editor.press('Backspace');assert.equal(await page.locator('.checkrow').count(),6);
  assert.equal(await page.evaluate(()=>document.activeElement===document.querySelector('[data-checktext]')),true);
  assert.equal(await page.locator('[data-checktext]').first().evaluate(e=>e.selectionStart),5);
  await page.evaluate(async()=>{await flushMemo();await openNoteById('b',20);});assert.equal(await page.locator('#noteTitle').inputValue(),'다른 메모');
  await page.evaluate(async()=>{await openNoteById('a',21);});
  await page.locator('[data-checktext]').first().fill('');
  await page.locator('[data-checktext]').first().evaluate(e=>{e.dispatchEvent(new CompositionEvent('compositionstart'));e.dispatchEvent(new InputEvent('beforeinput',{inputType:'deleteContentBackward',bubbles:true,cancelable:true,isComposing:true}));});assert.equal(await page.locator('.checkrow').count(),6);
  await page.locator('[data-checktext]').first().evaluate(e=>{e.dispatchEvent(new CompositionEvent('compositionend'));e.dispatchEvent(new InputEvent('beforeinput',{inputType:'deleteContentBackward',bubbles:true,cancelable:true}));});assert.equal(await page.locator('.checkrow').count(),5);
  await page.evaluate(()=>{for(const editor of [...document.querySelectorAll('[data-checktext]')]){editor.value='';editor.dispatchEvent(new InputEvent('beforeinput',{inputType:'deleteContentBackward',bubbles:true,cancelable:true}));}});assert.equal(await page.locator('.checkrow').count(),0);
  await page.evaluate(async()=>{await flushMemo();});assert.equal(await page.evaluate(()=>saved.at(-1).checks.length),0);
  await page.locator('[data-act="addCheck"]').click();assert.equal(await page.locator('.checkrow').count(),1);
  await page.locator('[data-checktext]').fill('첫 항목');await page.locator('[data-checktext]').press('Enter');assert.equal(await page.locator('.checkrow').count(),2);
  await page.evaluate(()=>{restored=false;fixture.savedNote='a';});await page.evaluate(()=>openNoteById('b',22));assert.equal(await page.locator('#noteTitle').inputValue(),'다른 메모');
  console.log('PASS compact geometry/density, wrapping+resize, keyboard/IME deletion+empty/add/Enter/save, exact widget note route.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
