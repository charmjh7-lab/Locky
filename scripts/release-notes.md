Locky 1.2.6 — 잠금 해제 창의 표시 상태와 프레임 검증 분리

기존 앱 ID `com.locky.app`와 `signing/personal.p12` 서명을 유지합니다. 시작화면 배치·여백·메모 크기·글자·테마와 ChatGPT 전송/저장/스트리밍 구현은 변경하지 않았습니다.

확인한 내부 경로와 수정

1. 이전 `setOverlayExposure()`의 `behindKeyguard` 조건은 `frameReady && !preparingStart`에 의존했습니다. 따라서 준비된 창도 목적 재배치 시작이나 DOM revision 무효화 직후 `revealOverlay()`가 호출되면 alpha가 1 → 0, 준비 완료 후 0 → 1로 바뀔 수 있었습니다. 이번에는 이미 검증된 문서의 픽셀 사용 가능 여부(`retainedFrame`)를 현재 revision의 `frameReady`와 분리했습니다. DOM 재검증과 준비 watchdog은 기존 픽셀을 버리지 않습니다. 최초 문서, 실제 detach/pause, 문서 교체와 렌더러 종료는 기존 검증을 다시 기다립니다. 초기 프레임 검증과 흰 화면 복구는 유지했습니다.

2. 창 상태를 HIDDEN / WAITING / BEHIND_KEYGUARD / INTERACTIVE / SUSPENDED로 결정하고, alpha·visibility·입력 플래그의 실제 변경은 `applyExposure()`에 모았습니다. 준비된 일반 오버레이의 정상 잠금 해제는 BEHIND_KEYGUARD → INTERACTIVE이며 alpha=1을 유지하고 포커스·터치 플래그만 바꿉니다. 같은 값의 WindowManager 갱신은 수행하지 않습니다. 프레임 콜백에서 창을 다시 생성하는 경로를 제거했습니다.

3. 이전 권한 정합성 처리는 `alpha==0`, `waitingUnlock` 등을 창 교체 가능 조건으로 사용했습니다. 화면 켜짐 직후 준비 중인 창도 remove/add 대상이 될 수 있었습니다. 이제 창 종류 변경은 실제 화면 꺼짐, 명시적 화면 닫기, 잠금 해제 전환이 끝난 실제 시스템 화면 뒤에서만 수행합니다. 권한 허용은 준비 중이거나 표시 중인 잠금 해제 창을 승격시키지 않습니다. 권한 취소는 표시·입력을 중단하고, 시스템 화면 또는 안정된 앱 스냅샷으로 잠금 해제 종료가 확인된 뒤 필요한 접근성 창 복구를 한 번 수행합니다. 이 복구는 실제 권한 손실에 한한 예외이며 정상 잠금 해제에는 remove/add가 없습니다.

4. 잠금 중 관찰한 시스템 창 ID를 현재 잠금 해제에 귀속했습니다. 같은 keyguard 창의 늦은 WINDOWS_CHANGE_ADDED 이벤트는 새 시스템 인터럽트로 취급하지 않습니다. 안정된 로키 포커스가 확인되면 전환을 종료하며, 이후 같은 ID의 알림창도 정상 인터럽트가 됩니다. 별도의 새 시스템 창, 권한 화면, 최근 앱과 수신 전화에는 기존대로 제어권을 넘깁니다. 단순히 모든 시스템 이벤트를 무시하거나 고정 지연을 추가하지 않았습니다.

5. 휴대폰 시작에는 권한이 있을 때 TYPE_APPLICATION_OVERLAY, 관리 앱 차단에는 TYPE_ACCESSIBILITY_OVERLAY를 사용합니다. 일반 오버레이의 임의 Binder 토큰을 제거하고, Android 11+에서는 기본 디스플레이의 재사용 WindowContext/WindowManager를 사용합니다. 이전 Android는 접근성 토큰을 상속하지 않는 application display context를 사용합니다. 기존 접근성 WindowManager는 앱 차단과 플로팅 타이머에 유지합니다. 한 WebHost/WebView와 기존 사전 전달·복귀를 유지합니다.

6. 잠금 중 불투명 유지는 기본 디스플레이의 일반 오버레이이며 SHOW_WHEN_LOCKED/DISMISS_KEYGUARD 플래그가 없는 경우에만 허용합니다. 실제 View 디스플레이 또는 해당 창 생성 컨텍스트로 배치를 확인할 수 없으면 투명·입력 금지 경로를 유지합니다. FLAG_SECURE는 캡처 제한에만 사용하며 잠금화면 가림의 근거로 삼지 않습니다.

7. 설정 스위치는 ‘사용 요청’임을 표시합니다. 권한 부족, 실제 적용, 다음 안전한 시점 대기, 앱 차단용 접근성 창, 서비스 꺼짐, 창 생성/갱신 실패, 권한 취소 후 복구 대기를 구분하고 실제 창 종류를 함께 표시합니다. 권한 설정은 사용자가 버튼을 누를 때만 열립니다. 권한이 없어도 시작화면·앱 차단의 기존 접근성 경로는 유지합니다.

제거한 동작

- `preparingStart`/DOM revision을 창 전체 숨김 조건으로 직접 사용하는 처리
- `alpha==0`이나 화면 켜짐/해제 대기를 창 교체의 근거로 삼는 처리
- 프레임 노출 콜백의 재귀적 remove/add 및 권한 강등
- 접근성 WindowManager에 임의 Binder를 지정해 일반 창을 만드는 우회
- hide/permission/render 경로가 독립적으로 창 속성을 쓰는 처리

검증

- 기존 브라우저 검사: ChatGPT 즉시 대화 진입·동일 방/질문/메시지 노드·메모 저장 대기·중복/실패 재시도·늦은 응답·대화 재열기 통과.
- 기존 브라우저 검사: 목적 부분 재배치와 포커스/선택/스크롤, 사전 반복 검색·성공 시만 검색어 삭제·실패/취소/재시도와 초안 보존 통과.
- 새 설정 검사: 요청과 실제 상태 구분, 꺼짐/권한 부족/실패/대기/적용/서비스 상태 및 명시적 권한 이동 통과.
- 배포 작업은 관련 GateRestore/OverlayRendering/ForegroundPolicy/DictionaryReturn/QuickQuestion/UiTheme/FloatingTimerInteraction JVM 회귀와 lint를 먼저 실행한 뒤 release APK를 빌드합니다. 서명 인증서와 앱 ID가 기존 값과 같은지 검증한 APK만 게시합니다.
- 새 JVM 회귀는 준비 중 빠른 해제, 준비 프레임의 alpha 유지와 입력 플래그만 변경, 중복 이벤트 무변경, 늦은 keyguard ADDED, 실제 시스템 인터럽트, 권한 변경 시 창 교체 횟수와 안전한 복귀, 배치 근거가 없을 때 비노출을 확인합니다.

실기기 확인과 남은 판단

위 경로는 소스에서 확인하고 회귀 검사를 추가한 앱 내부의 불연속 원인입니다. 삼성 실기기의 반투명 합성을 관찰한 결과는 아닙니다. 지문/PIN/패턴, 화면을 끈 직후/오래 뒤 해제, AOD·알림·전화, 잠금 중 개인정보 비노출, 해제 후 첫 입력·IME와 실제 사전 복귀는 기기에서 확인해야 합니다. 입력 플래그 변경은 WindowManager.updateViewLayout 한 번을 필요로 하지만, alpha와 레이아웃 크기·형식은 유지합니다. 제조사 합성/IME 레이아웃의 실제 결과는 JVM 검사로 확정할 수 없습니다.

장치에서 창 상태가 안정적인데도 같은 전환이 남는 경우, 앱의 상태 변경과 Android keyguard 합성을 로그로 분리할 수 있습니다. 로그가 정상이어도 합성 문제라고 단정하지 않습니다. 그때 적용 경로는 실험용 창을 누적하는 대신 휴대폰 시작만 일반 Activity/task의 기존 surface로 유지하고, 앱 차단은 접근성 창에 남기는 구조입니다. 현재는 그 변경을 정당화할 기기 관찰이 없어 도입하지 않았습니다.

기존 `LockyUnlock` 로그만 확장했습니다. 릴리즈 기본값은 비활성입니다. 필요 시 `adb shell setprop log.tag.LockyUnlock DEBUG` 후 화면 꺼짐/해제를 한 번 수행하고 `adb logcat -s LockyUnlock:D`로 확인합니다. 각 cycle은 준비 구간 최대 120건, 해제 구간 최소 80건의 제한된 예산과 30초 기록 구간을 사용합니다. 오래 잠근 후에도 해제 구간은 다시 기록됩니다. 창 종류/add/remove, attach/detach, alpha/visibility/입력 플래그, 프레임/준비 상태, 전환 시작/종료, 시스템 중단 이유, reveal 요청과 fallback 이유만 기록하며 입력 내용은 기록하지 않습니다. `adb shell setprop log.tag.LockyUnlock INFO`로 끌 수 있습니다.

플랫폼 근거

- [Android WindowContext 문서](https://developer.android.com/reference/android/content/Context#createWindowContext(int,android.os.Bundle))
- [AOSP WindowManagerImpl 기본 토큰 처리](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/WindowManagerImpl.java)
- [AOSP DisplayPolicy keyguard 가림 정책](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/wm/DisplayPolicy.java)
