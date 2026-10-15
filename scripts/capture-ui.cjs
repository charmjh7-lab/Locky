const {chromium}=require('playwright');
const {execFileSync}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const assets=path.join(__dirname,'../app/src/main/assets');
const output=path.join(__dirname,'../distribution');
fs.mkdirSync(output,{recursive:true});
const fixture={
 overlay:false,free:false,gate:{mode:'app',pkg:'video.app',label:'영상 앱',purpose:'영상 하나 보기',daily:2400000,session:300000},
 prefs:{enabled:true,start:540,end:1260,reminders:[30,10,5],weeklyDay:1,floatingTimer:true,model:'test'},
 status:{accessibility:true,usage:true,notifications:true,exact:true},
 allPurposes:[
 {id:'a',scope:'phone',label:'연락 확인'},{id:'b',scope:'phone',label:'책 읽기'},{id:'c',scope:'phone',label:'필요한 정보 검색'},
 {id:'d',scope:'phone',label:'영상 하나 보기'},{id:'e',scope:'phone',label:'일정 확인'},
 {id:'f',scope:'app',label:'영상 하나 보기'},{id:'g',scope:'app',label:'메시지 확인'}],
 purposes:[],
 notes:[{id:'memo',title:'오늘 할 일',body:'우유 사기\n책 읽기\n약속 확인하기',mode:'text',checks:'[]'}],
 chats:[{id:'chat1',title:'독서 내용 정리',updated:Date.now()}],
 accounts:[{active:true,connected:true,email:'연결된 계정',plan:true}],
 connection:{},models:[{slug:'test',name:'모델'}],rules:[{pkg:'video.app',label:'영상 앱',mode:'limited',minutes:60}],
 dictionary:'naver',busy:false,ruleSeen:[],activeChat:'chat1'
};
const briefing={
 days:1,date:'2026-10-14',start:Date.now()-86400000,total:5100000,unlocks:11,aimless:1,
 summary:'오늘은 85분 사용했어요.',insight:'영상 보기로 시작한 뒤 메시지 확인으로 이동한 기록이 있어요.',
 apps:[{label:'영상 앱',ms:2700000},{label:'메시지 앱',ms:1500000},{label:'읽기 앱',ms:900000}],
 events:[{kind:'unlock',purpose:'영상 하나 보기',time:Date.now()-4800000},{kind:'unlock',purpose:'연락 확인',time:Date.now()-2400000}],
 timeline:[],usageAccess:true,recovered:0
};
function getFile(version,name){
 if(version==='before')return execFileSync('git',['show','HEAD^:app/src/main/assets/'+name],{encoding:'utf8'});
 return fs.readFileSync(path.join(assets,name),'utf8');
}
async function snap(browser,version,screen,theme,width,height,name){
 const page=await browser.newPage({viewport:{width,height},colorScheme:theme,deviceScaleFactor:1});
 const errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 try{
  const css=getFile(version,'style.css'),js=getFile(version,'app.js');
  await page.setContent('<!doctype html><html lang="ko"><head><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><style>'+css+'</style></head><body><div id="app"></div><div id="sheet"></div><div id="toast"></div></body></html>');
  await page.addScriptTag({content:js});
  await page.evaluate(({data,target,theme,brief})=>{
   window.Native={request:()=>{}};state=JSON.parse(JSON.stringify(data));state.purposes=state.allPurposes.filter(p=>p.scope==='phone');
   noteId='memo';chatId='chat1';window.nativeKeyboard=false;
   document.documentElement.dataset.theme=theme;screen=target;
   render();
   if(screen==='brief')renderBrief(brief);
   if(screen==='chat')document.querySelector('#messages').innerHTML='<div class="message user">책 내용 요약해 줘</div><div class="message assistant chat-markdown">'+chatBody('**주요 내용**을 정리하면 다음과 같습니다.\n\n- 첫째, 핵심 질문\n- 둘째, 발견한 점',[],1)+'</div>';
  },{data:fixture,target:screen,theme,brief:briefing});
  await page.waitForTimeout(140);
  assert.deepEqual(errors,[],'JavaScript console failures '+name);
  if(screen==='home'||screen==='app'){
   const r=await page.evaluate(()=>{const rect=s=>document.querySelector(s)?.getBoundingClientRect();
    const footer=rect('.start-footer'),area=rect('.start-purposes'),dismiss=rect('.dismiss'),direct=rect('[data-act=direct]');
    return {footerTop:footer.top,footerBottom:footer.bottom,areaBottom:area.bottom,dismissBottom:dismiss.bottom,
    directBottom:direct.bottom,h:innerHeight,scroll:document.documentElement.scrollHeight};});
   assert(r.footerBottom<=r.h+1,'Footer offscreen '+name+' '+JSON.stringify(r));
   assert(r.areaBottom<=r.footerTop+2,'Purpose overlap '+name+' '+JSON.stringify(r));
   assert(r.directBottom<=r.footerTop+2,'Direct purpose inaccessible '+name+' '+JSON.stringify(r));
  }
  if(theme==='dark'&&version==='after'){
   const color=await page.evaluate(()=>getComputedStyle(document.documentElement).backgroundColor);
   assert.notEqual(color,'rgb(246, 247, 244)','Dark palette not applied');
  }
  await page.screenshot({path:path.join(output,name+'.png')});
  console.log(name+'.png');
 }finally{await page.close();}
}
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  for(const item of [
   ['before','home','light',390,780,'ui-before-home'],
   ['after','home','light',390,780,'ui-after-home-light'],
   ['after','home','dark',390,780,'ui-after-home-dark'],
   ['after','home','light',320,560,'ui-after-home-small'],
   ['before','app','light',390,780,'ui-before-app'],
   ['after','app','light',390,780,'ui-after-app'],
   ['before','settings','light',390,780,'ui-before-settings'],
   ['after','settings','light',390,780,'ui-after-settings'],
   ['before','brief','light',390,780,'ui-before-brief'],
   ['after','brief','light',390,780,'ui-after-brief'],
   ['after','notes','dark',390,780,'ui-after-notes-dark'],
   ['after','chat','dark',390,780,'ui-after-chat-dark']
  ])await snap(browser,...item);
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
