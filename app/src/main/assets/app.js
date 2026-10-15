'use strict';
const $=s=>document.querySelector(s),esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const shapes={lock:'M7 10V7a5 5 0 0 1 10 0v1 M5 10h14v11H5z M12 14v3',leaf:'M5 19C3 8 11 3 21 3c0 10-5 16-16 16z M5 19l10-10',home:'M3 11l9-8 9 8 M6 10v11h12V10 M10 21v-7h4v7',chart:'M4 20V10 M10 20V4 M16 20v-8 M22 20H2',settings:'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z',plus:'M12 5v14 M5 12h14',arrow:'M5 12h14 M14 7l5 5-5 5',back:'M19 12H5 M10 7l-5 5 5 5',chat:'M3 4h18v13H9l-6 4z M7 8h10 M7 12h6',book:'M3 4h7l2 2 2-2h7v16h-7l-2 1-2-1H3z M12 6v15',note:'M5 3h14v18H5z M8 8h8 M8 12h8 M8 16h4',close:'M6 6l12 12 M18 6L6 18',check:'M4 12l5 5L20 6',trash:'M3 6h18 M9 6V3h6v3 M6 6v15h12V6 M10 10v7 M14 10v7',clock:'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 7v5l4 2',send:'M3 3l18 9-18 9 4-9z M7 12h14',moon:'M20 15A9 9 0 0 1 9 3a9 9 0 1 0 11 12',newnote:'M5 3h14v18H5z M12 8v8 M8 12h8',expand:'M8 3H3v5 M16 3h5v5 M3 16v5h5 M21 16v5h-5',more:'M12 5h.01 M12 12h.01 M12 19h.01',search:'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14 M15 15l6 6',power:'M12 2v10 M6 5a9 9 0 1 0 12 0',link:'M9 15l6-6 M8 16l-2 2a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0 M14 8l2-2a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0'};
const icon=n=>`<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${shapes[n]||shapes.leaf}"/></svg>`;
let seq=0,requests=new Map(),state=null,screen='home',selected='',minutes=5,chatId='',appsCache=null,appsPromise=null,noteId='',briefDays=1,briefOffset=0,refreshing=false,refreshAgain=false,poll=null,memoTimer=null,memoPending=null,memoSaving=null,chatScrolled=false;
let reshufflePurposeOnRender=true,purposePositions=new Map(),previousPurposePositions=new Map();
let normalHeight=0,normalWidth=0;
let quickQuestionDraft='',dictionaryWordDraft='';
let dictionaryHandoff=false,dictionaryHandoffScroll=null;
function captureDictionaryStartState(){
 return JSON.stringify({word:$('#word')?.value??dictionaryWordDraft,quickQuestion:$('#quickQuestion')?.value??quickQuestionDraft,
 noteId,purposePositions:[...purposePositions],previousPurposePositions:[...previousPurposePositions],scrollY:window.scrollY});
}
function restoreDictionaryStartState(raw){
 try{
  const s=JSON.parse(raw);
  dictionaryWordDraft=String(s.word??dictionaryWordDraft);
  quickQuestionDraft=String(s.quickQuestion??quickQuestionDraft);
  if(typeof s.noteId==='string')noteId=s.noteId;
  if(Array.isArray(s.purposePositions))purposePositions=new Map(s.purposePositions);
  if(Array.isArray(s.previousPurposePositions))previousPurposePositions=new Map(s.previousPurposePositions);
  dictionaryHandoff=true;
  if(Number.isFinite(s.scrollY))dictionaryHandoffScroll=s.scrollY;
  if($('#word'))$('#word').value=dictionaryWordDraft;
  if($('#quickQuestion'))$('#quickQuestion').value=quickQuestionDraft;
 }catch(e){}
}
let nativeNavigation=0;
let nativeKeyboard=false,purposeScope='phone',restored=false,briefSeq=0,settingsTimer=null;
let purposeStarting=false,briefData=null;const briefSort={apps:'desc',events:'desc',timeline:'desc'};
Object.assign(shapes,{sortDown:'M4 5h12 M4 10h8 M4 15h4 M19 4v16 M15 16l4 4 4-4',sortUp:'M4 5h4 M4 10h8 M4 15h12 M19 20V4 M15 8l4-4 4 4'});
const gated=['app','renew','blocked'],starts=['home','unlock',...gated];
function api(action,args={}){return new Promise((resolve,reject)=>{const id=String(++seq),timeout=setTimeout(()=>{requests.delete(id);reject(Error('응답을 기다리는 중이에요. 다시 시도해 주세요.'));},25000);requests.set(id,{resolve,reject,timeout});Native.request(id,action,JSON.stringify(args));});}
function receive(id,value,error){const r=requests.get(id);if(!r)return;clearTimeout(r.timeout);requests.delete(id);error?r.reject(Error(error)):r.resolve(value);}
function toast(text){$('#toast').textContent=text;$('#toast').style.display='block';clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('#toast').style.display='none',3500);}
async function safe(fn){try{return await fn();}catch(e){toast(e.message);}}
function fmt(ms){let s=Math.ceil(Math.max(0,ms)/1000);return `${String(Math.floor(s/60)).padStart(2,'0')}:${String(s%60).padStart(2,'0')}`;}
function mins(ms){return Math.round(ms/60000);}
function time(m){return `${String(Math.floor(m/60)).padStart(2,'0')}:${String(m%60).padStart(2,'0')}`;}
function timeVal(t){const [h,m]=t.split(':').map(Number);return h*60+m;}
function dateTime(t){return new Date(t).toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit',hour12:false});}
function header(back=false,title='Locky'){return `<div class="top">${back?`<button class="iconbtn" data-act="back" aria-label="뒤로">${icon('back')}</button>`:''}<strong>${esc(title)}</strong><span class="pill">${state?.free?'자유 시간':'집중 시간'}</span></div>`;}
function nav(){if(state?.overlay||['chat',...gated,'unlock'].includes(screen))return '';return `<div class="bottom"><nav class="nav" aria-label="주 메뉴">${[['home','home','시작'],['chats','chat','대화'],['brief','chart','브리핑'],['settings','settings','설정']].map(([s,i,l])=>`<button data-nav="${s}" class="${screen===s?'active':''}">${icon(i)}<span>${l}</span></button>`).join('')}</nav></div>`;}
function startPolling(){clearInterval(poll);poll=null;if(['chat','chats','timer',...gated].includes(screen)&&document.visibilityState!=='hidden')poll=setInterval(()=>refresh(),1000);}
function remember(){if(state&&!state.overlay&&!gated.includes(screen)&&screen!=='unlock')safe(()=>api('remember',{screen,chat:chatId,note:noteId}));}
async function navigate(s,navigation=nativeNavigation){if(['home','unlock','app'].includes(s)&&!dictionaryHandoff)reshufflePurposeOnRender=true;dictionaryHandoff=false;if(settingsTimer&&screen==='settings'){clearTimeout(settingsTimer);settingsTimer=null;await safe(saveReminders);}await flushMemo();if(navigation<nativeNavigation)return;nativeNavigation=navigation;closeSheet();screen=s;selected='';minutes=5;document.body.classList.toggle('start-screen',starts.includes(screen));window.scrollTo(0,0);await refresh(true);startPolling();remember();if(state&&['notes','chat','chats'].includes(s))safe(()=>api('utility',{name:s==='notes'?'메모':'ChatGPT'}));}
async function refresh(force=false){if(refreshing){if(force)refreshAgain=true;return;}refreshing=true;try{
 if(!force&&state&&['chat','timer',...gated].includes(screen))Object.assign(state,await api('tick'));else state=await api('state');
 if(!restored){restored=true;chatId=chatId||state.savedChat||'';noteId=state.savedNote||noteId;}
 if(!force){if(screen==='chat'){await renderMessages(false);return;}if(screen==='timer'||gated.includes(screen)){updateTimers();return;}if(document.activeElement?.matches('input,textarea,select')||memoPending||memoSaving)return;if(screen==='chats'){const c=$('#connection');if(c){c.innerHTML=connectionHTML();bind(c);}return;}}
 const oldScroll=window.scrollY;render();if(!force)window.scrollTo(0,oldScroll);if(screen==='brief')loadBrief();if(screen==='apps')loadApps();if(screen==='usageApps')loadUsageApps();if(screen==='chat')renderMessages(force);
 }catch(e){toast(e.message);}finally{refreshing=false;if(refreshAgain){refreshAgain=false;refresh(true);}}}
function openChatById(id,navigation=nativeNavigation){chatId=id;return navigate('chat',navigation);}
function signalRendered(){Native.request('0','rendered',JSON.stringify({screen,navigation:nativeNavigation}));}
function nativeRestore(){if($('#app')?.childElementCount)signalRendered();else return refresh(true);}
function render(){if($('#quickQuestion'))quickQuestionDraft=$('#quickQuestion').value;if($('#word'))dictionaryWordDraft=$('#word').value;let html='';switch(screen){case 'unlock':case 'home':html=home();break;case 'setup':html=setup();break;case 'settings':html=settings();break;case 'apps':html=appsPage();break;case 'usageApps':html=usageAppsPage();break;case 'app':case 'renew':case 'blocked':html=gate();break;case 'timer':html=timer();break;case 'notes':html=notesPage();break;case 'chats':html=chats();break;case 'chat':html=chat();break;case 'brief':html=brief();break;case 'week':briefDays=7;screen='brief';html=brief();break;default:screen='home';html=home();}
 document.body.classList.toggle('start-screen',starts.includes(screen));$('#app').innerHTML=html+nav();bind();viewportChanged();if(dictionaryHandoffScroll!==null){const pos=dictionaryHandoffScroll;dictionaryHandoffScroll=null;requestAnimationFrame(()=>window.scrollTo(0,pos));}signalRendered();}
function dismissButton(){return `<button class="dismiss" data-act="dismiss">${icon('power')}그냥 끄기</button>`;}
function home(){return `<main class="start-page ${state.overlay?'overlay-start':''}">${header(false,'휴대폰 시작')}<div class="quick-tools"><section class="mini-note">${memoHTML(false)}</section><section class="quick-chat"><div class="mini-head"><button data-act="openChat">${icon('chat')} ChatGPT</button><button class="iconbtn" data-act="openChat" aria-label="대화 목록">${icon('expand')}</button></div><textarea id="quickQuestion" placeholder="바로 질문하기" aria-label="바로 질문하기" maxlength="20000">${esc(quickQuestionDraft)}</textarea><button class="quick-send" data-act="quickSend" aria-label="질문 전송">${icon('send')}</button></section></div><form id="dictionaryForm" class="dictionary-bar"><button type="button" data-act="dictionaryChoice" aria-label="사전 선택">${icon('book')}</button><input id="word" value="${esc(dictionaryWordDraft)}" placeholder="사전에서 찾을 단어" aria-label="찾을 단어"><button type="submit" aria-label="사전 검색">${icon('search')}</button></form><section class="start-purposes" aria-labelledby="purposeQuestion"><div class="purpose-intro"><div class="sectionhead"><h2 id="purposeQuestion">무엇을 하려고 열었나요?</h2><button class="textbtn purpose-edit" data-act="managePurposes" aria-label="시작 목적 편집">편집</button></div><p class="purpose-helper">목적을 선택하면 휴대폰 사용을 시작합니다.</p></div>${purposeGrid(false)}</section><footer class="start-footer">${!state.status.accessibility?'<button class="textbtn" data-nav="setup">자동 관리를 위한 권한 설정</button>':''}${dismissButton()}</footer></main>`;}
function reshufflePurposesForStart(){reshufflePurposeOnRender=true;if(state&&['home','unlock','app'].includes(screen)&&!$('#sheet')?.innerHTML)render();}
function visiblePurposes(isApp){
 const scope=isApp?(state.gate.pkg||'app'):'phone';
 const available=state.allPurposes.filter(p=>p.scope===scope||(isApp&&p.scope==='app')||(!isApp&&p.scope==='*'));
 const ids=available.map(p=>p.id),known=purposePositions.get(scope)||[];
 if(reshufflePurposeOnRender||known.length!==ids.length||ids.some(id=>!known.includes(id))){
   const shuffled=[...ids];for(let i=shuffled.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[shuffled[i],shuffled[j]]=[shuffled[j],shuffled[i]];}
   const previous=previousPurposePositions.get(scope)||known;
   if(shuffled.length>1&&shuffled.every((id,i)=>id===previous[i]))[shuffled[0],shuffled[1]]=[shuffled[1],shuffled[0]];
   purposePositions.set(scope,shuffled);previousPurposePositions.set(scope,shuffled);reshufflePurposeOnRender=false;
 }
 const order=purposePositions.get(scope)||ids;
 return available.sort((a,b)=>order.indexOf(a.id)-order.indexOf(b.id));
}
function purposeGrid(isApp,all=false){const ps=visiblePurposes(isApp);
 return `<div class="reason-grid ${all?'all-reasons':''}"><button class="reason direct" data-act="direct">${icon('plus')} 직접 적기</button>${ps.map(p=>`<div class="purpose-item"><button class="reason ${p.id===selected?'selected':''} ${p.aimless?'aimless':''}" data-purpose="${esc(p.id)}"><span>${esc(p.label)}</span>${p.pkg||p.uri?icon('link'):''}</button>${!state.overlay&&screen==='home'?`<button class="preset-edit" data-editpurpose="${esc(p.id)}" aria-label="${esc(p.label)} 편집">${icon('more')}</button>`:''}</div>`).join('')}${!all?`<button class="reason more-reasons" data-act="morePurposes" hidden>${icon('more')} 전체 ${ps.length}개 보기</button>`:''}</div>`;}
function currentNote(){if(!state.notes.some(n=>n.id===noteId))noteId=state.notes[0]?.id||'';return state.notes.find(n=>n.id===noteId)||{id:'',title:'메모',body:'',checks:'[]',mode:'text'};}
function memoHTML(full){let n=currentNote(),checks=[];try{checks=JSON.parse(n.checks||'[]');}catch{}const check=n.mode==='check';return `<div class="note-panel ${full?'full-note':'compact-note'}"><div class="mini-head">${full?`<input class="note-title" id="noteTitle" maxlength="100" value="${esc(n.title)}" aria-label="메모 제목">`:`<button class="note-heading" data-nav="notes">${icon('note')} ${esc(n.title||'메모')}</button>${state.notes.length>1?`<button class="iconbtn" data-note-step="-1" aria-label="이전 메모">${icon('back')}</button><button class="iconbtn" data-note-step="1" aria-label="다음 메모">${icon('arrow')}</button>`:'' }<button class="iconbtn" data-nav="notes" aria-label="메모 크게 보기">${icon('expand')}</button>`}</div>${full?`<div class="note-tabs">${state.notes.map(n=>`<button class="${n.id===noteId?'selected':''}" data-note="${esc(n.id)}">${esc(n.title||'메모')}</button>`).join('')}</div>`:''}<div class="note-content">${check?`<div id="checks">${checks.map((c,i)=>`<div class="checkrow ${c.done?'done':''}"><input type="checkbox" data-check="${i}" ${c.done?'checked':''} aria-label="완료"><textarea data-checktext="${i}" rows="1" aria-label="할 일">${esc(c.text)}</textarea><button data-delcheck="${i}" aria-label="할 일 삭제">${icon('close')}</button></div>`).join('')}</div>`:`<textarea id="noteBody" placeholder="메모를 바로 적으세요" aria-label="메모 내용">${esc(n.body)}</textarea>`}</div><div class="note-actions"><span id="saveState" class="small muted">자동 저장</span>${check?`<button class="iconbtn" data-act="addCheck" aria-label="체크 항목 추가">${icon('plus')}</button>`:''}${full?`<select id="noteMode" aria-label="메모 종류"><option value="text" ${!check?'selected':''}>글 메모</option><option value="check" ${check?'selected':''}>체크 메모</option></select>`:''}<button class="iconbtn" data-act="newNote" aria-label="새 메모">${icon('newnote')}</button>${full&&n.id?`<button class="iconbtn danger" data-act="deleteNote" aria-label="메모 삭제">${icon('trash')}</button>`:''}</div></div>`;}
function notesPage(){return `<main class="page notes-page ${state.overlay?'overlay-note':''}">${header(true,'메모')}${memoHTML(true)}</main>`;}
function gate(){
 const g=state.gate,locked=screen==='blocked',renew=screen==='renew';
 const duration=`<section class="duration"><h3>${renew?'더 사용할 시간':'이번에 사용할 시간'} · 분</h3><div class="minutes">${[5,6,7,8,9,10].map(m=>`<button data-min="${m}" class="${minutes===m?'selected':''}" aria-pressed="${minutes===m}">${m}</button>`).join('')}</div></section>`;
 return `<main class="start-page gate-page overlay-start">${header(false,g.label||'앱 사용')}<div class="gate-info"><h2>${locked?'지금은 쉬어갈 시간이에요':renew?'조금 더 머물고 싶나요?':'무엇을 하러 들어가나요?'}</h2>${!locked&&!renew?`<button class="iconbtn gate-edit" data-act="managePurposes" aria-label="앱 사용 목적 편집">${icon('more')}</button>`:''}<span>오늘 남은 <strong id="dailyLeft">${fmt(g.daily)}</strong></span></div>${locked?`<p class="muted">자유 시간이 시작되면 다시 열 수 있어요.<br>${time(state.prefs.start)}–${time(state.prefs.end)} 집중 시간</p>`:renew?`<div class="gate-note"><span>처음 정한 목적</span><strong>${esc(g.purpose||'선택한 목적 없음')}</strong></div>${duration}`:`<div class="app-decision">${duration}<section class="start-purposes"><p class="small muted">목적을 선택하면 바로 시작합니다.</p>${purposeGrid(true)}</section></div>`}<footer class="start-footer">${renew?`<button id="startApp" class="primary" data-act="extend">같은 목적으로 연장 ${icon('arrow')}</button>`:''}${dismissButton()}</footer></main>`;
}
async function choosePurpose(id){
 if(purposeStarting)return;purposeStarting=true;const target=screen,chosenMinutes=minutes;
 const controls=[...document.querySelectorAll('[data-purpose],[data-min],[data-act=direct]')];controls.forEach(el=>el.disabled=true);
 try{await flushMemo();await api(target==='app'?'grant':'choose',target==='app'?{id,minutes:chosenMinutes}:{id});closeSheet();}
 finally{purposeStarting=false;controls.forEach(el=>{if(el.isConnected)el.disabled=false;});}
}

function timer(){let g=state.gate;return `<main class="page">${header(true,'남은 시간')}<h2>${esc(g.label||'사용 중인 관리 앱 없음')}</h2><div class="timer-ring"><div class="small">오늘 남은 시간</div><div id="dailyLeft" class="bigtime">${fmt(g.daily)}</div><div class="divider"></div><div class="small">이번 사용의 남은 시간</div><h2 id="sessionLeft">${fmt(g.session)}</h2><p class="small muted">이 화면을 보는 동안에는 잠시 멈춰요.</p></div><div class="gate-note"><span>이번 목적</span><strong>${esc(g.purpose||'선택한 목적 없음')}</strong></div><button class="secondary" data-act="exit">사용하던 앱으로 돌아가기</button>${!state.status.notifications?'<div class="notice"><button data-permission="notification">타이머 알림 허용하기</button></div>':''}</main>`;}
function updateTimers(){if($('#dailyLeft'))$('#dailyLeft').textContent=fmt(state.gate.daily);if($('#sessionLeft'))$('#sessionLeft').textContent=fmt(state.gate.session);}
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
function setup(){return `<main class="page">${header(false,'초기 설정')}<section class="card">${[['accessibility','첫 화면과 앱 관리','접근성 → 설치된 앱 → Locky'],['usage','앱 사용 기록','사용 정보 접근에서 Locky 허용'],['notification','타이머·브리핑 알림','알림을 허용하면 남은 시간을 표시해요'],['exact','시간 전환 알림','정확한 알람 허용']].map(([k,l,d])=>`<div class="permission"><div class="row"><strong>${l}</strong><button data-permission="${k}">${state.status[k==='notification'?'notifications':k]?'설정 확인':'허용'}</button></div><p>${d}</p></div>`).join('')}</section>${liveTimerSettings()}<div class="card"><h3>배터리 설정</h3><p class="small muted">삼성 절전 앱에서 Locky를 제외해 주세요. 접근성 설정이 막혀 있다면 앱 정보 메뉴에서 ‘제한된 설정 허용’을 먼저 켜 주세요.</p><button class="secondary" data-permission="battery">배터리 설정 열기</button></div><button class="primary" data-nav="home">시작</button></main>`;}
function settings(){let p=state.prefs;return `<main class="page">${header(false,'설정')}<section class="card"><div class="row"><h3>집중 시간 관리</h3><button id="enabled" class="toggle ${p.enabled?'on':''}" role="switch" aria-checked="${p.enabled}" aria-label="집중 시간 관리">${p.enabled?'켜짐':'꺼짐'}</button></div><div class="split"><div><label for="startTime">집중 시작</label><input class="field" type="time" id="startTime" value="${time(p.start)}"></div><div><label for="endTime">자유 시간 시작</label><input class="field" type="time" id="endTime" value="${time(p.end)}"></div></div><p class="small muted">변경 즉시 적용 · 매일 반복</p></section><section class="card"><div class="row"><h3>화면 위 타이머</h3><button id="floatingTimer" class="toggle ${p.floatingTimer!==false?'on':''}" role="switch" aria-checked="${p.floatingTimer!==false}" aria-label="화면 위 타이머">${p.floatingTimer!==false?'켜짐':'꺼짐'}</button></div><p class="small muted">누르면 남은 시간과 목적을 확인하고 사용을 종료할 수 있어요. 길게 눌러 위치를 옮길 수 있고, 전체 화면에서는 더 작게 표시돼요. 알림창·최근 앱·다른 앱에서는 숨겨져요.</p></section><section class="card"><h3>집중 시작 전 알림</h3><div class="reminder-buttons">${[30,20,10,5,1].map(m=>`<button class="reminder ${p.reminders.includes(m)?'selected':''}" data-reminder="${m}" aria-pressed="${p.reminders.includes(m)}">${m}분</button>`).join('')}</div><label for="customReminders">추가 알림 · 분</label><input class="field" id="customReminders" inputmode="numeric" placeholder="예: 45, 15" value="${p.reminders.filter(n=>![30,20,10,5,1].includes(n)).join(', ')}"><label for="weeklyDay">주간 브리핑</label><select class="field" id="weeklyDay">${['월','화','수','목','금','토','일'].map((d,i)=>`<option value="${i+1}" ${p.weeklyDay===i+1?'selected':''}>${d}요일 · 자유 시간에</option>`).join('')}</select></section><button class="setting-link card" data-nav="apps">${icon('lock')}<strong>관리할 앱</strong><span>${state.rules.length}개</span>${icon('arrow')}</button><button class="setting-link card" data-nav="usageApps">${icon('clock')}<strong>사용시간 제외 앱</strong>${icon('arrow')}</button><button class="setting-link card" data-act="managePurposes">${icon('link')}<strong>사용 목적과 연결</strong>${icon('arrow')}</button><button class="setting-link card" data-act="dictionaryChoice">${icon('book')}<strong>기본 사전</strong>${icon('arrow')}</button><button class="setting-link card" data-nav="setup">${icon('settings')}<strong>권한·배터리 설정</strong>${icon('arrow')}</button><p class="small muted">Locky 1.1.1 · 설정은 자동 저장돼요</p></main>`;}
function appsPage(){return `<main class="page">${header(true,'관리할 앱')}<input id="appSearch" class="field" placeholder="앱 이름으로 검색" aria-label="앱 검색"><div id="appList" class="card"><p class="empty">불러오는 중</p></div></main>`;}
async function getApps(){if(appsCache)return appsCache;if(!appsPromise)appsPromise=api('apps').then(items=>(appsCache=items,items)).finally(()=>{appsPromise=null;});return appsPromise;}
async function loadApps(){await safe(async()=>{await getApps();renderApps();});}
function renderApps(){let list=$('#appList');if(!list||!appsCache)return;let q=$('#appSearch')?.value.toLowerCase()||'',seen=state.ruleSeen||[];
 const rank=a=>state.rules.some(r=>r.pkg===a.pkg)?0:seen.includes(a.pkg)?1:2;
 list.innerHTML=appsCache.filter(a=>a.label.toLowerCase().includes(q)).slice().sort((a,b)=>rank(a)-rank(b)||((rank(a)<2)?seen.indexOf(a.pkg)-seen.indexOf(b.pkg):a.label.localeCompare(b.label,'ko'))).map(a=>{let r=state.rules.find(r=>r.pkg===a.pkg);return `<div class="app-row"><input type="checkbox" class="manage-check" data-manage="${esc(a.pkg)}" ${r?'checked':''} aria-label="${esc(a.label)} 관리"><img src="${esc(a.icon)}" alt=""><div class="info"><strong>${esc(a.label)}</strong><p>${r?.mode==='limited'?`하루 ${r.minutes}분`:r?.mode==='locked'?'집중 중 잠금':seen.includes(a.pkg)?'이전에 선택한 앱':'제한 없음'}</p></div><button class="iconbtn" data-rule="${esc(a.pkg)}" aria-label="${esc(a.label)} 관리 방식">${icon('more')}</button></div>`;}).join('')||'<p class="empty">검색 결과 없음</p>';bind(list);}
function connectionHTML(){let c=state.connection,connected=state.accounts.find(a=>a.active&&a.connected);return connected?`<div class="row"><div><strong>${esc(connected.email||'연결된 ChatGPT 계정')}</strong><p class="small muted" style="margin:4px 0 0">${connected.plan?'ChatGPT 요금제로 사용 중':'요금제 사용 승인 필요'}</p></div><button class="textbtn" data-act="accountSheet">계정</button></div><button class="textbtn" data-act="manageUsage">ChatGPT 사용량 관리 ${icon('arrow')}</button>`:`<p class="small muted">ChatGPT 계정을 연결하면 이곳에서 간단한 대화를 나눌 수 있어요. 사용 가능한 요금제와 계정의 모델을 사용해요.</p><button class="primary" data-act="signin">Continue with ChatGPT</button>${c.text?`<p class="small muted" style="margin:12px 0 0">${esc(c.text)}</p>`:''}${c.detail?`<p class="small danger">${esc(c.detail)}</p>`:''}${['starting','checking','browser','returning','exchange','identity','saving'].includes(c.phase)?'<button class="textbtn" data-act="cancelLogin">연결 취소</button>':''}`;}

function chats(){return `<main class="page">${header(state.overlay,'ChatGPT')}<section id="connection" class="card">${connectionHTML()}</section>${state.accounts.some(a=>a.active&&a.connected)?`<section class="card"><label for="model">모델 · 웹 검색 활성화</label><div class="link-line"><select class="field" id="model">${state.models.map(m=>`<option value="${esc(m.slug)}" ${state.prefs.model===m.slug?'selected':''}>${esc(m.name)}</option>`).join('')}</select><button class="iconbtn" data-act="models" aria-label="모델 새로고침">↻</button></div></section>`:''}<div class="sectionhead"><h2>대화</h2><button class="iconbtn" data-act="newChat" aria-label="새 대화">${icon('plus')}</button></div><div class="card chat-list">${state.chats.map(c=>`<div class="row"><button data-chat="${esc(c.id)}"><strong>${esc(c.title)}</strong></button><button class="iconbtn danger" data-deletechat="${esc(c.id)}" aria-label="대화 삭제">${icon('trash')}</button></div>`).join('')||'<p class="empty">새 대화를 시작해 보세요</p>'}</div></main>`;}
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

async function renderMessages(force){let box=$('#messages');if(!box)return;const id=chatId,messages=await api('messages',{id});if(id!==chatId||screen!=='chat')return;const atBottom=window.innerHeight+window.scrollY>=document.body.scrollHeight-150;
 const html=messages.map((m,i)=>`<div class="message ${esc(m.role)}">${m.role==='assistant'?'<div class="chat-markdown">'+chatBody(m.body||(m.status==='streaming'?'답변을 생각하고 있어요…':''),m.citations,m.id||i)+'</div>':'<div class="chat-plain">'+esc(m.body||'')+'</div>'}${m.status==='interrupted'?'<span class="small danger">완료하지 못한 답변</span>':''}</div>`).join('')||'<p class="empty">무엇이 궁금한가요?</p>';
 if(box.innerHTML!==html){const expanded=new Set([...box.querySelectorAll('[data-source-toggle][aria-expanded="true"]')].map(el=>el.dataset.sourceToggle));box.innerHTML=html;bind(box);for(const sourceId of expanded){const trigger=[...box.querySelectorAll('[data-source-toggle]')].find(el=>el.dataset.sourceToggle===sourceId),panel=document.getElementById(sourceId);if(trigger&&panel){trigger.setAttribute('aria-expanded','true');panel.hidden=false;}}if(atBottom||force)window.scrollTo(0,document.body.scrollHeight);}const button=$('.composer button');if(button){button.dataset.act=state.busy?'cancelResponse':'send';button.innerHTML=icon(state.busy?'close':'send');button.setAttribute('aria-label',state.busy?'답변 중지':'전송');}
 if(state.chatError&&state.chatError!==renderMessages.lastError){renderMessages.lastError=state.chatError;toast(state.chatError);}}
function brief(){return `<main class="page">${header(state.overlay,'브리핑')}<div class="segmented"><button data-days="1" class="${briefDays===1?'active':''}">하루</button><button data-days="7" class="${briefDays===7?'active':''}">일주일</button></div><div class="row"><button class="iconbtn" data-act="previousBrief" aria-label="이전 날짜">${icon('back')}</button><span id="briefDate" class="small muted"></span><button class="iconbtn" data-act="nextBrief" ${briefOffset===0?'disabled':''} aria-label="다음 날짜">${icon('arrow')}</button></div><div class="brief-export"><div><strong>하루 원본 기록</strong><p class="small muted">선택한 날짜의 사용 구간·목적 기록 JSON</p></div><div class="brief-export-actions"><button class="secondary" data-act="copyDay">복사</button><button class="secondary" data-act="saveDay">파일 저장</button></div></div><div id="briefContent"><p class="empty">기록을 모으는 중</p></div></main>`;}
function collapsible(rows,renderRow,count=4){return rows.slice(0,count).map(renderRow).join('')+(rows.length>count?`<details class="more-list"><summary>나머지 ${rows.length-count}개 펼치기</summary>${rows.slice(count).map(renderRow).join('')}</details>`:'');}
async function loadBrief(){const sequence=++briefSeq;await safe(async()=>{const b=await api('brief',{days:briefDays,offset:briefOffset});if(!$('#briefContent')||sequence!==briefSeq)return;briefData=b;$('#briefDate').textContent=b.days===7?`${new Date(b.start).toLocaleDateString('ko-KR')} – ${b.date}`:b.date;renderBrief(b);});}
function briefSortButton(key){
 const desc=briefSort[key]==='desc',name=key==='apps'?'사용한 앱':key==='events'?'시작의 이유':'앱 사용 시간선';
 const order=key==='apps'?(desc?'사용시간 많은 순':'사용시간 적은 순'):(desc?'최근 기록부터':'오래된 기록부터');
 return `<button type="button" class="iconbtn brief-sort" data-brief-sort="${key}" aria-label="${name}: ${order}, 정렬 전환" title="${order}">${icon(desc?'sortDown':'sortUp')}</button>`;
}
function renderBrief(b){
 const area=$('#briefContent');if(!area)return;
 const opened=[...area.querySelectorAll('details')].map(d=>d.open),position=window.scrollY;
 const direction=key=>briefSort[key]==='desc'?-1:1;
 const apps=[...b.apps].sort((a,b)=>direction('apps')*(a.ms-b.ms)||a.label.localeCompare(b.label,'ko'));
 const events=[...b.events].filter(e=>['unlock','app','utility','extend'].includes(e.kind)).sort((a,b)=>direction('events')*(a.time-b.time));
 const timeline=[...b.timeline].sort((a,b)=>direction('timeline')*(a.start-b.start));
 area.innerHTML=`<div class="leaf"><p>${esc(b.summary)}</p></div><div class="statgrid"><div class="stat"><b>${mins(b.total)}</b><span>사용 · 분</span></div><div class="stat"><b>${b.unlocks}</b><span>시작</span></div><div class="stat"><b>${b.aimless}</b><span>무목적</span></div></div><div class="card"><h3>목적과 실제 사용</h3><p class="small muted">${esc(b.insight)}</p></div>${!b.usageAccess?'<div class="notice">사용 정보 접근을 켜면 중단 구간도 보완해요.</div>':''}${b.recovered?`<p class="small muted">Android 기록으로 ${mins(b.recovered)}분 보완 · 해당 구간은 목적 미확인</p>`:''}<section class="section"><div class="sectionhead"><h3>사용한 앱</h3>${briefSortButton('apps')}</div><div class="card">${collapsible(apps,a=>`<div class="usage-row"><div class="row"><strong>${esc(a.label)}</strong><span>${mins(a.ms)}분</span></div><div class="meter"><span style="width:${b.total?Math.max(1,a.ms/b.total*100):0}%"></span></div></div>`)||'<p class="empty">기록 없음</p>'}</div></section><section class="section"><div class="sectionhead"><h3>시작의 이유</h3>${briefSortButton('events')}</div><div class="card">${collapsible(events,e=>`<div class="event-row"><time>${b.days===7?new Date(e.time).toLocaleDateString('ko-KR')+' · ':''}${dateTime(e.time)}</time><div><strong>${esc(e.purpose)}</strong><span>${e.kind==='unlock'?'휴대폰':e.kind==='extend'?'연장':e.kind==='utility'?'도구 사용':esc(state.rules.find(r=>r.pkg===e.pkg)?.label||appsCache?.find(a=>a.pkg===e.pkg)?.label||e.pkg)}</span></div></div>`)||'<p class="empty">기록 없음</p>'}</div></section><details class="card brief-timeline"><summary><span>앱 사용 시간선</span>${briefSortButton('timeline')}</summary>${collapsible(timeline,t=>`<div class="app-row"><div class="info"><strong>${esc(t.label)}</strong><p>${dateTime(t.start)}–${dateTime(t.end)} · ${esc(t.purpose)}</p>${t.initialPurpose&&t.initialPurpose!==t.purpose?`<p>처음 목적 · ${esc(t.initialPurpose)}</p>`:''}</div><span class="small">${mins(t.end-t.start)}분</span></div>`,10)}</details>`;
 [...area.querySelectorAll('details')].forEach((d,i)=>d.open=!!opened[i]);bind(area);window.scrollTo(0,position);
}

function sheet(content){$('#sheet').innerHTML=`<section class="sheet" role="dialog" aria-modal="true">${content}</section>`;$('#sheet').onclick=e=>{if(e.target===$('#sheet'))closeSheet();};bind($('#sheet'));viewportChanged();}
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
 sheet(`${sheetHead(direct?'이번에 할 일':'사용 목적')}<div class="formrow"><label for="purposeLabel">목적</label><input class="field" id="purposeLabel" maxlength="100" value="${esc(p.label)}" placeholder="예: 친구에게 답장"></div><label class="link-toggle"><input id="purposeLink" type="checkbox" ${p.pkg||p.uri?'checked':''}> 목적 선택 시 앱이나 동작 열기</label><div id="purposeConnection" ${p.pkg||p.uri?'':'hidden'}><div class="formrow"><label for="purposeAppSearch">앱 아이콘에서 찾기</label><input class="field" id="purposeAppSearch" type="search" placeholder="앱 이름으로 검색" autocomplete="off"><div id="purposeAppPreview" class="purpose-app-preview"></div><div id="purposeAppResults" class="purpose-app-results"></div><p class="small muted" id="purposeAppCount">앱 목록 준비 중</p><label for="purposeApp" class="small muted">전체 앱 목록에서 선택</label><select class="field" id="purposeApp" disabled><option value="${esc(p.pkg)}">${esc(p.pkg?`기존 연결 · ${p.pkg}`:'목록 준비 중')}</option></select></div><div class="formrow"><label for="purposeUri">앱 동작 주소 · 선택</label><input class="field" id="purposeUri" value="${esc(p.uri)}" placeholder="앱이 지원하는 딥링크"><button class="textbtn" data-act="testPurposeLink">연결 시험 ${icon('arrow')}</button></div></div>${!direct?`<div class="formrow"><label for="purposeScope">표시할 화면</label><select class="field" id="purposeScope"><option value="phone" ${p.scope==='phone'?'selected':''}>휴대폰 시작</option><option value="app" ${p.scope==='app'?'selected':''}>모든 관리 앱</option>${p.scope!=='phone'&&p.scope!=='app'?`<option value="${esc(p.scope)}" selected>선택한 앱</option>`:''}</select></div>`:''}<div class="sheet-actions"><button class="primary" id="savePurpose" data-act="savePurpose" data-id="${esc(id||'')}" data-scope="${esc(p.scope)}" data-direct="${direct}" data-aimless="${p.aimless?1:0}">${direct?'저장하고 시작':'저장'} ${icon('check')}</button>${id&&!direct?`<button class="iconbtn danger" data-act="deletePurpose" data-id="${esc(id)}" aria-label="목적 삭제">${icon('trash')}</button>`:''}</div>`);
 const link=$('#purposeLink');link.onchange=()=>{const enabled=link.checked;$('#purposeConnection').hidden=!enabled;if(enabled)safe(()=>populatePurposeApps(p.pkg,p.scope));};
 if(!direct||link.checked)safe(()=>populatePurposeApps(p.pkg,p.scope));
 if($('#purposeAppSearch'))$('#purposeAppSearch').oninput=renderPurposeAppResults;
 if($('#purposeApp'))$('#purposeApp').onchange=renderPurposeAppResults;
 $('#purposeLabel').focus({preventScroll:true});
}
function managePurposes(scope=purposeScope){purposeScope=scope;sheet(`${sheetHead('사용 목적')}<div class="segmented"><button data-switchscope="phone" class="${purposeScope==='phone'?'active':''}">휴대폰 시작</button><button data-switchscope="app" class="${purposeScope==='app'?'active':''}">앱 시작</button></div><div class="purpose-manage">${state.allPurposes.filter(p=>purposeScope==='phone'?p.scope==='phone'||p.scope==='*':p.scope!=='phone'&&p.scope!=='*').map(p=>`<div class="app-row"><div class="info"><strong>${esc(p.label)}</strong><p>${esc(p.pkg?'연결 앱 · '+(appsCache?.find(a=>a.pkg===p.pkg)?.label||p.pkg):p.uri?'연결된 동작':'앱 연결 없음')}</p></div><button class="iconbtn" data-editpurpose="${esc(p.id)}" aria-label="목적 편집">${icon('more')}</button><button class="iconbtn danger" data-remove="${esc(p.id)}" aria-label="목적 삭제">${icon('trash')}</button></div>`).join('')}</div><div class="sheet-actions"><button class="primary" data-act="addPurpose">${icon('plus')} 추가</button></div>`);}
async function ruleSheet(pkg){await getApps();let a=appsCache.find(a=>a.pkg===pkg),r=state.rules.find(r=>r.pkg===pkg)||{mode:'limited',minutes:60};if(!a)return;
 sheet(`${sheetHead(a.label)}<label for="ruleMode">관리 방식</label><select class="field" id="ruleMode"><option value="none" ${r.mode==='none'?'selected':''}>관리 안 함</option><option value="limited" ${r.mode==='limited'?'selected':''}>시간 조절</option><option value="locked" ${r.mode==='locked'?'selected':''}>집중 중 잠금</option></select><div class="formrow" id="dailyControl"><label for="dailyMinutes">하루 <b id="dailyValue">${r.minutes}</b>분</label><input id="dailyMinutes" type="range" min="5" max="120" step="5" value="${r.minutes}"></div><p class="small muted">변경 즉시 적용 · 기본 60분, 최대 120분</p>`);
 const save=()=>safe(async()=>{const rule={pkg,mode:$('#ruleMode').value,minutes:Number($('#dailyMinutes').value)};await api('rule',rule);state=await api('state');renderApps();});const update=()=>{const c=$('#dailyControl');if(c)c.style.display=$('#ruleMode').value==='limited'?'':'none';};$('#ruleMode').onchange=()=>{update();save();};$('#dailyMinutes').oninput=()=>$('#dailyValue').textContent=$('#dailyMinutes').value;$('#dailyMinutes').onchange=save;update();}
async function dictionaryChoice(word=''){await api('dictionaryPicker',{word});}
function usageAppsPage(){return `<main class="page">${header(true,'사용시간 제외 앱')}<p class="muted">시스템 앱은 모두 제외돼요. 체크한 앱도 사용시간과 브리핑에서 제외해요.</p><input id="usageSearch" class="field" placeholder="앱 이름으로 검색"><div id="usageList" class="card"></div></main>`;}
let usageCache=[];
async function loadUsageApps(){usageCache=await api('usageApps');renderUsageApps();}
function renderUsageApps(){let query=$('#usageSearch')?.value.trim().toLowerCase()||'';$('#usageList').innerHTML=usageCache.filter(a=>(a.label+' '+a.pkg).toLowerCase().includes(query)).sort((a,b)=>Number(b.excluded)-Number(a.excluded)||a.label.localeCompare(b.label)).map(a=>`<label class="app-row"><input class="manage-check" type="checkbox" data-exclude="${esc(a.pkg)}" ${a.excluded?'checked':''} ${a.system?'disabled':''}>${a.icon?`<img src="${esc(a.icon)}" alt="">`:icon('settings')}<span class="info"><strong>${esc(a.label)}</strong><p>${a.system?'시스템 앱 · 자동 제외':esc(a.pkg)}</p></span></label>`).join('');bind($('#usageList'));}
async function accountSheet(){sheet(`<div class="sectionhead"><h2>ChatGPT 계정</h2><button class="iconbtn" data-act="closeSheet" aria-label="닫기">${icon('close')}</button></div>${state.accounts.map(a=>`<div class="app-row"><div class="info"><strong>${esc(a.email||a.client)}</strong><p>${a.active?'현재 계정 · ':''}${a.connected?'연결됨':'연결 해제됨'}</p></div><button class="textbtn" data-account="${esc(a.client)}">선택</button></div>`).join('')}<div class="stack" style="margin-top:20px"><button class="primary" data-act="signin">다른 계정 연결</button><button class="secondary" data-act="consent">요금제 사용 승인</button><button class="secondary danger" data-act="signout">현재 계정 연결 해제</button></div>`);}

function confirmSheet(title,detail,action,args){sheet(`${sheetHead(title)}<p class="muted">${esc(detail)}</p><div class="sheet-actions"><button id="confirm" class="primary danger">삭제</button></div>`);$('#confirm').onclick=()=>safe(async()=>{await api(action,args);closeSheet();if(action==='deleteNote')noteId='';await refresh(true);});}
function captureMemo(){const body=$('#noteBody'),checks=$('#checks');if(!body&&!checks)return null;let old=currentNote();const values=checks?[...checks.querySelectorAll('.checkrow')].map(r=>({text:r.querySelector('[data-checktext]').value,done:r.querySelector('input[type=checkbox]').checked})):JSON.parse(old.checks||'[]');
 const note={id:old.id||crypto.randomUUID(),title:$('#noteTitle')?.value||old.title||'메모',body:body?body.value:old.body||'',checks:values,mode:old.mode||'text'};noteId=note.id;return note;}
function insertCheckRow(after,text=''){
 const list=$('#checks');if(!list)return;
 const row=document.createElement('div');row.className='checkrow';
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
async function flushMemo(){clearTimeout(memoTimer);if(memoSaving)await memoSaving;if(!memoPending)return;const note=memoPending;memoPending=null;memoSaving=(async()=>{try{await api('saveNote',note);let n=state?.notes.find(n=>n.id===note.id);let value={...note,checks:JSON.stringify(note.checks),updated:Date.now()};if(n)Object.assign(n,value);else state?.notes.unshift(value);if($('#saveState'))$('#saveState').textContent='자동 저장';}catch(e){if(!memoPending)memoPending=note;throw e;}})();try{await memoSaving;}finally{memoSaving=null;}if(memoPending)await flushMemo();}
function autoNoteHeight(){const scroller=$('.full-note .note-content');if(!scroller)return;const top=scroller.scrollTop;scroller.querySelectorAll('textarea').forEach(el=>{const key=el.value+'|'+el.clientWidth;if(el._measureKey===key)return;el._measureKey=key;el.style.height='auto';el.style.height=Math.max(el.id==='noteBody'?scroller.clientHeight-8:32,el.scrollHeight)+'px';});scroller.scrollTop=top;}
function insetsChanged(visible){nativeKeyboard=visible;viewportChanged(visible);}
function layoutStart(){const page=$('.start-page');if(!page||document.body.classList.contains('keyboard'))return;const area=page.querySelector('.start-purposes'),tools=page.querySelector('.quick-tools');if(!area)return;
 const grid=area.querySelector('.reason-grid');if(!grid)return;const items=[...grid.querySelectorAll('.purpose-item')],more=grid.querySelector('.more-reasons'),css=getComputedStyle(page),gap=parseFloat(css.gap)||0,areaMarginTop=parseFloat(getComputedStyle(area).marginTop)||0;
 // Keep utilities compact above the nearby purpose chooser; spare height stays below it.
 if(tools){tools.style.height='';tools.style.flex='0 0 auto';}
 const preferredTools=tools?tools.getBoundingClientRect().height:0,minimumTools=tools?Math.min(206,Math.max(128,Math.floor(page.clientHeight*.29))):0;
 const decision=page.querySelector('.app-decision');
 const children=[...page.children];let available=page.clientHeight-parseFloat(css.paddingTop)-parseFloat(css.paddingBottom)-gap*(children.length-1)-areaMarginTop;
 for(const child of children)if(child!==area&&child!==tools&&child!==decision)available-=child.getBoundingClientRect().height;
 if(decision){const frame=getComputedStyle(decision);available-=(parseFloat(frame.rowGap)||0)*(decision.children.length-1);for(const child of decision.children)if(child!==area){const style=getComputedStyle(child);available-=child.getBoundingClientRect().height+(parseFloat(style.marginTop)||0)+(parseFloat(style.marginBottom)||0);}}
 const intro=area.querySelector('.purpose-intro');
 if(intro){
   const frame=getComputedStyle(area),introStyle=getComputedStyle(intro);
   const size=name=>parseFloat(frame.getPropertyValue(name))||0;
   available-=size('padding-top')+size('padding-bottom')+size('border-top-width')+size('border-bottom-width')+intro.getBoundingClientRect().height+(parseFloat(introStyle.marginBottom)||0);
 }else{const heading=area.querySelector(':scope > p')||area.querySelector('.sectionhead');if(heading){const style=getComputedStyle(heading);available-=heading.getBoundingClientRect().height+(parseFloat(style.marginTop)||0)+(parseFloat(style.marginBottom)||5);}}
 const row=44,rowGap=6,direct=44;let count=items.length;
 const required=n=>direct+(n?rowGap+Math.ceil(n/2)*row+(Math.ceil(n/2)-1)*rowGap:0);
 while(count>0&&required(count)+(count<items.length?rowGap+34:0)+minimumTools>available)count--;
 if(count<items.length&&count%2===1)count--;
 const chosen=items.findIndex(el=>el.querySelector('.selected'));const shown=new Set(items.slice(0,count));
 if(chosen>=count&&count>0){shown.delete(items[count-1]);shown.add(items[chosen]);}
 items.forEach(el=>el.hidden=!shown.has(el));if(more)more.hidden=count===items.length;
 const content=required(count)+(count<items.length?rowGap+34:0);
 if(tools){const height=Math.max(minimumTools,Math.min(preferredTools,available-content));tools.style.height=height+'px';tools.style.flex='0 0 '+height+'px';}
 area.style.flex='0 0 auto';
}
function viewportChanged(reveal=false){requestAnimationFrame(()=>{const v=window.visualViewport,h=v?.height||innerHeight,top=v?.offsetTop||0,editing=document.activeElement?.matches('input,textarea,select');const keyboard=nativeKeyboard||innerHeight-h>100||(editing&&normalWidth===innerWidth&&normalHeight-h>100);
 if(!normalHeight||normalWidth!==innerWidth||(!keyboard&&!editing)){normalHeight=h;normalWidth=innerWidth;}
 document.documentElement.style.setProperty('--viewport',h+'px');document.documentElement.style.setProperty('--rest-height',normalHeight+'px');document.documentElement.style.setProperty('--vtop',top+'px');document.body.classList.toggle('keyboard',!!keyboard);layoutStart();autoNoteHeight();const active=document.activeElement;if(reveal===true&&active?.matches('input,textarea,select'))active.scrollIntoView({block:'nearest',inline:'nearest'});});}
async function saveReminders(){if(!$('#customReminders'))return;settingsTimer=null;const values=[...document.querySelectorAll('[data-reminder].selected')].map(el=>Number(el.dataset.reminder));const extra=$('#customReminders')?.value.trim()||'';if(extra){const parsed=extra.split(',').map(v=>Number(v.trim()));if(parsed.some(n=>!Number.isInteger(n)||n<1||n>180))throw Error('추가 알림은 1~180분으로 입력해 주세요.');values.push(...parsed);}await api('settings',{reminders:values});Object.assign(state.prefs,{reminders:values});}
async function lookup(){const word=$('#word')?.value.trim();if(!word)throw Error('찾을 단어를 입력해 주세요.');if(!state.dictionary){await dictionaryChoice(word);return;}await flushMemo();await api('dictionary',{word});}
async function action(name,el){switch(name){case 'back':back();break;case 'closeSheet':closeSheet();break;case 'exit':await flushMemo();await api('exit');break;case 'dismiss':await flushMemo();await api('dismiss');break;
 case 'direct':await purposeSheet(null,true);break;case 'addPurpose':await purposeSheet();break;case 'managePurposes':managePurposes(screen==='app'?'app':screen==='home'||screen==='unlock'?'phone':purposeScope);break;
 case 'morePurposes':sheet(`${sheetHead('사용 목적')}${purposeGrid(screen==='app',true)}`);break;
 case 'savePurpose':{if(el.disabled)return;el.disabled=true;try{if($('#purposeLink').checked&&$('#purposeApp').disabled)throw Error('앱 목록을 불러오는 중입니다.');if($('#purposeLink').checked&&!$('#purposeApp').value&&!$('#purposeUri').value.trim())throw Error('연결할 앱이나 동작 주소를 선택해 주세요.');const p={label:$('#purposeLabel').value,pkg:$('#purposeLink').checked?$('#purposeApp').value:'',uri:$('#purposeLink').checked?$('#purposeUri').value:'',scope:$('#purposeScope')?.value||el.dataset.scope,aimless:el.dataset.aimless==='1'};if(el.dataset.id)p.id=el.dataset.id;const saved=await api('purpose',p);closeSheet();state=await api('state');if(el.dataset.direct==='true'){await choosePurpose(saved.id);render();}else {render();if(screen==='settings')managePurposes();}}finally{if(el.isConnected)el.disabled=false;}break;}
 case 'deletePurpose':await api('removePurpose',{id:el.dataset.id});closeSheet();await refresh(true);break;
 case 'testPurposeLink':await api('linkTest',{pkg:$('#purposeApp').value,uri:$('#purposeUri').value});break;
 case 'grant':await flushMemo();await api('grant',{id:selected,minutes});break;case 'extend':await api('extend',{minutes});break;case 'stop':await api('stop');break;
 case 'newNote':await flushMemo();sheet(`${sheetHead('새 메모')}<div class="split"><button class="secondary" data-newnote="text">${icon('note')} 글 메모</button><button class="secondary" data-newnote="check">${icon('check')} 체크 메모</button></div>`);break;
 case 'addCheck':insertCheckRow(null,'');break;
 case 'deleteNote':await flushMemo();confirmSheet('메모 삭제','이 메모를 삭제할까요?','deleteNote',{id:noteId});break;
 case 'openChat':if(state?.overlay)await api('open',{screen:'chats'});else await navigate('chats');break;
 case 'quickSend':{const text=$('#quickQuestion')?.value.trim();if(!text)return;if(!state.accounts.some(a=>a.active&&a.connected)){toast('ChatGPT를 먼저 연결해 주세요.');if(state.overlay)await api('open',{screen:'chats'});else await navigate('chats');return;}if(state.busy)throw Error('현재 답변을 받고 있습니다.');const button=$('[data-act=quickSend]');if(button?.disabled)return;if(button)button.disabled=true;try{const c=await api('newChat');chatId=c.id;await api('send',{id:c.id,text});quickQuestionDraft='';if($('#quickQuestion'))$('#quickQuestion').value='';if(state.overlay)await api('open',{screen:'chat',chat:c.id});else await openChatById(c.id);}finally{if(button?.isConnected)button.disabled=false;}break;}
 case 'dictionaryChoice':await dictionaryChoice();break;
 case 'signin':closeSheet();await api('signin');await refresh(true);break;case 'consent':closeSheet();await api('signin',{client:state.accounts.find(a=>a.active)?.client||'',consent:true});await refresh(true);break;
 case 'cancelLogin':await api('cancelLogin');await refresh(true);break;case 'models':await api('models');await refresh(true);break;
 case 'manageUsage':await api('linkTest',{uri:'https://chatgpt.com/settings/usage'});break;case 'accountSheet':await accountSheet();break;
 case 'signout':{let r=await api('signout');closeSheet();toast(r.message);await refresh(true);break;}
 case 'newChat':{const c=await api('newChat');chatId=c.id;await navigate('chat');break;}
 case 'send':{const text=$('#chatInput').value.trim();if(!text)return;await api('send',{id:chatId,text});$('#chatInput').value='';await refresh();break;}
 case 'cancelResponse':await api('cancelResponse');break;
 case 'copyDay':await api('copyDay',{offset:briefOffset});toast('선택한 날짜의 원본 JSON을 복사했습니다.');break;case 'saveDay':await api('saveDay',{offset:briefOffset});toast('저장할 파일 위치를 선택해 주세요.');break;
  case 'previousBrief':briefOffset=Math.min(179,briefOffset+(briefDays===7?7:1));await refresh(true);break;case 'nextBrief':briefOffset=Math.max(0,briefOffset-(briefDays===7?7:1));await refresh(true);break;
 }}
function bind(root=$('#app')){
 root.querySelectorAll('[data-act]').forEach(el=>el.onclick=()=>safe(()=>action(el.dataset.act,el)));
 root.querySelectorAll('[data-nav]').forEach(el=>el.onclick=()=>safe(()=>navigate(el.dataset.nav)));
 root.querySelectorAll('[data-purpose]').forEach(el=>el.onclick=()=>safe(()=>choosePurpose(el.dataset.purpose)));
 root.querySelectorAll('[data-min]').forEach(el=>el.onclick=()=>{minutes=Number(el.dataset.min);render();});
 root.querySelectorAll('[data-note]').forEach(el=>el.onclick=()=>safe(async()=>{await flushMemo();noteId=el.dataset.note;render();remember();}));
 root.querySelectorAll('[data-note-step]').forEach(el=>el.onclick=()=>safe(async()=>{await flushMemo();const notes=state.notes;if(notes.length<2)return;const index=notes.findIndex(n=>n.id===noteId);noteId=notes[(index+Number(el.dataset.noteStep)+notes.length)%notes.length].id;render();remember();}));
 root.querySelectorAll('[data-newnote]').forEach(el=>el.onclick=()=>safe(async()=>{noteId=crypto.randomUUID();await api('saveNote',{id:noteId,title:'새 메모',body:'',checks:[],mode:el.dataset.newnote});closeSheet();await refresh(true);if(el.dataset.newnote==='check')await action('addCheck');else $('#noteBody')?.focus();remember();}));
 root.querySelectorAll('[data-chat]').forEach(el=>el.onclick=()=>safe(async()=>{chatId=el.dataset.chat;await navigate('chat');}));
 root.querySelectorAll('[data-deletechat]').forEach(el=>el.onclick=()=>confirmSheet('대화 삭제','이 대화를 삭제할까요?','deleteChat',{id:el.dataset.deletechat}));
 root.querySelectorAll('[data-editpurpose]').forEach(el=>el.onclick=()=>safe(()=>purposeSheet(el.dataset.editpurpose)));
 root.querySelectorAll('[data-remove]').forEach(el=>el.onclick=()=>safe(async()=>{const editing=!!$('#sheet').innerHTML;await api('removePurpose',{id:el.dataset.remove});state=await api('state');render();if(editing)managePurposes();}));
 root.querySelectorAll('[data-switchscope]').forEach(el=>el.onclick=()=>{purposeScope=el.dataset.switchscope;managePurposes();});
 root.querySelectorAll('[data-rule]').forEach(el=>el.onclick=()=>safe(()=>ruleSheet(el.dataset.rule)));
 root.querySelectorAll('[data-manage]').forEach(el=>el.onchange=()=>safe(async()=>{await api('rule',{pkg:el.dataset.manage,mode:el.checked?'restore':'none'});state=await api('state');renderApps();}));
 root.querySelectorAll('[data-exclude]').forEach(el=>el.onchange=()=>safe(async()=>{await api('excludeUsage',{pkg:el.dataset.exclude,excluded:el.checked});const a=usageCache.find(a=>a.pkg===el.dataset.exclude);if(a)a.excluded=el.checked;}));
 root.querySelectorAll('[data-dictionary]').forEach(el=>el.onclick=()=>safe(async()=>{await api('dictionaryChoice',{id:el.dataset.dictionary});state.dictionary=el.dataset.dictionary;closeSheet();if(el.dataset.word)await api('dictionary',{word:el.dataset.word});}));
 root.querySelectorAll('[data-pickapp]').forEach(el=>el.onclick=()=>{const selected=$('#purposeApp');if(!selected||selected.disabled)return;selected.value=el.dataset.pickapp;renderPurposeAppResults();});
  root.querySelectorAll('[data-url]').forEach(el=>el.onclick=()=>safe(()=>api('linkTest',{uri:el.dataset.url})));
  root.querySelectorAll('[data-brief-sort]').forEach(el=>el.onclick=e=>{e.preventDefault();e.stopPropagation();const key=el.dataset.briefSort;briefSort[key]=briefSort[key]==='desc'?'asc':'desc';if(briefData){renderBrief(briefData);$(`[data-brief-sort="${key}"]`)?.focus({preventScroll:true});}});
 root.querySelectorAll('[data-source-toggle]').forEach(el=>el.onclick=()=>{const panel=document.getElementById(el.dataset.sourceToggle);if(panel){panel.hidden=!panel.hidden;el.setAttribute('aria-expanded',String(!panel.hidden));}});
 root.querySelectorAll('[data-permission]').forEach(el=>el.onclick=()=>safe(()=>api('permission',{name:el.dataset.permission})));
 root.querySelectorAll('[data-account]').forEach(el=>el.onclick=()=>safe(async()=>{await api('account',{client:el.dataset.account});await api('models');closeSheet();await refresh(true);}));
 root.querySelectorAll('[data-days]').forEach(el=>el.onclick=()=>{briefDays=Number(el.dataset.days);briefOffset=0;refresh(true);});
 root.querySelectorAll('#noteTitle,#noteBody,[data-checktext],[data-check]').forEach(el=>{
 el.oninput=e=>{if(el.matches('[data-checktext]')&&!e.isComposing&&el.value.includes('\n')){const at=el.value.indexOf('\n');splitCheckRow(el,at,at+1);}else scheduleMemo();};
 el.onfocus=()=>{safe(()=>api('utility',{name:'메모'}));viewportChanged();};
 el.onchange=()=>{if(el.type==='checkbox')el.closest('.checkrow').classList.toggle('done',el.checked);scheduleMemo();};
 });
 root.querySelectorAll('[data-checktext]').forEach(el=>{
 el.onkeydown=e=>{if(e.key==='Enter'&&!e.isComposing){e.preventDefault();splitCheckRow(el);}};
 el.onbeforeinput=e=>{if(!e.isComposing&&['insertParagraph','insertLineBreak'].includes(e.inputType)){e.preventDefault();splitCheckRow(el);}};
 });
 root.querySelectorAll('[data-delcheck]').forEach(el=>el.onclick=()=>{el.closest('.checkrow').remove();scheduleMemo();});
 if($('#noteMode'))$('#noteMode').onchange=()=>safe(async()=>{await flushMemo();let n=captureMemo(),mode=$('#noteMode').value;if(mode==='check'){n.checks=n.body.trim()?[{text:n.body,done:false},...n.checks]:n.checks;n.body='';}else {n.body=n.checks.map(c=>(c.done?'✓ ':'')+c.text).join('\n');n.checks=[];}n.mode=mode;await api('saveNote',n);await refresh(true);});
 if($('#appSearch'))$('#appSearch').oninput=renderApps;if($('#usageSearch'))$('#usageSearch').oninput=renderUsageApps;
 if($('#dictionaryForm'))$('#dictionaryForm').onsubmit=e=>{e.preventDefault();safe(lookup);};
 if($('#floatingTimer'))$('#floatingTimer').onclick=()=>safe(async()=>{const enabled=state.prefs.floatingTimer===false;await api('settings',{floatingTimer:enabled});state.prefs.floatingTimer=enabled;$('#floatingTimer').classList.toggle('on',enabled);$('#floatingTimer').setAttribute('aria-checked',enabled);$('#floatingTimer').textContent=enabled?'켜짐':'꺼짐';});
 if($('#enabled'))$('#enabled').onclick=()=>safe(async()=>{const enabled=!state.prefs.enabled;await api('settings',{enabled});state.prefs.enabled=enabled;$('#enabled').classList.toggle('on',enabled);$('#enabled').setAttribute('aria-checked',enabled);$('#enabled').textContent=enabled?'켜짐':'꺼짐';});
 ['startTime','endTime'].forEach(id=>{if($('#'+id))$('#'+id).onchange=()=>safe(async()=>{const start=timeVal($('#startTime').value),end=timeVal($('#endTime').value);await api('settings',{start,end});state.prefs.start=start;state.prefs.end=end;});});
 root.querySelectorAll('[data-reminder]').forEach(el=>el.onclick=()=>safe(async()=>{el.classList.toggle('selected');el.setAttribute('aria-pressed',el.classList.contains('selected'));await saveReminders();}));
 if($('#customReminders')){$('#customReminders').oninput=()=>{clearTimeout(settingsTimer);settingsTimer=setTimeout(()=>safe(saveReminders),700);};$('#customReminders').onchange=()=>safe(saveReminders);}
 if($('#weeklyDay'))$('#weeklyDay').onchange=()=>safe(()=>api('settings',{weeklyDay:Number($('#weeklyDay').value)}));
 if($('#model'))$('#model').onchange=()=>safe(()=>api('settings',{model:$('#model').value}));
}
function back(){if($('#sheet').innerHTML){closeSheet();return;}if(screen==='chat'){navigate('chats');return;}if(gated.includes(screen)){api('stop');return;}if(state?.overlay){navigate(state.gate.mode||'unlock');return;}if(screen==='apps'||screen==='usageApps'){navigate('settings');return;}if(screen==='home'||screen==='setup'){api('exit');return;}navigate('home');}
window.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden'){clearInterval(poll);poll=null;safe(flushMemo);remember();}else{startPolling();refresh();}});
window.addEventListener('resize',()=>viewportChanged(nativeKeyboard));window.visualViewport?.addEventListener('resize',()=>viewportChanged(nativeKeyboard));window.visualViewport?.addEventListener('scroll',()=>viewportChanged());
document.addEventListener('focusin',()=>{if(nativeKeyboard)viewportChanged(true);});

