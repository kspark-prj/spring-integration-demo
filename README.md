# 🚀 Spring Boot 3.x & Spring Integration 6.x Demo Project

이 프로젝트는 **Spring Boot 3.x** 및 **Spring Integration 6.x (Java DSL)** 기반의 엔터프라이즈 통합 패턴 예제입니다. 파일 감시, SFTP 전송, TCP/IP 소켓 통신 및 PostgreSQL 데이터베이스 연동(Export/Import) 시 발생할 수 있는 **미완성 파일 읽기, 중복 처리, 동시성 이슈를 방어하는 실무형 패턴**을 제공합니다.

---

## 🛠️ 주요 기능 및 안정성 패턴 (Defensive Logic)

| 기능 | 주요 기술 / 클래스 | 안전성 / 방어 로직 |
| --- | --- | --- |
| **로컬 파일 감시** | `LastModifiedFileListFilter`<br>

<br>`FileSystemPersistentAcceptOnceFileListFilter` | 파일 작성 완료 시점(최소 5초 대기)을 확인하여 미완성 파일 읽기 방지 및 중복 처리 차단 |
| **SFTP 안전 업로드** | `SftpOutboundGateway`<br>

<br>`temporaryFileSuffix` | 업로드 완료 전까지 임시 확장자(`.writing`)를 사용하고 완료 후 원자적(Atomic) Rename 처리 |
| **SFTP 안전 다운로드** | `SftpSimplePatternFileListFilter`<br>

<br>`SftpPersistentAcceptOnceFileListFilter` | 완료된 파일 패턴만 감시하여 다운로드하고 메타데이터 스토어로 중복 다운로드 방지 |
| **TCP/IP 소켓 통신** | `TcpNetServerConnectionFactory`<br>

<br>`ByteArrayCrLfSerializer` | CRLF(`\r\n`) 기준 메시지 프레이밍 처리 및 동기식(Blocking) PING/PONG 요청-응답 구현 |
| **DB ➔ SFTP Export** | `JdbcPollingChannelAdapter`<br>

<br>`CsvTransformer` | 트랜잭션 내 `PENDING` ➔ `PROCESSING` 상태 즉시 변경으로 중복 조회 방지 후 SFTP 전송 |
| **SFTP ➔ DB Import** | `FileSplitter`<br>

<br>`JdbcMessageHandler` | 대용량 CSV 파일의 스트림 기반 분할 처리 및 `ON CONFLICT` 구문을 활용한 UPSERT 저장 |

---

## 📂 프로젝트 구조

```text
spring-integration-demo
├── pom.xml                                  # Maven 빌드 및 의존성 설정
└── src
    └── main
        ├── java/com/example/integration
        │   ├── SpringIntegrationApplication.java    # 메인 클래스
        │   ├── config/             # 기능별 Java DSL Integration Flow 설정
        │   │   ├── DbIntegrationConfig.java          # DB <-> SFTP Flow
        │   │   ├── LocalFileIntegrationConfig.java   # 로컬 파일 감시 Flow
        │   │   ├── SftpIntegrationConfig.java        # SFTP 업로드/다운로드 Flow
        │   │   └── TcpIntegrationConfig.java         # TCP Server/Client Flow
        │   ├── dto/                # 데이터 교환용 DTO
        │   ├── runner/             # 시뮬레이션 테스트 실행 러너
        │   ├── service/            # 비즈니스 로직 서비스
        │   └── transformer/        # CSV <-> DTO 변환 트랜스포머
        └── resources
            ├── application.yml              # 프로퍼티 설정
            └── db/                          # 자동 실행 DDL/DML (schema.sql, data.sql)

```

---

## 🚀 시작하기 (Quick Start)

### 1. 사전 준비 사항

* **Java 17 이상**
* **Maven 3.x**
* **PostgreSQL** (테스트 DB: `integration_db`)
* **SFTP Server** *(선택 사항: Docker로 실행 가능)*
```bash
docker run -d --name sftp-server -p 2222:22 atmoz/sftp sftpuser:sftppassword:::upload,incoming

```



### 2. 설정 (`src/main/resources/application.yml`)

본인의 로컬 환경에 맞게 DB 및 SFTP 접속 정보를 수정합니다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/integration_db
    username: postgres
    password: postgres

demo:
  sftp:
    host: localhost
    port: 2222
    username: sftpuser
    password: sftppassword

```

### 3. 빌드 및 실행

```bash
# Maven 구동
mvn spring-boot:run

```

---

## 🔍 검증 시나리오 (자동 시뮬레이션)

애플리케이션이 실행되면 내장된 `IntegrationDemoRunner`에 의해 아래 시나리오가 백그라운드에서 순차적으로 실행되며 콘솔 로그로 결과를 확인할 수 있습니다.

1. **로컬 파일 감시**: 작성 지연(3초) 파일 생성 ➔ 5초 대기 후 안전하게 파일 내용 수신
2. **TCP/IP 통신**: TCP Client가 `PING` 전송 ➔ TCP Server가 수신 후 `PONG` 동기 응답
3. **DB ➔ SFTP Export**: `user_export` 테이블(`PENDING`) 조회 ➔ CSV 변환 ➔ SFTP 업로드 ➔ DB 상태 `PROCESSED` 업데이트
4. **SFTP ➔ DB Import**: SFTP 다운로드 완료 CSV 감지 ➔ 라인별 스트림 분할 ➔ `user_import` 테이블에 UPSERT 저장