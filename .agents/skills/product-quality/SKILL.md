---
name: product-quality
description: Select and apply Locky-specific user journey, Android integration, resilience, interaction completeness, testing and scope preservation requirements before and after implementation.
---

# Product Quality — Locky

작업 시작 시 AGENTS.md와 docs/CHANGE_SCOPE_POLICY.md, docs/UX_PROJECT_BRIEF.md에서 이번 작업의 범위·유지할 사용 흐름을 확인한다. 이 스킬은 임의의 개선 작업 허가증이 아니다. 사용자 요청이 없는 화면 배치·상태·권한·데이터 변경을 하지 않는다.

사용자가 완결하려는 행동을 시작, 입력/선택, 처리, 결과, 종료/취소, 복귀까지 추적한다. 기능에 맞는 생성·편집·삭제·닫기·뒤로 가기와 재시도 경로를 확인한다. docs/INTERACTION_COMPLETENESS.md 및 docs/HUMAN_UX_ACCEPTANCE.md에서 이번 변경에 맞는 최소 시나리오만 선택한다. UI·Android 작업이면 docs/UX_PRODUCT_QUALITY.md와 docs/UI_DESIGN_GUIDE.md 및 관련 외부 공식 기준을 참조한다.

Android에서는 접근성 오버레이와 MainActivity, WebViewAssetLoader와 JavaScript bridge, 화면 꺼짐/잠금 해제, 최근 앱 task, 앱 전환, 알림창/Overview, IME·폴더블, 외부 사전·로그인 브라우저 반환에 관한 실제 책임 코드를 확인한다. 반복적으로 발생했던 오류는 docs/REPEATED_FAILURE_PATTERNS.md를 참고하되 모든 작업에서 모든 오류를 재검사하지 않는다. 데이터·사용시간·서명·개인정보는 원래의 정확성과 호환성을 유지한다.

시각 작업은 .agents/skills/interface-quality/SKILL.md도 적용한다. 단위 테스트와 모의 브라우저 검사, Android 통합 테스트, 실제 삼성 기기 사용, 실계정 외부 서비스, 사람의 시각적 평가는 서로 다른 근거다. 실행하지 않은 것을 성공으로 보고하지 않는다. 변경 범위에 직접 연결된 검사만 우선 실행하며 전체 빌드와 APK 배포는 필요하거나 사용자가 요청한 단계에서 수행한다.
