# Claude 협업 및 CI 리뷰 게이트 설계

## 목표

PetEver 개발에서 Codex가 기능 설계와 코드 작성을 주도하고 Claude가 독립적으로 설계와 구현을 검증한다. 로컬에서는 두 모델이 같은 Claude 세션을 이어가며 설계를 다듬고, GitHub Pull Request에서는 테스트와 Claude 리뷰가 모두 통과해야 병합할 수 있게 한다.

## 역할

- Codex는 요구사항 정리, 설계 초안, 코드 작성, 발견된 결함 수정을 담당한다.
- Claude는 설계의 반례와 누락을 찾고, 구현 후 정확성·보안·회귀·테스트 누락을 검토한다.
- Claude는 검토 과정에서 소스 파일을 수정하지 않는다.
- 테스트 성공 여부는 실제 명령의 종료 코드로 판단하며 Claude의 설명으로 대체하지 않는다.

## 로컬 설계 협업

프로젝트 스킬이 `scripts/claude-consult.ps1`을 호출한다. 스크립트는 Claude Code의 비대화형 JSON 출력을 읽고 `session_id`를 `.codex/state/`에 저장한다. 같은 기능을 다시 검토할 때 저장된 세션을 재개해 Codex의 수정 내용과 Claude의 이전 지적 사항이 한 대화 안에서 이어지게 한다.

설계 라운드는 다음 조건을 만족할 때 끝난다.

1. Claude가 요구사항 누락, 주요 반례, 보안 또는 데이터 무결성 문제를 더 이상 blocking finding으로 보고하지 않는다.
2. Codex가 Claude의 지적을 반영했거나, 반영하지 않은 이유를 근거와 함께 기록한다.
3. 사용자에게 제시한 최종 설계가 양쪽 검토 결과와 일치한다.

새 기능을 시작할 때는 이전 기능의 문맥이 섞이지 않도록 새 Claude 세션을 만든다. 세션 파일은 로컬 상태이므로 Git에 커밋하지 않는다.

## GitHub Actions 흐름

`.github/workflows/claude-review.yml`은 Pull Request의 생성, 새 커밋 반영, 재오픈, 리뷰 준비 전환 시 실행한다.

1. 전체 이력을 포함해 PR 커밋을 체크아웃한다.
2. 프로젝트가 제공하는 테스트와 정적 검사를 실행한다. 현재 계획상 백엔드는 Java 21과 Gradle Wrapper를 사용하므로 `backend/gradlew test`가 기본 검증 명령이다.
3. 테스트 결과와 base/head 간 diff를 Claude에 제공한다.
4. Claude는 읽기 및 제한된 Git 조회만 사용해 변경을 검토한다.
5. Claude는 JSON Schema에 맞춰 `PASS` 또는 `BLOCK` 판정과 findings를 반환한다.
6. 테스트 실패, Claude 실행 오류, 잘못된 JSON, `BLOCK` 중 하나라도 발생하면 workflow를 실패시킨다.

Claude 판정은 다음 의미를 갖는다.

- `PASS`: 병합을 막을 correctness, security, regression, data-integrity, missing-test 문제가 없다.
- `BLOCK`: 재현 가능하거나 구체적인 근거가 있는 중대한 문제가 하나 이상 있다.

스타일 선호, 선택적 리팩터링, 근거 없는 추측은 `BLOCK` 사유로 사용하지 않는다. findings에는 심각도, 파일, 가능하면 줄 번호, 근거, 권장 조치를 포함한다.

## 인증과 권한

GitHub Actions는 Claude Pro/Max 구독에서 `claude setup-token`으로 발급한 OAuth 토큰을 사용한다. 토큰은 저장소 Secret `CLAUDE_CODE_OAUTH_TOKEN`으로 등록한다. Anthropic API 키와 API 과금 계정은 사용하지 않으며, 실제 토큰은 파일, 로그, PR 본문에 기록하지 않는다.

Workflow 권한은 `contents: read`와 리뷰 결과를 표시하는 데 필요한 최소 Pull Request 권한으로 제한한다. Claude에는 소스 편집 도구를 제공하지 않는다. 외부 fork에서 만든 PR에는 저장소 Secret이 제공되지 않으므로 인증이 없을 때 검사를 건너뛰지 않고 실패 처리한다.

## 병합 차단

GitHub 저장소를 만든 뒤 기본 브랜치의 ruleset 또는 branch protection에서 테스트 job과 Claude review gate job을 required status checks로 지정한다. Workflow 파일만 추가해서는 병합 차단이 강제되지 않으므로 이 저장소 설정이 완료 조건에 포함된다.

Claude는 GitHub의 사람 승인 리뷰를 대신하지 않는다. CI job의 성공 또는 실패만 제공하며, 저장소 정책이 그 상태를 병합 조건으로 사용한다.

## 오류 처리

- `CLAUDE_CODE_OAUTH_TOKEN`이 없으면 `claude setup-token`과 GitHub Secret 등록 방법을 안내하고 job을 실패시킨다.
- Claude 호출이 제한 시간, 사용량 제한, 네트워크 문제로 끝나면 fail closed로 처리해 병합을 허용하지 않는다.
- 테스트가 실패하면 Claude 판정과 관계없이 CI를 실패시킨다.
- 새 커밋이 push되면 이전 성공 판정을 폐기하고 전체 검증을 다시 실행한다.
- Claude가 잘못된 구조를 반환하면 구조화 출력 검증 실패로 처리한다.

## 구성 파일

- `AGENTS.md`: 로컬 작업에서 Codex와 Claude의 역할 및 완료 조건
- `.codex/skills/claude-pair-review/SKILL.md`: 설계와 구현 검토 시 적용할 협업 절차
- `scripts/claude-consult.ps1`: 로컬 Claude 세션 생성·재개 및 권한 제한
- `.github/workflows/claude-review.yml`: 테스트, Claude 리뷰, 판정 게이트
- `.github/claude/review-prompt.md`: CI 리뷰 기준과 출력 요구사항
- `docs/claude-ci-setup.md`: 저장소 생성 후 Secret과 required check 설정 절차

## 검증

- PowerShell 스크립트 구문 분석과 인자 검증을 자동 테스트한다.
- 설치된 Claude CLI를 대상으로 읽기 전용 설계 호출을 한 번 실행해 JSON 파싱과 세션 저장을 확인한다.
- Workflow YAML을 파싱하고 필수 이벤트, 최소 권한, Secret 참조, JSON gate 조건을 검사한다.
- 실제 GitHub 저장소 생성 후 테스트 PR에서 `PASS`와 의도적으로 만든 `BLOCK` 사례를 각각 확인한다.

## 완료 조건

- 로컬에서 Codex가 Claude와 같은 세션을 이어가며 설계를 검토할 수 있다.
- Claude는 로컬 및 CI 검토에서 소스 파일을 수정할 수 없다.
- Pull Request에서 실제 테스트가 실행된다.
- Claude의 `BLOCK` 판정과 호출 오류가 CI 실패로 이어진다.
- GitHub OAuth Secret 등록과 required status check 설정 방법이 문서화된다.
- GitHub 저장소가 생성된 뒤 required check를 켜면 실패한 검증을 우회해 병합할 수 없다.
