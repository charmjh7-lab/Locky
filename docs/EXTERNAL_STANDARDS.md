# 외부 UX·접근성·플랫폼 품질 기준

지침을 적용할 때 공식 플랫폼 요구, 전문가 휴리스틱, 프로젝트별 사용자 경험 계약을 구분한다. 링크와 권고는 실제 작업 시 최신 원문을 재확인한다.

Google Android 핵심 앱 품질: https://developer.android.com/docs/quality-guidelines/core-app-quality — 앱 복귀·상태 유지, 터치 조작, 화면 크기·다크모드 등의 기본 품질.
Google Material Dialog: https://developer.android.com/develop/ui/compose/components/dialog — 닫기/확인/취소와 내용 레이아웃 및 dismiss 설계.
Material Design 3: https://developer.android.com/develop/ui/compose/designsystems/material3 — 의미 기반 색상과 일관된 컴포넌트 사용.
Android 접근성: https://developer.android.com/design/ui/mobile/guides/foundations/accessibility 및 https://developer.android.com/guide/topics/ui/accessibility/apps — 최소 48dp 터치 대상 권장, 명확한 라벨·접근 가능 조작·색상 외 상태 구분.
Google 적응형 레이아웃: https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes — 기기명이 아닌 실제 창 크기 기준.
W3C WCAG 2.2: https://www.w3.org/TR/WCAG22/ — 웹·웹뷰 대비, 리플로, 키보드 포커스, 드래그 대안 등 해당 기준 적용.
NN/g 사용성 휴리스틱: https://www.nngroup.com/articles/ten-usability-heuristics/ — 상태 가시성, 사용자 통제, 오류 예방·회복·일관성.

모든 화면에 CRUD를 강제하거나 모든 다이얼로그를 동일하게 고정하는 것이 공식 지침이라는 식으로 과장하지 않는다. 사용자 목적 및 현재 앱 제약에 맞는 동등하게 접근 가능한 해결책을 선택한다.
