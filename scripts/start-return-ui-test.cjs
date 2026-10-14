const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,args:['--no-sandbox','--no-zygote','--single-process']}:{})});
 try{
  const page=await browser.newPage({viewport:{width:390,height:780}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<html><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8')});
  await page.evaluate(()=>{
   window.fixture={overlay:true,prefs:{enabled:true,start:540,end:1260},status:{accessibility:true},gate:{mode:'unlock'},allPurposes:[],notes:[],chats:[],accounts:[],models:[],connection:{},dictionary:'process:dictionary/Popup'};window.calls=[];
   window.Native={request:(id,action,raw)=>{calls.push({action,args:JSON.parse(raw)});if(action==='rendered')return;setTimeout(()=>receive(id,action==='state'?fixture:{},null),0);}};
   state=fixture;screen='unlock';render();
  });
  const word='첫 단어 "<뜻>"',question='입력 중인 질문';await page.locator('#word').fill(word);await page.locator('#quickQuestion').fill(question);
  await page.locator('#dictionaryForm').evaluate(form=>form.requestSubmit());await page.waitForFunction(()=>calls.some(x=>x.action==='dictionary'));
  // Dictionary selection and service callbacks refresh the actual start renderer.
  await page.locator('#word').blur();await page.evaluate(()=>refresh(true));
  assert.equal(await page.locator('#word').inputValue(),word);assert.equal(await page.locator('#quickQuestion').inputValue(),question);
  await page.locator('#word').fill('두 번째 단어');await page.locator('#dictionaryForm').evaluate(form=>form.requestSubmit());await page.waitForFunction(()=>calls.filter(x=>x.action==='dictionary').length===2);
  const lookups=await page.evaluate(()=>calls.filter(x=>x.action==='dictionary').map(x=>x.args.word));assert.deepEqual(lookups,[word,'두 번째 단어']);
  await page.evaluate(()=>{state.dictionary='';});await page.locator('#dictionaryForm').evaluate(form=>form.requestSubmit());await page.waitForFunction(()=>calls.some(x=>x.action==='dictionaryPicker'));
  await page.evaluate(()=>refresh(true));assert.equal(await page.locator('#word').inputValue(),'두 번째 단어');assert.equal(await page.locator('#quickQuestion').inputValue(),question);
  assert.equal(await page.evaluate(()=>calls.filter(x=>['dismiss','stop','choose'].includes(x.action)).length),0);assert.deepEqual(errors,[]);
  console.log('Dictionary start return: repeated searches, chooser refresh, escaped input and question drafts retained.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
