# 휴대폰 시작화면 Activity 재구현 (실험 브랜치)

사용자의 2026-10-16 요청에 따라 `refactor/phone-start-activity-20261016`에서 구현한다. 기준 main은 `9d3483f`. 화면 배치·CSS·기존 DB 스키마·서명은 변경하지 않는다.

- `PhoneStartActivity`: 불투명 일반 Activity, 독립 `com.locky.app.phoneStart` task, singleTask, 최근 앱 제외, 비공개. MainActivity의 launcher/task 설정은 유지한다. showWhenLocked/turnScreenOn은 false다.
- `PhoneStartController`: 기존 GuardService 수신기에서 SCREEN_OFF당 준비 요청 1회. 생존 owner의 주기 acknowledgement를 확인하고, SCREEN_ON(잠금 없음)/USER_PRESENT/접근성 잠금 해제 관찰 중 최초 확인에서만 필요한 복구 요청 1회. 타이머나 창 이벤트로 Activity를 반복 실행하지 않는다. 준비 여부와 주기는 기존 preferences에 별도 키로 저장한다. 서비스 재연결도 이미 요청한 준비를 반복하지 않는다.
- 직접 Activity 실행은 시스템이 바인딩한 AccessibilityService 경로를 사용한다. 기존 SYSTEM_ALERT_WINDOW 허용도 플랫폼 예외가 될 수 있으나 추가 권한을 요구하거나 전경 서비스를 추가하지 않는다. 공식 제한: https://developer.android.com/guide/components/activities/background-starts
- `onCreate`만 WebView 생성. `onNewIntent`는 새 주기와 날짜만 반영. onPause/onStop/알림창/홈은 finish하거나 문서를 reload하지 않는다. 회전·폴더블·테마·글꼴·밀도 구성 변경을 같은 Activity에서 처리한다. 실제 프로세스/렌더러 소멸에만 별도 phone-start-session에서 화면·대화 ID·메모 ID·입력 초안을 복구한다. 기존 메모 자동저장 경로는 유지한다.
- WebHost 역할: PHONE_START/MAIN/APP_GATE. PHONE_START는 시각 프레임 fence, alpha, 오버레이 포커스 처리 없이 일반 Activity에 연결된다. 같은 HTML/CSS/JS를 사용하며 MAIN의 하단 탐색은 숨긴다. ChatGPT quickSend 및 저장 엔진·메시지 ID는 변경하지 않는다.
- 메모 전체 보기와 ChatGPT는 같은 WebView 안에서 이동한다. 로그인·설정 등 일반 앱 기능은 기존 MainActivity로 연다. phone 탐색 상태는 Main의 last-screen과 별개이며 사용자 데이터는 공통 Store다.
- 사전은 PhoneStartActivity가 WebView를 소유한 채 외부 화면만 실행한다. Daum PROCESS_TEXT는 기존에 사용한 독립 미니 팝업 플래그와 서비스 실행 경로를 유지한다. 다른 사전은 기존 Activity 호출자로 복귀한다. DictionaryActivity·bitmap cover·WebView 이동·프레임 대기·복귀 재부착은 제거했다. 실제 OEM 사전 UI는 기기에서 확인한다.
- 목적 선택은 동일 Store의 unlock/phoneEvent 기록 후 연결 앱 실행 또는 홈 이동, Activity 종료. Activity의 선택 완료 플래그로 연타/늦은 같은 주기 Intent를 차단한다. 관리 앱은 기존 규칙·목적/시간 확인을 적용하며 임의로 추가 사용시간을 주지 않는다. 하루 목적 순서는 기존 Store.purposes/purposeOrder가 결정한다.
- '그냥 끄기'는 기존 접근성 GLOBAL_ACTION_LOCK_SCREEN으로 잠근다. Android 8은 이 API가 없어 기존과 같이 실패를 알린다. 버튼으로 화면만 닫아 잠금을 가장하지 않는다.
- Android 13+ OnBackInvokedCallback(default priority)과 이전 시스템 Back을 연결한다. IME → 편집 창 → 하위 화면 순으로 돌아가며 phone root는 그대로 둔다. 홈 및 알림창 이벤트에서 재실행하지 않는다.
- GuardService는 앱 차단용 TYPE_ACCESSIBILITY_OVERLAY, 사용시간/세션/집중시간/타이머만 창 단위로 관리한다. 휴대폰용 TYPE_APPLICATION_OVERLAY, 창 컨텍스트/종류 이동, 키가드 창 ID, unlockTransition, BEHIND_KEYGUARD, alpha 노출, 입력 모드 전환, 사전 handoff를 삭제했다.
- 기존 진단 보관/켜기/삭제/내보내기는 유지하며 Activity 요청·생명주기·재사용/실패를 기록한다. 메모·검색어·대화 내용은 기록하지 않는다.

검증은 두 브라우저 상호작용 검사와 관련 JVM 검사, lint 및 서명 APK 빌드로 한정한다. 삼성 잠금 해제 애니메이션·화면 꺼짐 중 백그라운드 실행 허용·Daum 실제 팝업은 실기기 미확인이다. 별도 prerelease는 stable latest와 기존 안정 APK를 교체·정리하지 않는다. main 병합은 수행하지 않는다.

## 2026-10-16 후속: 홈 이탈 억제와 APP_GATE 로딩

이후 사용자 요청으로 목적/도구 사용 전 홈 이동에는 기존 task를 복귀시키도록 변경했다. PhoneNavigation은 주기 ID에 PURPOSE_PENDING/NAVIGATION_RELEASED를 저장하고 같은 주기에서 해제를 되돌리지 않는다. 실제 입력란 선택·입력, 메모/대화 조작, 사전 검색·목적 선택만 해제한다. 렌더 완료·단순 복귀·빈 공간/목적 목록 조작은 해제하지 않는다. JS는 현재 native cycle을 조회하므로 이전 WebView를 유지한 다음 잠금 해제도 정확한 주기를 사용한다.

GuardService는 기본 HOME으로 해석된 Activity 클래스와 실제 focused/active 전체 화면 런처 창을 함께 확인한다. 알림창/overview/잠금/시스템 화면은 제외한다. 기존 taskId에 moveTaskToFront와 애니메이션 0 옵션을 사용하고, onResume까지 한 복귀 요청을 래치한다. REORDER_TASKS는 공식 API에 필요한 일반 권한이며 별도 사용자 허용 화면은 없다. 보조 휴대폰 오버레이나 startActivity 재실행 대안은 추가하지 않는다. OEM의 홈 애니메이션과 백그라운드 작업 복귀 허용은 실기기 확인 대상이다.

APP_GATE 문서는 서비스 연결 때 창 없이 준비한다. 실제 진입은 현재 앱/예산/목적/정책 payload를 준비된 JS에 직접 전달한다. 매 진입에서 전체 문서를 다시 로드하거나 정상 문서를 로딩 패널로 교체하지 않는다. 같은 앱·종류는 달라진 구획만 갱신한다. 현재 진입의 DOM/visual callback 전에는 접근성 창을 INVISIBLE 상태로 레이아웃하고 입력을 막으며, 확인 후 기존 창을 노출한다. 예전 앱의 frame·버튼·지연된 grant는 진입 generation으로 거부한다. 최초 생성과 실제 renderer 소멸에는 기존 초기 준비/실패 복구를 유지한다. PHONE_START의 렌더링 생명주기는 이 APP_GATE 변경과 공유하지 않는다.
