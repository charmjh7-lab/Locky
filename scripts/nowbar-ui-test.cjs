const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
// Evaluate the production renderer without rebuilding unrelated app screens or an APK.
const context={window:{addEventListener(){}},document:{addEventListener(){}},setTimeout,clearTimeout,setInterval,clearInterval};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8'),context);
function panel(value){context.input=value;return vm.runInContext('state={status:{liveTimer:input}};liveTimerSettings()',context);}
assert.equal(panel(undefined),'');
let html=panel({supported:false,notifications:true,channelImportance:2});
assert.match(html,/일반 타이머 알림/);assert.doesNotMatch(html,/data-permission="liveTimer"/);
assert.match(html,/data-permission="timerChannel"/);
const ready={supported:true,notifications:true,channelImportance:2,eligible:true,permitted:true,posted:true,promoted:false};
assert.match(panel({...ready,notifications:false}),/앱 알림을 먼저/);
assert.match(panel({...ready,supported:false,notifications:false}),/앱 알림을 먼저/);
assert.match(panel({...ready,channelImportance:0}),/중요도를 무음 이상/);
assert.match(panel({...ready,channelImportance:1}),/중요도를 무음 이상/);
assert.match(panel({...ready,eligible:false}),/일반 알림으로 표시/);
assert.match(panel({...ready,permitted:false}),/설정과 기기 지원 여부/);
assert.match(panel({...ready,posted:false}),/사용을 시작하면/);
html=panel(ready);assert.match(html,/현재 일반 타이머/);assert.doesNotMatch(html,/시스템이 실시간 알림으로 처리/);
html=panel({...ready,promoted:true});assert.match(html,/시스템이 실시간 알림으로 처리/);
assert.match(html,/허용되어도 Now Bar에 표시되지 않을 수/);
assert.match(html,/data-permission="liveTimer"/);
console.log('Now Bar setup: unsupported, denied, blocked/min channel, ineligible, idle, ordinary and promoted states passed.');
