# Claude Collaboration CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Codex가 로컬 Claude와 기능 설계를 검증하고, GitHub Pull Request에서 실제 테스트와 Claude의 구조화 리뷰가 모두 통과해야 병합 가능한 검사를 제공한다.

**Architecture:** 로컬 협업은 기존 `claude-consult.ps1`과 프로젝트 스킬을 유지하며 Claude Code 세션을 재개한다. CI는 Anthropic 공식 `claude-code-action@v1`을 OAuth 토큰으로 실행하고, 미리 생성한 diff와 테스트 로그를 읽기 전용으로 검토한 뒤 JSON 판정을 별도 게이트 단계가 성공 또는 실패로 변환한다.

**Tech Stack:** PowerShell 5.1, Pester 3.4, Claude Code CLI 2.1.269+, GitHub Actions, `anthropics/claude-code-action@v1`, Java 21, Gradle Wrapper

**Spec:** `docs/superpowers/specs/2026-09-12-claude-collaboration-ci-design.md`

## Global Constraints

- Codex가 설계와 코드 작성을 담당하고 Claude는 독립 검증만 담당한다.
- Claude는 로컬과 CI 모두에서 소스 파일을 수정하거나 임의 명령을 실행할 수 없다.
- 테스트 성공 여부는 실제 테스트 명령의 종료 코드로만 판단한다.
- CI 인증은 `CLAUDE_CODE_OAUTH_TOKEN`만 사용하며 `ANTHROPIC_API_KEY`를 사용하지 않는다.
- Claude 실행 오류, 인증 누락, 잘못된 JSON, `BLOCK` 판정은 모두 CI 실패로 처리한다.
- `.codex/state/`의 세션과 임시 리뷰 입력은 Git에 커밋하지 않는다.

---

## File Map

- Modify: `scripts/claude-consult.ps1` — 테스트 가능한 Claude 실행 경로와 OAuth 친화적 오류 안내를 제공한다.
- Create: `scripts/tests/claude-consult.Tests.ps1` — 실제 네트워크 없이 JSON 파싱, 세션 저장·재개, 도구 제한을 검증한다.
- Modify: `.codex/skills/claude-pair-review/SKILL.md` — 로컬 설계 검증과 CI 역할을 구분하고 OAuth 제약을 명시한다.
- Modify: `AGENTS.md` — 기능 설계 전 Claude 검토와 구현 후 CI 게이트를 프로젝트 완료 조건으로 설정한다.
- Create: `.github/claude/review-prompt.md` — Claude의 blocking 기준과 구조화 판정 계약을 정의한다.
- Create: `.github/workflows/claude-review.yml` — 테스트, diff 수집, Claude 리뷰, PASS/BLOCK 게이트를 실행한다.
- Create: `scripts/tests/validate-claude-workflow.ps1` — workflow의 이벤트, 최소 권한, OAuth Secret, 읽기 전용 도구, 게이트 조건을 정적으로 검사한다.
- Create: `docs/claude-ci-setup.md` — 저장소 생성 후 OAuth Secret과 required status check 설정 절차를 설명한다.
- Modify: `.gitignore` — 기존 `.codex/state/` 제외 규칙을 확인하고 CI 임시 산출물이 로컬에 남을 경우 제외한다.

### Task 1: Local Claude Bridge Contract

**Files:**
- Modify: `scripts/claude-consult.ps1`
- Create: `scripts/tests/claude-consult.Tests.ps1`

**Interfaces:**
- Consumes: `-Mode design|review`, `-Prompt` 또는 `-PromptFile`, 선택적 `-Reset`, `-Model`, `-ClaudeExecutable`
- Produces: Claude의 텍스트 응답을 stdout으로 출력하고 `.codex/state/claude-<mode>-session.txt`에 `session_id`를 저장한다.

- [ ] **Step 1: 테스트용 Claude 실행 파일을 주입하는 실패 테스트를 작성한다**

`scripts/tests/claude-consult.Tests.ps1`에서 임시 fake Claude 스크립트를 만들고 다음을 검증한다.

```powershell
Describe "claude-consult" {
    It "stores the returned session id" {
        & $sut -Mode design -Reset -Prompt "설계 검토" -ClaudeExecutable $fakeClaude
        (Get-Content -Raw $designSession) | Should Be "session-design-1"
    }

    It "resumes the stored session" {
        & $sut -Mode design -Prompt "다시 검토" -ClaudeExecutable $fakeClaude
        (Get-Content -Raw $capturedArgs) | Should Match "--resume.*session-design-1"
    }

    It "never enables mutation or command tools" {
        & $sut -Mode review -Reset -Prompt "리뷰" -ClaudeExecutable $fakeClaude
        $args = Get-Content -Raw $capturedArgs
        $args | Should Match "--allowedTools.*Read,Grep,Glob"
        $args | Should Match "--disallowedTools.*Bash,Edit,Write"
    }
}
```

- [ ] **Step 2: 테스트가 현재 인터페이스에서 실패하는지 확인한다**

Run: `powershell -NoProfile -Command "$result = Invoke-Pester scripts/tests/claude-consult.Tests.ps1 -PassThru; if ($result.FailedCount -gt 0) { exit 1 }"`

Expected: FAIL because `claude-consult.ps1` does not define `-ClaudeExecutable`.

- [ ] **Step 3: 최소 인터페이스 변경을 구현한다**

`scripts/claude-consult.ps1`에 다음 매개변수를 추가하고 하드코딩된 실행 파일을 교체한다.

```powershell
[string]$ClaudeExecutable = "claude"
```

```powershell
$rawOutput = $Prompt | & $ClaudeExecutable @claudeArgs
```

Claude가 종료 코드 0이 아닌 값으로 끝났을 때 오류 메시지에는 로컬에서는 `claude auth status`, CI에서는 `CLAUDE_CODE_OAUTH_TOKEN`을 확인하라는 안내를 포함한다. 토큰 값 자체는 출력하지 않는다.

- [ ] **Step 4: 로컬 브리지 테스트를 통과시킨다**

Run: `powershell -NoProfile -Command "$result = Invoke-Pester scripts/tests/claude-consult.Tests.ps1 -PassThru; if ($result.FailedCount -gt 0) { exit 1 }"`

Expected: all tests PASS; fake Claude 인자에 `Bash`, `Edit`, `Write`가 허용되지 않고 두 번째 호출에 `--resume session-design-1`이 포함된다.

- [ ] **Step 5: 변경을 커밋한다**

```powershell
git add scripts/claude-consult.ps1 scripts/tests/claude-consult.Tests.ps1
git commit -m "test: verify Claude consultation bridge"
```

### Task 2: Claude Review Prompt and GitHub Gate

**Files:**
- Create: `.github/claude/review-prompt.md`
- Create: `.github/workflows/claude-review.yml`
- Create: `scripts/tests/validate-claude-workflow.ps1`

**Interfaces:**
- Consumes: GitHub `pull_request` base/head SHA, `secrets.CLAUDE_CODE_OAUTH_TOKEN`, `backend/gradlew`
- Produces: required check `Claude review gate / review`와 `structured_output` JSON `{ verdict, summary, findings }`

- [ ] **Step 1: workflow 계약의 실패 검사를 작성한다**

`scripts/tests/validate-claude-workflow.ps1`은 workflow 텍스트를 읽어 다음 불변조건이 없으면 종료 코드 1을 반환한다.

```powershell
$required = @(
    'pull_request:',
    'contents: read',
    'CLAUDE_CODE_OAUTH_TOKEN',
    'anthropics/claude-code-action@v1',
    '--json-schema',
    'structured_output',
    'verdict',
    'BLOCK'
)

$forbidden = @('ANTHROPIC_API_KEY', 'Edit,', 'Write,', 'Bash(')
```

- [ ] **Step 2: workflow가 아직 없어 검사가 실패하는지 확인한다**

Run: `powershell -NoProfile -File scripts/tests/validate-claude-workflow.ps1`

Expected: FAIL with `.github/workflows/claude-review.yml not found`.

- [ ] **Step 3: Claude 리뷰 기준을 작성한다**

`.github/claude/review-prompt.md`는 다음 계약을 포함한다.

```markdown
# Pull Request Review Contract

Review `.claude-review/pr.diff`, `.claude-review/changed-files.txt`, and
`.claude-review/test-output.txt`.

Return `BLOCK` only for a concrete correctness, security, regression,
data-integrity, or missing-test defect supported by repository evidence.
Return `PASS` when no blocking defect remains. Style preferences and optional
refactors are not blocking. Do not edit files or run commands.
```

- [ ] **Step 4: GitHub Actions workflow를 작성한다**

`.github/workflows/claude-review.yml`은 다음 형태를 사용한다.

```yaml
name: Claude review gate

on:
  pull_request:
    types: [opened, synchronize, reopened, ready_for_review]

permissions:
  contents: read
  pull-requests: read
  id-token: write

concurrency:
  group: claude-review-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  review:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v6
        with:
          fetch-depth: 0
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "21"
          cache: gradle
          cache-dependency-path: backend/**/*.gradle*
      - name: Require Claude OAuth token
        env:
          CLAUDE_CODE_OAUTH_TOKEN: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}
        run: test -n "$CLAUDE_CODE_OAUTH_TOKEN"
      - name: Run tests
        shell: bash
        run: |
          set -o pipefail
          mkdir -p .claude-review
          chmod +x backend/gradlew
          (cd backend && ./gradlew test) 2>&1 | tee .claude-review/test-output.txt
      - name: Prepare review evidence
        env:
          BASE_SHA: ${{ github.event.pull_request.base.sha }}
          HEAD_SHA: ${{ github.event.pull_request.head.sha }}
        run: |
          git diff --binary "$BASE_SHA" "$HEAD_SHA" > .claude-review/pr.diff
          git diff --name-status "$BASE_SHA" "$HEAD_SHA" > .claude-review/changed-files.txt
      - name: Run Claude review
        id: claude_review
        uses: anthropics/claude-code-action@v1
        with:
          claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}
          prompt: |
            Read .github/claude/review-prompt.md and review the prepared evidence.
          claude_args: |
            --model opus
            --max-turns 10
            --allowedTools Read,Grep,Glob
            --disallowedTools Bash,Edit,Write,NotebookEdit,WebFetch,WebSearch
            --strict-mcp-config
            --json-schema '{"type":"object","properties":{"verdict":{"type":"string","enum":["PASS","BLOCK"]},"summary":{"type":"string"},"findings":{"type":"array","items":{"type":"object","properties":{"severity":{"type":"string"},"file":{"type":"string"},"line":{"type":["integer","null"]},"title":{"type":"string"},"evidence":{"type":"string"},"recommendation":{"type":"string"}},"required":["severity","file","line","title","evidence","recommendation"]}}},"required":["verdict","summary","findings"]}'
      - name: Enforce Claude verdict
        env:
          REVIEW_JSON: ${{ steps.claude_review.outputs.structured_output }}
        shell: python
        run: |
          import json, os, sys
          review = json.loads(os.environ["REVIEW_JSON"])
          print(review["summary"])
          for finding in review["findings"]:
              print(f'{finding["severity"]}: {finding["file"]}:{finding["line"]} {finding["title"]}')
          if review["verdict"] != "PASS":
              sys.exit(1)
```

- [ ] **Step 5: workflow 계약 검사를 통과시킨다**

Run: `powershell -NoProfile -File scripts/tests/validate-claude-workflow.ps1`

Expected: PASS; OAuth만 참조하고 Claude의 명령 실행·파일 편집 도구가 허용되지 않는다.

- [ ] **Step 6: 변경을 커밋한다**

```powershell
git add .github/claude/review-prompt.md .github/workflows/claude-review.yml scripts/tests/validate-claude-workflow.ps1
git commit -m "ci: add Claude review gate"
```

### Task 3: Project Instructions and Setup Guide

**Files:**
- Modify: `AGENTS.md`
- Modify: `.codex/skills/claude-pair-review/SKILL.md`
- Modify: `.gitignore`
- Create: `docs/claude-ci-setup.md`

**Interfaces:**
- Consumes: local `claude` login, GitHub repository, `CLAUDE_CODE_OAUTH_TOKEN`, workflow check name
- Produces: repeatable local design-review policy and exact GitHub activation steps

- [ ] **Step 1: 기존 API 키 지침을 OAuth 전용 지침으로 교체한다**

`AGENTS.md`와 스킬에서 `ANTHROPIC_API_KEY` 및 별도 API 과금 문구를 제거한다. 다음 규칙을 명시한다.

```markdown
- 로컬 설계 검증은 설치된 Claude Code 로그인을 사용한다.
- GitHub Actions는 `CLAUDE_CODE_OAUTH_TOKEN`만 사용한다.
- Codex가 구현을 마친 뒤 로컬 테스트를 실행하고 Claude 검토를 받는다.
- Pull Request에서는 `Claude review gate / review`가 통과해야 완료로 본다.
```

- [ ] **Step 2: GitHub 설정 문서를 작성한다**

`docs/claude-ci-setup.md`에 아래 순서를 실제 메뉴 이름과 함께 기록한다.

```text
1. `claude setup-token`을 로컬에서 실행한다.
2. GitHub 저장소 Settings > Secrets and variables > Actions에서
   `CLAUDE_CODE_OAUTH_TOKEN` Secret을 만든다.
3. 첫 Pull Request로 workflow가 한 번 실행되게 한다.
4. Settings > Rules > Rulesets에서 기본 브랜치 대상 ruleset을 만든다.
5. Require status checks to pass를 켜고 `Claude review gate / review`를 선택한다.
6. 새 커밋 push 후 이전 성공 결과가 취소되고 검사가 다시 실행되는지 확인한다.
```

외부 fork PR에는 Secret이 전달되지 않아 검사가 실패한다는 제한과 토큰 회전 방법도 포함한다.

- [ ] **Step 3: 로컬 상태 제외 규칙을 확인한다**

`.gitignore`에 아래 규칙이 정확히 한 번 존재하게 한다.

```gitignore
.codex/state/
.claude-review/
```

- [ ] **Step 4: 문서와 스킬에서 API 키 참조가 사라졌는지 확인한다**

Run: `rg -n "ANTHROPIC_API_KEY|API 과금" AGENTS.md .codex/skills/claude-pair-review/SKILL.md docs/claude-ci-setup.md .github/workflows .github/claude`

Expected: no matches.

- [ ] **Step 5: 변경을 커밋한다**

```powershell
git add AGENTS.md .codex/skills/claude-pair-review/SKILL.md .gitignore docs/claude-ci-setup.md
git commit -m "docs: define Claude OAuth review workflow"
```

### Task 4: Integrated Verification

**Files:**
- Verify: `scripts/claude-consult.ps1`
- Verify: `scripts/tests/claude-consult.Tests.ps1`
- Verify: `scripts/tests/validate-claude-workflow.ps1`
- Verify: `.github/workflows/claude-review.yml`

**Interfaces:**
- Consumes: installed and authenticated Claude Code CLI
- Produces: local verification evidence; GitHub activation remains documented until a remote repository exists

- [ ] **Step 1: PowerShell 구문과 단위 테스트를 실행한다**

Run:

```powershell
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
  (Resolve-Path scripts/claude-consult.ps1), [ref]$null, [ref]$errors
) | Out-Null
if ($errors.Count -gt 0) { $errors; exit 1 }
$result = Invoke-Pester scripts/tests/claude-consult.Tests.ps1 -PassThru
if ($result.FailedCount -gt 0) { exit 1 }
```

Expected: no parser errors and all Pester tests PASS.

- [ ] **Step 2: workflow 계약 검사를 실행한다**

Run: `powershell -NoProfile -File scripts/tests/validate-claude-workflow.ps1`

Expected: PASS.

- [ ] **Step 3: 실제 Claude 읽기 전용 호출을 실행한다**

Run:

```powershell
./scripts/claude-consult.ps1 -Mode design -Reset -Prompt @"
이 저장소의 Claude 협업 및 CI 리뷰 게이트 설계를 읽고 검증하라.
파일을 수정하거나 명령을 실행하지 말고, blocking finding이 없으면 명시하라.
"@
```

Expected: exit code 0, 텍스트 응답 출력, `.codex/state/claude-design-session.txt` 생성. 호출 전후 `git status --short`에서 추적 파일 차이가 없어야 한다.

- [ ] **Step 4: 전체 변경의 포맷과 비밀값 노출을 확인한다**

Run:

```powershell
git diff --check
git grep -n -E "sk-ant-(api|oat)"
```

Expected: whitespace errors and committed secret values are absent.

- [ ] **Step 5: GitHub 미생성 제한을 기록한다**

현재 remote repository가 없으므로 실제 Actions 실행, Secret 등록, ruleset 강제는 로컬에서 검증할 수 없다. `docs/claude-ci-setup.md`의 후속 단계로 남기고, 로컬에서 확인한 항목과 확인하지 못한 항목을 최종 보고에 구분한다.

- [ ] **Step 6: 최종 변경을 커밋한다**

```powershell
git add -A
git commit -m "chore: verify Claude collaboration workflow"
```
