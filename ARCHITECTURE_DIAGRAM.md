# End-to-End Architecture Diagram

This page has three views: the system components (§1), a single request traced through them (§2), and how it runs in Docker Compose (§3). Details are in [ARCHITECTURE.md](ARCHITECTURE.md). Rendered images are in [`diagrams/`](diagrams/). Channel-level flows (SMS, Push, App Inbox) are in [NOTIFICATION_CHANNEL_ARCHITECTURE.md](NOTIFICATION_CHANNEL_ARCHITECTURE.md).

## 1. System view

```mermaid
flowchart LR
  %% ---------- Actors ----------
  subgraph ACT["Clients"]
    direction TB
    EXT["External applications<br/>(order, CRM, mobile backends)"]
    BIZ["Business users<br/>(marketers, admins)"]
    DEV["MyM1+ app<br/>(M1AIAlert SDK + Firebase SDK)"]
  end

  %% ---------- Frontend ----------
  subgraph FE["Dashboard SPA (React · TS · MUI)"]
    direction TB
    JB["Journey Builder<br/>React Flow canvas"]
    PAGES["Audiences · Templates · Customer 360<br/>Executions · Notifications · Analytics · Config"]
  end

  %% ---------- Platform ----------
  subgraph CEP["Customer Engagement Platform: Spring Boot modular monolith (Java 21)"]
    direction LR

    subgraph GW["API Gateway layer"]
      direction TB
      AUTH["AuthN: API key / JWT<br/>RBAC · tenant resolution"]
      RL["Rate limit · idempotency<br/>schema validation · OpenAPI"]
    end

    subgraph SYNC["Domain modules"]
      direction TB
      AUD["Audience Mgmt<br/>versions · rule evaluator"]
      EVT["Event Ingestion<br/>schemas · dedup"]
      CUS["Customer Profile<br/>attributes · devices"]
      PREF["Preferences<br/>consent · quiet hours"]
      JM["Journey Mgmt<br/>drafts · graph validator · publish"]
      TPL["Template Mgmt<br/>versions · rendering"]
      ANA["Analytics<br/>read models"]
    end

    subgraph ASYNC["Async backbone"]
      direction TB
      OUT[("Transactional outbox")]
      Q[("Durable job queue<br/>run_at · lease · retry · DLQ")]
      W["Workers<br/>SKIP LOCKED polling"]
    end

    subgraph ENG["Journey Execution Engine"]
      direction TB
      RUN["Executor<br/>checkpoint per step"]
      REG["Node registry<br/>START · AUDIENCE · EVENT_TRIGGER<br/>CONDITION · DECISION · WAIT<br/>SMS · PUSH · APP_INBOX · WEBHOOK · END"]
    end

    subgraph CDX["Channel Dispatcher (D13)"]
      direction TB
      TRG["NM trigger API<br/>/alerts/trigger"]
      PIPE["Campaign evaluation: template → recipient →<br/>consent → suppression → freq cap → channel"]
      SMSC["SMS channel<br/>NmSmsWebhookChannel"]
      PUSHC["Push channel<br/>FcmPushChannel"]
      INBC["App Inbox channel<br/>MsInboxChannel"]
      TRG --> PIPE
    end
  end

  %% ---------- Providers ----------
  subgraph PROV["External channel systems (mocked in local / test, D16)"]
    direction TB
    NMX["NM (Notification Manager)<br/>trigger · SMS processing"]
    GWY["SMS Gateway<br/>(connected to NM only)"]
    FCM["Firebase FCM HTTP v1"]
    MS["MS APIs<br/>Store · Fetch All · Read · Unread Count"]
    WH["External webhook targets<br/>(allowlisted, journey WEBHOOK node only)"]
  end

  %% ---------- Data & ops ----------
  subgraph DATA["Data and infrastructure"]
    direction TB
    PG[("PostgreSQL 16<br/>system of record · JSONB")]
    RD[("Redis 7<br/>rate limits · caps · locks")]
    PROM["Prometheus"] --> GRAF["Grafana"]
  end

  %% ---------- Flows ----------
  EXT -- "POST /journeys/execute<br/>POST /events/ingest<br/>POST /notifications/send" --> AUTH
  BIZ --> FE
  FE -- "REST + JWT" --> AUTH
  DEV -- "device tokens · SDK events" --> AUTH
  DEV == "App Inbox: Fetch All · Read · Unread Count" ==> MS
  NMX -- "POST /alerts/trigger" --> AUTH
  AUTH -- "NM trigger" --> TRG
  AUTH --> RL
  RL --> SYNC
  RL -- "start execution" --> RUN

  JM -- "published version" --> RUN
  EVT -- "event stored" --> OUT
  RUN -- "next step / WAIT" --> OUT
  OUT --> Q
  Q --> W
  W -- "journey.step / wake" --> RUN
  W -- "event.process" --> EVT
  W -- "notification.dispatch" --> PIPE
  RUN <--> REG
  REG -- "eligibility" --> AUD
  REG -- "profile and context" --> CUS
  REG -- "SMS / PUSH / INBOX node" --> OUT
  PIPE --> TPL
  PIPE --> PREF
  PIPE -- SMS --> SMSC
  PIPE -- PUSH --> PUSHC
  PIPE -- APP_INBOX --> INBC
  SMSC == "SMS webhook (ack only)" ==> NMX
  NMX -- "submit SMS · DLR" --> GWY
  PUSHC == "messages:send" ==> FCM
  FCM == "deliver" ==> DEV
  INBC == "Store Messages" ==> MS
  REG -- "WEBHOOK node" --> WH

  CEP == "all modules persist here<br/>(module-owned tables)" ==> PG
  CEP -- "limits · caps · locks" --> RD
  CEP -. "/actuator/prometheus" .-> PROM

  classDef store fill:#eef3fb,stroke:#3b6db3,color:#1a2b45;
  classDef engine fill:#fff4e5,stroke:#c77700,color:#3d2600;
  classDef notif fill:#eaf7ef,stroke:#2e8b57,color:#123d26;
  classDef future stroke-dasharray:5 5,color:#666;
  class PG,RD,OUT,Q store;
  class RUN,REG engine;
  class TRG,PIPE,SMSC,PUSHC,INBC notif;
```

## 2. Request lifecycle: the acceptance scenario

This traces one request. The journey is START → AUDIENCE → SMS → WAIT 30m → PUSH → APP_INBOX → END.

```mermaid
sequenceDiagram
  autonumber
  participant App as External app
  participant GW as API Gateway
  participant JE as Journey Engine
  participant DB as PostgreSQL
  participant Q as Job queue
  participant W as Worker
  participant AU as Audience
  participant NM as Channel Dispatcher
  participant P as Mock NM / FCM / MS APIs

  App->>GW: POST /journeys/execute {requestId: REQ10001, journeyId, customerId}
  GW->>GW: API key → tenant · rate limit · schema check
  GW->>JE: start(request)
  JE->>DB: lookup (tenant, requestId)
  alt duplicate requestId
    DB-->>JE: existing execution
    JE-->>App: 202 {executionId, duplicate: true}
  else new request
    JE->>DB: load published version · resolve customer · check re-entry
    JE->>DB: INSERT execution (unique requestId) + job START  [one tx]
    JE-->>App: 202 {executionId, status: PENDING}
  end

  W->>Q: lease journey.step (SKIP LOCKED)
  W->>JE: run START → AUDIENCE
  JE->>AU: evaluate(customer, audience version)
  AU-->>JE: match
  JE->>DB: step SUCCEEDED + notification request (outbox)  [one tx]
  W->>NM: notification.dispatch (SMS)
  NM->>NM: template · consent · suppression · freq cap
  NM->>P: SMS webhook to NM (mock)
  P-->>NM: 2xx ack → SUBMITTED_TO_NM (final, NM sends via its SMS Gateway)
  NM->>DB: delivery log · notify engine

  JE->>DB: WAIT step → job journey.wake run_at = now + 30m
  Note over W,DB: App restarts here. Nothing is held in memory.<br/>The job row survives, and expired leases are reclaimed.
  W->>Q: lease journey.wake when run_at is reached
  W->>JE: resume → PUSH → APP_INBOX
  JE->>NM: PUSH, then APP_INBOX (each idempotent on executionId:nodeKey)
  NM->>P: push via FCM (mock) · Store Messages in MS APIs (mock)
  JE->>DB: END → execution COMPLETED
  Note over App,P: The dashboard reads GET /executions/{id} for the step timeline and notification status.
```

## 3. Deployment view (Docker Compose, v1)

```mermaid
flowchart LR
  subgraph HOST["Docker host"]
    WEB["frontend<br/>nginx serving SPA<br/>:3000"] -- "/api proxy" --> API["cep-app<br/>Spring Boot :8080<br/>API + workers"]
    API --> PG[("postgres:16<br/>:5432 · volume pgdata")]
    API --> RD[("redis:7<br/>:6379")]
    PR["prometheus :9090"] -. scrape .-> API
    GF["grafana :3001"] --> PR
  end
  U["Browser"] --> WEB
  X["External apps"] --> API
```

When load requires it, `cep-app` can be scaled to several replicas. Workers coordinate only through the database (`SKIP LOCKED` leases and advisory locks), so no other changes are needed. The outbox relay is the place where Kafka would be added later.
