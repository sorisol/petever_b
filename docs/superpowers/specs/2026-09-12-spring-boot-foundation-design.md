# Petever Spring Boot 기반 설계

## 목표

PetEver의 백엔드 개발을 시작할 수 있는 실행 가능한 Spring Boot 프로젝트를 생성한다. 이번 범위는 애플리케이션 기반과 Supabase PostgreSQL 연결 경계까지다. 회원가입, 동물 조회, 상담 같은 실제 업무 기능은 다음 구현 단위에서 추가한다.

## 기술 선택

- Java 21: 현재 PC에 설치된 Java 21.0.12 LTS를 사용한다.
- Spring Boot 4.1.1: Java 21과 호환되는 현재 안정 버전을 사용한다.
- Gradle Wrapper와 Groovy DSL: PC에 Gradle을 별도 설치하지 않고 동일한 빌드를 재현한다.
- 패키지명: `com.petever.api`
- 프로젝트 위치: 저장소 루트의 `backend/`
- 의존성: Spring Web MVC, Validation, Spring Data JPA, Spring Security, Actuator, Flyway, PostgreSQL Driver, Testcontainers, JUnit 5.

## 애플리케이션 구조

기능 중심 패키지를 사용한다.

```text
com.petever.api
├── common          공통 응답과 예외 처리
├── config          보안·웹·JPA 설정
├── member          회원과 인증
├── shelter         보호소와 담당자 승인
├── animal          동물·사진·관심 동물
├── consultation    입양 상담과 상태 이력
├── clinic          동물병원 안내
├── notification    서비스 내 알림
└── sync            공공 API 수집
```

이번 생성 단계에서는 빈 기능 패키지를 억지로 만들지 않는다. 애플리케이션 진입점, 공통 예외 응답, 공개 헬스체크 설정처럼 실제 코드가 필요한 부분만 만든다. 이후 기능을 추가할 때 해당 패키지를 생성한다.

## 데이터베이스 연결

Supabase의 PostgreSQL에 JDBC로 직접 연결한다. 저장소에는 비밀번호나 연결 문자열을 넣지 않고 다음 환경변수를 사용한다.

- `DB_URL`: PostgreSQL JDBC URL
- `DB_USERNAME`: DB 사용자명
- `DB_PASSWORD`: DB 비밀번호

로컬 기본 프로필은 DB 연결 없이도 애플리케이션 컨텍스트와 테스트가 실행되도록 구성한다. Supabase 연결은 `supabase` 프로필에서만 활성화한다. JPA의 스키마 자동 생성은 항상 끄고 `ddl-auto=validate`를 사용해 이미 생성된 12개 테이블과 엔티티의 불일치를 빠르게 발견한다.

이번 단계에는 아직 엔티티를 만들지 않으므로 Flyway는 의존성만 포함하고 마이그레이션 실행은 비활성화한다. 기존 Supabase 스키마를 코드 마이그레이션으로 가져오는 작업은 다음 데이터 계층 구현 단위에서 별도로 수행한다. Supabase 대시보드에서 이미 적용된 초기 SQL을 다시 실행하지 않는다.

## HTTP와 보안

- `GET /api/health`는 인증 없이 `200 OK`와 애플리케이션 상태를 반환한다.
- Actuator의 `GET /actuator/health`도 인증 없이 허용한다.
- 그 외 요청은 기본적으로 인증이 필요하다.
- 이번 단계에서는 로그인 방식을 구현하지 않으므로 브라우저 로그인 폼, 임시 사용자, 생성된 개발 비밀번호를 제공하지 않는다.
- CORS 허용 출처는 `APP_CORS_ALLOWED_ORIGINS` 환경변수로 받고, 로컬 기본값은 `http://localhost:5173`으로 둔다.

Spring Security 세션 대신 이후 JWT 인증을 추가할 수 있도록 서버는 stateless로 설정한다. `public.users`와 Supabase Auth는 자동으로 연결하지 않는다.

## 오류 응답

검증 실패와 예상 가능한 애플리케이션 오류는 다음 형태로 반환한다.

```json
{
  "code": "VALIDATION_ERROR",
  "message": "요청값을 확인해 주세요.",
  "fieldErrors": [
    { "field": "email", "message": "올바른 이메일 형식이어야 합니다." }
  ]
}
```

예상하지 못한 예외의 상세 내용과 스택 트레이스는 응답에 노출하지 않는다. 요청 경로와 오류는 서버 로그에 남긴다.

## 설정 파일

- `application.yml`: 포트, 애플리케이션 이름, CORS, Actuator, 로컬 실행 설정
- `application-supabase.yml`: 환경변수 기반 DataSource, PostgreSQL, JPA 검증 설정
- `.env.example`: 변수 이름과 예시 형식만 제공하고 실제 비밀값은 포함하지 않음
- `.gitignore`: 빌드 결과물, IDE 설정, `.env` 및 로컬 비밀 설정 제외

## 테스트와 완료 조건

- Gradle Wrapper로 전체 테스트가 통과한다.
- Java 21로 프로젝트가 컴파일된다.
- DB 자격 증명 없이 기본 프로필의 애플리케이션 컨텍스트 테스트가 통과한다.
- 헬스 컨트롤러 테스트에서 `/api/health`가 인증 없이 `200 OK`를 반환한다.
- 보호된 임의 경로는 인증 없이 접근할 수 없다.
- 오류 응답 단위 테스트에서 내부 예외 정보가 노출되지 않는다.
- Supabase 자격 증명이 제공되면 `supabase` 프로필로 시작하여 DB 연결을 확인할 수 있다. 자격 증명은 테스트 로그에 출력하지 않는다.

## 이번 범위에서 제외

- 도메인 엔티티와 Repository
- 회원가입·로그인·JWT 발급
- 공공 동물 API 호출과 스케줄러
- Redis와 Kafka
- React 프로젝트
- Supabase Storage 이미지 업로드

각 항목은 작은 기능 단위로 구현·테스트·수정하는 루프로 진행한다.
