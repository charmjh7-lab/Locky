'use strict';
// Apply the host theme before rendering; configuration changes keep this document and its drafts.
function themeChanged(dark){document.documentElement.dataset.theme=dark?'dark':'light';}
if(window.Native&&typeof Native.darkTheme==='function')themeChanged(Native.darkTheme());
document.addEventListener('focusin',event=>{if(state?.overlay&&event.target.matches('textarea,input:not([type=button]):not([type=checkbox]):not([type=radio]),[contenteditable=true]')&&window.Native)Native.request('0','editorFocus',JSON.stringify({gateNavigation:nativeNavigation}));});
// Only explicit tool interaction releases the phone-cycle home guard. Native owns state.
let navigationReleasedCycle=-1;
function releasePhoneNavigation(reason){
 if(state?.role!=='PHONE_START'||!window.Native)return;
 const cycle=typeof Native.phoneCycle==='function'?Number(Native.phoneCycle()):Number(state.phoneCycle||0);
 if(navigationReleasedCycle===cycle)return;navigationReleasedCycle=cycle;
 Native.request('0','releaseNavigation',JSON.stringify({cycle,reason}));
}
const phoneToolEditor='#noteBody,#noteTitle,[data-checktext],#quickQuestion,#chatInput,#word';
document.addEventListener('pointerdown',event=>{if(event.isTrusted&&event.target.matches(phoneToolEditor))releasePhoneNavigation('tool-editor');});
document.addEventListener('input',event=>{if(event.isTrusted&&event.target.matches(phoneToolEditor))releasePhoneNavigation('tool-editor');});
document.addEventListener('click',event=>{if(!event.isTrusted)return;if(event.target.matches(phoneToolEditor)){releasePhoneNavigation('tool-editor');return;}const el=event.target.closest('[data-nav="notes"],[data-act="openChat"],[data-chat],[data-act="newChat"],[data-act="newNote"],[data-newnote],[data-note],[data-note-step],[data-act="deleteNote"],[data-check],[data-delcheck],[data-act="addCheck"],#noteMode');if(el)releasePhoneNavigation('tool-action');});
const $=s=>document.querySelector(s),esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const shapes={lock:'M7 10V7a5 5 0 0 1 10 0v1 M5 10h14v11H5z M12 14v3',leaf:'M5 19C3 8 11 3 21 3c0 10-5 16-16 16z M5 19l10-10',home:'M3 11l9-8 9 8 M6 10v11h12V10 M10 21v-7h4v7',chart:'M4 20V10 M10 20V4 M16 20v-8 M22 20H2',settings:'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z',plus:'M12 5v14 M5 12h14',arrow:'M5 12h14 M14 7l5 5-5 5',back:'M19 12H5 M10 7l-5 5 5 5',chat:'M3 4h18v13H9l-6 4z M7 8h10 M7 12h6',book:'M3 4h7l2 2 2-2h7v16h-7l-2 1-2-1H3z M12 6v15',note:'M5 3h14v18H5z M8 8h8 M8 12h8 M8 16h4',close:'M6 6l12 12 M18 6L6 18',check:'M4 12l5 5L20 6',trash:'M3 6h18 M9 6V3h6v3 M6 6v15h12V6 M10 10v7 M14 10v7',clock:'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 7v5l4 2',send:'M3 3l18 9-18 9 4-9z M7 12h14',moon:'M20 15A9 9 0 0 1 9 3a9 9 0 1 0 11 12',newnote:'M5 3h14v18H5z M12 8v8 M8 12h8',expand:'M8 3H3v5 M16 3h5v5 M3 16v5h5 M21 16v5h-5',more:'M12 5h.01 M12 12h.01 M12 19h.01',search:'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14 M15 15l6 6',power:'M12 2v10 M6 5a9 9 0 1 0 12 0',link:'M9 15l6-6 M8 16l-2 2a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0 M14 8l2-2a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0'};
const icon=n=>`<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${shapes[n]||shapes.leaf}"/></svg>`;
let seq=0,requests=new Map(),state=null,screen='home',selected='',minutes=5,chatId='',appsCache=null,appsPromise=null,noteId='',briefDays=1,briefOffset=0,refreshing=false,refreshAgain=false,poll=null,memoTimer=null,memoPending=null,memoSaving=null,chatScrolled=false;
let purposeEntry=null,purposePositions=new Map();
let chatReturn='chats',chatsReturn='home',briefTarget=null,briefAppFilter='all';
let normalHeight=0,normalWidth=0;
let quickQuestionDraft='',dictionaryWordDraft='';
let nativeNavigation=0,domRevision=0,renderedRoute='',startNoteSnapshot=null;
const quickTurns=new Map();
let nativeKeyboard=false,purposeScope='phone',restored=false,briefSeq=0,settingsTimer=null;
let purposeStarting=false,briefData=null;const briefSort={apps:'desc',events:'asc',timeline:'asc'};
Object.assign(shapes,{sortDown:'M4 5h12 M4 10h8 M4 15h4 M19 4v16 M15 16l4 4 4-4',sortUp:'M4 5h4 M4 10h8 M4 15h12 M19 20V4 M15 8l4-4 4 4'});
const gated=['app','renew','blocked'],starts=['home','unlock',...gated];
function api(action,args={}){if(state?.role==='APP_GATE'&&['state','tick','grant','extend','stop','dismiss','choose','purpose','removePurpose','linkTest','open'].includes(action))args={gateNavigation:nativeNavigation,gateTransition:state.gateTransition,...args};return new Promise((resolve,reject)=>{const id=String(++seq),timeout=setTimeout(()=>{requests.delete(id);reject(Error('응답을 기다리는 중이에요. 다시 시도해 주세요.'));},25000);requests.set(id,{resolve,reject,timeout});Native.request(id,action,JSON.stringify(args));});}
function receive(id,value,error){const r=requests.get(id);if(!r)return;clearTimeout(r.timeout);requests.delete(id);error?r.reject(Error(error)):r.resolve(value);}
function toast(text){$('#toast').textContent=text;$('#toast').style.display='block';clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('#toast').style.display='none',3500);}
async function safe(fn){const entry=gateSnapshot();try{return await fn();}catch(e){if(!entry||currentGate(entry))toast(e.message);}}
function fmt(ms){let s=Math.ceil(Math.max(0,ms)/1000);return `${String(Math.floor(s/60)).padStart(2,'0')}:${String(s%60).padStart(2,'0')}`;}
function mins(ms){return Math.round(ms/60000);}
function time(m){return `${String(Math.floor(m/60)).padStart(2,'0')}:${String(m%60).padStart(2,'0')}`;}
function timeVal(t){const [h,m]=t.split(':').map(Number);return h*60+m;}
function dateTime(t,timeZone=undefined){return new Date(t).toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit',hour12:false,timeZone});}
function header(back=false,title='Locky',showStatus=true){return `<div class="top">${back?`<button class="iconbtn" data-act="back" aria-label="뒤로">${icon('back')}</button>`:''}<strong>${esc(title)}</strong>${showStatus?`<span class="pill">${state?.free?'자유 시간':'집중 시간'}</span>`:''}</div>`;}
function compactHost(){return state?.role==='PHONE_START'||state?.overlay;}
function nav(){if(compactHost()||['chat',...gated,'unlock'].includes(screen))return '';return `<div class="bottom"><nav class="nav" aria-label="주 메뉴">${[['home','home','시작'],['chats','chat','대화'],['brief','chart','브리핑'],['settings','settings','설정']].map(([s,i,l])=>`<button data-nav="${s}" class="${screen===s?'active':''}">${icon(i)}<span>${l}</span></button>`).join('')}</nav></div>`;}
function startPolling(){clearInterval(poll);poll=null;if(state?.role==='APP_GATE'&&(gateExposure!=='INTERACTIVE'||!gateAlive))return;if(['chat','chats','brief','timer',...gated].includes(screen)&&document.visibilityState!=='hidden')poll=setInterval(()=>refresh(),screen==='brief'?30000:1000);}
function remember(){if(!state)return;if(state.role==='PHONE_START'){safe(()=>api('remember',{screen,chat:chatId,note:noteId,chatReturn,chatsReturn,quickQuestion:$('#quickQuestion')?.value??quickQuestionDraft,word:$('#word')?.value??dictionaryWordDraft,chatDraft:$('#chatInput')?.value??state.phoneSession?.chatDraft??''}));return;}if(!state.overlay&&!gated.includes(screen)&&screen!=='unlock')safe(()=>api('remember',{screen,chat:chatId,note:noteId,chatReturn,chatsReturn,briefTarget:screen==='brief'?{...(briefTarget||{}),date:briefData?.kind!=='focus'?briefData?.date:undefined,days:briefDays}:undefined}));}
let phoneDraftTimer=null;
document.addEventListener('input',()=>{if(state?.role==='PHONE_START'){clearTimeout(phoneDraftTimer);phoneDraftTimer=setTimeout(remember,200);}});
async function navigate(s,navigation=null,keepBrief=false){if(state?.role==='APP_GATE'&&!gated.includes(s)&&s!=='gateIdle'){const entry=gateSnapshot();await flushMemo();if(currentGate(entry))await api('open',{screen:s,...(s==='chat'?{chat:chatId}:{})});return;}if(state?.role==='PHONE_START'&&!['unlock','notes','chats','chat'].includes(s)){await api('open',{screen:s});return;}if(s==='brief'&&navigation===null&&!keepBrief&&screen!=='brief'){briefTarget=null;briefDays=1;briefOffset=0;}const local=navigation===null;if(local)navigation=nativeNavigation;if(['home','unlock','app'].includes(s))purposeEntry=null;if(s==='chats'&&starts.includes(screen))chatsReturn=screen;if(s==='chat'&&screen==='chats')chatReturn='chats';if(settingsTimer&&screen==='settings'){clearTimeout(settingsTimer);settingsTimer=null;await safe(saveReminders);}await flushMemo();if(navigation<nativeNavigation)return;const retainedNavigation=renderedRoute===s&&navigation!==nativeNavigation;nativeNavigation=navigation;if(local&&state?.overlay&&['chat','chats','unlock'].includes(s)&&s!==screen)presentScreen(s);closeSheet();const leavingBrief=screen==='brief'&&s!=='brief';if(leavingBrief)++briefSeq;screen=s;selected='';minutes=5;document.body.classList.toggle('start-screen',starts.includes(screen));window.scrollTo(0,0);if(leavingBrief&&state)render();const revision=domRevision;await refresh(true);if(retainedNavigation&&navigation===nativeNavigation&&renderedRoute===s&&revision===domRevision)signalRendered();startPolling();remember();if(state&&['notes','chat','chats'].includes(s))safe(()=>api('utility',{name:s==='notes'?'메모':'ChatGPT'}));}
async function refresh(force=false){if(screen==='gateIdle')return;if(state?.role==='APP_GATE')return refreshGate(force);if(refreshing){if(force)refreshAgain=true;return;}refreshing=true;try{
 const entry=nativeNavigation,appEntry=state?.role==='APP_GATE',tick=!force&&state&&['chat','timer',...gated].includes(screen);
 const next=await api(tick?'tick':'state');if(appEntry&&entry!==nativeNavigation)return;
 if(tick)Object.assign(state,next);else state=next;
 for(const turn of quickTurns.values())if(!state.chats.some(c=>c.id===turn.id))state.chats.unshift({id:turn.id,title:turn.text.slice(0,30)});
 syncMemoChecks();
 if(!restored){restored=true;if(state.role==='PHONE_START'){const saved=state.phoneSession||{};quickQuestionDraft=saved.quickQuestion||'';dictionaryWordDraft=saved.word||'';noteId=saved.note||noteId;chatReturn=saved.chatReturn||'unlock';chatsReturn=saved.chatsReturn||'unlock';}if(screen==='chat')chatReturn=state.chatReturn||'chats';if(screen==='chats')chatsReturn=state.chatsReturn||'home';if(screen==='brief'&&!briefTarget&&state.savedBrief){briefTarget=state.savedBrief;briefDays=state.savedBrief.days||1;}chatId=chatId||state.savedChat||'';noteId=noteId||state.savedNote||'';}
 if(['home','unlock'].includes(screen)&&renderedRoute===screen&&$('.start-page')){updateStart();return;}
 if(force&&state?.role==='APP_GATE'&&gated.includes(screen)&&renderedRoute===screen){presentGate(screen,state,nativeNavigation);return;}
 if(!force){if(screen==='chat'){await renderMessages(false);return;}if(screen==='timer'||gated.includes(screen)){updateTimers();return;}if(document.activeElement?.matches('input,textarea,select')||memoPending||memoSaving)return;if(screen==='brief'){loadBrief();return;}if(screen==='chats'){const c=$('#connection');if(c){c.innerHTML=connectionHTML();bind(c);}return;}}
 const oldScroll=window.scrollY;render();if(!force)window.scrollTo(0,oldScroll);if(screen==='brief')loadBrief();if(screen==='apps')loadApps();if(screen==='usageApps')loadUsageApps();if(screen==='chat')renderMessages(force);
 }catch(e){toast(e.message);}finally{refreshing=false;if(refreshAgain){refreshAgain=false;refresh(true);}}}
async function openNoteById(id,navigation=null){await flushMemo();noteId=id;await navigate('notes',navigation);remember();}
function openChatById(id,navigation=null){chatId=id;return navigate('chat',navigation);}
// APP_GATE owns a single prepared document. Gate data comes with the native entry,
// so navigation never waits behind memo/chat bridge work or displays the old app.
let gatePurposeHTML=null,gateExposure='HIDDEN',gateAlive=false,gateWorkEpoch=0,gateRefresh=null,lastGateRendered='',gateDOMObserver=null;
// Reuse pixels only while *all* visual DOM state is unchanged, including editor,
// disabled controls, theme and viewport styles. Entry tokens remain independent.
function observeGateDOM(){
 if(gateDOMObserver)return;
 gateDOMObserver=new MutationObserver(records=>{if(records.length&&state?.role==='APP_GATE'){++domRevision;signalRendered();}});
 gateDOMObserver.observe(document.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});
}
let gateCatalog=null,gatePurposeTemplates=new Map(),gateFallbackTemplates=new Map();
function preparedGatePurposeTemplate(){
 const scope=state?.gate?.pkg||'app';
 if(gateCatalog&&state?.catalogVersion===gateCatalog.catalogVersion&&gatePurposeTemplates.has(scope))return gatePurposeTemplates.get(scope);
 const ps=visiblePurposes(true),key=JSON.stringify([state.purposeDate,ps]);let cached=gateFallbackTemplates.get(scope);
 if(cached?.key===key)return cached;
 const html=purposeGridMarkup(ps,true,false,'',false),template=document.createElement('template');template.innerHTML=html;
 cached={key,html,node:template.content.firstElementChild};gateFallbackTemplates.set(scope,cached);return cached;
}
function patchPreparedGatePurposes(main){
 const prepared=preparedGatePurposeTemplate();if(prepared.html===gatePurposeHTML)return false;
 const changed=patchGateNode(main.querySelector('.app-decision .reason-grid'),prepared.node);gatePurposeHTML=prepared.html;return changed;
}
function installGateCatalog(data,update=true){
 if(gateCatalog&&data.catalogVersion<gateCatalog.catalogVersion)return;
 if(gateCatalog?.catalogVersion===data.catalogVersion&&gateCatalog?.purposeDate===data.purposeDate)return;
 gateCatalog={catalogVersion:data.catalogVersion,purposeDate:data.purposeDate,prefs:data.prefs,allPurposes:data.allPurposes,purposeOrders:data.purposeOrders,rules:data.rules};
 gatePurposeTemplates=new Map();gateFallbackTemplates.clear();
 for(const [scope,purposes] of Object.entries(data.purposeOrders||{})){
  const html=purposeGridMarkup(purposes,true,false,'',false),template=document.createElement('template');template.innerHTML=html;
  gatePurposeTemplates.set(scope,{html,node:template.content.firstElementChild});
 }
 if(state?.role!=='APP_GATE')return;
 state={...state,...gateCatalog,purposes:gateCatalog.purposeOrders[state.gate?.pkg||'app']||[]};purposeEntry=null;
 if(!update||!document.querySelector('.gate-page'))return;
 if(gateAlive&&gated.includes(screen))presentGate(screen,state,nativeNavigation);
 else if(patchPreparedGatePurposes(document.querySelector('.gate-page'))){++domRevision;viewportChanged();signalRendered();}
}
let purposeStartOperation=null;
function gateSnapshot(){return state?.role==='APP_GATE'?{navigation:nativeNavigation,transition:state.gateTransition,pkg:state.gate?.pkg,screen,epoch:gateWorkEpoch}:null;}
function currentGate(entry,response=null){
 if(!entry||!gateAlive||state?.role!=='APP_GATE'||entry.navigation!==nativeNavigation||entry.transition!==state.gateTransition||entry.pkg!==state.gate?.pkg||entry.screen!==screen||entry.epoch!==gateWorkEpoch)return false;
 return !response||(response.gateActive===true&&response.gateNavigation===entry.navigation&&response.gateTransition===entry.transition&&response.gate?.pkg===entry.pkg);
}
function gateExposureChanged(exposure,navigation,transition,pkg){
 if(state?.role!=='APP_GATE'||navigation!==nativeNavigation||transition!==state.gateTransition||pkg!==state.gate?.pkg||exposure===gateExposure)return;
 gateExposure=exposure;++gateWorkEpoch;gateRefresh=null;
 if(exposure==='HIDDEN'){gateAlive=false;closeSheet();}
 startPolling();
 if(exposure==='INTERACTIVE'&&gateAlive)safe(()=>refreshGate());
}
function refreshGate(force=false){
 const entry=gateSnapshot();
 if(!currentGate(entry)||gateExposure!=='INTERACTIVE'||document.visibilityState==='hidden')return Promise.resolve(false);
 if(gateRefresh){if(force)gateRefresh.force=true;return gateRefresh.promise;}
 const request={entry,force,promise:null};gateRefresh=request;
 request.promise=(async()=>{
  do {
   const full=request.force;request.force=false;
   const next=await api(full?'state':'tick');
   if(!currentGate(entry,next)||gateExposure!=='INTERACTIVE')return false;
   if(full&&gateCatalog&&next.catalogVersion!==undefined&&next.catalogVersion<gateCatalog.catalogVersion){if(request.force)continue;return false;}
   if(full)presentGate(screen,next,entry.navigation);
   else {state.gate=next.gate;state.free=next.free;updateTimers();}
  }while(request.force&&currentGate(entry));
  return true;
 })().catch(error=>{if(currentGate(entry))toast(error.message);return false;}).finally(()=>{if(gateRefresh===request)gateRefresh=null;});
 return request.promise;
}
async function saveGatePurpose(el){
 const entry=gateSnapshot(),editor=$('#sheet .sheet'),direct=el.dataset.direct==='true';el.disabled=true;
 try {
  if($('#purposeLink').checked&&$('#purposeApp').disabled)throw Error('앱 목록을 불러오는 중입니다.');
  if($('#purposeLink').checked&&!$('#purposeApp').value&&!$('#purposeUri').value.trim())throw Error('연결할 앱이나 동작 주소를 선택해 주세요.');
  const p={label:$('#purposeLabel').value,pkg:$('#purposeLink').checked?$('#purposeApp').value:'',uri:$('#purposeLink').checked?$('#purposeUri').value:'',scope:$('#purposeScope')?.value||el.dataset.scope,aimless:el.dataset.aimless==='1'};
  if(el.dataset.id)p.id=el.dataset.id;
  if(direct&&!$('#purposeSave').checked){await choosePurpose({...p,transient:true});return;}
  const saved=await api('purpose',p);if(!currentGate(entry))return;
  if(!await refreshGate(true)||!currentGate(entry))return;
  if($('#sheet .sheet')!==editor)return;
  if(direct)await choosePurpose(saved.id);else closeSheet();
 }finally{if(el.isConnected)el.disabled=false;}
}
async function removeGatePurpose(id,manager=false){
 const entry=gateSnapshot(),editor=$('#sheet .sheet');
 await api('removePurpose',{id});if(!currentGate(entry))return;
 if(!await refreshGate(true)||!currentGate(entry)||$('#sheet .sheet')!==editor)return;
 if(manager)managePurposes();else closeSheet();
}
// A pointer/key begun on a previous gate cannot become a click on the new gate.
let gateGestureNavigation=null;
document.addEventListener('pointerdown',()=>{gateGestureNavigation=nativeNavigation;},true);
document.addEventListener('keydown',()=>{gateGestureNavigation=nativeNavigation;},true);
document.addEventListener('click',event=>{
 if(state?.role==='APP_GATE'&&gateGestureNavigation!==null&&gateGestureNavigation!==nativeNavigation){event.preventDefault();event.stopImmediatePropagation();}
 gateGestureNavigation=null;
},true);
function prepareGate(navigation=0){
 observeGateDOM();
 clearInterval(poll);poll=null;closeSheet();
 nativeNavigation=navigation;screen='gateIdle';state={role:'APP_GATE',overlay:true,gateTransition:-1,gate:{},prefs:{start:0,end:0},notes:[],chats:[],accounts:[],models:[],connection:{},rules:[],allPurposes:[],purposeOrders:{},...(gateCatalog||{})};
 gateAlive=false;gateExposure='HIDDEN';++gateWorkEpoch;gateRefresh=null;purposeEntry=null;
 let main=$('.gate-page');
 if(!main){$('#app').innerHTML=gate();main=$('.gate-page');bind(main);gatePurposeHTML=preparedGatePurposeTemplate().html;++domRevision;}
 main.inert=true;document.body.classList.add('start-screen');renderedRoute=screen;signalRendered(true);
}
// Patch attributes/text in place. Only changed list membership creates/removes nodes.
function patchGateNode(old,next){
 if(old.nodeType!==next.nodeType||old.nodeName!==next.nodeName){const replacement=next.cloneNode(true);old.replaceWith(replacement);bindGateNode(replacement);return true;}
 if(old.nodeType===Node.TEXT_NODE){if(old.data===next.data)return false;old.data=next.data;return true;}
 let changed=false;
 for(const attr of [...old.attributes])if(!next.hasAttribute(attr.name)){old.removeAttribute(attr.name);changed=true;}
 for(const attr of [...next.attributes])if(old.getAttribute(attr.name)!==attr.value){old.setAttribute(attr.name,attr.value);changed=true;}
 const key=node=>node.nodeType===Node.ELEMENT_NODE?(node.dataset.purpose||node.querySelector(':scope > [data-purpose]')?.dataset.purpose||node.dataset.act||node.dataset.min||node.id||''):'';
 const existing=new Map([...old.childNodes].map(node=>[key(node),node]).filter(([key])=>key));
 for(let i=0;i<next.childNodes.length;i++){
  const wanted=next.childNodes[i],id=key(wanted);let node=id?existing.get(id):old.childNodes[i];
  if(node&&id!==key(node))node=null;
  if(!node){node=wanted.cloneNode(true);old.insertBefore(node,old.childNodes[i]||null);bindGateNode(node);changed=true;}
  else {if(old.childNodes[i]!==node){old.insertBefore(node,old.childNodes[i]||null);changed=true;}changed=patchGateNode(node,wanted)||changed;}
 }
 while(old.childNodes.length>next.childNodes.length){old.lastChild.remove();changed=true;}
 return changed;
}
function bindGateNode(node){
 if(node.nodeType!==Node.ELEMENT_NODE)return;
 bind(node);
 if(node.matches('[data-purpose]'))node.onclick=()=>safe(()=>choosePurpose(node.dataset.purpose));
 else if(node.matches('[data-act]'))node.onclick=()=>safe(()=>action(node.dataset.act,node));
}
function presentGate(name,payload,navigation){
 if(navigation<nativeNavigation)return;
 if(gateCatalog&&payload.catalogVersion!==undefined&&payload.catalogVersion<gateCatalog.catalogVersion)return;
 const same=screen===name&&state?.gate?.pkg===payload.gate.pkg;
 const transition=!same||navigation!==nativeNavigation||state?.gateTransition!==payload.gateTransition;
 if(!transition&&!gateAlive)return;nativeNavigation=navigation;
 if(transition){closeSheet();selected='';minutes=5;purposeEntry=null;purposeStarting=false;purposeStartOperation=null;gateAlive=true;gateExposure=payload.gateExposure||'WAITING';++gateWorkEpoch;gateRefresh=null;}
 if(payload.catalogVersion!==undefined&&payload.allPurposes)installGateCatalog(payload,false);
 const shared=gateCatalog&&gateCatalog.catalogVersion===payload.catalogVersion?gateCatalog:{};
 const orders={...(state?.purposeOrders||{}),...(shared.purposeOrders||{}),...(payload.purposeOrders||{})};
 if(payload.purposes)orders[payload.scope]=payload.purposes;
 state={...state,...shared,...payload,role:'APP_GATE',overlay:true,purposes:payload.purposes||orders[payload.scope]||[],purposeOrders:orders};
 screen=name;let main=$('.gate-page'),changed=false,layout=false;
 if(!main){$('#app').innerHTML=gate();main=$('.gate-page');bind(main);gatePurposeHTML=preparedGatePurposeTemplate().html;changed=true;layout=true;}
 const g=state.gate,locked=name==='blocked',renew=name==='renew',policyBlock=state.rules.find(r=>r.pkg===g.pkg)?.mode==='locked';
 const text=(selector,value,first=false)=>{const el=main.querySelector(selector),node=first?el.firstChild:el;if((first?node.data:node.textContent)!==value){if(first)node.data=value;else node.textContent=value;changed=true;}};
 const hidden=(selector,value)=>{const el=main.querySelector(selector);if(el.hidden!==value){el.hidden=value;changed=true;}};
 const classes=`start-page gate-page overlay-start ${locked?'blocked-page':renew?'renew-page':''}`;
 if(main.className!==classes){main.className=classes;changed=true;}
 text('.top>strong',locked?'사용 제한':renew?'사용 연장':'앱 시작');text('.top .pill',state.free?'자유 시간':'집중 시간');
 text('.gate-app',g.label||'앱 사용');text('.gate-info h2',locked?(policyBlock?'집중 시간에는 차단된 앱이에요':'오늘의 사용 시간을 모두 썼어요'):renew?'같은 목적으로 더 사용하나요?':'무엇을 하러 들어가나요?');
 hidden('.daily-budget',locked);if(!locked)text('#dailyLeft',fmt(g.daily));
 hidden('.block-detail',!locked);if(locked){text('.block-detail>strong',time(state.prefs.end),true);text('.block-detail p',`${time(state.prefs.start)}–${time(state.prefs.end)} 집중 시간`);}
 hidden('.gate-note',!renew);if(renew)text('.gate-note strong',g.purpose||'선택한 목적 없음');
 hidden('.app-decision',locked);hidden('.app-decision .start-purposes',locked||renew);
 if(!locked){text('.duration h3',renew?'추가할 시간':'사용할 시간');for(const el of main.querySelectorAll('[data-min]')){const chosen=Number(el.dataset.min)===minutes;if(el.classList.contains('selected')!==chosen){el.classList.toggle('selected',chosen);el.setAttribute('aria-pressed',chosen);changed=true;}}}
 hidden('#startApp',!renew);if(renew)text('#startApp',`${minutes}분 연장 `,true);
 if(!locked&&!renew){
  if(patchPreparedGatePurposes(main)){changed=true;layout=true;}
 }
 if(main.inert){main.inert=false;changed=true;}if(!document.body.classList.contains('start-screen'))document.body.classList.add('start-screen');
 if(transition)main.querySelectorAll('[data-purpose],[data-min],[data-act=direct]').forEach(el=>{if(el.disabled){el.disabled=false;changed=true;}});
 if(changed)++domRevision;if(layout||(transition&&changed))viewportChanged();
 const needsAck=transition||changed||renderedRoute!==screen;renderedRoute=screen;if(needsAck)signalRendered();
 if(transition)startPolling();
}
function presentScreen(name){const previous=nativeNavigation++;Native.request('0','present',JSON.stringify({screen:name,previous,navigation:nativeNavigation,chat:chatId}));}
function signalRendered(force=false){if(state?.role==='APP_GATE'){if(gateDOMObserver?.takeRecords().length)++domRevision;const stamp=JSON.stringify([screen,nativeNavigation,state.gateTransition,domRevision]);if(!force&&lastGateRendered===stamp)return;lastGateRendered=stamp;}Native.request('0','rendered',JSON.stringify({screen,navigation:nativeNavigation,revision:domRevision}));}
function nativeRestore(){if($('#app')?.childElementCount)signalRendered(true);else return refresh(true);}
function render(){if(state?.role==='APP_GATE'&&gated.includes(screen)){presentGate(screen,state,nativeNavigation);return;}const previousPresetScroll=$('.preset-scroll')?.scrollTop||0;if($('#quickQuestion'))quickQuestionDraft=$('#quickQuestion').value;if($('#word'))dictionaryWordDraft=$('#word').value;let html='';switch(screen){case 'gateIdle':break;case 'unlock':case 'home':html=home();break;case 'setup':html=setup();break;case 'settings':html=settings();break;case 'apps':html=appsPage();break;case 'usageApps':html=usageAppsPage();break;case 'app':case 'renew':case 'blocked':html=gate();break;case 'timer':html=timer();break;case 'notes':html=notesPage();break;case 'chats':html=chats();break;case 'chat':html=chat();break;case 'brief':html=brief();break;case 'week':briefDays=7;screen='brief';html=brief();break;default:screen='home';html=home();}
 document.body.classList.toggle('start-screen',starts.includes(screen));$('#app').innerHTML=html+nav();if(gated.includes(screen))gatePurposeHTML=null;bind();if(state?.role==='PHONE_START'&&screen==='chat'&&$('#chatInput')&&state.phoneSession?.chat===chatId)$('#chatInput').value=state.phoneSession.chatDraft||'';if($('.preset-scroll'))$('.preset-scroll').scrollTop=previousPresetScroll;viewportChanged();if(screen==='chat'&&quickTurns.has(chatId))applyMessages(quickMessages(quickTurns.get(chatId)),true);renderedRoute=screen;if(['home','unlock'].includes(screen))startNoteSnapshot=noteSnapshot();++domRevision;signalRendered();}
// Refresh data without discarding the prepared start document or its editors.
function noteSnapshot(){const n=currentNote();return {note:{id:n.id,title:n.title,body:n.body,checks:n.checks||'[]',mode:n.mode},count:state.notes.length};}
function updateStart(){
 let changed=false;
 const pill=$('.start-page .top .pill'),label=state.free?'자유 시간':'집중 시간';
 if(pill&&pill.textContent!==label){pill.textContent=label;changed=true;}
 const grid=$('.start-purposes .reason-grid');
 if(grid){
  const template=document.createElement('template');template.innerHTML=purposeGrid(false);
  const wanted=[...template.content.querySelectorAll('.purpose-item')];
  const existing=new Map([...grid.children].map(el=>[el.querySelector('[data-purpose]')?.dataset.purpose,el]));
  for(let i=0;i<wanted.length;i++){
   const candidate=wanted[i],id=candidate.querySelector('[data-purpose]').dataset.purpose;
   let node=existing.get(id);existing.delete(id);
   if(!node){node=candidate;bind(node);}
   else if(node.innerHTML!==candidate.innerHTML){node.innerHTML=candidate.innerHTML;bind(node);changed=true;}
   if(grid.children[i]!==node){grid.insertBefore(node,grid.children[i]||null);changed=true;}
  }
  for(const node of existing.values()){node.remove();changed=true;}
 }
 // An unchanged note is not a reason to replace an input node. Never overwrite
 // a local pending edit, including a check-list save captured during screen-off.
 const note=$('.mini-note'),editing=note?.contains(document.activeElement);
 if(note&&!editing&&!memoPending&&!memoSaving){
  const snapshot=noteSnapshot(),old=startNoteSnapshot?.note,current=snapshot.note;
  const content=n=>JSON.stringify(n&&[n.id,n.title,n.body,n.checks||'[]',n.mode]);
  if(content(old)!==content(current)||startNoteSnapshot?.count!==snapshot.count){
   if(old?.id===current.id&&old?.mode===current.mode&&current.mode!=='check'&&startNoteSnapshot?.count===snapshot.count){
    const body=$('#noteBody'),heading=note.querySelector('.note-heading');
    if(body&&body.value!==current.body){body.value=current.body||'';changed=true;}
    if(heading&&old.title!==current.title){heading.innerHTML=icon('note')+' '+esc(current.title||'메모');changed=true;}
   }else{note.innerHTML=memoHTML(false);bind(note);autoNoteHeight();changed=true;}
  }
  startNoteSnapshot=snapshot;
 }
 const footer=$('.start-footer'),permission=footer?.querySelector('[data-nav="setup"]');
 if(state.status.accessibility&&permission){permission.remove();changed=true;}
 else if(!state.status.accessibility&&footer&&!permission){
  const button=document.createElement('button');button.className='textbtn';button.dataset.nav='setup';button.textContent='자동 관리를 위한 권한 설정';footer.prepend(button);bind(footer);changed=true;
 }
 if(changed){viewportChanged();++domRevision;signalRendered();}
}
function dismissButton(){return `<button class="dismiss" data-act="dismiss">${icon('power')}그냥 끄기</button>`;}
function home(){return `<main class="start-page ${compactHost()?'overlay-start':''}">${header(false,compactHost()?'휴대폰 시작':'Locky')}<div class="utility-space"><div class="quick-tools"><section class="mini-note">${memoHTML(false)}</section><section class="quick-chat"><div class="mini-head"><button data-act="openChat">${icon('chat')} ChatGPT</button><button class="iconbtn" data-act="openChat" aria-label="대화 목록">${icon('expand')}</button></div><textarea id="quickQuestion" placeholder="바로 질문하기" aria-label="바로 질문하기" maxlength="20000">${esc(quickQuestionDraft)}</textarea><button class="quick-send" data-act="quickSend" aria-label="질문 전송">${icon('send')}</button></section></div><form id="dictionaryForm" class="dictionary-bar"><button type="button" data-act="dictionaryChoice" aria-label="사전 선택">${icon('book')}</button><input id="word" value="${esc(dictionaryWordDraft)}" placeholder="사전에서 찾을 단어" aria-label="찾을 단어"><button type="submit" aria-label="사전 검색">${icon('search')}</button></form></div><section class="start-purposes" aria-labelledby="purposeQuestion"><div class="purpose-intro"><div class="sectionhead"><h2 id="purposeQuestion">무엇을 하려고 열었나요?</h2><button class="textbtn purpose-edit" data-act="managePurposes" aria-label="시작 목적 편집">편집</button></div></div>${purposeGrid(false)}</section><footer class="start-footer">${!state.status.accessibility?'<button class="textbtn" data-nav="setup">자동 관리를 위한 권한 설정</button>':''}${dismissButton()}</footer></main>`;}
function prepareStart(order=null){
 safe(flushMemo);
 if(!state)return domRevision;
 if(order){state.purposeDate=order.date;state.purposeOrders={...(state.purposeOrders||{}),[order.scope]:order.purposes};}
 purposeEntry=null;reshufflePurposesForStart();return domRevision;
}
function reshufflePurposesForStart(){
 if(!state||!['home','unlock','app'].includes(screen)||$('#sheet')?.innerHTML)return;
 const grid=$('.start-purposes .reason-grid');if(!grid)return;
 const items=[...grid.querySelectorAll(':scope > .purpose-item')];
 const byId=new Map(items.map(el=>[el.querySelector('[data-purpose]').dataset.purpose,el]));
 const order=visiblePurposes(screen==='app').map(p=>byId.get(p.id));
 if(order.length!==items.length||order.some(el=>!el)||order.every((el,i)=>el===items[i]))return;
 const scroller=$('.preset-scroll'),scroll=scroller?.scrollTop||0,end=grid.querySelector('.more-reasons');
 order.forEach(el=>grid.insertBefore(el,end));if(scroller)scroller.scrollTop=scroll;if(!scroller)layoutStart();++domRevision;signalRendered();
}
function visiblePurposes(isApp){
 const scope=isApp?(state.gate.pkg||'app'):'phone';
 const native=state.purposeOrders?.[scope]||state.purposes||[];
 const available=state.allPurposes.filter(p=>p.scope===scope||(isApp&&p.scope==='app')||(!isApp&&p.scope==='*'));
 if(!purposeEntry||purposeEntry.scope!==scope){purposeEntry={scope,date:state.purposeDate};purposePositions.set(scope,native.map(p=>p.id));}
 let order=purposePositions.get(scope)||[];
 if(purposeEntry.date===state.purposeDate)order=native.map(p=>p.id);
 else order=[...order.filter(id=>available.some(p=>p.id===id)),...native.map(p=>p.id).filter(id=>!order.includes(id))];
 purposePositions.set(scope,order);
 return available.sort((a,b)=>order.indexOf(a.id)-order.indexOf(b.id));
}
function purposeLinkIcon(p,isApp){
 if(!p.pkg&&!p.uri)return '';
 const image=!isApp&&['PHONE_START','MAIN'].includes(state?.role)&&state.purposeIcons?.[p.id];
 return image?`<span class="purpose-link-icon" aria-hidden="true"><img class="linked-app-icon" src="${esc(image)}" alt="">${icon('link')}</span>`:icon('link');
}
document.addEventListener('error',event=>{const img=event.target;if(img.matches?.('.linked-app-icon')){img.parentElement.classList.add('icon-failed');}},true);
function purposeGrid(isApp,all=false){return purposeGridMarkup(visiblePurposes(isApp),isApp,all,selected,purposeStarting);}
function purposeGridMarkup(ps,isApp,all=false,chosen='',starting=false){
 const items=ps.map(p=>`<div class="purpose-item"><button class="reason ${p.id===chosen?'selected':''} ${p.aimless?'aimless':''} ${!isApp&&['PHONE_START','MAIN'].includes(state?.role)?'phone-purpose':''}" data-purpose="${esc(p.id)}" ${isApp&&starting?'disabled':''}><span>${esc(p.label)}</span>${purposeLinkIcon(p,isApp)}</button></div>`).join('');
 const direct=`<button class="reason direct" data-act="direct">${icon('plus')} 직접 적기</button>`;
 if(!isApp&&!all)return `<div class="reason-picker">${direct}<div class="preset-scroll" role="region" tabindex="0" aria-label="목적 프리셋 목록"><div class="reason-grid">${items}</div></div></div>`;
 return `<div class="reason-grid ${all?'all-reasons':''}">${direct}${items}${!all?`<button class="reason more-reasons" data-act="morePurposes" hidden>${icon('more')} 전체 ${ps.length}개 보기</button>`:''}</div>`;
}
function currentNote(){if(!state.notes.some(n=>n.id===noteId))noteId=state.notes[0]?.id||'';return state.notes.find(n=>n.id===noteId)||{id:'',title:'메모',body:'',checks:'[]',mode:'text'};}
let memoBase=null;
function newCheckId(){return 'check-'+Array.from(crypto.getRandomValues(new Uint32Array(4)),n=>n.toString(16).padStart(8,'0')).join('');}
function memoHTML(full){let n=currentNote(),checks=[];try{checks=JSON.parse(n.checks||'[]');}catch{}checks.forEach(c=>c.id=c.id||newCheckId());memoBase={id:n.id,title:n.title,body:n.body,mode:n.mode,checks:structuredClone(checks)};const check=n.mode==='check';return `<div class="note-panel ${full?'full-note':'compact-note'}"><div class="mini-head">${full?`<input class="note-title" id="noteTitle" maxlength="100" value="${esc(n.title)}" aria-label="메모 제목">`:`<button class="note-heading" data-nav="notes">${icon('note')} ${esc(n.title||'메모')}</button>${state.notes.length>1?`<button class="iconbtn" data-note-step="-1" aria-label="이전 메모">${icon('back')}</button><button class="iconbtn" data-note-step="1" aria-label="다음 메모">${icon('arrow')}</button>`:'' }<button class="iconbtn" data-nav="notes" aria-label="메모 크게 보기">${icon('expand')}</button>`}</div>${full?`<div class="note-tabs">${state.notes.map(n=>`<button class="${n.id===noteId?'selected':''}" data-note="${esc(n.id)}">${esc(n.title||'메모')}</button>`).join('')}</div>`:''}<div class="note-content">${check?`<div id="checks">${checks.map((c,i)=>`<div class="checkrow ${c.done?'done':''}" data-checkid="${esc(c.id)}"><input type="checkbox" data-check="${i}" ${c.done?'checked':''} aria-label="완료"><textarea data-checktext="${i}" rows="1" aria-label="할 일">${esc(c.text)}</textarea><button data-delcheck="${i}" aria-label="할 일 삭제">${icon('close')}</button></div>`).join('')}</div>`:`<textarea id="noteBody" placeholder="메모를 바로 적으세요" aria-label="메모 내용">${esc(n.body)}</textarea>`}</div><div class="note-actions"><span id="saveState" class="small muted">자동 저장</span>${check?`<button class="iconbtn" data-act="addCheck" aria-label="체크 항목 추가">${icon('plus')}</button>`:''}${full?`<select id="noteMode" aria-label="메모 종류"><option value="text" ${!check?'selected':''}>글 메모</option><option value="check" ${check?'selected':''}>체크 메모</option></select>`:''}<button class="iconbtn" data-act="newNote" aria-label="새 메모">${icon('newnote')}</button>${full&&n.id?`<button class="iconbtn danger" data-act="deleteNote" aria-label="메모 삭제">${icon('trash')}</button>`:''}</div></div>`;}
function notesPage(){return `<main class="page notes-page ${compactHost()?'overlay-note':''}">${header(true,'메모')}${memoHTML(true)}</main>`;}
function gate(){
 const g=state.gate,locked=screen==='blocked',renew=screen==='renew',idle=screen==='gateIdle';
 const rule=state.rules.find(r=>r.pkg===g.pkg),policyBlock=rule?.mode==='locked';
 const duration=`<section class="duration"><div class="sectionhead"><h3>${renew?'추가할 시간':'사용할 시간'}</h3><span class="small muted">분</span></div><div class="minutes">${[5,6,7,8,9,10].map(m=>`<button data-min="${m}" class="${minutes===m?'selected':''}" aria-pressed="${minutes===m}">${m}</button>`).join('')}</div></section>`;
 return `<main class="start-page gate-page overlay-start ${locked?'blocked-page':renew?'renew-page':''}" ${idle?'inert':''}>${header(false,locked?'사용 제한':renew?'사용 연장':'앱 시작')}<div class="gate-info"><div class="gate-app">${esc(g.label||'앱 사용')}</div><h2>${locked?(policyBlock?'집중 시간에는 차단된 앱이에요':'오늘의 사용 시간을 모두 썼어요'):renew?'같은 목적으로 더 사용하나요?':'무엇을 하러 들어가나요?'}</h2><div class="daily-budget" ${locked?'hidden':''}><span>오늘 남은 시간</span><strong id="dailyLeft">${fmt(g.daily||0)}</strong></div></div><section class="block-detail" ${locked?'':'hidden'}><span>다시 사용할 수 있는 시간</span><strong>${time(state.prefs.end)}<small>자유 시간 시작</small></strong><p class="muted">${time(state.prefs.start)}–${time(state.prefs.end)} 집중 시간</p></section><div class="gate-note" ${renew?'':'hidden'}><span>처음 정한 목적</span><strong>${esc(g.purpose||'선택한 목적 없음')}</strong></div><div class="app-decision" ${locked?'hidden':''}>${duration}<section class="start-purposes" ${locked||renew?'hidden':''}><div class="purpose-intro sectionhead"><h3>사용 목적</h3><button class="textbtn" data-act="managePurposes" aria-label="앱 사용 목적 편집">편집</button></div>${preparedGatePurposeTemplate().html}</section></div><footer class="start-footer"><button id="startApp" class="primary" data-act="extend" ${renew?'':'hidden'}>${minutes}분 연장 ${icon('arrow')}</button>${dismissButton()}</footer></main>`;
}
async function choosePurpose(id){
 if(screen==='gateIdle')return;
 if(purposeStarting)return;const operation={};purposeStartOperation=operation;purposeStarting=true;const target=screen,chosenMinutes=minutes,entry=gateSnapshot(),appEntry=state?.role==='APP_GATE';
 const controls=[...document.querySelectorAll('[data-purpose],[data-min],[data-act=direct]')];controls.forEach(el=>el.disabled=true);
 try{await flushMemo();if(appEntry&&!currentGate(entry))return;const purpose=typeof id==='object'?id:{id};await api(target==='app'?'grant':'choose',target==='app'?{...purpose,minutes:chosenMinutes,gateNavigation:entry?.navigation??nativeNavigation}:purpose);if(appEntry&&!currentGate(entry))return;closeSheet();}
 finally{if(purposeStartOperation===operation){purposeStarting=false;purposeStartOperation=null;controls.forEach(el=>{if(el.isConnected)el.disabled=false;});}}
}

function timer(){let g=state.gate;return `<main class="page">${header(true,'남은 시간')}<h2>${esc(g.label||'사용 중인 관리 앱 없음')}</h2><div class="timer-ring"><div class="small">오늘 남은 시간</div><div id="dailyLeft" class="bigtime">${fmt(g.daily)}</div><div class="divider"></div><div class="small">이번 사용의 남은 시간</div><h2 id="sessionLeft">${fmt(g.session)}</h2><p class="small muted">이 화면을 보는 동안에는 잠시 멈춰요.</p></div><div class="gate-note"><span>이번 목적</span><strong>${esc(g.purpose||'선택한 목적 없음')}</strong></div><button class="secondary" data-act="exit">사용하던 앱으로 돌아가기</button>${!state.status.notifications?'<div class="notice"><button data-permission="notification">타이머 알림 허용하기</button></div>':''}</main>`;}
function updateTimers(){let changed=false;for(const [selector,value] of [['#dailyLeft',fmt(state.gate.daily)],['#sessionLeft',fmt(state.gate.session)]]){const el=$(selector);if(el&&el.textContent!==value){el.textContent=value;changed=true;}}if(changed&&state?.role==='APP_GATE'){++domRevision;signalRendered();}}
function liveTimerSettings(){
 const t=state.status.liveTimer;if(!t)return '';
 let message;
 if(!t.notifications)message='앱 알림을 먼저 허용해 주세요.';
 else if(t.channelImportance<2)message='남은 사용 시간 알림을 켜고 중요도를 무음 이상으로 설정해 주세요.';
 else if(!t.supported)message='이 Android 버전에서는 일반 타이머 알림으로 표시합니다.';
 else if(!t.eligible)message='기기에서 실시간 타이머 형식을 지원하지 않아 일반 알림으로 표시합니다.';
 else if(!t.permitted)message='실시간 알림이 허용되지 않았습니다. 설정과 기기 지원 여부를 확인해 주세요.';
 else if(t.posted&&t.promoted)message='시스템이 실시간 알림으로 처리하고 있습니다.';
 else if(t.posted)message='현재 일반 타이머 알림으로 표시하고 있습니다.';
 else message='관리 앱 사용을 시작하면 남은 시간을 표시합니다.';
 return `<section class="card" aria-label="실시간 타이머 설정"><h3>Now Bar · 실시간 타이머</h3><p class="small">${message}</p>${t.supported?'<p class="small muted">기기 정책에 따라 실시간 알림이 허용되어도 Now Bar에 표시되지 않을 수 있습니다.</p><button class="secondary" data-permission="liveTimer">실시간 알림 설정</button>':''}<button class="secondary" data-permission="timerChannel">남은 사용 시간 알림 설정</button></section>`;
}
function setup(){return `<main class="page">${header(false,'초기 설정')}<section class="card">${[['accessibility','첫 화면과 앱 관리','접근성 → 설치된 앱 → Locky'],['usage','앱 사용 기록','사용 정보 접근에서 Locky 허용'],['notification','타이머·브리핑 알림','알림을 허용하면 남은 시간을 표시해요'],['exact','시간 전환 알림','정확한 알람 허용']].map(([k,l,d])=>`<div class="permission"><div class="row"><strong>${l}</strong><button data-permission="${k}">${state.status[k==='notification'?'notifications':k]?'설정 확인':'허용'}</button></div><p>${d}</p></div>`).join('')}</section>${startWindowSettings()}${liveTimerSettings()}<div class="card"><h3>배터리 설정</h3><p class="small muted">삼성 절전 앱에서 Locky를 제외해 주세요. 접근성 설정이 막혀 있다면 앱 정보 메뉴에서 ‘제한된 설정 허용’을 먼저 켜 주세요.</p><button class="secondary" data-permission="battery">배터리 설정 열기</button></div><button class="primary" data-nav="home">시작</button></main>`;}
function startWindowSettings(){
 const s=state.status.startWindow||{};
 return `<section class="settings-group"><h2>잠금 해제 첫 화면</h2><p class="small">일반 Android 화면으로 준비합니다. 잠금화면 인증 후에 표시되며 화면을 자동으로 켜지 않습니다.</p><p class="small" role="status" data-start-window-status>${state.status.accessibility?'접근성 서비스 연결됨 · Activity 방식':'접근성 서비스를 켜 주세요'}</p><p class="small muted">마지막 잠금 해제: ${s.lastUnlockAt?esc(new Date(s.lastUnlockAt).toLocaleString('ko-KR')):'아직 없음'}<br>마지막 실행 오류: ${s.lastFailure?esc(s.lastFailure)+(s.lastFailureAt?' · '+esc(new Date(s.lastFailureAt).toLocaleString('ko-KR')):''):'없음'}</p></section>`;
}
function appGateDiagnosticsSettings(){
 const d=state.status.appGateDiagnostics||{},when=d.lastAt?new Date(d.lastAt).toLocaleString('ko-KR'):'아직 없음';
 return `<section class="settings-group"><div class="row"><h2>관리 앱 시작 진단</h2><button id="appGateDiagnostics" class="toggle ${d.enabled?'on':''}" role="switch" aria-checked="${!!d.enabled}" aria-label="관리 앱 시작 진단 기록">${d.enabled?'켜짐':'꺼짐'}</button></div><p class="small muted">관리 앱 진입 감지부터 데이터 적용, 렌더링 콜백, 표시 요청까지 걸린 시간을 기록합니다. 켠 뒤 앱을 연달아 전환하고, 한동안 사용하지 않았다가 다시 진입해 비교해 보세요. 앱 이름·목적·입력 내용은 기록하지 않습니다.</p><p class="small" role="status" id="appGateDiagnosticStatus">최근 기록: ${esc(when)} · 앱 진입: ${d.appGates||0}회</p>${d.error?`<p class="small danger">${esc(d.error)}</p>`:''}<div class="diagnostic-actions"><button class="secondary" data-act="viewDiagnostics">로그 보기</button><button class="secondary" data-act="saveDiagnostics" ${d.appGates?'':'disabled'}>로그 내보내기</button><button class="secondary danger" data-act="deleteDiagnostics" ${d.appGates?'':'disabled'}>기록 삭제</button></div><p class="small muted">최근 24회 · 최대 96KB · 이 기기에만 보관</p></section>`;
}
async function viewAppGateDiagnostics(){
 const data=await api('diagnostics');
 sheet(`${sheetHead('관리 앱 시작 진단')}<p class="small muted">단계별 소요 시간을 비교할 수 있습니다. 시스템 화면 합성이 끝난 정확한 시점은 별도로 확인해야 합니다.</p><pre class="diagnostic-log">${esc(data.text)}</pre><div class="sheet-actions"><button class="primary" data-act="saveDiagnostics">파일 내보내기</button><button class="secondary" data-act="closeSheet">닫기</button></div>`);
}
function settings(){let p=state.prefs;return `<main class="page settings-page">${header(false,'설정')}<section class="settings-group"><div class="row"><h2>집중 시간</h2><button id="enabled" class="toggle ${p.enabled?'on':''}" role="switch" aria-checked="${p.enabled}" aria-label="집중 시간 관리">${p.enabled?'켜짐':'꺼짐'}</button></div><div class="split"><div><label for="startTime">집중 시작</label><input class="field" type="time" id="startTime" value="${time(p.start)}"></div><div><label for="endTime">자유 시간 시작</label><input class="field" type="time" id="endTime" value="${time(p.end)}"></div></div><p class="small muted">매일 반복 · 변경 즉시 적용</p></section><section class="settings-group"><h2>앱과 사용 목적</h2><button class="setting-link" data-nav="apps"><strong>관리할 앱</strong><span>${state.rules.length}개</span>${icon('arrow')}</button><button class="setting-link" data-nav="usageApps"><strong>사용시간 제외 앱</strong>${icon('arrow')}</button><button class="setting-link" data-act="managePurposes"><strong>목적 프리셋과 앱 연결</strong>${icon('arrow')}</button><button class="setting-link" data-act="dictionaryChoice"><strong>기본 사전</strong>${icon('arrow')}</button></section><section class="settings-group"><div class="row"><h2>화면 위 타이머</h2><button id="floatingTimer" class="toggle ${p.floatingTimer!==false?'on':''}" role="switch" aria-checked="${p.floatingTimer!==false}" aria-label="화면 위 타이머">${p.floatingTimer!==false?'켜짐':'꺼짐'}</button></div><p class="small muted">눌러서 시간·목적 확인, 길게 눌러 위치 이동</p></section>${startWindowSettings()}${appGateDiagnosticsSettings()}<section class="settings-group"><h2>화면 테마</h2><label for="themeMode">표시 방식</label><select class="field" id="themeMode" aria-label="화면 테마"><option value="system" ${(p.themeMode||'system')==='system'?'selected':''}>기기 설정 따르기</option><option value="light" ${p.themeMode==='light'?'selected':''}>라이트 모드 고정</option><option value="dark" ${p.themeMode==='dark'?'selected':''}>다크 모드 고정</option></select></section><section class="settings-group"><h2>알림과 브리핑</h2><label>집중 시작 전 알림</label><div class="reminder-buttons">${[30,20,10,5,1].map(m=>`<button class="reminder ${p.reminders.includes(m)?'selected':''}" data-reminder="${m}" aria-pressed="${p.reminders.includes(m)}">${m}분</button>`).join('')}</div><label for="customReminders">추가 알림 · 분</label><input class="field" id="customReminders" inputmode="numeric" placeholder="예: 45, 15" value="${p.reminders.filter(n=>![30,20,10,5,1].includes(n)).join(', ')}"><div class="setting-line"><label for="dailyBrief">하루 브리핑 알림</label><input id="dailyBrief" type="checkbox" ${p.dailyBrief!==false?'checked':''}></div><label for="dailyBriefTime">하루·주간 알림 시각</label><input class="field" id="dailyBriefTime" type="time" value="${time(p.dailyBriefTime??540)}"><div class="setting-line"><label for="focusBrief">집중시간 종료 알림</label><input id="focusBrief" type="checkbox" ${p.focusBrief!==false?'checked':''}></div><div class="notice"><strong>브리핑 알림</strong><p class="small">${state.status.briefing?.bannerEligible?'배너를 표시할 수 있는 상태':'알림 권한 또는 배너 채널 설정 확인 필요'} · 채널 중요도 ${state.status.briefing?.importance??'미확인'}</p><button class="textbtn" data-permission="notification">알림 권한 확인</button><button class="textbtn" data-permission="briefChannel">배너 알림 시스템 설정</button><p class="small muted">배너가 보이지 않으면 삼성 알림 설정의 팝업 표시·방해 금지·앱 알림을 확인해 주세요.</p></div><label for="weeklyDay">주간 브리핑</label><select class="field" id="weeklyDay">${['월','화','수','목','금','토','일'].map((d,i)=>`<option value="${i+1}" ${p.weeklyDay===i+1?'selected':''}>${d}요일 · 종료된 7일</option>`).join('')}</select><button class="setting-link" data-nav="setup"><strong>권한·배터리 설정</strong>${icon('arrow')}</button></section><p class="small muted settings-footnote">설정은 자동 저장됩니다</p></main>`;}
function appsPage(){return `<main class="page">${header(true,'관리할 앱')}<input id="appSearch" class="field" placeholder="앱 이름으로 검색" aria-label="앱 검색"><div id="appList" class="card"><p class="empty">불러오는 중</p></div></main>`;}
async function getApps(){if(appsCache)return appsCache;if(!appsPromise)appsPromise=api('apps').then(items=>(appsCache=items,items)).finally(()=>{appsPromise=null;});return appsPromise;}
async function loadApps(){await safe(async()=>{await getApps();renderApps();});}
function renderApps(){let list=$('#appList');if(!list||!appsCache)return;let q=$('#appSearch')?.value.toLowerCase()||'',seen=state.ruleSeen||[];
 const rank=a=>state.rules.some(r=>r.pkg===a.pkg)?0:seen.includes(a.pkg)?1:2;
 list.innerHTML=appsCache.filter(a=>a.label.toLowerCase().includes(q)).slice().sort((a,b)=>rank(a)-rank(b)||((rank(a)<2)?seen.indexOf(a.pkg)-seen.indexOf(b.pkg):a.label.localeCompare(b.label,'ko'))).map(a=>{let r=state.rules.find(r=>r.pkg===a.pkg);return `<div class="app-row"><input type="checkbox" class="manage-check" data-manage="${esc(a.pkg)}" ${r?'checked':''} aria-label="${esc(a.label)} 관리"><img src="${esc(a.icon)}" alt=""><div class="info"><strong>${esc(a.label)}</strong><p>${r?.mode==='limited'?`하루 ${r.minutes}분`:r?.mode==='locked'?'집중 중 잠금':seen.includes(a.pkg)?'이전에 선택한 앱':'제한 없음'}</p></div><button class="iconbtn" data-rule="${esc(a.pkg)}" aria-label="${esc(a.label)} 관리 방식">${icon('more')}</button></div>`;}).join('')||'<p class="empty">검색 결과 없음</p>';bind(list);}
function connectionHTML(){let c=state.connection,connected=state.accounts.find(a=>a.active&&a.connected);return connected?`<div class="row"><div><strong>${esc(connected.email||'연결된 ChatGPT 계정')}</strong><p class="small muted" style="margin:4px 0 0">${connected.plan?'ChatGPT 요금제로 사용 중':'요금제 사용 승인 필요'}</p></div><button class="textbtn" data-act="accountSheet">계정</button></div><button class="textbtn" data-act="manageUsage">ChatGPT 사용량 관리 ${icon('arrow')}</button>`:`<p class="small muted">ChatGPT 계정을 연결하면 이곳에서 간단한 대화를 나눌 수 있어요. 사용 가능한 요금제와 계정의 모델을 사용해요.</p><button class="primary" data-act="signin">Continue with ChatGPT</button>${c.text?`<p class="small muted" style="margin:12px 0 0">${esc(c.text)}</p>`:''}${c.detail?`<p class="small danger">${esc(c.detail)}</p>`:''}${['starting','checking','browser','returning','exchange','identity','saving'].includes(c.phase)?'<button class="textbtn" data-act="cancelLogin">연결 취소</button>':''}`;}

function chats(){return `<main class="page chats-page">${header(compactHost(),'ChatGPT')}<section id="connection" class="card">${connectionHTML()}</section>${state.accounts.some(a=>a.active&&a.connected)?`<section class="model-settings"><label for="model">모델 · 웹 검색 활성화</label><div class="link-line"><select class="field" id="model">${state.models.map(m=>`<option value="${esc(m.slug)}" ${state.prefs.model===m.slug?'selected':''}>${esc(m.name)}</option>`).join('')}</select><button class="iconbtn" data-act="models" aria-label="모델 새로고침">↻</button></div></section>`:''}<div class="sectionhead"><h2>대화</h2><button class="iconbtn" data-act="newChat" aria-label="새 대화">${icon('plus')}</button></div><div class="chat-list">${state.chats.map(c=>`<div class="row"><button data-chat="${esc(c.id)}"><strong>${esc(c.title)}</strong></button><button class="iconbtn danger" data-deletechat="${esc(c.id)}" aria-label="대화 삭제">${icon('trash')}</button></div>`).join('')||'<p class="empty">저장된 대화가 없습니다</p>'}</div></main>`;}
function chat(){const c=state.chats.find(c=>c.id===chatId);return `<main class="page chat-page">${header(true,c?.title||'새 대화')}<div id="messages"></div></main><div class="composer"><div class="row"><textarea class="field" id="chatInput" placeholder="메시지 입력" aria-label="메시지" maxlength="20000"></textarea><button data-act="${state.busy?'cancelResponse':'send'}" aria-label="${state.busy?'답변 중지':'전송'}">${icon(state.busy?'close':'send')}</button></div></div>`;}
function sourceUrl(value){try{const u=new URL(String(value));return u.protocol==='https:'||u.protocol==='http:'?u.href:'';}catch{return '';}}

function chatSourceGroup(refs,id,label='',extra=''){
 const valid=[...new Map(refs.filter(r=>sourceUrl(r.url)).map(r=>[r.url,r])).values()];
 if(!valid.length)return '';
 const numbers=valid.map((r,i)=>r.number||i+1),name=label?`${label} ${valid.length}개`:`참고 자료 ${numbers.join(', ')}번 보기`;
 return `<span class="chat-source-group"><button type="button" class="chat-source-toggle ${label?'':'inline-reference'}" data-source-toggle="${esc(id)}" aria-label="${esc(name)}" aria-controls="${esc(id)}" aria-expanded="false">${icon('link')}<span>${label?`${esc(label)} ${valid.length}`:numbers.join(', ')}</span></button><span class="chat-source-list" id="${esc(id)}" hidden>${valid.map((r,i)=>`<button class="chat-source-item" type="button" data-url="${esc(r.url)}"><span>${r.number||i+1}</span><span>${esc(r.title||new URL(r.url).hostname)}</span></button>`).join('')}${extra}</span></span>`;
}
function citationLabel(label,links){
 const value=String(label).trim().toLowerCase().replace(/^www\./,'');
 return links.some(ref=>{const host=new URL(ref.url).hostname.toLowerCase().replace(/^www\./,'');return value===host||host.endsWith('.'+value);});
}
function citationOnly(text,links){
 let value=text.trim();if(/^[（(][\s\S]*[)）]$/.test(value))value=value.slice(1,-1).trim();
 if(/^(?:【[^】]+】|\[\d+\]|cite[\s\S]*|〔[^〕]+〕)$/.test(value))return true;
 const md=value.match(/^\[([^\]]+)\]\((https?:\/\/[^\s]+)\)$/),auto=value.match(/^<(https?:\/\/[^>]+)>$/);
 if(md)return links.some(ref=>sourceUrl(md[2])===ref.url);
 const url=sourceUrl(auto?auto[1]:value);return url?links.some(ref=>ref.url===url):citationLabel(value,links);
}
function precedingCitation(prefix,links){
 // Remove only a citation-only domain/link adjacent to this annotation, never a prose link elsewhere.
 const match=prefix.match(/(?:[（(]\s*)?(?:\[([^\]\n]+)\]\((https?:\/\/[^\s]+?)\)|(https?:\/\/[^\s()]+)|([a-z0-9.-]+\.[a-z]{2,}))\s*[)）]?\s*$/i);
 if(!match)return prefix.length;
 const linked=match[2]||match[3],label=match[1]||match[4];
 const same=linked&&links.some(ref=>sourceUrl(linked)===ref.url);
 if(!(same&&(!label||citationLabel(label,links)))&&!(match[4]&&citationLabel(match[4],links)))return prefix.length;
 return match.index;
}

function chatInline(text){
 const reserved=[],keep=html=>'LOCKYHOLD'+(reserved.push(html)-1)+'END';
 let raw=String(text||'');
 raw=raw.replace(/`([^`\n]+)`/g,(_,v)=>keep('<code>'+esc(v)+'</code>'));
 raw=raw.replace(/\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/g,(full,label,url)=>{const u=sourceUrl(url);return u?keep(`<button class="chat-md-link" type="button" data-url="${esc(u)}">${esc(label)}</button>`):full;});
 raw=raw.replace(/<(https?:\/\/[^\s>]+)>/g,(full,url)=>{const u=sourceUrl(url);return u?keep(`<button class="chat-md-link" type="button" data-url="${esc(u)}">${esc(url)}</button>`):full;});
 let out=esc(raw);
 out=out.replace(/(\*\*|__)([^\n]+?)\1/g,'<strong>$2</strong>');
 out=out.replace(/(^|[^\w*])\*([^*\n]+)\*(?!\*)/g,'$1<em>$2</em>');
 out=out.replace(/(^|[^\w_])_([^_\n]+)_(?!_)/g,'$1<em>$2</em>');
 out=out.replace(/~~([^\n]+?)~~/g,'<del>$1</del>');
 return out.replace(/LOCKYHOLD(\d+)END/g,(_,i)=>reserved[Number(i)]||'');
}

function chatMarkdown(text){
 const lines=String(text||'').replace(/\r\n?/g,'\n').split('\n');
 let out='',para=[],kind='',quotes=[];
 const flushPara=()=>{if(para.length){out+='<p>'+para.map(chatInline).join('<br>')+'</p>';para=[];}};
 const flushList=()=>{if(kind){out+='</'+kind+'>';kind='';}};
 const flushQuote=()=>{if(quotes.length){out+='<blockquote>'+quotes.map(q=>'<p>'+chatInline(q)+'</p>').join('')+'</blockquote>';quotes=[];}};
 const flush=()=>{flushPara();flushList();flushQuote();};
 const cells=line=>line.trim().replace(/^\||\|$/g,'').split('|').map(v=>v.trim());
 for(let i=0;i<lines.length;i++){
  const line=lines[i],v=line.trim();
  if(/^(```|~~~)/.test(v)){
   flush();const fence=v.slice(0,3),lang=v.slice(3).trim().split(/\s+/)[0],code=[];
   while(++i<lines.length&&!lines[i].trim().startsWith(fence))code.push(lines[i]);
   out+='<pre><code'+(lang&&/^[a-z0-9+#_-]+$/i.test(lang)?' class="language-'+esc(lang)+'"':'')+'>'+esc(code.join('\n'))+'</code></pre>';continue;
  }
  if(!v){flush();continue;}
  if(i+1<lines.length&&v.includes('|')&&/^:?-{3,}:?(\s*\|\s*:?-{3,}:?)*$/.test(lines[i+1].trim().replace(/^\||\|$/g,''))){
   flush();const header=cells(line);let rows=[];i++;
   while(i+1<lines.length&&lines[i+1].includes('|'))rows.push(cells(lines[++i]));
   out+='<div class="chat-table-scroll"><table><thead><tr>'+header.map(c=>'<th>'+chatInline(c)+'</th>').join('')+'</tr></thead><tbody>'+rows.map(row=>'<tr>'+header.map((_,j)=>'<td>'+chatInline(row[j]||'')+'</td>').join('')+'</tr>').join('')+'</tbody></table></div>';continue;
  }
  const h=line.match(/^\s*(#{1,6})\s+(.+?)\s*#*\s*$/);
  if(h){flush();const size=Math.min(4,h[1].length+1);out+='<h'+size+'>'+chatInline(h[2])+'</h'+size+'>';continue;}
  if(/^([-*_]\s*){3,}$/.test(v)){flush();out+='<hr>';continue;}
  const q=line.match(/^\s*>\s?(.*)$/);
  if(q){flushPara();flushList();quotes.push(q[1]);continue;}
  flushQuote();
  const bullet=line.match(/^\s*[-*+]\s+(.+)$/),numbered=line.match(/^\s*\d+[.)]\s+(.+)$/);
  if(bullet||numbered){flushPara();const next=numbered?'ol':'ul';if(kind!==next){flushList();kind=next;out+='<'+next+'>';}out+='<li>'+chatInline((bullet||numbered)[1])+'</li>';continue;}
  flushList();para.push(v);
 }
 flush();return out;
}

function chatBody(text,rawRefs,id){
 let all=[];try{all=typeof rawRefs==='string'?JSON.parse(rawRefs||'[]'):Array.isArray(rawRefs)?rawRefs:[];}catch{}
 if(!Array.isArray(all))all=[];
 const refs=all.map(r=>({url:sourceUrl(r?.url),title:String(r?.title||''),start_index:r?.start_index,end_index:r?.end_index})).filter(r=>r.url);
 let body=String(text||''),ending='';
 const heads=[...body.matchAll(/(?:^|\n)\s*(?:#{1,4}\s*)?(?:Sources|References|출처|참고 자료)\s*:?\s*\n/gi)];
 if(heads.length){const h=heads[heads.length-1],trailer=body.slice(h.index+h[0].length);if(/https?:\/\//.test(trailer)){body=body.slice(0,h.index).trimEnd();ending=trailer;}}
 const unique=new Map();
 const add=ref=>{const old=unique.get(ref.url);if(old){if(!old.title&&ref.title)old.title=ref.title;return old;}const entry={...ref,number:unique.size+1};unique.set(ref.url,entry);return entry;};
 refs.forEach(add);
 // Fold the model's trailing list into the same disclosure, deduplicating metadata citations.
 const extraLinks=[];
 let remainder=ending.replace(/\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/g,(_,title,url)=>{const u=sourceUrl(url);if(u)extraLinks.push({url:u,title});return '';});
 remainder=remainder.replace(/https?:\/\/[^\s<>()]+/g,url=>{const u=sourceUrl(url.replace(/[.,;]+$/,''));if(u)extraLinks.push({url:u,title:''});return '';});
 extraLinks.forEach(add);
 const extra=remainder.replace(/^[\s\d.*+_()\[\]<>:#-]+$/gm,'').trim();
 const cps=Array.from(body),groups=new Map();
 for(const ref of refs){const start=Number(ref.start_index),end=Number(ref.end_index);
  if(!Number.isInteger(start)||!Number.isInteger(end)||start<0||end<=start||end>cps.length)continue;
  const key=start+':'+end,rows=groups.get(key)||[];if(!rows.some(r=>r.url===ref.url))rows.push(unique.get(ref.url));groups.set(key,rows);
 }
 // Compute edits against the original code points before applying any replacements.
 // A marker's adjacent domain can itself have an annotation; merge those edits first.
 const edits=[...groups].map(([key,links])=>{
  const [from,to]=key.split(':').map(Number),original=cps.slice(from,to).join(''),only=citationOnly(original,links);
  let start=only?from:to,end=to;
  const prefix=cps.slice(0,start).join(''),clean=precedingCitation(prefix,links);
  if(clean<prefix.length)start=Array.from(prefix.slice(0,clean)).length;
  if(only){const left=cps.slice(0,start).join('').match(/[（(]\s*$/),right=cps.slice(end).join('').match(/^\s*[)）]/);if(left&&right){start-=Array.from(left[0]).length;end+=Array.from(right[0]).length;}}
  return {start,end,links:[...links],anchor:from};
 }).sort((a,b)=>a.start-b.start||a.end-b.end);
 const merged=[];
 for(const edit of edits){
  const last=merged.at(-1);
  if(last&&edit.start<=last.end){last.end=Math.max(last.end,edit.end);last.anchor=Math.min(last.anchor,edit.anchor);for(const ref of edit.links)if(!last.links.some(r=>r.url===ref.url))last.links.push(ref);}
  else merged.push(edit);
 }
 const widgets=[];
 for(const edit of merged.reverse()){
  const placeholder='LOCKYCITE'+widgets.length+'BUTTON';widgets.push(chatSourceGroup(edit.links,'cite-'+id+'-'+edit.anchor));
  cps.splice(edit.start,edit.end-edit.start,placeholder);
 }
 let html=chatMarkdown(cps.join('')).replace(/LOCKYCITE(\d+)BUTTON/g,(_,n)=>widgets[Number(n)]||'');
 if(unique.size)html+='<div class="chat-source-footer">'+chatSourceGroup([...unique.values()],'cites-'+id,'참고 자료',extra?chatMarkdown(extra):'')+'</div>';
 return html;
}

function quickRequestId(){const bytes=crypto.getRandomValues(new Uint8Array(16));bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;const h=[...bytes].map(b=>b.toString(16).padStart(2,'0')).join('');return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;}
function startQuickQuestion(){
 const text=$('#quickQuestion')?.value.trim();if(!text)return;releasePhoneNavigation('chat-send');
 if(!state.accounts.some(a=>a.active&&a.connected)){toast('ChatGPT를 먼저 연결해 주세요.');safe(()=>navigate('chats'));return;}
 if(state.busy||[...quickTurns.values()].some(t=>t.inFlight))throw Error('현재 질문을 처리하고 있습니다.');
 const turn={id:quickRequestId(),text,accepted:false,inFlight:false,error:'',messages:null};quickTurns.set(turn.id,turn);
 // No await before entering chat: memo saving and durable question acceptance run afterwards.
 chatReturn=screen;safe(flushMemo);chatId=turn.id;state.chats.unshift({id:turn.id,title:text.slice(0,30)});
 presentScreen('chat');screen='chat';closeSheet();document.body.classList.remove('start-screen');window.scrollTo(0,0);render();startPolling();remember();
 submitQuickQuestion(turn);
}
function quickMessages(turn){
 if(turn.messages)return turn.messages.map(m=>({...m,quickRetry:m.id===turn.reply&&m.status==='interrupted',quickError:m.id===turn.reply?turn.error:''}));
 return [{id:turn.id+':user',role:'user',body:turn.text,status:'complete'},
  {id:turn.id+':reply',role:'assistant',body:turn.error?'질문을 보내지 못했어요.':'질문을 보내고 있어요…',status:turn.error?'failed':'sending',quickRetry:!!turn.error,quickError:turn.error}];
}
function adoptQuickMessages(turn,result,box=$('#messages')){
 const entries=box?.messageEntries;
 if(entries&&chatId===turn.id)for(const [suffix,key] of [['user',result.user],['reply',result.reply]]){
  const node=entries.get(turn.id+':'+suffix);if(node){entries.delete(turn.id+':'+suffix);entries.set(String(key),node);}
 }
 turn.user=result.user;turn.reply=result.reply;turn.accepted=true;
}
async function submitQuickQuestion(turn,retry=false){
 if(turn.inFlight)return;const observed=turn.messages;turn.inFlight=true;turn.error='';
 if(screen==='chat'&&chatId===turn.id)applyMessages(quickMessages(turn),false);
 try{
  const result=await api('quickSend',{request:turn.id,text:turn.text,retry});
  adoptQuickMessages(turn,result);if(turn.messages===observed){turn.messages=result.messages;state.busy=result.busy;state.activeChat=turn.id;}
  if(quickQuestionDraft===turn.text)quickQuestionDraft='';if($('#quickQuestion')?.value.trim()===turn.text)$('#quickQuestion').value='';if(state?.role==='PHONE_START')remember();
 }catch(e){if(!turn.accepted)turn.error=e.message;else if(!turn.messages?.some(m=>m.status==='streaming'))turn.error=e.message;}
 finally{turn.inFlight=false;if(screen==='chat'&&chatId===turn.id){applyMessages(quickMessages(turn),false);startPolling();}}
}
async function renderMessages(force){
 const box=$('#messages');if(!box)return;
 const id=chatId,request=box.messageRequest=(box.messageRequest||0)+1;
 const messages=await api('messages',{id});
 if(id!==chatId||screen!=='chat'||box!==$('#messages')||request!==box.messageRequest)return;
 let turn=quickTurns.get(id);const reply=messages.find(m=>m.quickRequest===id);
 if(reply){
  const user=messages.find(m=>m.role==='user');
  if(!turn){turn={id,text:user?.body||'',accepted:true,inFlight:false,error:''};quickTurns.set(id,turn);}
  adoptQuickMessages(turn,{user:user.id,reply:reply.id},box);turn.messages=messages;
 }
 applyMessages(turn&&(!messages.length||reply)?quickMessages(turn):messages,force);
}
function applyMessages(messages,force){
 const box=$('#messages');if(!box)return;
 const viewport=window.visualViewport?.height||innerHeight;
 const follow=window.scrollY+viewport>=document.documentElement.scrollHeight-150;
 const initial=!box.messageEntries,entries=box.messageEntries||new Map();
 const anchor=[...box.children].find(node=>node.classList.contains('message')&&node.getBoundingClientRect().bottom>0);
 const anchorTop=anchor?.getBoundingClientRect().top,position=window.scrollY;
 const keep=new Map();let changed=false;
 for(const [i,m] of messages.entries()){
   const key=String(m.id??i),signature=JSON.stringify([m.role,m.body,m.status,m.citations||'[]',!!m.quickRetry,m.quickError||'']);
   let node=entries.get(key);
   if(!node){node=document.createElement('div');node.className='message '+m.role;}
   // Compare message data, never disclosure attributes changed by the reader.
   if(node.messageSignature!==signature){
     const expanded=new Set([...node.querySelectorAll('[data-source-toggle][aria-expanded="true"]')].map(el=>el.dataset.sourceToggle));
     node.innerHTML=(m.role==='assistant'?'<div class="chat-markdown">'+chatBody(m.body||(m.status==='streaming'?'답변을 생각하고 있어요…':''),m.citations,m.id||i)+'</div>':'<div class="chat-plain">'+esc(m.body||'')+'</div>')+(m.status==='interrupted'?'<span class="small danger">완료하지 못한 답변</span>':'')+(m.quickError?'<p class="small danger">'+esc(m.quickError)+'</p>':'')+(m.quickRetry?'<button class="textbtn" data-act="retryQuick">다시 시도</button>':'');
     node.className='message '+m.role;node.messageSignature=signature;bind(node);changed=true;
     for(const toggle of node.querySelectorAll('[data-source-toggle]'))if(expanded.has(toggle.dataset.sourceToggle)){
       toggle.setAttribute('aria-expanded','true');node.querySelectorAll('.chat-source-list').forEach(panel=>{if(panel.id===toggle.dataset.sourceToggle)panel.hidden=false;});
     }
   }
   if(box.children[i]!==node){box.insertBefore(node,box.children[i]||null);changed=true;}
   keep.set(key,node);
 }
 const retained=new Set(keep.values());
 for(const node of [...box.children])if(!retained.has(node)&&(messages.length||!node.classList.contains('empty'))){node.remove();changed=true;}
 if(!messages.length&&!box.querySelector('.empty')){box.innerHTML='<p class="empty">무엇이 궁금한가요?</p>';changed=true;}
 box.messageEntries=keep;
 if(changed){
   if(follow||(force&&initial))window.scrollTo(0,document.documentElement.scrollHeight);
   else if(anchor?.isConnected)window.scrollBy(0,anchor.getBoundingClientRect().top-anchorTop);
   else window.scrollTo(0,position);
 }
 const button=$('.composer button');if(button){const pending=quickTurns.get(chatId);button.disabled=!!pending&&!pending.accepted;const action=state.busy?'cancelResponse':'send';if(button.dataset.act!==action){button.dataset.act=action;button.innerHTML=icon(state.busy?'close':'send');button.setAttribute('aria-label',state.busy?'답변 중지':'전송');}}
 if(state.chatError&&state.chatError!==renderMessages.lastError){renderMessages.lastError=state.chatError;toast(state.chatError);}
}
function brief(){return `<main class="page briefing-page">${header(compactHost(),'사용 기록',false)}<div class="segmented"><button data-days="1" class="${briefDays===1?'active':''}">하루</button><button data-days="7" class="${briefDays===7?'active':''}">일주일</button></div><div class="row brief-period"><button class="iconbtn" data-act="previousBrief" aria-label="이전 날짜">${icon('back')}</button><span id="briefDate" class="small muted"></span><button class="iconbtn" data-act="nextBrief" ${briefOffset===0?'disabled':''} aria-label="다음 날짜">${icon('arrow')}</button><button class="textbtn" data-act="todayBrief">오늘</button><button class="iconbtn" data-act="refreshBrief" aria-label="기록 새로고침">↻</button></div><div id="briefContent"><p class="empty">기록을 불러오는 중</p></div><details class="brief-export"><summary>원본 기록 내보내기</summary><p class="small muted">선택한 날짜 · JSON v1</p><div class="brief-export-actions"><button class="secondary" data-act="copyDay">복사</button><button class="secondary" data-act="saveDay">파일 저장</button></div></details></main>`;}
async function openBriefRoute(route,navigation=null){
 const [,kind,value]=route.split('|');
 if(kind==='focus'){briefTarget={kind:'focus',key:value};briefDays=1;}
 else {briefDays=kind==='week'?7:1;briefTarget={date:value};const [y,m,d]=value.split('-').map(Number),today=new Date();briefOffset=Math.max(0,Math.round((Date.UTC(today.getFullYear(),today.getMonth(),today.getDate())-Date.UTC(y,m-1,d))/86400000));}
 await navigate('brief',navigation,true);
}
const briefInFlight=new Map();
async function loadBrief(){const sequence=++briefSeq,args={days:briefDays,offset:briefOffset,...(briefTarget||{})},key=JSON.stringify(args);await safe(async()=>{let pending=briefInFlight.get(key);if(!pending){pending=api('brief',args).finally(()=>briefInFlight.delete(key));briefInFlight.set(key,pending);}const b=await pending;if(screen!=='brief'||!$('#briefContent')||sequence!==briefSeq)return;briefData=b;remember();if(b.kind==='focus'){const d=new Date(b.end-1),today=new Date();briefOffset=Math.max(0,Math.round((Date.UTC(today.getFullYear(),today.getMonth(),today.getDate())-Date.UTC(d.getFullYear(),d.getMonth(),d.getDate()))/86400000));}$('#briefDate').textContent=b.kind==='focus'?`${new Date(b.start).toLocaleDateString('ko-KR',{timeZone:b.timeZone})} ${dateTime(b.start,b.timeZone)} – ${dateTime(b.end,b.timeZone)}`:b.days===7?`${new Date(b.start).toLocaleDateString('ko-KR',{timeZone:b.timeZone})} – ${b.date}`:b.date;renderBrief(b);});}
function briefSortButton(key){
 const desc=briefSort[key]==='desc',name=key==='apps'?'사용한 앱':key==='events'?'행동별 기록':'앱 사용 시간선';
 const order=key==='apps'?(desc?'많이 사용한 순':'적게 사용한 순'):(desc?'최근 기록부터':'오래된 기록부터');
 return `<button type="button" class="textbtn brief-sort" data-brief-sort="${key}" aria-label="${name}: ${order}, 정렬 전환" title="${order}"><span>${order}</span>${icon(desc?'sortDown':'sortUp')}</button>`;
}
function briefStatTime(ms){return ms>0&&ms<60000?'<span class="brief-short-time">1분 미만</span>':`${mins(ms||0)}<small>분</small>`;}

function briefDuration(ms){return ms>0&&ms<60000?'1분 미만':`${mins(ms)}분`;}
function briefFold(key,items,paint,count=5){return items.slice(0,count).map(paint).join('')+(items.length>count?briefLazyDetails('brief-'+key,`전체 ${items.length}개 보기`,()=>items.slice(count).map((item,i)=>paint(item,i+count)).join('')):'');}
// Presentation only: keep native observed durations, explicit purpose links and unknown intervals intact.
let briefRecordFilter='all',briefSelectedHour=null;
const briefMs=t=>Math.max(0,Number(t.ms??(t.end-t.start))||0);
const briefPercent=(part,total)=>total>0?Math.round(part/total*100):0;
const briefFormats=new Map(),briefModels=new WeakMap(),briefLazyViews=new Map();
let briefViewRevision=0,briefRecordSignature='';
function briefFormat(at,zone,kind){
 const key=(zone||'')+'|'+kind;let formatter=briefFormats.get(key);
 if(!formatter){const options=kind==='date'?{year:'numeric',month:'2-digit',day:'2-digit'}:kind==='label'?{month:'numeric',day:'numeric',weekday:'short'}:{hour:'2-digit',minute:'2-digit',hour12:false,...(kind==='exact'?{second:'2-digit'}:{})};formatter=new Intl.DateTimeFormat(kind==='date'?'en-CA':'ko-KR',{timeZone:zone,...options});briefFormats.set(key,formatter);}
 return formatter.format(at);
}
function briefKnownPurpose(value){return ['목적 미확인','목적 연결 미확인','대상 연결 미확인'].includes(value)?'':value||'';}
function briefLazyDetails(id,summary,paint,classes=''){
 briefLazyViews.set(id,paint);
 return `<details id="${esc(id)}" class="${classes}" data-brief-revision="${briefViewRevision}"><summary>${summary}</summary><div data-brief-lazy="${esc(id)}"></div></details>`;
}
function briefHydrate(details){
 if(!details?.open)return;
 const body=[...details.children].find(el=>el.dataset?.briefLazy===details.id);
 if(!body||body.briefReady)return;
 const paint=briefLazyViews.get(details.id);if(!paint)return;
 body.briefReady=true;body.innerHTML=paint();bind(body);
}
function briefKeepDetails(root,opened){
 let changed=true;while(changed){changed=false;for(const d of root.querySelectorAll('details'))if(opened.has(d.id)&&!d.open){d.open=true;briefHydrate(d);changed=true;}}
}
function briefInstallLazy(){
 const area=$('#briefContent');if(!area||area.briefLazyInstalled)return;area.briefLazyInstalled=true;
 area.addEventListener('toggle',e=>{if(e.target.tagName==='DETAILS'&&e.target.open)briefHydrate(e.target);},true);
}
function briefReplaceSection(id,html){
 const old=document.getElementById(id);if(!old)return;
 const opened=new Set([...old.querySelectorAll('details[open]')].map(d=>d.id));if(old.open)opened.add(id);
 const template=document.createElement('template');template.innerHTML=html;const next=template.content.firstElementChild;next.briefMarkup=next.outerHTML;next.briefKey=next.id;old.replaceWith(next);if(opened.has(next.id)){next.open=true;briefHydrate(next);}bind(next);briefKeepDetails(next,opened);
}
function briefModel(b){
 let m=briefModels.get(b);if(m)return m;
 const events=new Map();for(const e of [...(b.purposeContexts||[]),...(b.flows||[]).map(f=>f.event),...(b.events||[])])if(e)events.set(String(e.id),e);
 const scope=new Map(),choices=[...events.values()].filter(e=>['unlock','app'].includes(e.kind)).sort((a,b)=>a.time-b.time||a.id-b.id);
 for(const event of choices)scope.set(String(event.id),{event,start:event.time,end:event.time,steps:[],keys:new Set(),directMillis:0,otherMillis:0});
 const purposeUsage=new Map();
 for(const [i,t] of (b.timeline||b.intervals||[]).entries()){
  const ancestors=[],seen=new Set();let event=events.get(String(t.parent||t.eventId));
  if(!event)event=events.get(String(t.eventId));
  while(event&&!seen.has(String(event.id))){seen.add(String(event.id));if(scope.has(String(event.id)))ancestors.push(scope.get(String(event.id)));event=events.get(String(event.parent));}
  const purposes=new Set();for(const f of ancestors){f.steps.push(t);f.keys.add(i);f.end=Math.max(f.end,t.end);if(String(t.eventId)===String(f.event.id)){if(t.relation==='direct')f.directMillis+=briefMs(t);else if(t.relation==='other-app')f.otherMillis+=briefMs(t);}const purpose=briefKnownPurpose(f.event.purpose);if(purpose)purposes.add(purpose);}
  for(const purpose of purposes){if(!purposeUsage.has(purpose))purposeUsage.set(purpose,[]);purposeUsage.get(purpose).push(t);}
 }
 // Older saved focus snapshots may lack parent contexts. Use only their explicit flow records as a fallback.
 for(const f of b.flows||[]){let target=scope.get(String(f.event.id));if(!target)continue;if(!target.steps.length&&(f.steps||f.blocks||[]).length){target.steps=[...(f.steps||f.blocks)];target.end=f.end;target.directMillis=f.directMillis||0;target.otherMillis=f.otherMillis||0;const p=briefKnownPurpose(f.event.purpose);if(p){const list=purposeUsage.get(p)||[],known=new Set(list.map(t=>[t.start,t.end,t.pkg,t.parent].join('|')));for(const t of target.steps){const key=[t.start,t.end,t.pkg,t.parent].join('|');if(!known.has(key)){known.add(key);list.push(t);}}purposeUsage.set(p,list);}}}
 const flows=[...scope.values()].filter(f=>f.steps.length||(f.event.time>=b.start&&f.event.time<b.end)).map(f=>{f.steps.sort((a,b)=>a.start-b.start);f.apps=briefAppTotals(f.steps);f.total=f.steps.reduce((n,t)=>n+briefMs(t),0);return f;}).sort((a,b)=>b.start-a.start);
 const byPurpose=new Map();for(const f of flows){const p=briefKnownPurpose(f.event.purpose);if(!p)continue;if(!byPurpose.has(p))byPurpose.set(p,[]);byPurpose.get(p).push(f);}
 const nativePurposes=new Map((b.purposes||[]).map(p=>[p.purpose,p])),purposes=[];
 for(const [purpose,related] of byPurpose){const steps=purposeUsage.get(purpose)||[],native=nativePurposes.get(purpose)||{},inRange=related.filter(f=>f.event.time>=b.start&&f.event.time<b.end);purposes.push({...native,purpose,count:native.count??inRange.length,priorSelection:!(native.count??inRange.length),steps,apps:briefAppTotals(steps),total:steps.reduce((n,t)=>n+briefMs(t),0)});}
 for(const p of b.purposes||[])if(!byPurpose.has(p.purpose))purposes.push({...p,steps:[],apps:[],total:0});
 purposes.sort((a,b)=>b.total-a.total||(b.count||0)-(a.count||0));
 m={flows,events,byPurpose,purposes,timeline:null};briefModels.set(b,m);return m;
}
function briefPhoneContext(id,m){
 const seen=new Set();let e=m.events.get(String(id));while(e&&!seen.has(String(e.id))){seen.add(String(e.id));if(e.kind==='unlock')return e;e=m.events.get(String(e.parent));}return null;
}
function briefBoundaryRecords(b){
 const periods=b.focus?.periods||[],out=[];
 for(let i=0;i<periods.length;i++){const p=periods[i],before=periods[i-1];if(!p.known||before?.known&&before.focus===p.focus)continue;
  const phase=p.focus?'집중시간':'자유시간',text=p.start===b.start?`기간 시작 · ${phase}`:before&&!before.known?`이력 시작 · ${phase}`:`${phase} 시작`;
  out.push({time:p.start,type:'boundary',text,recordKey:'b'+i});
 }return out;
}
function briefTimelineModel(b){
 const model=briefModel(b);if(model.timeline)return model.timeline;
 const rawUsage=b.timeline||b.intervals||[],ranges=new Map();
 for(const h of b.hours||[]){const date=briefDateKey(h.start,b.timeZone),range=ranges.get(date);if(range)range.end=h.end;else ranges.set(date,{start:h.start,end:h.end});}
 const usage=rawUsage.flatMap(t=>{const slices=[];for(const range of ranges.values()){const start=Math.max(t.start,range.start),end=Math.min(t.end,range.end);if(end>start)slices.push({...t,start,end,ms:end-start,sourceStart:t.start,sourceEnd:t.end});}return slices.length?slices:[t];});
 const events=(b.events||[]).filter(e=>['unlock','app','utility','extend','dismiss','cancel'].includes(e.kind));
 const markers=[...usage.map((t,i)=>({...t,time:t.start,type:'usage',recordKey:'u'+t.start+'-'+encodeURIComponent(t.pkg||'')+'-'+(t.eventId||0)})),...events.map(e=>({...e,type:briefEventType(e),recordKey:'e'+e.id})),...briefBoundaryRecords(b)].sort((a,b)=>a.time-b.time||(a.type==='usage'?1:-1));
 const days=new Map(),locations=new Map(),pendingVisits=new Map();
 const phones=[...model.events.values()].filter(e=>e.kind==='unlock').sort((a,b)=>a.time-b.time||a.id-b.id);let phoneIndex=-1;
 for(const h of b.hours||[]){const key=briefDateKey(h.start,b.timeZone);let day=days.get(key);if(!day){day={key,start:h.start,end:h.end,ms:0,markers:[],nodes:[],phoneGroups:new Map(),spans:[]};days.set(key,day);}day.end=h.end;}
 for(const t of markers){
  const dayKey=briefDateKey(t.time,b.timeZone);let day=days.get(dayKey);
  if(!day){day={key:dayKey,start:t.time,end:t.end||t.time+1,ms:0,markers:[],nodes:[],phoneGroups:new Map(),spans:[]};days.set(dayKey,day);}day.markers.push(t);
  if(t.type==='usage'){day.spans.push(t);day.ms+=briefMs(t);}
  // The phone opening contains subsequent records by time, including recovered/unlinked spans.
  // Explicit app intent remains separate and is never inferred from this containment.
  while(phoneIndex+1<phones.length&&(phones[phoneIndex+1].time<t.time||phones[phoneIndex+1].time===t.time&&(t.type==='usage'||phones[phoneIndex+1].id<=(t.id||0))))phoneIndex++;
  const phone=t.kind==='unlock'?t:phones[phoneIndex];
  let owner=day.nodes,parents=['brief-record-day-'+dayKey];
  if(phone){let group=day.phoneGroups.get(String(phone.id));if(!group){group={kind:'phone',event:phone,time:Math.max(phone.time,day.start),nodes:[],id:'brief-phone-'+dayKey+'-'+phone.id};day.phoneGroups.set(String(phone.id),group);day.nodes.push(group);}owner=group.nodes;parents.push(group.id);}
  if(t.kind==='unlock'){locations.set(t.recordKey,parents);continue;}
  if(t.type==='usage'||t.kind==='app'){
   const choice=t.kind==='app'?t:model.events.get(String(t.parent))?.kind==='app'?model.events.get(String(t.parent)):model.events.get(String(t.eventId));
   const appChoice=choice?.kind==='app'&&(!phone||choice.time>=phone.time)&&(!choice.pkg||choice.pkg===t.pkg)?choice:null;
   const pkg=t.pkg||t.target||'',label=t.label||b.apps?.find(a=>a.pkg===pkg)?.label||pkg;
   const visitKey=appChoice?dayKey+'|'+appChoice.id+'|'+pkg:null;
   const pending=visitKey?pendingVisits.get(visitKey):null;
   let tail=owner.length-1;while(tail>=0&&owner[tail].kind==='event'&&owner[tail].record.type==='boundary')tail--;
   const previous=pending?.owner===owner&&!pending.visit.steps.length?pending.visit:owner[tail],sameChoice=String(previous?.choice?.id||'')===String(appChoice?.id||'');
   const canJoin=t.type==='usage'&&previous?.kind==='visit'&&previous.pkg===pkg&&sameChoice&&(!previous.steps.length||t.start<=previous.end||t.technicalResume&&t.start-previous.end<=1500);
   let visit=canJoin?previous:{kind:'visit',pkg,label,choice:appChoice,time:t.time,end:t.time,steps:[],id:'brief-visit-'+dayKey+'-'+t.recordKey};
   if(!canJoin)owner.push(visit);if(t.type==='usage'){visit.steps.push(t);visit.end=t.end;visit.ms=(visit.ms||0)+briefMs(t);if(visitKey)pendingVisits.delete(visitKey);}else{visit.event=t;if(visitKey)pendingVisits.set(visitKey,{visit,owner});}
   locations.set(t.recordKey,[...parents,visit.id]);
  }else{owner.push({kind:'event',time:t.time,record:t});locations.set(t.recordKey,parents);}
 }
 for(const day of days.values()){day.spans=usage.filter(t=>t.start<day.end&&t.end>day.start);day.ms=day.spans.reduce((n,t)=>n+Math.max(0,Math.min(t.end,day.end)-Math.max(t.start,day.start)),0);}
 const counts=new Map();for(const t of markers)counts.set(t.type,(counts.get(t.type)||0)+1);
 model.timeline={markers,days:[...days.values()].filter(d=>d.markers.length).sort((a,b)=>a.start-b.start),locations,counts};return model.timeline;
}
function briefVisitView(v,b){
 const purpose=briefKnownPurpose(v.choice?.purpose),summary=`<span class="brief-visit-heading"><strong>${esc(v.label)}</strong><time>${briefWhen(v.time,b)}${v.end>v.time?'–'+briefClock(v.end,b.timeZone):''}</time>${purpose?`<span class="brief-app-purpose"><small>앱 진입 목적</small>${esc(purpose)}</span>`:''}</span><b>${v.ms?briefNumber(v.ms):'<span class="brief-no-observation">사용 관측 없음</span>'}</b>`;
 return briefLazyDetails(v.id,summary,()=>`<div class="brief-visit-records">${v.steps.map(t=>briefRecordView({...t,hidePurpose:true,hideApp:true},b)).join('')||'<p class="small muted">연결된 앱 사용 구간 없음</p>'}</div>`,'brief-visit-unit');
}
function briefPhoneView(group,b){
 const visits=group.nodes.filter(n=>n.kind==='visit'),total=visits.reduce((n,v)=>n+(v.ms||0),0),purpose=briefKnownPurpose(group.event.purpose);
 const summary=`<span class="brief-phone-heading"><time>${briefWhen(group.event.time,b)}${group.event.time<b.start?' · 기간 이전 선택':''}</time><strong>${esc(purpose)}</strong><small>휴대폰 시작 목적 · ${visits.length}번 앱 진입</small></span><b>${total?briefNumber(total):'<span class="brief-no-observation">사용 관측 없음</span>'}</b>`;
 return briefLazyDetails(group.id,summary,()=>`<div class="brief-phone-children">${(briefSort.timeline==='desc'?[...group.nodes].reverse():group.nodes).map(n=>n.kind==='visit'?briefVisitView(n,b):briefRecordView(n.record,b)).join('')}</div>`,'brief-phone-unit');
}
function briefTimelineView(b){
 const m=briefTimelineModel(b),desc=briefSort.timeline==='desc',options=[['all','전체'],['usage','앱 사용'],['choices','목적 선택'],['cancels','취소'],['extensions','연장'],['tools','도구'],['boundary','집중·자유시간']];
 const filters=`<div class="brief-filters brief-record-filters" aria-label="기록 유형">${options.map(([key,label])=>`<button data-brief-record-filter="${key}" class="${briefRecordFilter===key?'selected':''}" aria-pressed="${briefRecordFilter===key}">${label}<small>${key==='all'?m.markers.length:m.counts.get(key)||0}</small></button>`).join('')}</div>`;
 const days=desc?[...m.days].reverse():m.days;
 const groups=days.map(day=>{
  const items=day.markers.filter(t=>briefRecordFilter==='all'||t.type===briefRecordFilter);if(!items.length)return '';
  const summary=`<span>${briefDateLabel(day.start,b.timeZone)}<small>${items.length}개 기록 · 외부 앱 ${briefDuration(day.ms)}</small></span>${icon('arrow')}`;
  const filter=briefRecordFilter;
  return briefLazyDetails('brief-record-day-'+day.key,summary,()=>{
   const track=`<div class="brief-day-track" aria-label="실제 사용 구간, 빈 곳은 외부 앱 관측 없음">${day.spans.map(t=>`<i style="left:${Math.max(0,(t.start-day.start)/(day.end-day.start)*100)}%;width:${Math.max(0,(Math.min(t.end,day.end)-Math.max(t.start,day.start))/(day.end-day.start)*100)}%"></i>`).join('')}</div><div class="brief-chart-axis"><span>${briefClock(day.start,b.timeZone)}</span><span>${briefClock(day.end,b.timeZone)}</span></div>`;
   if(filter==='all')return track+(desc?[...day.nodes].reverse():day.nodes).map(n=>n.kind==='phone'?briefPhoneView(n,b):n.kind==='visit'?briefVisitView(n,b):briefRecordView(n.record,b)).join('');
   const buckets=new Map();for(const t of(desc?[...items].reverse():items)){const hour=briefClock(t.time,b.timeZone).split(':')[0];if(!buckets.has(hour))buckets.set(hour,[]);buckets.get(hour).push(t);}
   return track+[...buckets].map(([hour,rows])=>briefLazyDetails('brief-record-'+day.key+'-'+hour,`<strong>${hour}시</strong><span>${rows.length}개 기록</span>`,()=>`<div class="brief-record-list">${rows.map(t=>briefRecordView(t,b)).join('')}</div>`,'brief-record-hour')).join('');
  },'brief-day-unit');
 }).join('');
 return filters+'<p class="small muted">'+(briefRecordFilter==='all'?'휴대폰 목적 → 앱 → 앱 진입 목적 순서로 확인합니다.':'날짜와 시간대를 펼쳐 확인합니다.')+' 사용 막대의 빈 곳은 미사용 또는 미관측 구간입니다.</p>'+ (groups||'<p class="empty">선택한 유형의 기록 없음</p>');
}
function briefTimelineSection(b){return `<details class="card brief-timeline" id="briefTimeline"><summary><span>시간선과 행동 기록</span>${briefSortButton('timeline')}</summary><div id="briefTimelineBody">${briefTimelineView(b)}</div></details>`;}
function briefAppsSection(b){
 const hasFocus=Number(b.focus?.knownMillis)>0,field=!hasFocus||briefAppFilter==='all'?'ms':briefAppFilter,apps=[...(b.apps||[])].sort((a,b)=>(briefSort.apps==='desc'?-1:1)*((a[field]||0)-(b[field]||0))||a.label.localeCompare(b.label,'ko')),sum=apps.reduce((n,a)=>n+(a[field]||0),0);
 const rows=briefFold('apps',apps.filter(a=>a[field]>0),a=>`<div class="usage-row"><div class="row"><strong>${esc(a.label)}</strong><span>${briefNumber(a[field])} <small>${briefPercent(a[field],sum)}%</small></span></div><div class="meter"><span style="width:${sum?a[field]/sum*100:0}%"></span></div><p class="small muted">${field==='ms'?(hasFocus?`집중 ${briefDuration(a.focus)} · 자유 ${briefDuration(a.free)}`:'시간대 이력 없음'):`전체 ${briefDuration(a.ms)}`}${a.unknown?' · 시간대 미확인 '+briefDuration(a.unknown):''}</p></div>`);
 return `<section class="section" id="brief-apps"><div class="sectionhead"><h2>앱 사용 구성</h2>${briefSortButton('apps')}</div><div class="brief-filters">${[['all','전체'],['focus','집중시간'],['free','자유시간']].map(([key,label])=>`<button data-brief-filter="${key}" class="${(field==='ms'?'all':field)===key?'selected':''}" aria-pressed="${(field==='ms'?'all':field)===key}" ${!hasFocus&&key!=='all'?'disabled':''}>${label}</button>`).join('')}</div><p class="small muted brief-filter-total">${field==='ms'?'전체':field==='focus'?'집중시간':'자유시간'} ${briefDuration(sum)} 기준</p>${rows||'<p class="empty">해당 시간대의 사용 기록 없음</p>'}</section>`;
}
function briefHourSelection(b,selected){
 const spans=(b.timeline||[]).filter(t=>t.start<selected.end&&t.end>selected.start).map(t=>({...t,ms:Math.max(0,Math.min(t.end,selected.end)-Math.max(t.start,selected.start))}));
 return `<div class="row"><strong>${briefRange(selected.start,selected.end,b)}</strong><b>${briefPrecise(selected.ms)}</b></div><div class="brief-app-totals">${briefAppTotals(spans).map(a=>`<div><span>${esc(a.label)}</span><b>${briefPrecise(a.ms)}</b></div>`).join('')||'<p class="small muted">외부 앱 사용 관측 없음</p>'}</div><button class="textbtn" data-brief-jump="${selected.start}">시간선에서 보기</button>`;
}
function briefSelectHour(at){
 const b=briefData,selected=b?.hours?.find(h=>h.start===at);if(!selected)return;briefSelectedHour=at;
 const section=$('#brief-hours');for(const el of section.querySelectorAll('.brief-hour-cell')){const active=Number(el.dataset.briefHour)===at;el.classList.toggle('selected',active);el.setAttribute('aria-pressed',String(active));}
 const panel=section.querySelector('.brief-hour-selection');panel.innerHTML=briefHourSelection(b,selected);bind(panel);panel.scrollIntoView({block:'nearest',behavior:'smooth'});
}
function briefJump(at){
 const b=briefData;if(!b)return;
 if(briefRecordFilter!=='all'){briefRecordFilter='all';briefReplaceSection('briefTimeline',briefTimelineSection(b));}
 const timeline=$('#briefTimeline');timeline.open=true;const m=briefTimelineModel(b);
 const target=m.markers.find(t=>t.type==='usage'&&t.start<=at&&t.end>at)||m.markers.find(t=>t.time>=at)||m.markers.at(-1);if(!target)return;
 for(const id of m.locations.get(target.recordKey)||[]){const detail=document.getElementById(id);if(detail){detail.open=true;briefHydrate(detail);}}
 const candidates=[...timeline.querySelectorAll('[data-timeline-start]')];
 const row=candidates.find(el=>Number(el.dataset.timelineStart)===target.time)||document.getElementById((m.locations.get(target.recordKey)||[]).at(-1));row?.scrollIntoView({block:'center',behavior:'smooth'});
}
function briefClock(at,zone){return briefFormat(at,zone,'clock');}
function briefDateKey(at,zone){return briefFormat(at,zone,'date');}
function briefDateLabel(at,zone){return briefFormat(at,zone,'label');}
function briefExactTime(at,b){return briefFormat(at,b.timeZone,'exact');}
function briefWhen(at,b){return `${b.days===7?briefDateLabel(at,b.timeZone)+' · ':''}${briefClock(at,b.timeZone)}`;}
function briefRange(start,end,b){return `${briefWhen(start,b)}–${briefDateKey(start,b.timeZone)!==briefDateKey(Math.max(start,end-1),b.timeZone)?briefWhen(end,b):briefClock(end,b.timeZone)}`;}
function briefPrecise(ms){if(ms>0&&ms<1000)return '1초 미만';const seconds=Math.floor(Math.max(0,ms||0)/1000);return seconds<60?`${seconds}초`:`${Math.floor(seconds/60)}분${seconds%60?' '+seconds%60+'초':''}`;}
function briefNumber(ms){return `<span title="${briefPrecise(ms)}">${briefDuration(ms)}</span>`;}
function briefParts(parts,label){
 const values=parts.filter(p=>p.ms>0),total=values.reduce((n,p)=>n+p.ms,0);
 return `<div class="brief-parts" role="img" aria-label="${esc(label+': '+parts.map(p=>p.label+' '+briefPrecise(p.ms)).join(', '))}">${values.map(p=>`<i class="${p.tone||''}" style="width:${p.ms/total*100}%"></i>`).join('')}</div><div class="brief-legend">${parts.filter(p=>p.label||p.ms>0).map(p=>`<span><i class="${p.tone||''}"></i>${esc(p.label)} <b>${briefDuration(p.ms)}</b>${total?`<small>${briefPercent(p.ms,total)}%</small>`:''}</span>`).join('')}</div>`;
}
function briefAppTotals(steps){
 const byApp=new Map();for(const t of steps){const key=t.pkg||t.label,app=byApp.get(key)||{label:t.label||t.pkg||'앱 미확인',ms:0};app.ms+=briefMs(t);byApp.set(key,app);}
 return [...byApp.values()].sort((a,b)=>b.ms-a.ms);
}
function briefFlowUnits(b){
 const units=(b.flows||[]).map(f=>({...f,steps:[...(f.steps||f.blocks||[])].sort((a,b)=>a.start-b.start)})),ids=new Set(units.map(f=>String(f.event.id)));
 for(const event of b.events||[])if(['unlock','app'].includes(event.kind)&&!ids.has(String(event.id)))units.push({event,start:event.time,end:event.time,steps:[],gaps:[],directMillis:0,otherMillis:0});
 return units.sort((a,b)=>b.start-a.start);
}
function briefFlowView(f,b){
 const event=f.event||{},steps=f.steps||[],total=f.total??steps.reduce((n,t)=>n+briefMs(t),0),apps=f.apps||briefAppTotals(steps),key=event.id??f.start;
 const summary=`<span class="brief-flow-heading"><time>${briefWhen(f.start,b)}${f.start<b.start?' · 기간 이전 선택':''}</time><strong>${esc(briefKnownPurpose(event.purpose))}</strong><small>${apps.length?apps.slice(0,3).map(a=>esc(a.label)).join(' · ')+(apps.length>3?` 외 ${apps.length-3}개`:''):'연결된 앱 사용 기록 없음'}</small></span><span class="brief-flow-total">${total?briefNumber(total):'<span class="brief-no-observation">사용 관측 없음</span>'}<small>${apps.length}개 앱 · ${steps.length}개 구간</small></span>`;
 return briefLazyDetails('brief-flow-'+key,summary,()=>{
  const focused=steps.filter(t=>t.focusState==='focus').reduce((n,t)=>n+briefMs(t),0),free=steps.filter(t=>t.focusState==='free').reduce((n,t)=>n+briefMs(t),0),unknown=Math.max(0,total-focused-free);let last=Math.max(b.start,f.start);
  const sequence=steps.map(t=>{
   const gap=t.start>last?`<div class="brief-sequence-gap"><time>${briefClock(last,b.timeZone)}–${briefClock(t.start,b.timeZone)}</time><span>선택 기록 사이 간격 · ${briefPrecise(t.start-last)}</span></div>`:'';last=Math.max(last,t.end);
   const relation=t.relation==='direct'?'대상 앱':t.relation==='other-app'?'다른 앱':'';
   return gap+`<div class="brief-sequence-step"><time>${b.days===7?briefDateLabel(t.start,b.timeZone)+'<br>':''}${briefExactTime(t.start,b)}<small>–${briefDateKey(t.start,b.timeZone)!==briefDateKey(t.end,b.timeZone)?briefDateLabel(t.end,b.timeZone)+' ':''}${briefExactTime(t.end,b)}</small></time><div><strong>${esc(t.label)}</strong><span>${[relation,t.focusState==='focus'?'집중시간':t.focusState==='free'?'자유시간':'시간대 이력 없음'].filter(Boolean).join(' · ')}</span>${t.source==='usage-access'?'<small>Android 사용 정보로 보완</small>':''}${t.sourceStart!==undefined&&(t.sourceStart!==t.start||t.sourceEnd!==t.end)?`<small>원본 구간 ${briefRange(t.sourceStart,t.sourceEnd,b)}</small>`:''}${t.excludedTransitionMillis?`<small>전환 ${briefPrecise(t.excludedTransitionMillis)} 제외</small>`:''}</div><b>${briefPrecise(briefMs(t))}</b></div>`;
  }).join('');
  return `<div class="brief-flow-body">${total?briefParts([{label:'집중시간',ms:focused},{label:'자유시간',ms:free,tone:'free'},...(unknown?[{label:'시간대 이력 없음',ms:unknown,tone:'unknown'}]:[])],'선택 기록에 속한 사용'):''}${apps.length?`<div class="brief-app-totals">${apps.map(a=>`<div><span>${esc(a.label)}</span><b>${briefPrecise(a.ms)}</b></div>`).join('')}</div>`:''}<h3>실제 사용 순서</h3>${sequence||'<p class="small muted">이 선택에 연결된 외부 앱 사용은 관측되지 않았습니다.</p>'}${(f.newChoices||[]).length?`<p class="small muted">구간 안 새 목적 선택 ${f.newChoices.length}회 · 시간선에서 확인</p>`:''}<button class="textbtn" data-brief-jump="${f.start}">해당 시간선 보기 ${icon('arrow')}</button></div>`;
 },'brief-flow-unit');
}
function briefFlowGroups(flows,b){
 const days=new Map();for(const f of flows){const key=briefDateKey(f.start,b.timeZone);if(!days.has(key))days.set(key,[]);days.get(key).push(f);}
 return [...days].map(([key,items])=>`<div class="brief-record-day"><h3>${briefDateLabel(items[0].start,b.timeZone)}<span>${items.length}번 선택</span></h3>${briefFold('flows-'+key,items,f=>briefFlowView(f,b),4)}</div>`).join('')||'<p class="empty">목적 선택 기록 없음</p>';
}
function briefPurposeView(p,flows,b,index){
 const id='brief-purpose-'+encodeURIComponent(p.purpose),related=briefModel(b).byPurpose.get(p.purpose)||[];
 const summary=`<span><strong>${esc(briefKnownPurpose(p.purpose))}</strong><small>${p.priorSelection?'기간 이전 선택':(p.count||0)+'번 선택'} · ${p.apps.length}개 앱</small></span><b>${p.total?briefNumber(p.total):'<span class="brief-no-observation">사용 관측 없음</span>'}<small>선택 기록에 속한 사용</small></b>`;
 return briefLazyDetails(id,summary,()=>{
  const focused=p.steps.filter(t=>t.focusState==='focus').reduce((n,t)=>n+briefMs(t),0),free=p.steps.filter(t=>t.focusState==='free').reduce((n,t)=>n+briefMs(t),0),unknown=Math.max(0,p.total-focused-free);
  return `${p.apps.length?`<div class="brief-app-totals brief-purpose-apps">${p.apps.map(a=>`<div><span>${esc(a.label)}</span><b>${briefPrecise(a.ms)}</b></div>`).join('')}</div>`:'<p class="small muted">이 선택 기록에 속한 외부 앱 사용은 관측되지 않았습니다.</p>'}${p.total?briefParts([{label:'집중시간',ms:focused},{label:'자유시간',ms:free,tone:'free'},...(unknown?[{label:'시간대 이력 없음',ms:unknown,tone:'unknown'}]:[])],'목적 선택 기록에 속한 사용'):''}<h3 class="brief-occurrence-heading">선택 시각</h3>${briefFold('purpose-choices-'+encodeURIComponent(p.purpose),related,f=>`<div class="brief-purpose-choice"><div><time>${briefWhen(f.event.time,b)}</time><small>${f.event.kind==='unlock'?'휴대폰 선택':'앱 선택'}${f.event.time<b.start?' · 기간 이전':''}</small></div><b>${f.total?briefNumber(f.total):'사용 관측 없음'}</b><button class="textbtn" data-brief-jump="${Math.max(b.start,f.event.time)}">시간선</button></div>`,5)}<p class="small muted">기록된 선택 관계에 속한 앱 사용입니다. 목적 달성 여부를 뜻하지 않으며, 휴대폰·앱 목적이 다른 행은 같은 구간을 포함할 수 있습니다.</p>`;
 },'brief-purpose-unit');
}
function briefHoursView(b){
 const hours=b.hours||[],days=new Map();for(const h of hours){const key=briefDateKey(h.start,b.timeZone);if(!days.has(key))days.set(key,[]);days.get(key).push(h);}
 if(!hours.length)return '<p class="empty">시간대별 기록 없음</p>';
 const max=Math.max(1,...hours.map(h=>h.ms||0)),peak=hours.reduce((a,h)=>h.ms>a.ms?h:a,hours[0]);
 const selected=hours.find(h=>h.start===briefSelectedHour)||peak;
 const cell=h=>`<button type="button" data-brief-hour="${h.start}" class="brief-hour-cell ${selected.start===h.start?'selected':''} ${h.ms?'has-use':''}" style="--level:${h.ms?Math.max(.15,h.ms/max):0}" aria-pressed="${selected.start===h.start}" aria-label="${esc(briefRange(h.start,h.end,b)+' · '+briefPrecise(h.ms))}">${b.days===7?'':`<i style="height:${h.ms?Math.max(2,h.ms/max*100):0}%"></i>`}</button>`;
 const chart=b.days===7?`<div class="brief-heat-scroll"><div class="brief-heat"><div class="brief-heat-axis"><span></span>${[0,6,12,18].map(h=>`<span>${h}시</span>`).join('')}</div>${[...days].map(([key,items])=>`<div class="brief-heat-row"><span>${briefDateLabel(items[0].start,b.timeZone)}<small>${briefDuration(items.reduce((n,h)=>n+h.ms,0))}</small></span><div>${items.map(cell).join('')}</div></div>`).join('')}</div></div>`:`<div class="brief-hour-chart">${hours.map(cell).join('')}</div><div class="brief-chart-axis"><span>${briefClock(hours[0].start,b.timeZone)}</span><span>${briefClock(hours[Math.floor(hours.length/2)].start,b.timeZone)}</span><span>${briefClock(hours.at(-1).end,b.timeZone)}</span></div>`;
 const dayTotals=[...days.values()].map(items=>({start:items[0].start,ms:items.reduce((n,h)=>n+h.ms,0)})),busyDay=dayTotals.reduce((a,d)=>d.ms>a.ms?d:a,dayTotals[0]);
 return `<div class="brief-pattern-lead"><div><span>${b.days===7?'가장 사용이 많았던 날':'가장 사용이 많았던 시간'}</span><strong>${(b.days===7?busyDay.ms:peak.ms)>0?(b.days===7?briefDateLabel(busyDay.start,b.timeZone):briefClock(peak.start,b.timeZone)+'–'+briefClock(peak.end,b.timeZone)):'관측된 사용 없음'}</strong></div><b>${briefDuration(b.days===7?busyDay.ms:peak.ms)}</b></div>${chart}<p class="small muted brief-chart-note">${b.days===7?'진할수록 관측 사용량이 많습니다.':'막대 높이는 시간별 관측 사용량입니다.'} 시간대를 누르면 앱별 내역을 볼 수 있습니다.</p><div class="brief-hour-selection" aria-live="polite">${briefHourSelection(b,selected)}</div>${briefLazyDetails('brief-hours-values','모든 시간대의 수치',()=>[...days].map(([key,items])=>briefLazyDetails('brief-hours-'+key,`${briefDateLabel(items[0].start,b.timeZone)} · ${briefDuration(items.reduce((n,h)=>n+h.ms,0))}`,()=>`<div class="brief-hour-values">${items.map(h=>`<button data-brief-hour="${h.start}"><time>${briefClock(h.start,b.timeZone)}–${briefClock(h.end,b.timeZone)}</time><b>${briefPrecise(h.ms)}</b></button>`).join('')}</div>`)).join(''))}`;
}
function briefEventType(e){return ['unlock','app'].includes(e.kind)?'choices':['dismiss','cancel'].includes(e.kind)?'cancels':e.kind==='extend'?'extensions':'tools';}
function briefEventLabel(e){return e.kind==='unlock'?'휴대폰 목적 선택':e.kind==='app'?'앱 목적 선택':e.kind==='extend'?'사용 연장':e.kind==='utility'?'로키 도구':'사용 취소';}
function briefRecordView(t,b){
 if(t.type==='usage'){
  const purpose=t.hidePurpose?'':briefKnownPurpose(t.purpose),relation=t.relation==='direct'?'대상 앱':t.relation==='other-app'?'다른 앱':'';
  return `<div class="brief-record usage" data-timeline-start="${t.start}" data-timeline-end="${t.end}"><time>${briefExactTime(t.start,b)}<small>–${briefDateKey(t.start,b.timeZone)!==briefDateKey(t.end,b.timeZone)?briefDateLabel(t.end,b.timeZone)+' ':''}${briefExactTime(t.end,b)}</small></time><div>${t.hideApp?'':`<strong>${esc(t.label)}</strong>`}${purpose?`<span>${esc(purpose)}</span>`:''}<small>${[t.focusState==='focus'?'집중시간':t.focusState==='free'?'자유시간':'시간대 이력 없음',relation,t.source==='usage-access'?'Android 정보로 보완':t.source==='legacy'?'관측 출처 미확인':''].filter(Boolean).join(' · ')}</small>${t.sourceStart!==undefined&&(t.sourceStart!==t.start||t.sourceEnd!==t.end)?`<small>원본 구간 ${briefRange(t.sourceStart,t.sourceEnd,b)}</small>`:''}${t.excludedTransitionMillis?`<small>전환 ${briefPrecise(t.excludedTransitionMillis)} 제외</small>`:''}</div><b>${briefPrecise(briefMs(t))}</b></div>`;
 }
 if(t.type==='boundary')return `<div class="brief-record boundary" data-timeline-start="${t.time}"><time>${briefExactTime(t.time,b)}</time><div><strong>${esc(t.text)}</strong></div></div>`;
 return `<div class="brief-record action" data-timeline-start="${t.time}"><time>${briefExactTime(t.time,b)}</time><div><small>${briefEventLabel(t)}</small><strong>${esc(briefKnownPurpose(t.purpose||t.label||t.name))}</strong>${t.label||t.pkg||t.target?`<span>${esc(t.label||b.apps?.find(a=>a.pkg===(t.target||t.pkg))?.label||t.target||t.pkg)}</span>`:''}</div></div>`;
}
function briefComparisonView(b){
 if(!b.previous&&!b.comparison)return '';
 const records=b.comparison?.records||[],enough=b.complete&&b.quality?.comparisonEligible&&records.length>=5;
 const weekly=b.days===7&&b.complete&&b.quality?.comparisonEligible&&b.previous?.comparisonEligible;
 const baseline=enough?records.reduce((n,d)=>n+d.total,0)/records.length:weekly?b.previous.total:null;
 const label=enough?`최근 ${records.length}일 평균`:b.days===7?'이전 7일':'이전 날짜';
 const before=baseline??(b.previous?.available?b.previous.total:null),max=Math.max(1,b.total||0,before||0),diff=baseline===null?null:(b.total||0)-baseline;
 return `<section class="section" id="brief-comparison"><h2>기간별 비교</h2>${diff!==null?`<div class="brief-change"><strong>${diff===0?'비슷한 사용량':`${diff>0?'↑':'↓'} ${briefDuration(Math.abs(diff))}`}</strong><span>${label}보다 ${diff>0?'늘었어요':diff<0?'줄었어요':'차이가 없습니다'}${baseline>0&&diff!==0?' · '+Math.round(Math.abs(diff)/baseline*100)+'%':''}</span></div>`:'<p class="small muted">비교 가능한 관측이 부족해 변화 해석을 생략합니다.</p>'}<div class="brief-compare-bars">${[[label,before,'free'],['이번 기간',b.total||0,'']].map(([name,ms,tone])=>`<div><span>${name}</span><div class="meter"><span class="${tone}" style="width:${ms===null?0:ms/max*100}%"></span></div><b>${ms===null?'기록 없음':briefDuration(ms)}</b></div>`).join('')}</div>${enough&&b.previous?`<p class="small muted">이전 날짜 관측 ${b.previous.available?briefDuration(b.previous.total):'기록 없음'}</p>`:''}<p class="small muted">관측된 사용량을 비교합니다. 기록 공백을 0분으로 채우거나 실제 집중 여부를 추정하지 않습니다.</p>${records.length?`<details id="brief-comparison-days"><summary>비교에 사용한 날짜 ${records.length}일</summary><div class="brief-app-totals">${records.map(d=>`<div><time>${esc(d.date)}</time><b>${briefPrecise(d.total)}</b></div>`).join('')}</div></details>`:''}</section>`;
}
function briefHighlightsView(b){
 return (b.highlights||[]).slice(0,3).map(h=>{
  const names={'focus-app':'집중시간의 주요 앱','focus-boundary-change':'집중시간 전후 변화','other-app':'목적 선택 이후의 사용',cancel:'취소 이후 기록',change:'최근 사용량 변화','time-change':'시간대 비중 변화'};
  const isRange=['change','time-change'].includes(h.type),flow=(b.flows||[]).find(f=>f.event.id===h.eventId);
  const detail=h.type==='focus-app'&&b.focus?.observedMillis?`집중시간 사용의 ${briefPercent(h.ms,b.focus.observedMillis)}%`:flow?`다른 앱 ${briefDuration(flow.otherMillis)} · 대상 앱 ${briefDuration(flow.directMillis)}`:'';
  return `<button class="brief-highlight" data-brief-jump="${h.start}" aria-label="${esc(h.text)} · 시간선 보기"><span class="brief-highlight-type">${names[h.type]||'주요 관측 기록'}</span><strong>${esc(h.text)}</strong>${detail?`<small>${detail}</small>`:''}<time>${isRange?'선택한 기간':briefRange(h.start,h.end,b)}</time>${icon('arrow')}</button>`;
 }).join('');
}
function renderBrief(b){
 const area=$('#briefContent');if(!area)return;
 const opened=new Set([...area.querySelectorAll('details[open]')].map(d=>d.id)),position=window.scrollY;
 const signature=briefModel(b).signature??=JSON.stringify([b.start,b.days,b.timeZone,b.timeline,b.intervals,b.events,b.purposeContexts,b.flows,b.purposes,b.hours,b.focus?.periods,b.focusResults]);
 if(signature!==briefRecordSignature){briefRecordSignature=signature;briefViewRevision++;briefLazyViews.clear();}
 const focus=b.focus||{},hasFocus=Number(focus.knownMillis)>0,flows=briefModel(b).flows,highlights=briefHighlightsView(b);
 const active=(b.focusResults||[]).find(w=>!w.complete&&w.start<=Date.now()&&w.end>Date.now());
 const stateLine=b.complete?'기록 종료':`진행 중 · ${!hasFocus?'집중시간 이력 없음':active?'집중시간 · '+briefDuration(active.end-Date.now())+' 남음':'자유시간'}`;
 const purposeRows=briefModel(b).purposes;
 const html=`<section class="brief-overview" id="brief-overview">${b.kind==='focus'?'<h1>집중시간 결과</h1>':''}<p class="brief-state">${esc(stateLine)}</p><div class="statgrid"><div class="stat"><span>외부 앱 사용</span><b>${briefStatTime(b.total)}</b></div><div class="stat"><span>집중시간 중 사용</span><b>${hasFocus?briefStatTime(focus.observedMillis):'—<small>기록 없음</small>'}</b></div><div class="stat"><span>사용 취소</span><b>${b.cancels??0}<small>회</small></b></div></div><p class="small muted brief-metric-note">${hasFocus?'집중시간은 설정한 시간대 기준입니다.':'집중시간 이력이 없어 시간대별로 구분할 수 없습니다.'}${focus.unknownMillis&&hasFocus?' 일부 시간대 이력 없음':''}</p></section>
 ${highlights?`<section class="section" id="brief-highlights"><div class="sectionhead"><h2>눈여겨볼 기록</h2></div><div class="brief-highlights">${highlights}</div></section>`:''}
 <section class="section brief-pattern" id="brief-hours"><div class="sectionhead"><h2>시간대별 사용</h2><span class="small muted">${b.days===7?'날짜 × 시간':'시간별 분포'}</span></div>${briefHoursView(b)}</section>
 <section class="section" id="brief-focus"><div class="sectionhead"><h2>집중시간과 자유시간</h2></div>${hasFocus?briefParts([{label:'집중시간',ms:focus.observedMillis||0},{label:'자유시간',ms:focus.freeObservedMillis||0,tone:'free'},{label:'시간대 미확인',ms:focus.unknownObservedMillis||0,tone:'unknown'}],'외부 앱 사용 시간대'):'<p class="small muted">이 기간에는 집중시간 이력이 없습니다.</p>'}<details id="brief-focus-policy"><summary>집중시간 중 앱 관리 구성</summary>${hasFocus?briefParts([{label:'관리 앱',ms:focus.managedAppMillis||0},{label:'비관리 앱',ms:focus.unmanagedAppMillis||0,tone:'free'},{label:'관리 여부 미확인',ms:focus.unknownAppPolicyMillis||0,tone:'unknown'}],'집중시간 중 앱 관리 구성'):''}<div class="brief-facts"><div><span>설정된 집중시간</span><b>${hasFocus?briefDuration(focus.configuredMillis):'기록 없음'}</b></div><div><span>집중시간 중 목적 선택</span><b>${focus.choices||0}회</b></div><div><span>집중시간 중 사용 취소</span><b>${focus.cancels||0}회</b></div></div></details>${(b.focusResults||[]).length?`<details id="brief-focus-windows"><summary>집중시간별 종료 결과</summary>${b.focusResults.map(w=>`<div class="brief-window"><span>${briefRange(w.start,w.end,b)}</span>${w.complete?`<button class="textbtn" data-brief-focus="${esc(w.key)}">결과 보기</button>`:'<small>진행 중</small>'}</div>`).join('')}</details>`:''}</section>
 ${briefAppsSection(b)}
 <section class="section" id="brief-purposes"><div class="sectionhead"><h2>목적과 연결된 사용</h2><strong class="brief-count">${(b.unlocks||0)+(b.appStarts||0)}번 선택</strong></div>${briefFold('purposes',purposeRows,(p,i)=>briefPurposeView(p,flows,b,i),5)||'<p class="empty">목적 선택 기록 없음</p>'}<details id="brief-purpose-counts"><summary>선택 횟수 상세</summary><div class="brief-facts">${[['휴대폰',b.unlocks],['관리 앱',b.appStarts],['명시적 무목적',b.aimless],['같은 목적 반복',b.repeatedPurposeChoices],['연장',b.extensions]].map(([label,count])=>`<div><span>${label}</span><b>${count||0}회</b></div>`).join('')}</div></details></section>
 ${briefComparisonView(b)}
 <div class="brief-records-heading" id="brief-records-heading"><h2>구체적인 사용 기록</h2><p>목적 선택별 흐름과 날짜별 시간선</p></div>
 ${briefLazyDetails('brief-flows',`<span>목적 선택 후 사용 흐름</span><small>${flows.length}건</small>`,()=>briefFlowGroups(flows,b),'card')}
 ${briefTimelineSection(b)}
 <details class="card" id="brief-quality"><summary>기록 범위와 관측 정보</summary><p class="small">${esc(b.quality?.note||'')}</p><p class="small">집중시간은 설정된 시간대이며 실제 집중 여부를 뜻하지 않습니다. 현재 설정을 과거 기록에 적용하지 않습니다.</p><p class="small">목적 선택과 연결 앱 사용은 목적 달성 여부를 나타내지 않습니다. 다른 앱은 같은 선택에 연결된 앱입니다.</p><div class="brief-facts"><div><span>시각 기준</span><b>${esc(b.timeZone||'기기 시간대')}</b></div><div><span>서비스·일정 실행 관측</span><b>${(focus.observations||[]).length}회</b></div><div><span>로키 내부 화면 · 합계 제외</span><b>${briefDuration(b.internalMillis||0)}</b></div><div><span>사전 도구 · 합계 제외</span><b>${briefDuration(b.toolMillis||0)}</b></div><div><span>Android 정보로 보완 · 목적 미확인</span><b>${briefDuration(b.recovered||0)}</b></div></div><p class="small">관측 시점 사이의 연속 작동은 보장하지 않습니다. 집중시간 이력 시작 ${focus.reliableSince?esc(new Date(focus.reliableSince).toLocaleString('ko-KR',{timeZone:b.timeZone})):'기록 없음'}</p>${!b.usageAccess?'<p class="small">사용 정보 접근이 꺼져 있어 중단 구간을 보완할 수 없습니다.</p>':''}${b.finalizedAt?`<p class="small">종료 결과 확정 ${esc(new Date(b.finalizedAt).toLocaleString('ko-KR',{timeZone:b.timeZone}))}</p>`:''}</details>`;
 if(area.briefMarkup===html)return;area.briefMarkup=html;
 const template=document.createElement('template');template.innerHTML=html;
 const old=new Map([...area.children].map((node,i)=>[node.briefKey||node.id||'section-'+i,node]));
 [...template.content.children].forEach((candidate,i)=>{
  const key=candidate.id||'section-'+i,previous=old.get(key),markup=candidate.outerHTML;old.delete(key);
  let node=previous;if(!node||node.briefMarkup!==markup){node=candidate;node.briefMarkup=markup;node.briefKey=key;}
  if(area.children[i]!==node)area.insertBefore(node,area.children[i]||null);
  if(previous&&previous!==node)previous.remove();
 });for(const node of old.values())node.remove();
 briefInstallLazy();bind(area);briefKeepDetails(area,opened);window.scrollTo(0,position);
}

function sheet(content){$('#sheet').innerHTML=`<section class="sheet" role="dialog" aria-modal="true">${content}</section>`;$('#sheet').onclick=e=>{if(e.target===$('#sheet')){if(state?.purposeEditor)back();else closeSheet();}};bind($('#sheet'));viewportChanged();}
function sheetHead(title){return `<div class="sectionhead"><h2>${esc(title)}</h2><button class="iconbtn" data-act="closeSheet" aria-label="닫기">${icon('close')}</button></div>`;}
function closeSheet(){if($('#sheet'))$('#sheet').innerHTML='';}
async function populatePurposeApps(pkg='',scope='phone'){
 const target=$('#purposeApp'),scopeEl=$('#purposeScope');
 const selectedPkg=target?.value||pkg,selectedScope=scopeEl?.value||scope;
 try{const apps=await getApps();if($('#purposeApp')!==target||$('#purposeScope')!==scopeEl)return;
 if(target){const old=selectedPkg&&!apps.some(app=>app.pkg===selectedPkg)?`<option value="${esc(selectedPkg)}">기존 연결 · ${esc(selectedPkg)}</option>`:'';
  target.innerHTML='<option value="">연결 없음</option>'+old+apps.map(app=>`<option value="${esc(app.pkg)}">${esc(app.label)}</option>`).join('');target.value=selectedPkg;target.disabled=false;renderPurposeAppResults();}
 if(scopeEl){const wanted=selectedScope==='*'?'phone':selectedScope;
  const old=wanted!=='phone'&&wanted!=='app'&&!apps.some(app=>app.pkg===wanted)?`<option value="${esc(wanted)}">기존 앱 대상 · ${esc(wanted)}</option>`:'';
  scopeEl.innerHTML='<option value="phone">휴대폰 시작</option><option value="app">모든 관리 앱</option>'+old+apps.map(app=>`<option value="${esc(app.pkg)}">${esc(app.label)} 시작</option>`).join('');scopeEl.value=wanted;}
 }catch(e){toast(e.message);}
}
function renderPurposeAppResults(){
 const select=$('#purposeApp'),list=$('#purposeAppResults'),search=$('#purposeAppSearch');
 if(!select||!list||!appsCache)return;
 const chosen=select.value,query=(search?.value||'').trim().toLocaleLowerCase('ko');
 const all=appsCache.filter(a=>(a.label+' '+a.pkg).toLocaleLowerCase('ko').includes(query));
 const visible=all.slice(0,18);
 const selected=appsCache.find(a=>a.pkg===chosen);
 if(selected&&!visible.some(a=>a.pkg===chosen)&&!query)visible.unshift(selected);
 list.innerHTML=visible.map(a=>`<button type="button" class="purpose-app-row ${a.pkg===chosen?'selected':''}" data-pickapp="${esc(a.pkg)}" aria-pressed="${a.pkg===chosen}"><img alt="" src="${esc(a.icon)}"><span><strong>${esc(a.label)}</strong><small>${esc(a.pkg)}</small></span>${a.pkg===chosen?icon('check'):''}</button>`).join('')||'<p class="small muted" style="padding:10px">검색 결과가 없습니다.</p>';
 const total=$('#purposeAppCount');if(total)total.textContent=all.length>visible.length?`${all.length}개 중 ${visible.length}개 표시 · 전체는 아래 목록에서 선택`:`${all.length}개 앱`;
 const preview=$('#purposeAppPreview');
 if(preview)preview.innerHTML=selected?`<img src="${esc(selected.icon)}" alt=""><span><strong>${esc(selected.label)}</strong><small>선택한 앱</small></span>`:chosen?`<span><strong>${esc(chosen)}</strong><small>기존 앱 연결</small></span>`:'<span class="muted">연결할 앱을 선택해 주세요.</span>';
 bind(list);
}
function purposeSheet(id=null,direct=false){
 const p=state.allPurposes.find(x=>x.id===id)||{label:'',pkg:'',uri:'',scope:gated.includes(screen)?(state.gate.pkg||'app'):direct?'phone':purposeScope};
 sheet(`${sheetHead(direct?'이번에 할 일':'사용 목적')}<div class="formrow"><label for="purposeLabel">목적</label><input class="field" id="purposeLabel" maxlength="100" value="${esc(p.label)}" placeholder="예: 친구에게 답장"></div>${direct?'<label class="link-toggle"><input id="purposeSave" type="checkbox"> 목적 목록에 저장하기</label>':''}<label class="link-toggle"><input id="purposeLink" type="checkbox" ${p.pkg||p.uri?'checked':''}> 목적 선택 시 앱이나 동작 열기</label><div id="purposeConnection" ${p.pkg||p.uri?'':'hidden'}><div class="formrow"><label for="purposeAppSearch">앱 아이콘에서 찾기</label><input class="field" id="purposeAppSearch" type="search" placeholder="앱 이름으로 검색" autocomplete="off"><div id="purposeAppPreview" class="purpose-app-preview"></div><div id="purposeAppResults" class="purpose-app-results"></div><p class="small muted" id="purposeAppCount">앱 목록 준비 중</p><label for="purposeApp" class="small muted">전체 앱 목록에서 선택</label><select class="field" id="purposeApp" disabled><option value="${esc(p.pkg)}">${esc(p.pkg?`기존 연결 · ${p.pkg}`:'목록 준비 중')}</option></select></div><div class="formrow"><label for="purposeUri">앱 동작 주소 · 선택</label><input class="field" id="purposeUri" value="${esc(p.uri)}" placeholder="앱이 지원하는 딥링크"><button class="textbtn" data-act="testPurposeLink">연결 시험 ${icon('arrow')}</button></div></div>${!direct?`<div class="formrow"><label for="purposeScope">표시할 화면</label><select class="field" id="purposeScope"><option value="phone" ${p.scope==='phone'?'selected':''}>휴대폰 시작</option><option value="app" ${p.scope==='app'?'selected':''}>모든 관리 앱</option>${p.scope!=='phone'&&p.scope!=='app'?`<option value="${esc(p.scope)}" selected>선택한 앱</option>`:''}</select></div>`:''}<div class="sheet-actions"><button class="primary" id="savePurpose" data-act="savePurpose" data-id="${esc(id||'')}" data-scope="${esc(p.scope)}" data-direct="${direct}" data-aimless="${p.aimless?1:0}">${direct?'시작하기':'저장'} ${icon('check')}</button>${id&&!direct?`<button class="iconbtn danger" data-act="deletePurpose" data-id="${esc(id)}" aria-label="목적 삭제">${icon('trash')}</button>`:''}</div>`);
 const save=$('#purposeSave');if(save)save.onchange=()=>{$('#savePurpose').innerHTML=(save.checked?'저장하고 시작':'시작하기')+' '+icon('check');};
 const link=$('#purposeLink');link.onchange=()=>{const enabled=link.checked;$('#purposeConnection').hidden=!enabled;if(enabled)safe(()=>populatePurposeApps(p.pkg,p.scope));};
 if(!direct||link.checked)safe(()=>populatePurposeApps(p.pkg,p.scope));
 if($('#purposeAppSearch'))$('#purposeAppSearch').oninput=renderPurposeAppResults;
 if($('#purposeApp'))$('#purposeApp').onchange=renderPurposeAppResults;
 $('#purposeLabel').focus({preventScroll:true});
}
async function openPurposeManager(pkg,navigation){await navigate('settings',navigation);managePurposes('app');}
function managePurposes(scope=purposeScope){purposeScope=scope;const heading=sheetHead('사용 목적').replace('data-act=\"closeSheet\"',state.purposeEditor?'data-act=\"finishPurposeEditing\"':'data-act=\"closeSheet\"');sheet(`${heading}<div class="segmented"><button data-switchscope="phone" class="${purposeScope==='phone'?'active':''}">휴대폰 시작</button><button data-switchscope="app" class="${purposeScope==='app'?'active':''}">앱 시작</button></div><div class="purpose-manage">${state.allPurposes.filter(p=>purposeScope==='phone'?p.scope==='phone'||p.scope==='*':p.scope!=='phone'&&p.scope!=='*').map(p=>`<div class="app-row"><div class="info"><strong>${esc(p.label)}</strong><p>${esc(p.pkg?'연결 앱 · '+(appsCache?.find(a=>a.pkg===p.pkg)?.label||p.pkg):p.uri?'연결된 동작':'앱 연결 없음')}</p></div><button class="iconbtn" data-editpurpose="${esc(p.id)}" aria-label="목적 편집">${icon('more')}</button><button class="iconbtn danger" data-remove="${esc(p.id)}" aria-label="목적 삭제">${icon('trash')}</button></div>`).join('')}</div><div class="sheet-actions"><button class="primary" data-act="addPurpose">${icon('plus')} 추가</button></div>`);}
async function ruleSheet(pkg){await getApps();let a=appsCache.find(a=>a.pkg===pkg),r=state.rules.find(r=>r.pkg===pkg)||{mode:'limited',minutes:60};if(!a)return;
 sheet(`${sheetHead(a.label)}<label for="ruleMode">관리 방식</label><select class="field" id="ruleMode"><option value="none" ${r.mode==='none'?'selected':''}>관리 안 함</option><option value="limited" ${r.mode==='limited'?'selected':''}>시간 조절</option><option value="locked" ${r.mode==='locked'?'selected':''}>집중 중 잠금</option></select><div class="formrow" id="dailyControl"><label for="dailyMinutes">하루 <b id="dailyValue">${r.minutes}</b>분</label><input id="dailyMinutes" type="range" min="5" max="120" step="5" value="${r.minutes}"></div><p class="small muted">변경 즉시 적용 · 기본 60분, 최대 120분</p>`);
 const save=()=>safe(async()=>{const rule={pkg,mode:$('#ruleMode').value,minutes:Number($('#dailyMinutes').value)};await api('rule',rule);state=await api('state');renderApps();});const update=()=>{const c=$('#dailyControl');if(c)c.style.display=$('#ruleMode').value==='limited'?'':'none';};$('#ruleMode').onchange=()=>{update();save();};$('#dailyMinutes').oninput=()=>$('#dailyValue').textContent=$('#dailyMinutes').value;$('#dailyMinutes').onchange=save;update();}
async function dictionaryChoice(word=''){
 const choices=await api('dictionaries');
 if(!choices.length)throw Error('검색어를 전달할 수 있는 사전이나 브라우저가 없습니다.');
 sheet(`${sheetHead('기본 사전 선택')}<div class="stack">${choices.map(c=>`<button class="secondary" data-dictionary="${esc(c.id)}" data-word="${esc(word)}">${esc(c.label)}</button>`).join('')}</div>`);
}
function usageAppsPage(){return `<main class="page">${header(true,'사용시간 제외 앱')}<p class="muted">시스템 앱은 모두 제외돼요. 체크한 앱도 사용시간과 브리핑에서 제외해요.</p><input id="usageSearch" class="field" placeholder="앱 이름으로 검색"><div id="usageList" class="card"></div></main>`;}
let usageCache=[];
async function loadUsageApps(){usageCache=await api('usageApps');renderUsageApps();}
function renderUsageApps(){let query=$('#usageSearch')?.value.trim().toLowerCase()||'';$('#usageList').innerHTML=usageCache.filter(a=>(a.label+' '+a.pkg).toLowerCase().includes(query)).sort((a,b)=>Number(b.excluded)-Number(a.excluded)||a.label.localeCompare(b.label)).map(a=>`<label class="app-row"><input class="manage-check" type="checkbox" data-exclude="${esc(a.pkg)}" ${a.excluded?'checked':''} ${a.system?'disabled':''}>${a.icon?`<img src="${esc(a.icon)}" alt="">`:icon('settings')}<span class="info"><strong>${esc(a.label)}</strong><p>${a.system?'시스템 앱 · 자동 제외':esc(a.pkg)}</p></span></label>`).join('');bind($('#usageList'));}
async function accountSheet(){sheet(`<div class="sectionhead"><h2>ChatGPT 계정</h2><button class="iconbtn" data-act="closeSheet" aria-label="닫기">${icon('close')}</button></div>${state.accounts.map(a=>`<div class="app-row"><div class="info"><strong>${esc(a.email||a.client)}</strong><p>${a.active?'현재 계정 · ':''}${a.connected?'연결됨':'연결 해제됨'}</p></div><button class="textbtn" data-account="${esc(a.client)}">선택</button></div>`).join('')}<div class="stack" style="margin-top:20px"><button class="primary" data-act="signin">다른 계정 연결</button><button class="secondary" data-act="consent">요금제 사용 승인</button><button class="secondary danger" data-act="signout">현재 계정 연결 해제</button></div>`);}

function confirmSheet(title,detail,action,args){sheet(`${sheetHead(title)}<p class="muted">${esc(detail)}</p><div class="sheet-actions"><button id="confirm" class="primary danger">삭제</button></div>`);$('#confirm').onclick=()=>safe(async()=>{await api(action,args);closeSheet();if(action==='deleteNote')noteId='';if(action==='deleteChat')quickTurns.delete(args.id);await refresh(true);});}
function captureMemo(){const body=$('#noteBody'),checks=$('#checks');if(!body&&!checks)return null;let old=memoBase||currentNote();const values=checks?[...checks.querySelectorAll('.checkrow')].map(r=>({id:r.dataset.checkid,text:r.querySelector('[data-checktext]').value,done:r.querySelector('input[type=checkbox]').checked})):(Array.isArray(old.checks)?structuredClone(old.checks):JSON.parse(old.checks||'[]'));
 const note={id:old.id||noteId||crypto.randomUUID(),title:$('#noteTitle')?.value||old.title||'메모',body:body?body.value:old.body||'',checks:values,mode:old.mode||'text',base:structuredClone(memoBase)};noteId=note.id;return note;}
function insertCheckRow(after,text=''){
 const list=$('#checks');if(!list)return;
 const row=document.createElement('div');row.className='checkrow';row.dataset.checkid=newCheckId();
 row.innerHTML='<input type="checkbox" data-check aria-label="완료"><textarea data-checktext rows="1" aria-label="할 일"></textarea><button data-delcheck aria-label="할 일 삭제">'+icon('close')+'</button>';
 const editor=row.querySelector('textarea');editor.value=text;
 if(after)after.after(row);else list.appendChild(row);
 bind(row);scheduleMemo();editor.focus();
}
function splitCheckRow(editor,start=editor.selectionStart,end=editor.selectionEnd){
 const text=editor.value,first=text.slice(0,start),remaining=text.slice(end);
 editor.value=first;insertCheckRow(editor.closest('.checkrow'),remaining);
}
function scheduleMemo(){memoPending=captureMemo();if($('#saveState'))$('#saveState').textContent='저장 중';clearTimeout(memoTimer);memoTimer=setTimeout(()=>safe(flushMemo),300);autoNoteHeight();}
async function flushMemo(){clearTimeout(memoTimer);if(memoSaving)await memoSaving;if(!memoPending)return;const note=memoPending;memoPending=null;memoSaving=(async()=>{try{
 const saved=await api('saveNote',note);let n=state?.notes.find(n=>n.id===note.id);let value=saved?.id?saved:{...note,checks:JSON.stringify(note.checks),updated:Date.now()};if(n)Object.assign(n,value);else state?.notes.unshift(value);
 // Update only check bits. Text nodes, caret, IME composition and scroll stay intact.
 syncMemoChecks(value,note);
 if(memoBase?.id===note.id){const checks=typeof value.checks==='string'?JSON.parse(value.checks):value.checks||[];memoBase={id:value.id,title:value.title,body:value.body,mode:value.mode,checks:structuredClone(checks)};}
 if($('#saveState'))$('#saveState').textContent='자동 저장';
 }catch(e){if(!memoPending)memoPending=note;throw e;}})();try{await memoSaving;}finally{memoSaving=null;}if(memoPending)await flushMemo();}
function syncMemoChecks(saved=null,submitted=null){
 if(!memoBase||!$('#checks'))return;const latest=saved||state?.notes?.find(n=>n.id===memoBase.id);if(!latest||latest.id!==memoBase.id)return;
 let checks=[];try{checks=typeof latest.checks==='string'?JSON.parse(latest.checks):latest.checks||[];}catch{return;}
 const before=new Map((submitted?.checks||memoBase.checks||[]).map(c=>[c.id,c])),fresh=new Map(checks.map(c=>[c.id,c]));
 for(const row of document.querySelectorAll('#checks .checkrow')){const old=before.get(row.dataset.checkid),next=fresh.get(row.dataset.checkid),box=row.querySelector('[data-check]');if(!old||!next||box.checked!==!!old.done)continue;
  if(!submitted&&row._doneEdited)continue;box.checked=!!next.done;row.classList.toggle('done',box.checked);
  const base=memoBase.checks?.find(c=>c.id===next.id);if(base)base.done=!!next.done;
  if(submitted&&!memoPending)row._doneEdited=false;
 }
}
function updateNoteOverflow(scroller){scroller.classList.toggle('has-more',scroller.scrollHeight-scroller.clientHeight-scroller.scrollTop>1);}
function autoNoteHeight(){document.querySelectorAll('.note-content').forEach(scroller=>{const top=scroller.scrollTop;scroller.querySelectorAll('textarea').forEach(el=>{if(el.id==='noteBody'&&!el.closest('.full-note'))return;const key=el.value+'|'+el.clientWidth+'|'+scroller.clientHeight;if(el._measureKey===key)return;el._measureKey=key;el.style.height='0px';el.style.height=Math.max(el.id==='noteBody'?scroller.clientHeight-8:28,el.scrollHeight)+'px';});scroller.scrollTop=top;updateNoteOverflow(scroller);scroller.onscroll=()=>updateNoteOverflow(scroller);});}
let suppressCheckBackward=false;
document.addEventListener("keyup",event=>{if(event.key==="Backspace")suppressCheckBackward=false;});
function deleteEmptyCheck(editor,event){
 if(event.isComposing||editor._composing||event.keyCode===229||editor.value!==''||!editor.isConnected)return false;
 event.preventDefault();const row=editor.closest('.checkrow'),previous=row.previousElementSibling?.querySelector('[data-checktext]'),next=row.nextElementSibling?.querySelector('[data-checktext]');
 row.remove();scheduleMemo();const target=previous||next;if(target){target.focus();const end=previous?target.value.length:0;target.setSelectionRange(end,end);}else $('[data-act="addCheck"]')?.focus();return true;
}
function insetsChanged(visible){nativeKeyboard=visible;viewportChanged(visible);}
function layoutStart(){
 // Home's anchored title and internal preset scroller are sized by CSS flex, never by item count.
 if($('.preset-scroll'))return;
 const page=$('.start-page');if(!page)return;
 const area=page.querySelector('.start-purposes'),utility=page.querySelector('.utility-space');if(!area||area.closest('[hidden]'))return;
 const grid=area.querySelector('.reason-grid');if(!grid)return;
 const items=[...grid.querySelectorAll('.purpose-item')],more=grid.querySelector('.more-reasons');
 // Measure actual type and controls, including system font scaling and wrapped labels.
 items.forEach(el=>el.hidden=false);if(more)more.hidden=true;
 if(utility){utility.style.height='';utility.style.flex='';}
 const decision=page.querySelector('.app-decision'),css=getComputedStyle(page),gap=parseFloat(css.rowGap)||0;
 const visibleChildren=[...page.children].filter(child=>!child.hidden);
 let available=page.clientHeight-parseFloat(css.paddingTop)-parseFloat(css.paddingBottom)-gap*(visibleChildren.length-1);
 for(const child of visibleChildren)if(child!==area&&child!==utility&&child!==decision)available-=child.getBoundingClientRect().height;
 const decisionHeight=available;
 if(decision){decision.style.maxHeight='';const c=getComputedStyle(decision);available-=(parseFloat(c.rowGap)||0)*(decision.children.length-1);for(const child of decision.children)if(child!==area)available-=child.getBoundingClientRect().height;}
 const frame=getComputedStyle(area),intro=area.querySelector('.purpose-intro');
 available-=parseFloat(frame.paddingTop)+parseFloat(frame.paddingBottom)+(parseFloat(frame.borderTopWidth)||0);
 if(intro){const c=getComputedStyle(intro);available-=intro.getBoundingClientRect().height+(parseFloat(c.marginBottom)||0);}
 const minimumUtility=utility?Math.min(192,Math.max(164,page.clientHeight*.24)):0;
 const preferred=utility?.getBoundingClientRect().height||0,gridGap=parseFloat(getComputedStyle(grid).rowGap)||0;
 const direct=grid.querySelector('.direct').getBoundingClientRect().height;
 const rowHeights=[];for(let i=0;i<items.length;i+=2)rowHeights.push(Math.max(...items.slice(i,i+2).map(el=>el.getBoundingClientRect().height)));
 if(more)more.hidden=false;const moreHeight=more?.getBoundingClientRect().height||0;
 const required=n=>direct+rowHeights.slice(0,Math.ceil(n/2)).reduce((sum,h)=>sum+gridGap+h,0)+(n<items.length?gridGap+moreHeight:0);
 let count=items.length;while(count>0&&required(count)+minimumUtility>available)count=Math.max(0,count-2-(count%2));
 items.forEach((el,i)=>el.hidden=i>=count);if(more)more.hidden=count===items.length;
 if(utility){const height=Math.max(0,Math.min(preferred,available-required(count)));utility.style.height=height+'px';utility.style.flex='0 0 '+height+'px';}
 // On very low windows only the decision body scrolls; dismissal stays in the footer.
 if(decision)decision.style.maxHeight=Math.max(0,decisionHeight)+'px';
}
let startKeyboardScroll=null;
let gateViewportFrame=null,gateViewportReveal=false;
function viewportChanged(reveal=false){
 if(state?.role!=='APP_GATE'){requestAnimationFrame(()=>applyViewport(reveal));return;}
 gateViewportReveal=gateViewportReveal||reveal===true;if(gateViewportFrame!==null)return;
 gateViewportFrame=requestAnimationFrame(()=>{gateViewportFrame=null;const reveal=gateViewportReveal;gateViewportReveal=false;applyViewport(reveal);});
}
function applyViewport(reveal=false){
 const v=window.visualViewport,h=v?.height||innerHeight,top=v?.offsetTop||0,active=document.activeElement;
 const editing=active?.matches('input,textarea,select');
 const keyboard=nativeKeyboard||innerHeight-h>100||(editing&&normalWidth===innerWidth&&normalHeight-h>100);
 if(!normalHeight||(!keyboard&&(normalWidth!==innerWidth||!editing))){normalHeight=h;normalWidth=innerWidth;}
 const phoneStart=!!$('.preset-scroll'),wasInput=document.body.classList.contains('start-input');
 if(phoneStart&&keyboard&&!wasInput)startKeyboardScroll=window.scrollY;
 document.documentElement.style.setProperty('--viewport',h+'px');
 document.documentElement.style.setProperty('--rest-height',normalHeight+'px');
 document.documentElement.style.setProperty('--vtop',top+'px');
 document.body.classList.toggle('keyboard',!!keyboard);
 document.body.classList.toggle('start-input',phoneStart&&!!keyboard);
 // Use the unobscured height for start layout breakpoints, not the IME viewport.
 document.body.classList.toggle('compact-start',normalHeight<=650);
 document.body.classList.toggle('short-start',normalHeight<=430);
 layoutStart();autoNoteHeight();
 if(wasInput&&(!keyboard||!phoneStart)&&startKeyboardScroll!==null){window.scrollTo(0,startKeyboardScroll);startKeyboardScroll=null;}
 if(reveal===true&&editing){
   if(phoneStart&&!active.closest('.sheet')){
     const bounds=active.getBoundingClientRect(),bottom=top+h-12;
     if(bounds.bottom>bottom)window.scrollBy(0,bounds.bottom-bottom);
     else if(bounds.top<top)window.scrollBy(0,bounds.top-top-12);
   }else active.scrollIntoView({block:'nearest',inline:'nearest'});
 }
}
async function saveReminders(){if(!$('#customReminders'))return;settingsTimer=null;const values=[...document.querySelectorAll('[data-reminder].selected')].map(el=>Number(el.dataset.reminder));const extra=$('#customReminders')?.value.trim()||'';if(extra){const parsed=extra.split(',').map(v=>Number(v.trim()));if(parsed.some(n=>!Number.isInteger(n)||n<1||n>180))throw Error('추가 알림은 1~180분으로 입력해 주세요.');values.push(...parsed);}await api('settings',{reminders:values});Object.assign(state.prefs,{reminders:values});}
let dictionarySearching=false;
async function searchDictionary(word){releasePhoneNavigation('dictionary');
 if(dictionarySearching)return;
 dictionarySearching=true;
 const submitted=$('#word')?.value??dictionaryWordDraft;
 try{
  await flushMemo();const result=await api('dictionary',{word});
  if(result?.launched){
   // Preserve a new edit made while Android was opening the external dictionary.
   if(($('#word')?.value??dictionaryWordDraft)===submitted){dictionaryWordDraft='';if($('#word'))$('#word').value='';}
  }
 }finally{dictionarySearching=false;}
}
async function lookup(){const word=$('#word')?.value.trim();if(!word)throw Error('찾을 단어를 입력해 주세요.');if(!state.dictionary){await dictionaryChoice(word);return;}await searchDictionary(word);}
async function action(name,el){if(screen==='gateIdle')return;const gateEntry=gateSnapshot();switch(name){case 'finishPurposeEditing':await api('finishPurposeEditing');break;case 'viewDiagnostics':await viewAppGateDiagnostics();break;case 'saveDiagnostics':await api('saveDiagnostics');break;case 'deleteDiagnostics':sheet(`${sheetHead('진단 기록 삭제')}<p>기기에 보관한 관리 앱 시작 진단 기록을 모두 삭제할까요?</p><div class="sheet-actions"><button class="primary" data-act="confirmDeleteDiagnostics">모두 삭제</button><button class="secondary" data-act="closeSheet">취소</button></div>`);break;case 'confirmDeleteDiagnostics':await api('clearDiagnostics');closeSheet();await refresh(true);toast('진단 기록을 삭제했습니다.');break;case 'back':back();break;case 'closeSheet':if(state?.purposeEditor)managePurposes('app');else closeSheet();break;case 'exit':await flushMemo();await api('exit');break;case 'dismiss':await flushMemo();if(gateEntry&&!currentGate(gateEntry))return;await api('dismiss',gateEntry?{gateNavigation:gateEntry.navigation}:{});break;
 case 'direct':await purposeSheet(null,true);break;case 'addPurpose':await purposeSheet();break;case 'managePurposes':managePurposes(screen==='app'?'app':screen==='home'||screen==='unlock'?'phone':purposeScope);break;

 case 'morePurposes':sheet(`${sheetHead('사용 목적')}${purposeGrid(screen==='app',true)}`);break;
 case 'savePurpose':{if(el.disabled)return;if(state?.role==='APP_GATE'){await saveGatePurpose(el);break;}const entry=nativeNavigation,targetPkg=state?.gate?.pkg,appEntry=state?.role==='APP_GATE';el.disabled=true;try{if($('#purposeLink').checked&&$('#purposeApp').disabled)throw Error('앱 목록을 불러오는 중입니다.');if($('#purposeLink').checked&&!$('#purposeApp').value&&!$('#purposeUri').value.trim())throw Error('연결할 앱이나 동작 주소를 선택해 주세요.');const p={label:$('#purposeLabel').value,pkg:$('#purposeLink').checked?$('#purposeApp').value:'',uri:$('#purposeLink').checked?$('#purposeUri').value:'',scope:$('#purposeScope')?.value||el.dataset.scope,aimless:el.dataset.aimless==='1'};if(el.dataset.id)p.id=el.dataset.id;if(el.dataset.direct==='true'&&!$('#purposeSave').checked){await choosePurpose({...p,transient:true});break;}const saved=await api('purpose',appEntry?{...p,gateNavigation:entry}:p);if(appEntry&&(entry!==nativeNavigation||targetPkg!==state?.gate?.pkg))return;closeSheet();const next=await api('state');if(appEntry&&entry!==nativeNavigation)return;state=next;if(el.dataset.direct==='true'){await choosePurpose(saved.id);if(appEntry)presentGate(screen,state,nativeNavigation);else render();}else {render();if(screen==='settings')managePurposes();}}finally{if(el.isConnected)el.disabled=false;}break;}
 case 'deletePurpose':if(state?.role==='APP_GATE'){await removeGatePurpose(el.dataset.id);break;}await api('removePurpose',{id:el.dataset.id});closeSheet();await refresh(true);break;
 case 'testPurposeLink':await api('linkTest',{pkg:$('#purposeApp').value,uri:$('#purposeUri').value});break;
 case 'grant':await choosePurpose(selected);break;case 'extend':await api('extend',{minutes});break;case 'stop':await api('stop');break;
 case 'newNote':await flushMemo();sheet(`${sheetHead('새 메모')}<div class="split"><button class="secondary" data-newnote="text">${icon('note')} 글 메모</button><button class="secondary" data-newnote="check">${icon('check')} 체크 메모</button></div>`);break;
 case 'addCheck':insertCheckRow(null,'');break;
 case 'deleteNote':await flushMemo();confirmSheet('메모 삭제','이 메모를 삭제할까요?','deleteNote',{id:noteId});break;
 case 'openChat':await navigate('chats');break;
 case 'quickSend':startQuickQuestion();break;
 case 'retryQuick':{const turn=quickTurns.get(chatId);if(turn)await submitQuickQuestion(turn,true);break;}
 case 'dictionaryChoice':await dictionaryChoice();break;
 case 'signin':closeSheet();await api('signin');await refresh(true);break;case 'consent':closeSheet();await api('signin',{client:state.accounts.find(a=>a.active)?.client||'',consent:true});await refresh(true);break;
 case 'cancelLogin':await api('cancelLogin');await refresh(true);break;case 'models':await api('models');await refresh(true);break;
 case 'manageUsage':await api('linkTest',{uri:'https://chatgpt.com/settings/usage'});break;case 'accountSheet':await accountSheet();break;
 case 'signout':{let r=await api('signout');closeSheet();toast(r.message);await refresh(true);break;}
 case 'newChat':{const c=await api('newChat');chatId=c.id;await navigate('chat');break;}
 case 'send':{const pending=quickTurns.get(chatId);if(pending&&!pending.accepted)return;const text=$('#chatInput').value.trim();if(!text)return;await api('send',{id:chatId,text});$('#chatInput').value='';await refresh();break;}
 case 'cancelResponse':await api('cancelResponse');break;
 case 'refreshBrief':await loadBrief();break;
 case 'todayBrief':briefTarget=null;briefDays=1;briefOffset=0;render();await loadBrief();break;
 case 'copyDay':await api('copyDay',{offset:briefOffset});toast('선택한 날짜의 원본 JSON을 복사했습니다.');break;case 'saveDay':await api('saveDay',{offset:briefOffset});toast('저장할 파일 위치를 선택해 주세요.');break;
  case 'previousBrief':briefTarget=null;briefOffset=Math.min(179,briefOffset+(briefDays===7?7:1));render();await loadBrief();break;case 'nextBrief':briefTarget=null;briefOffset=Math.max(0,briefOffset-(briefDays===7?7:1));render();await loadBrief();break;
 }}
function bind(root=$('#app')){
 root.querySelectorAll('[data-act]').forEach(el=>el.onclick=()=>safe(()=>action(el.dataset.act,el)));
 root.querySelectorAll('[data-nav]').forEach(el=>el.onclick=()=>safe(()=>navigate(el.dataset.nav)));
 root.querySelectorAll('[data-purpose]').forEach(el=>el.onclick=()=>safe(()=>choosePurpose(el.dataset.purpose)));
 root.querySelectorAll('[data-min]').forEach(el=>el.onclick=()=>{
  minutes=Number(el.dataset.min);document.querySelectorAll('[data-min]').forEach(button=>{const chosen=Number(button.dataset.min)===minutes;button.classList.toggle('selected',chosen);button.setAttribute('aria-pressed',chosen);});
  if($('#startApp')){if(state?.role==='APP_GATE')$('#startApp').firstChild.data=`${minutes}분 연장 `;else $('#startApp').innerHTML=`${minutes}분 연장 ${icon('arrow')}`;}
  if(state?.role==='APP_GATE'){++domRevision;signalRendered();}
 });
 root.querySelectorAll('[data-note]').forEach(el=>el.onclick=()=>safe(async()=>{await flushMemo();noteId=el.dataset.note;render();remember();}));
 root.querySelectorAll('[data-note-step]').forEach(el=>el.onclick=()=>safe(async()=>{await flushMemo();const notes=state.notes;if(notes.length<2)return;const index=notes.findIndex(n=>n.id===noteId);noteId=notes[(index+Number(el.dataset.noteStep)+notes.length)%notes.length].id;render();remember();}));
 root.querySelectorAll('[data-newnote]').forEach(el=>el.onclick=()=>safe(async()=>{noteId=crypto.randomUUID();await api('saveNote',{id:noteId,title:'새 메모',body:'',checks:[],mode:el.dataset.newnote});closeSheet();await refresh(true);if(el.dataset.newnote==='check')await action('addCheck');else $('#noteBody')?.focus();remember();}));
 root.querySelectorAll('[data-chat]').forEach(el=>el.onclick=()=>safe(async()=>{chatId=el.dataset.chat;await navigate('chat');}));
 root.querySelectorAll('[data-deletechat]').forEach(el=>el.onclick=()=>confirmSheet('대화 삭제','이 대화를 삭제할까요?','deleteChat',{id:el.dataset.deletechat}));
 root.querySelectorAll('[data-editpurpose]').forEach(el=>el.onclick=()=>safe(()=>purposeSheet(el.dataset.editpurpose)));
 root.querySelectorAll('[data-remove]').forEach(el=>el.onclick=()=>safe(async()=>{if(state?.role==='APP_GATE'){await removeGatePurpose(el.dataset.remove,true);return;}const editing=!!$('#sheet').innerHTML;await api('removePurpose',{id:el.dataset.remove});state=await api('state');render();if(editing)managePurposes();}));
 root.querySelectorAll('[data-switchscope]').forEach(el=>el.onclick=()=>{purposeScope=el.dataset.switchscope;managePurposes();});
 root.querySelectorAll('[data-rule]').forEach(el=>el.onclick=()=>safe(()=>ruleSheet(el.dataset.rule)));
 root.querySelectorAll('[data-manage]').forEach(el=>el.onchange=()=>safe(async()=>{await api('rule',{pkg:el.dataset.manage,mode:el.checked?'restore':'none'});state=await api('state');renderApps();}));
 root.querySelectorAll('[data-exclude]').forEach(el=>el.onchange=()=>safe(async()=>{await api('excludeUsage',{pkg:el.dataset.exclude,excluded:el.checked});const a=usageCache.find(a=>a.pkg===el.dataset.exclude);if(a)a.excluded=el.checked;}));
 root.querySelectorAll('[data-dictionary]').forEach(el=>el.onclick=()=>safe(async()=>{await api('dictionaryChoice',{id:el.dataset.dictionary});state.dictionary=el.dataset.dictionary;closeSheet();if(el.dataset.word)await searchDictionary(el.dataset.word);}));
 root.querySelectorAll('[data-pickapp]').forEach(el=>el.onclick=()=>{const selected=$('#purposeApp');if(!selected||selected.disabled)return;selected.value=el.dataset.pickapp;renderPurposeAppResults();});
  root.querySelectorAll('[data-url]').forEach(el=>el.onclick=()=>safe(()=>api('linkTest',{uri:el.dataset.url})));
  root.querySelectorAll('[data-brief-focus]').forEach(el=>el.onclick=()=>safe(()=>openBriefRoute('brief|focus|'+el.dataset.briefFocus)));
 root.querySelectorAll('[data-brief-jump]').forEach(el=>el.onclick=()=>briefJump(Number(el.dataset.briefJump)));
 root.querySelectorAll('[data-brief-record-filter]').forEach(el=>el.onclick=()=>{briefRecordFilter=el.dataset.briefRecordFilter;if(briefData)briefReplaceSection('briefTimeline',briefTimelineSection(briefData));});
 root.querySelectorAll('[data-brief-hour]').forEach(el=>el.onclick=()=>briefSelectHour(Number(el.dataset.briefHour)));
 root.querySelectorAll('[data-brief-filter]').forEach(el=>el.onclick=()=>{briefAppFilter=el.dataset.briefFilter;if(briefData)briefReplaceSection('brief-apps',briefAppsSection(briefData));});
 root.querySelectorAll('[data-brief-sort]').forEach(el=>el.onclick=e=>{e.preventDefault();e.stopPropagation();const key=el.dataset.briefSort;briefSort[key]=briefSort[key]==='desc'?'asc':'desc';if(briefData){if(key==='apps')briefReplaceSection('brief-apps',briefAppsSection(briefData));else briefReplaceSection('briefTimeline',briefTimelineSection(briefData));$(`[data-brief-sort="${key}"]`)?.focus({preventScroll:true});}});
 root.querySelectorAll('[data-source-toggle]').forEach(el=>el.onclick=()=>{
   const panel=document.getElementById(el.dataset.sourceToggle);if(!panel)return;
   panel.hidden=!panel.hidden;el.setAttribute('aria-expanded',String(!panel.hidden));
 });
 root.querySelectorAll('[data-permission]').forEach(el=>el.onclick=()=>safe(()=>api('permission',{name:el.dataset.permission})));
 root.querySelectorAll('[data-account]').forEach(el=>el.onclick=()=>safe(async()=>{await api('account',{client:el.dataset.account});await api('models');closeSheet();await refresh(true);}));
 root.querySelectorAll('[data-days]').forEach(el=>el.onclick=()=>{briefTarget=null;briefDays=Number(el.dataset.days);briefOffset=briefDays===7?1:0;render();loadBrief();});
 root.querySelectorAll('#noteTitle,#noteBody,[data-checktext],[data-check]').forEach(el=>{
 el.oninput=e=>{if(el.matches('[data-checktext]')&&!e.isComposing&&el.value.includes('\n')){const at=el.value.indexOf('\n');splitCheckRow(el,at,at+1);}else scheduleMemo();};
 el.onfocus=()=>{safe(()=>api('utility',{name:'메모'}));viewportChanged();};
 el.onchange=()=>{if(el.type==='checkbox'){el.closest('.checkrow').classList.toggle('done',el.checked);el.closest('.checkrow')._doneEdited=true;}scheduleMemo();};
 });
 root.querySelectorAll('[data-checktext]').forEach(el=>{
 el.oncompositionstart=()=>{el._composing=true;};el.oncompositionend=()=>{el._composing=false;scheduleMemo();};
 el.onkeydown=e=>{if(e.key==='Backspace'){if(e.repeat&&suppressCheckBackward){e.preventDefault();return;}suppressCheckBackward=deleteEmptyCheck(el,e);return;}if(e.key==='Enter'&&!e.isComposing&&!el._composing&&e.keyCode!==229){e.preventDefault();splitCheckRow(el);}};
 el.onbeforeinput=e=>{if(e.inputType==='deleteContentBackward'){if(suppressCheckBackward){e.preventDefault();suppressCheckBackward=false;}else deleteEmptyCheck(el,e);return;}if(!e.isComposing&&!el._composing&&['insertParagraph','insertLineBreak'].includes(e.inputType)){e.preventDefault();splitCheckRow(el);}};
 });
 root.querySelectorAll('[data-delcheck]').forEach(el=>{el.onpointerdown=e=>{if(el.closest('.compact-note'))e.preventDefault();};el.onclick=()=>{el.closest('.checkrow').remove();scheduleMemo();};});
 if($('#noteMode'))$('#noteMode').onchange=()=>safe(async()=>{await flushMemo();let n=captureMemo(),mode=$('#noteMode').value;if(mode==='check'){n.checks=n.body.trim()?[{text:n.body,done:false},...n.checks]:n.checks;n.body='';}else {n.body=n.checks.map(c=>(c.done?'✓ ':'')+c.text).join('\n');n.checks=[];}n.mode=mode;await api('saveNote',n);await refresh(true);});
 if($('#appSearch'))$('#appSearch').oninput=renderApps;if($('#usageSearch'))$('#usageSearch').oninput=renderUsageApps;
 if($('#dictionaryForm'))$('#dictionaryForm').onsubmit=e=>{e.preventDefault();safe(lookup);};
 if($('#appGateDiagnostics'))$('#appGateDiagnostics').onclick=()=>safe(async()=>{await api('settings',{appGateDiagnostics:!state.status.appGateDiagnostics?.enabled});await refresh(true);});
 if($('#floatingTimer'))$('#floatingTimer').onclick=()=>safe(async()=>{const enabled=state.prefs.floatingTimer===false;await api('settings',{floatingTimer:enabled});state.prefs.floatingTimer=enabled;$('#floatingTimer').classList.toggle('on',enabled);$('#floatingTimer').setAttribute('aria-checked',enabled);$('#floatingTimer').textContent=enabled?'켜짐':'꺼짐';});
 if($('#enabled'))$('#enabled').onclick=()=>safe(async()=>{const enabled=!state.prefs.enabled;await api('settings',{enabled});state.prefs.enabled=enabled;$('#enabled').classList.toggle('on',enabled);$('#enabled').setAttribute('aria-checked',enabled);$('#enabled').textContent=enabled?'켜짐':'꺼짐';});
 ['startTime','endTime'].forEach(id=>{if($('#'+id))$('#'+id).onchange=()=>safe(async()=>{const start=timeVal($('#startTime').value),end=timeVal($('#endTime').value);await api('settings',{start,end});state.prefs.start=start;state.prefs.end=end;});});
 root.querySelectorAll('[data-reminder]').forEach(el=>el.onclick=()=>safe(async()=>{el.classList.toggle('selected');el.setAttribute('aria-pressed',el.classList.contains('selected'));await saveReminders();}));
 if($('#customReminders')){$('#customReminders').oninput=()=>{clearTimeout(settingsTimer);settingsTimer=setTimeout(()=>safe(saveReminders),700);};$('#customReminders').onchange=()=>safe(saveReminders);}
 ['dailyBrief','focusBrief'].forEach(id=>{if($('#'+id))$('#'+id).onchange=()=>safe(()=>api('settings',{[id]:$('#'+id).checked}));});
 if($('#dailyBriefTime'))$('#dailyBriefTime').onchange=()=>safe(()=>api('settings',{dailyBriefTime:timeVal($('#dailyBriefTime').value)}));
 if($('#weeklyDay'))$('#weeklyDay').onchange=()=>safe(()=>api('settings',{weeklyDay:Number($('#weeklyDay').value)}));
 if($('#themeMode'))$('#themeMode').onchange=()=>safe(async()=>{
   const mode=$('#themeMode').value;await api('settings',{themeMode:mode});
   state.prefs.themeMode=mode;
  });
  if($('#model'))$('#model').onchange=()=>safe(()=>api('settings',{model:$('#model').value}));
}
function back(){if(state?.purposeEditor){if($('#sheet [data-act=finishPurposeEditing]')||!$('#sheet').innerHTML){api('finishPurposeEditing');return;}managePurposes('app');return;}if($('#sheet').innerHTML){closeSheet();return;}if(screen==='chat'){navigate(chatReturn||'chats');return;}if(screen==='chats'){navigate(chatsReturn||'home');return;}if(gated.includes(screen)){api('stop');return;}if(state?.role==='PHONE_START'){if(screen!=='unlock')navigate('unlock');return;}if(state?.overlay){navigate(state.gate.mode||'app');return;}if(screen==='apps'||screen==='usageApps'){navigate('settings');return;}if(screen==='home'||screen==='setup'){api('exit');return;}navigate('home');}
window.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden'){clearInterval(poll);poll=null;safe(flushMemo);remember();}else{startPolling();if((state?.role==='PHONE_START'||screen==='unlock')&&$('#app')?.childElementCount)return;refresh();}});
window.addEventListener('resize',()=>viewportChanged(nativeKeyboard));window.visualViewport?.addEventListener('resize',()=>viewportChanged(nativeKeyboard));window.visualViewport?.addEventListener('scroll',()=>viewportChanged());
document.addEventListener('focusin',()=>{if(nativeKeyboard)viewportChanged(true);});

