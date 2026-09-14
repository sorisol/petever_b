# PetEver Spring Boot Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Java 21에서 실행되고 Supabase PostgreSQL 연결을 환경변수로 받을 수 있는 PetEver Spring Boot 백엔드 기반을 만든다.

**Architecture:** `backend/` 아래 하나의 Spring Boot 애플리케이션을 둔다. 기본 프로필은 DB 없이 실행하고, `supabase` 프로필만 DataSource와 JPA를 활성화한다. 공개 헬스 API를 제외한 요청은 stateless Spring Security로 보호한다.

**Tech Stack:** Java 21.0.12 LTS, Spring Boot 4.1.1, Gradle Groovy DSL, Spring MVC, Validation, Data JPA, Security, Actuator, Flyway, PostgreSQL, JUnit 5, MockMvc, Testcontainers

**Spec:** `docs/superpowers/specs/2026-09-12-spring-boot-foundation-design.md`

## Global Constraints

- Java toolchain은 21로 고정한다.
- 기본 패키지는 `com.petever.api`다.
- 실제 DB URL, 사용자명, 비밀번호를 저장소 파일이나 로그에 넣지 않는다.
- JPA 스키마 자동 생성은 사용하지 않는다.
- 기본 프로필은 DB 자격 증명 없이 실행 및 테스트 가능해야 한다.
- 프로젝트 디렉터리는 `backend/`다.

---

## File Map

- `backend/settings.gradle`: Gradle 프로젝트 이름
- `backend/build.gradle`: Java 21과 Spring 의존성
- `backend/gradlew`, `backend/gradlew.bat`, `backend/gradle/wrapper/*`: 재현 가능한 Gradle 실행기
- `backend/src/main/java/com/petever/api/PeteverApplication.java`: 애플리케이션 진입점
- `backend/src/main/java/com/petever/api/config/SecurityConfig.java`: stateless 보안 및 CORS
- `backend/src/main/java/com/petever/api/health/HealthController.java`: 서비스 헬스 API
- `backend/src/main/java/com/petever/api/common/error/ApiErrorResponse.java`: 오류 응답 계약
- `backend/src/main/java/com/petever/api/common/error/FieldErrorResponse.java`: 필드 오류 계약
- `backend/src/main/java/com/petever/api/common/error/GlobalExceptionHandler.java`: 검증 및 예외 변환
- `backend/src/main/resources/application.yml`: DB 없는 기본 설정
- `backend/src/main/resources/application-supabase.yml`: 환경변수 기반 PostgreSQL 설정
- `backend/.env.example`: 필요한 환경변수 예시
- `backend/.gitignore`: 로컬 비밀값과 생성물 제외
- `backend/README.md`: 실행·테스트·Supabase 연결 방법
- `backend/src/test/java/com/petever/api/PeteverApplicationTests.java`: 기본 컨텍스트 테스트
- `backend/src/test/java/com/petever/api/health/HealthControllerTest.java`: 공개 헬스 및 보호 경로 테스트
- `backend/src/test/java/com/petever/api/common/error/GlobalExceptionHandlerTest.java`: 오류 응답 테스트

### Task 1: Java 21 Spring Boot 프로젝트 기반

**Files:**
- Create: `backend/settings.gradle`
- Create: `backend/build.gradle`
- Create: `backend/gradlew`
- Create: `backend/gradlew.bat`
- Create: `backend/gradle/wrapper/gradle-wrapper.jar`
- Create: `backend/gradle/wrapper/gradle-wrapper.properties`
- Create: `backend/src/main/java/com/petever/api/PeteverApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/petever/api/PeteverApplicationTests.java`

**Interfaces:**
- Consumes: PC의 Java 21 런타임
- Produces: `PeteverApplication.main(String[] args)`와 Gradle Wrapper

- [ ] **Step 1: Spring Initializr 프로젝트를 생성한다**

`start.spring.io`에서 다음 값으로 ZIP을 받아 `backend/`에 푼다.

```text
type=gradle-project
language=java
bootVersion=4.1.1
groupId=com.petever
artifactId=api
name=petever-api
packageName=com.petever.api
javaVersion=21
dependencies=web,validation,data-jpa,security,actuator,flyway,postgresql
```

- [ ] **Step 2: Java 및 Wrapper 버전을 확인한다**

Run: `cd backend && java -version && .\gradlew.bat --version`

Expected: Java 21과 Gradle 8.14 이상 또는 9.x가 표시된다.

- [ ] **Step 3: DB 없이 시작하도록 기본 자동 설정을 조정한다**

`application.yml`:

```yaml
spring:
  application:
    name: petever-api
  autoconfigure:
    exclude:
      - org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
      - org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
      - org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration

server:
  port: ${SERVER_PORT:8080}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never

app:
  cors:
    allowed-origins: ${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173}
```

- [ ] **Step 4: 기본 컨텍스트 테스트를 실행한다**

`PeteverApplicationTests`는 `@SpringBootTest`와 빈 `contextLoads()` 메서드를 가진다.

Run: `cd backend && .\gradlew.bat test --tests com.petever.api.PeteverApplicationTests`

Expected: PASS without `DB_URL`, `DB_USERNAME`, or `DB_PASSWORD`.

### Task 2: 공개 헬스 API와 기본 보안

**Files:**
- Create: `backend/src/test/java/com/petever/api/health/HealthControllerTest.java`
- Create: `backend/src/main/java/com/petever/api/health/HealthController.java`
- Create: `backend/src/main/java/com/petever/api/config/SecurityConfig.java`

**Interfaces:**
- Consumes: `app.cors.allowed-origins` 문자열
- Produces: `GET /api/health -> HealthResponse(status="UP")`; `SecurityFilterChain securityFilterChain(HttpSecurity)`

- [ ] **Step 1: 실패하는 MVC 테스트를 작성한다**

```java
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {
    @Autowired MockMvc mockMvc;

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unknownApiPathRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/private"))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 테스트 실패를 확인한다**

Run: `cd backend && .\gradlew.bat test --tests com.petever.api.health.HealthControllerTest`

Expected: FAIL because `/api/health` does not exist.

- [ ] **Step 3: 최소 헬스 컨트롤러와 stateless 보안을 구현한다**

```java
@RestController
@RequestMapping("/api/health")
public class HealthController {
    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("UP");
    }

    public record HealthResponse(String status) {}
}
```

`SecurityConfig`는 CSRF, form login, HTTP Basic을 끄고 세션 정책을 `STATELESS`로 설정한다. `/api/health`, `/actuator/health`, `/error`를 허용하고 나머지는 인증을 요구한다. 인증되지 않은 요청은 `401`을 반환한다. `app.cors.allowed-origins`를 쉼표로 분리해 CORS 설정에 사용한다.

- [ ] **Step 4: 헬스 및 보안 테스트를 실행한다**

Run: `cd backend && .\gradlew.bat test --tests com.petever.api.health.HealthControllerTest`

Expected: 2 tests PASS.

### Task 3: 일관된 오류 응답

**Files:**
- Create: `backend/src/test/java/com/petever/api/common/error/GlobalExceptionHandlerTest.java`
- Create: `backend/src/main/java/com/petever/api/common/error/ApiErrorResponse.java`
- Create: `backend/src/main/java/com/petever/api/common/error/FieldErrorResponse.java`
- Create: `backend/src/main/java/com/petever/api/common/error/GlobalExceptionHandler.java`
- Create: `backend/src/test/java/com/petever/api/common/error/TestValidationController.java`

**Interfaces:**
- Consumes: `MethodArgumentNotValidException`, 예상하지 못한 `Exception`
- Produces: `ApiErrorResponse(String code, String message, List<FieldErrorResponse> fieldErrors)`

- [ ] **Step 1: 검증 실패 응답 테스트를 작성한다**

테스트 전용 컨트롤러는 `POST /test/validation`에서 `record TestRequest(@NotBlank String name) {}`를 받는다. 빈 JSON 요청 시 `400`, `VALIDATION_ERROR`, `요청값을 확인해 주세요.`, `name` 필드 오류를 검증한다. 별도 테스트 보안 설정에서 `/test/**`만 허용한다.

- [ ] **Step 2: 테스트 실패를 확인한다**

Run: `cd backend && .\gradlew.bat test --tests com.petever.api.common.error.GlobalExceptionHandlerTest`

Expected: FAIL because the common error contract does not exist.

- [ ] **Step 3: 오류 DTO와 전역 처리기를 구현한다**

```java
public record FieldErrorResponse(String field, String message) {}

public record ApiErrorResponse(
    String code,
    String message,
    List<FieldErrorResponse> fieldErrors
) {}
```

`GlobalExceptionHandler`는 검증 오류를 `400 / VALIDATION_ERROR`로 바꾼다. 처리되지 않은 예외는 로그에 남기고 응답은 `500 / INTERNAL_ERROR / 서버 오류가 발생했습니다.`와 빈 `fieldErrors`만 반환한다.

- [ ] **Step 4: 오류 테스트를 실행한다**

Run: `cd backend && .\gradlew.bat test --tests com.petever.api.common.error.GlobalExceptionHandlerTest`

Expected: validation and internal-error tests PASS, with no stack trace in JSON.

### Task 4: Supabase 프로필과 개발 문서

**Files:**
- Create: `backend/src/main/resources/application-supabase.yml`
- Create: `backend/.env.example`
- Modify: `backend/.gitignore`
- Create: `backend/README.md`
- Modify: `backend/build.gradle`

**Interfaces:**
- Consumes: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, optional `SERVER_PORT`, `APP_CORS_ALLOWED_ORIGINS`
- Produces: `supabase` 프로필의 PostgreSQL DataSource와 `ddl-auto=validate`

- [ ] **Step 1: Testcontainers 의존성을 추가한다**

```groovy
dependencies {
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'org.testcontainers:postgresql'
}
```

- [ ] **Step 2: Supabase 전용 설정을 작성한다**

```yaml
spring:
  autoconfigure:
    exclude: []
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: false
```

- [ ] **Step 3: 비밀값 예시와 제외 규칙을 작성한다**

`.env.example`에는 JDBC URL 예시, 사용자명, 비밀번호 placeholder, 포트, React 로컬 출처를 넣는다. `.gitignore`에는 `.env`, `.env.*`, `!.env.example`, `build/`, `.gradle/`, `.idea/`, `*.iml`을 포함한다.

- [ ] **Step 4: 실행 문서를 작성한다**

README에 Java 21 요구사항, `gradlew.bat test`, `gradlew.bat bootRun`, `SPRING_PROFILES_ACTIVE=supabase` 실행법, 필요한 환경변수, `/api/health` 확인법을 기록한다. Supabase SQL Editor의 초기 스키마를 재실행하지 말라는 주의도 넣는다.

- [ ] **Step 5: 전체 검증을 실행한다**

Run: `cd backend && .\gradlew.bat clean test`

Expected: all tests PASS on Java 21 without database credentials.

Run: `cd backend && .\gradlew.bat bootJar`

Expected: `backend/build/libs/api-0.0.1-SNAPSHOT.jar` exists.

- [ ] **Step 6: 비밀값 노출을 확인한다**

Run: `rg -n "jraihqgetttetqosvlvo|postgresql://[^ ]+:[^ ]+@" backend -g '!build/**'`

Expected: no real project credential or password is found. `.env.example` contains only placeholder values.

