Locky 1.2.8 — 준비된 잠금 해제 화면의 DOM·렌더링 연속성

기존 com.locky.app, 개인 서명 인증서, 사용자 데이터·설정을 유지하는 업데이트입니다.

실기기 로그에서 확인한 범위

1.2.7 / Samsung SM-F966N / Android 17의 Locky-unlock-20261015-233403.txt를 확인했습니다. 정상 해제 중 창 add/remove와 alpha 재변경 없이 갑작스러운 등장 현상이 관찰됐으며, 해제 후 overlay와 Activity 양쪽에서 DOM revision/visual 재검증이 발생했습니다. 이 로그는 반투명 현상을 재현한 기록이 아닙니다. 잠금 해제 확인과 시스템 창 전환 종료, View draw와 실제 디스플레이 합성은 구분합니다.

변경 사항

- 준비된 unlock 문서는 visibility 복귀만으로 refresh하지 않습니다. home/unlock에 실제 갱신 요청이 와도 같은 데이터라면 DOM/revision/visual callback을 유지합니다. 변경된 목적은 기존 항목 노드를 재사용하며 추가·삭제·내용만 반영합니다. 메모는 변경된 영역만 갱신하며 입력 중인 메모·질문·검색어와 목적 스크롤을 보존합니다. 문서 손실 시 기존 복구와 명시적 내비게이션의 준비 확인은 유지합니다.
- MainActivity.onResume은 해당 Activity만 갱신합니다. 비활성 로그인 브라우저 정리도 불필요한 서비스 policyChanged/전체 refresh를 발생시키지 않습니다. 메모·목적의 실제 저장/삭제는 다른 WebHost에 변경을 전달하므로 복귀 refresh를 제거해 데이터가 낡는 일을 피합니다. ChatGPT 완료·로그인·실제 설정/정책 변경의 갱신은 유지합니다.
- 준비된 프레임의 입력 활성화 때 추가 invalidate를 요청하지 않습니다. 정상 해제에서 동일 창·WebView·프레임을 유지하고, 기존 alpha=1/OPAQUE·입력 차단/편집 포커스·시스템 인터럽트 정책을 보존합니다. 새 지연·가림 화면·창 복제·스냅샷을 추가하지 않았습니다.
- 오버레이 창은 최초 add 전에 FLAG_HARDWARE_ACCELERATED를 설정하고 오버레이 WebView의 강제 SOFTWARE 레이어를 제거했습니다. 단일 화면 크기 오버레이에 공개 API setOffscreenPreRaster(true)를 적용해 부착된 화면의 사전 래스터링을 허용합니다. 일반 Activity의 기존 소프트웨어 처리와 실제 pause/detach/렌더러 종료의 복구는 유지합니다. 이는 가려진 표면의 실제 디스플레이 출력을 보장하는 API가 아닙니다.
- 일반 TYPE_APPLICATION_OVERLAY 구조를 유지합니다. 이번 로그만으로 Activity 전환이 두 증상을 해결한다고 입증되지 않았으며, 먼저 확인된 DOM 교체·강제 redraw·소프트웨어 창 경로를 정리했습니다. One UI의 잠금 해제 애니메이션을 변경하거나 비공개 API를 사용하지 않습니다.
- 진단은 같은 소스의 반복 관찰을 횟수·마지막 시각으로 압축하고, 제한 초과 시 반복/일반 관찰부터 제거합니다. 준비·창 속성·상태 전환·DOM/프레임·오류를 우선 보관하며 극단적인 핵심 이벤트 과다 시 생략 수를 별도로 표시합니다. 최대 5회/190KB, 끄기·삭제·UTF-8 내보내기는 유지합니다.
- 진단에 Android window visibility/가속 여부와 지원되는 하드웨어 프레임의 HWUI frame-commit 관찰을 추가했습니다. 버퍼 제출과 실제 합성을 구분하며 이 콜백으로 화면 표시나 입력을 지연하지 않습니다.

보존

CSS·레이아웃·여백·목적 영역·도구 크기·테마·설정 디자인·패키지 ID·서명·DB 구조·사용시간/세션·접근성 차단 정책은 변경하지 않았습니다. 기존 사전/ChatGPT/메모 처리와 최근 앱 task 구조도 유지합니다.

검증 범위

로컬 표적 브라우저 검사: start-return(변경 없는 refresh/visibility의 DOM·revision 유지, 실제 변경 부분 갱신, 명시적 복귀, 메모/사전), quick-chat(즉시 동일 방/메시지/재시도), chat-ime(출처/스트리밍/초안/키보드), start-window-settings(진단 보기·내보내기·삭제/취소·편집 포커스)가 통과했습니다. 기존 Actions 릴리즈는 관련 창/시스템 이벤트/렌더링/진단/사전/ChatGPT/테마/타이머/파일 내보내기 JVM 회귀와 lint를 통과한 경우에만 서명 APK를 게시합니다.

실기기 확인

두 시각 증상의 최종 해결 여부는 Samsung One UI에서 아직 확인하지 못했습니다. 설치 후 진단을 켜고 화면을 껐다가 잠금 해제 한 번으로 반투명 전환·늦은 등장·첫 버튼/입력이 함께 정상인지 확인할 수 있습니다. 계속 발생하면 같은 진단 .txt를 내보내 주세요. visual-ready/View draw/HWUI frame-commit은 실제 디스플레이 합성 완료를 뜻하지 않습니다.
