# 🚀 Spring Boot 3.x & Spring Integration 6.x Demo Project

이 프로젝트는 **Spring Boot 3.x** 및 **Spring Integration 6.x (Java DSL)** 기반의 엔터프라이즈 통합 패턴 예제입니다.
파일 감시, SFTP 전송, TCP/IP 소켓 통신, PostgreSQL 데이터베이스 연동(Export/Import) 뿐만 아니라,
**외부 대시보드 라이브러리 없이 Spring Security 및 Control Bus 기반으로 인바운드 어댑터의 상태(RUNNING / STOPPED), 총 처리 건수, 마지막 실행 시각을
실시간 모니터링하고 가동/정지 제어할 수 있는 모던 UI 대시보드**를 제공합니다.

## 테스트시 사전 준비사항

- 로컬 sftp 서버동작 (upload,incoming 폴더생성)

## 🛠️ 주요 기능 및 안정성 패턴 (Defensive Logic)

| 기능                                         | 주요 기술 / 클래스                                                                 | 안전성 / 방어 로직                                                                                                                                                                                             |
| -------------------------------------------- | ---------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **로컬 파일 감시**                           | `LastModifiedFileListFilter`<br>`FileSystemPersistentAcceptOnceFileListFilter`     | 파일 작성 완료 시점(최소 5초 대기)을 확인하여 미완성 파일 읽기 방지 및 중복 처리 차단                                                                                                                          |
| **SFTP 안전 업로드**                         | `SftpOutboundGateway`<br>`temporaryFileSuffix`                                     | 업로드 완료 전까지 임시 확장자(`.writing`)를 사용하고 완료 후 원자적(Atomic) Rename 처리                                                                                                                       |
| **SFTP 안전 다운로드**                       | `SftpSimplePatternFileListFilter`<br>`SftpPersistentAcceptOnceFileListFilter`      | 완료된 파일 패턴만 감시하여 다운로드하고 메타데이터 스토어로 중복 다운로드 방지                                                                                                                                |
| **TCP/IP 소켓 통신**                         | `TcpNetServerConnectionFactory`<br>`ByteArrayCrLfSerializer`                       | CRLF(`\r\n`) 기준 메시지 프레이밍 처리 및 동기식(Blocking) PING/PONG 요청-응답 구현                                                                                                                            |
| **DB ➔ SFTP Export**                         | `JdbcPollingChannelAdapter`<br>`CsvTransformer`                                    | 트랜잭션 내 `PENDING` ➔ `PROCESSING` 상태 즉시 변경으로 중복 조회 방지 후 SFTP 전송                                                                                                                            |
| **SFTP ➔ DB Import**                         | `FileSplitter`<br>`JdbcMessageHandler`                                             | 대용량 CSV 파일의 스트림 기반 분할 처리 및 `ON CONFLICT` 구문을 활용한 UPSERT 저장                                                                                                                             |
| **인바운드 어댑터 모니터링 & 제어 대시보드** | `ControlBus`<br>`SmartLifecycle`<br>`Spring Security`<br>`AdapterExecutionTracker` | ApplicationContext 스캔 기반 어댑터 상태, 건수, 마지막 실행 시각 수집 REST API 및 Control Bus SpEL (`@bean.start()`, `@bean.stop()`) 동적 제어. DB 기반 BCrypt 인증 & Live Pulse 애니메이션 단일 HTML 대시보드 |

---

## 📂 프로젝트 구조

```text
spring-integration-demo
├── pom.xml                                  # Maven 빌드 및 의존성 설정
└── src
    └── main
        ├── java/com/example/integration
        │   ├── SpringIntegrationApplication.java    # 메인 실행 클래스
        │   ├── config/             # 기능별 Java DSL Integration Flow & Security 설정
        │   │   ├── CrawlerConfig.java                # HTTP 뉴스 크롤러 Flow
        │   │   ├── CronIntegrationConfig.java       # Cron 주기 Polling Flow
        │   │   ├── DashboardSecurityConfig.java      # Security & URL 권한 설정
        │   │   ├── DbIntegrationConfig.java          # DB <-> SFTP Flow
        │   │   ├── IntegrationControlBusConfig.java  # Control Bus & 실시간 이벤트 추적기
        │   │   ├── LocalFileIntegrationConfig.java   # 로컬 파일 감시 Flow
        │   │   ├── SftpIntegrationConfig.java        # SFTP 업로드/다운로드 Flow
        │   │   ├── TcpIntegrationConfig.java         # TCP Server/Client Flow
        │   │   └── WebMvcConfig.java                 # 루트 경로 (/) 리다이렉트 설정
        │   ├── controller/         # 대시보드 REST API 컨트롤러
        │   │   └── DashboardIntegrationController.java
        │   ├── domain/             # JPA 데이터베이스 엔티티
        │   │   └── User.java                         # app_user 사용자 엔티티
        │   ├── repository/         # JPA Repository
        │   │   └── UserRepository.java
        │   ├── service/            # 비즈니스 서비스 & CustomUserDetailsService
        │   │   ├── CustomUserDetailsService.java
        │   │   ├── FileProcessService.java
        │   │   └── TcpBusinessService.java
        │   └── transformer/        # CSV <-> DTO 변환 트랜스포머
        └── resources
            ├── application.yml              # PostgreSQL & demo 설정
            ├── db/                          # DDL (schema.sql), DML (data.sql - admin/admin1!)
            └── static/                      # 대시보드 및 로그인 프론트엔드 UI
                ├── dashboard.html           # 인바운드 어댑터 상태 모니터링 모던 대시보드
                └── login.html               # 커스텀 로그인 페이지
```

---

## 🚀 시작하기 (Quick Start)

### 1. 사전 준비 사항

- **Java 21**
- **Maven 3.x**
- **PostgreSQL** (접속 DB 명: `my-app-db`)
- **SFTP Server** _(선택 사항: Docker로 실행 가능)_

```bash
docker run -d --name sftp-server -p 22:22 atmoz/sftp tester:password:::upload,incoming
```

### 2. 설정 (`src/main/resources/application.yml`)

본인의 로컬 환경에 맞게 DB 및 SFTP 접속 정보를 수정합니다.

```yaml
spring:
    datasource:
        url: jdbc:postgresql://localhost:5432/my-app-db
        username: postgres
        password: root
        driver-class-name: org.postgresql.Driver
    jpa:
        hibernate:
            ddl-auto: validate

demo:
    sftp:
        host: localhost
        port: 22
        username: tester
        password: password
```

### 3. 빌드 및 실행

```bash
# Maven 애플리케이션 실행
mvn spring-boot:run
```

---

## 🖥️ 대시보드 및 접속 안내 (Dashboard Access)

애플리케이션이 구동되면 웹 브라우저에서 아래 경로로 접속할 수 있습니다.

- **접속 URL**: `http://localhost:8080/` 또는 `http://localhost:8080/dashboard.html`
- **미인증 사용자 접속 시**: 로그인 페이지(`/login.html`)로 자동 리다이렉트됩니다.
- **초기 관리자 계정**:
    - **Username**: `admin`
    - **Password**: `admin1!`
    - **Role**: `ROLE_ADMIN`

### 🔑 주요 REST API 엔드포인트

| HTTP Method | API Path                                  | 설명                                                                                                       |
| ----------- | ----------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| **GET**     | `/api/dashboard/adapters`                 | 등록된 인바운드 어댑터/스마트 라이프사이클 빈의 상태(RUNNING/STOPPED), 총 처리 건수, 마지막 실행 시각 조회 |
| **POST**    | `/api/dashboard/adapters/{name}/{action}` | `controlBusChannel`을 통해 `@name.start()` 또는 `@name.stop()` 명령 전송                                   |

---

## 🔍 검증 시나리오 (자동 시뮬레이션 & 대시보드 제어)

1. **로그인 & 리다이렉트**: `http://localhost:8080/` 접속 ➔ 로그인 화면 ➔ `admin` / `admin1!` 입력 ➔ 대시보드 메인(`dashboard.html`) 자동 이동
2. **실시간 모니터링**: 3초 주기 자동 동기화로 Cron, DB Export, SFTP Import, Crawler 어댑터의 상태, 처리 건수 및 **마지막 실행 시각** 실시간 갱신
3. **Control Bus 어댑터 제어**: 대시보드 테이블의 **[Stop]** 또는 **[Start]** 버튼 클릭 시 해당 인바운드 어댑터가 즉시 가동/중지되며 Toast 알림 피드백 제공
