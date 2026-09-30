# Spring Integration 연동 가이드 및 방어 로직 분석

이 문서는 제공된 Spring Boot 3.x 기반 Spring Integration 데모 프로젝트의 핵심 구성 요소, 파일 처리 시 발생할 수 있는 "미완성 파일(Incomplete File) 처리 문제"를 예방하기 위한 방어 로직의 원리 및 설정, **PostgreSQL 데이터베이스 연동**, 그리고 **Spring Security & Control Bus 기반 인바운드 어댑터 모니터링/제어 대시보드 구조**를 설명합니다.

---

## 1. 프로젝트 아키텍처 개요

본 프로젝트는 다음과 같은 핵심 연동 및 모니터링 흐름을 제공합니다.

```mermaid
graph TD
    subgraph Local Watcher Flow
        A[Local Folder] -->|Polling 1s| B[FileReadingMessageSource]
        B -->|Filter Chain| C{Safe & Complete?}
        C -->|Yes| D[LocalFileWatchFlow]
        D -->|Payload: File| E[FileProcessService: processLocalFile]
    end

    subgraph SFTP Flow
        F[sftpUploadChannel] -->|Upload with .writing suffix| G[SftpOutboundFlow]
        G -->|Rename on finish| H[Remote SFTP Server]
        H -->|Polling & Filter *.csv| I[SftpInboundFileSynchronizer]
        I -->|Download| J[SftpInboundFlow]
        J -->|Payload: File| K[FileProcessService: processDownloadedFile]
    end

    subgraph TCP/IP Flow
        L[Client Request] -->|TCP IP Connection| M[TcpNetServer]
        M -->|ByteArrayCrLfSerializer| N[TcpServerFlow]
        N -->|Payload: String| O[TcpBusinessService: handleRequest]
        O -->|Return ACK String| M
        M -->|CRLF Ended Response| L
    end

    subgraph DB Export & Import Flow
        DB_E[(USER_EXPORT)] -->|Polling SELECT & Update PROCESSING| P[jdbcPollingChannelAdapter]
        P -->|DB to CSV Transform| Q[dbToSftpExportFlow]
        Q -->|SFTP Upload| R[Remote SFTP Server]
        R -->|Final Update PROCESSED| DB_E
        
        S[Remote SFTP Server] -->|Poll & Download *.csv| T[sftpImportSynchronizer]
        T -->|Split File by Line| U[sftpToDbImportFlow]
        U -->|Parse CSV Line to DTO| V[CsvTransformer]
        V -->|Batch/UPSERT Insert| W[jdbcImportMessageHandler]
        W --> DB_I[(USER_IMPORT)]
    end

    subgraph Security & Control Bus Dashboard
        Client[Web Browser] -->|Auth Filter & ROLE_ADMIN| Sec[Spring Security FilterChain]
        Sec -->|Redirect / -> /dashboard.html| Web[dashboard.html UI]
        Web -->|GET /api/dashboard/adapters| Ctrl[DashboardIntegrationController]
        Ctrl -->|Scan SmartLifecycle & Event Metrics| Web
        Web -->|POST /api/dashboard/adapters/{name}/{action}| Ctrl
        Ctrl -->|Send SpEL @bean.start() / @bean.stop()| CB[controlBusChannel]
        CB -->|ControlBus Flow| Flow[IntegrationFlow & Adapters Lifecycle]
    end
```

---

## 2. [요구사항 1] 로컬 미완성 파일 감지 방어 로직 (Incomplete File Prevention)

### 2.1 문제 상황
외부 프로세스가 감시 대상 폴더(Watch Directory)에 대용량 파일을 복사하고 있거나, 파일 스트림을 열어 쓰고 있는 상황이 발생할 수 있습니다.
이때 단순한 폴러(Poller)는 파일이 디렉터리에 노출되는 즉시 감지하여 파일 읽기를 시도하게 되고, **내용이 잘려 나간 불완전한 데이터를 읽거나 파일 잠금(Lock)으로 인한 I/O 예외**가 발생합니다.

### 2.2 해결 방안 및 방어 원리 (Spring Integration 필터링 체인)
본 코드에서는 `ChainFileListFilter`에 두 가지 필터를 조합하여 이 문제를 방지합니다.

1. **`LastModifiedFileListFilter` (수정 지연 시간 검증)**
   - **동작 원리**: 파일의 마지막 수정 시각(Last Modified Time)과 현재 시각의 차이를 계산하여 설정한 지연 시간(예: `5초`)보다 더 최근에 수정된 파일은 **"현재 쓰고 있는 중"**으로 간주하여 필터를 통과시키지 않고 보류합니다.
   - **적용**: 파일 쓰기 작업이 완전히 완료되어 더 이상 파일 수정 시각이 갱신되지 않고 5초 이상이 흘러야만 해당 파일이 최종 필터를 통과하여 비즈니스 로직으로 흐릅니다.

2. **`FileSystemPersistentAcceptOnceFileListFilter` (중복 처리 방지)**
   - **동작 원리**: 파일의 이름과 마지막 수정 시각 정보를 키로 생성하여 메타데이터 스토어(Metadata Store)에 영구 저장합니다. 한 번 처리된 파일은 다시 감지되더라도 중복 가공되지 않도록 차단합니다.

---

## 3. [요구사항 2 & 3] SFTP 안전 전송 및 원격지 파일 감시 방어 로직

### 3.1 SFTP 업로드 시 방어 로직 (Outbound)
SFTP로 원격지에 파일을 업로드할 때, 원격지 서버를 바라보고 있는 수신자(또는 감시자)가 불완전한 파일을 읽어가지 못하도록 차단해야 합니다.
- **`temporaryFileSuffix(tempFileSuffix)` 활용**:
  - 파일 업로드 도중에는 임시 확장자(예: `demo.txt.writing`)를 붙여 업로드합니다.
  - 업로드가 100% 완료된 시점에 SFTP 프로토콜의 `Rename` 명령을 실행하여 원래 파일명(`demo.txt`)으로 일괄 변경합니다.

### 3.2 SFTP 다운로드 시 방어 로직 (Inbound)
반대로 우리가 원격 SFTP 서버의 디렉터리를 감시하며 신규 파일을 안전하게 로컬로 가져올 때의 방어 로직입니다.
- **임시 파일 필터링 (`SftpSimplePatternFileListFilter`)**:
  - 송신자가 파일 업로드 시 사용하는 임시 파일 패턴(예: `*.writing`)을 필터링하고 완성 파일 패턴(예: `*.csv` 또는 `*.txt`)만 수집 대상으로 선정합니다.
- **중복 다운로드 방지 (`SftpPersistentAcceptOnceFileListFilter`)**:
  - 이미 로컬로 복사 완료한 원격 파일 목록을 로컬 메타데이터 스토어에 보관하여 불필요하게 동일한 파일을 반복 다운로드하는 대역폭 낭비를 차단합니다.

---

## 4. [요구사항 4] TCP/IP Client & Server 통신 메커니즘

### 4.1 메시지 프레이밍 (Serialization/Deserializer)
TCP는 연결 지향의 바이트 스트림(Byte Stream) 전송 프로토콜이므로, 패킷이 쪼개지거나 뭉쳐서 도착할 수 있습니다. 수신 측에서는 "어디가 메시지의 끝인지"를 알 수 있어야 합니다.
- 본 예제에서는 **`ByteArrayCrLfSerializer`**를 사용합니다.
- 송신 시 데이터의 마지막에 자동으로 캐리지 리턴과 라인 피드(CRLF, `\r\n`) 바이트를 덧붙여 전송하고, 수신 시 이를 구분자로 삼아 데이터를 분할합니다.

---

## 5. [요구사항 5 & 6] PostgreSQL 데이터베이스 연동 및 트랜잭션 관리

### 5.1 DB ➔ SFTP Export (데이터 조회 및 전송)
- **동작 원리**: `user_export` 테이블에서 `status = 'PENDING'`인 레코드를 조회하여 CSV 파일로 변환한 다음 SFTP 원격 디렉터리로 전송합니다.
- **트랜잭션 격리 및 중복 조회 방지**:
  - `JdbcPollingChannelAdapter`가 주기적으로 데이터를 폴링할 때, 여러 인스턴스가 동시에 기동되는 다중화 환경에서 동일 레코드가 이중 처리되는 현상을 방지해야 합니다.
  - 이를 해결하기 위해 폴러에 **`PlatformTransactionManager`**를 바인딩하고, **`setUpdateSql`** 설정을 적용합니다.
  - **`SELECT` 쿼리 실행 직후 동일 트랜잭션 내에서 즉시 `UPDATE user_export SET status = 'PROCESSING' WHERE id IN (:id)`가 실행**됩니다. 
  - 이를 통해 데이터를 전송하는 긴 네트워크 I/O 작업 전에 상태값이 트랜잭션 단위로 신속히 반영되어, 다른 노드의 폴링 스레드가 동일 데이터를 다시 가져가지 못하게 막습니다.

### 5.2 SFTP ➔ DB Import (다운로드 및 저장)
- **동작 원리**: SFTP 서버에서 파일 동기화로 신규 CSV 파일을 다운로드받은 뒤, 행 단위로 분할하여 DB에 적재합니다.
- **`FileSplitter`와 `Transformer` 조합**:
  - `FileSplitter`를 통해 파일 전체를 메모리에 올리지 않고 스트림 방식으로 줄(Line)마다 메시지를 쪼갭니다.
  - `CsvTransformer`가 각 줄의 문자열 데이터를 `UserImportDto` 객체로 역직렬화(Parsing)하고, 헤더 행 등은 필터링하여 걸러냅니다.
- **UPSERT 기법을 적용한 저장**:
  - `JdbcMessageHandler`에 **`ON CONFLICT (id) DO UPDATE`** 쿼리를 정의해 이미 동일한 ID를 가진 유저가 DB에 존재하는 경우 자동으로 신규 정보로 수정(Update)하고, 없는 경우 신규 등록(Insert)되도록 UPSERT를 수행합니다.

---

## 6. [대시보드 & Control Bus] 인바운드 어댑터 상태 모니터링 및 동적 제어

### 6.1 Control Bus 연동 아키텍처
Spring Integration의 `ControlBus` 메커니즘을 사용하여 별도의 런타임 재구동 없이 애플리케이션 내 MessageSource, Poller, InboundChannelAdapter, IntegrationFlow 등의 가동 상태를 동적으로 변경합니다.
- **채널 설정**: `controlBusChannel` (`DirectChannel`)
- **Control Bus 구성**: `IntegrationFlow.from(CONTROL_BUS_CHANNEL).controlBus().get()`
- **동적 명령 실행**: 대시보드 요청 시 `@beanName.start()` 또는 `@beanName.stop()` SpEL 구문을 `controlBusChannel`로 발송하여 런타임 제어를 수행합니다.

### 6.2 실행 횟수 및 마지막 실행 시각 추적 (`AdapterExecutionTracker`)
- **`@GlobalChannelInterceptor` 및 `ApplicationListener<IntegrationEvent>`**:
  - 채널을 거치는 모든 메시지 전송 이벤트(`preSend`) 및 Spring Integration 시스템 이벤트(`IntegrationEvent`)를 수집합니다.
  - 각 인바운드 어댑터 및 채널별 총 처리 건수(`totalCount`)를 atomic 카운터로 기록하고, **마지막 실행 시각(`lastExecutedTime`)**을 `LocalDateTime` 형태로 저장합니다.

### 6.3 Spring Security DB 연동 & 리다이렉트 흐름
- **계정 테이블 (`app_user`)**: PostgreSQL DB에 저장되며, 초기 생성되는 `admin` 계정은 `BCryptPasswordEncoder`로 암호화된 비밀번호(`admin1!`)와 `ROLE_ADMIN` 권한을 보유합니다.
- **`CustomUserDetailsService`**: `UserRepository`를 통해 데이터베이스 사용자를 검증합니다.
- **인증 및 페이지 이동 흐름**:
  1. 사용자 접속 `http://localhost:8080/` ➔ `WebMvcConfig`에 의해 `/dashboard.html`로 리다이렉트
  2. 미인증 상태 시 `DashboardSecurityConfig`에 의해 커스텀 로그인 페이지(`/login.html`)로 이동
  3. 로그인 성공 시 `defaultSuccessUrl('/dashboard.html', true)` 규칙으로 대시보드로 자동 진입
  4. `/dashboard.html` 및 `/api/dashboard/**` 접근 시 `ROLE_ADMIN` 권한을 검증하여 보호합니다.
