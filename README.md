# ArtGuard — Distributed Surveillance Backend

A distributed, real-time surveillance backend with a live operator dashboard.
Tracked subjects flow through a Kafka pipeline, get analyzed for suspicious
behavior on Java 21 virtual threads, are persisted and de-duplicated in
PostgreSQL + Redis, and stream to a React control room over WebSocket —
engineered for **sub-200 ms end-to-end latency** from event to alert.

**Stack:** Java 21 (virtual threads) · Spring Boot 3.3 · Apache Kafka · gRPC ·
PostgreSQL · Redis · Python · YOLOv8 (Ultralytics) · WebSocket · React · Docker

## Two modes

The system runs in one of two modes (`artguard.mode`):

- **`simulation`** (default) — an overhead, top-down view of the facility.
  People are simulated as points that wander through room layouts (avoiding
  walls and fixtures); a low rate of them trigger suspicious behavior. This is
  a clean, controllable demo with no false positives.
- **`camera`** — real computer vision: concurrent RTSP / video sources are
  ingested with backpressure and fanned out to a **Python/YOLOv8** gRPC service
  for object detection. (Verified end-to-end at p95 ~101 ms.)

Both modes share the same distributed pipeline below.

## Architecture

```
 subjects (simulated tracks, or RTSP/video frames)
        │  concurrent capture on virtual threads, bounded backpressure
        ▼
      Kafka
        │  one VIRTUAL THREAD per in-flight event/frame
        ▼
   analysis  (suspicious-behavior rules, or gRPC → YOLOv8 detection)
        │
        ▼
  IncidentService
   ├─ Redis  : per-(zone,type) open-incident window — dedup / correlate
   ├─ Postgres: durable incident store (Flyway-managed)
   └─ WebSocket: stream scene + alerts to dashboards, tagged with latency
```

**Why each piece**

- **Virtual threads (Java 21)** — analysis is one-blocking-call-per-event;
  virtual threads keep thousands in flight without a platform-thread cost.
- **Backpressure** — bounded permits per source; over-limit events are dropped,
  not queued, so a slow downstream can't balloon memory.
- **Kafka** — decouples capture from analysis with partitioned parallelism.
- **Redis + Postgres** — Redis is the hot dedup/correlation window; Postgres is
  the durable system of record.
- **gRPC** — strongly-typed Java↔Python boundary for the YOLOv8 model.

## Dashboard (React)

A surveillance control room (`dashboard/`):

- **6 zone displays** filling the screen, each a top-down floor plan with room
  structures (pillars, display cases, desks, crates, shelving). People render as
  **green dots**; a flagged subject turns **red** with a pulsing ring and its
  zone highlights.
- **Click an alert** to spotlight its subject (a pulsing white ring on the exact
  dot) and open that zone large; **click any zone** to open it large.
- **Triage** each alert from the feed: **mark benign** (false alarm → the dot
  returns to green) or **dismiss**. Flags do not auto-clear — an operator
  resolves them.
- **Facility-wide alert** — declare from the alert bar, or **escalate** from an
  alert's expanded view. It broadcasts a lockdown banner to every connected
  dashboard; the escalated subject is then auto-resolved (escorted out) with a
  confirmation popup.
- Live **p95 latency** indicator against the 200 ms SLO.

## Run it

```bash
# 1. Infra (Kafka KRaft + PostgreSQL + Redis)
docker compose up -d            # or: docker-compose up -d

# 2. Gateway (Java 21) — simulation mode by default
./gradlew :gateway:bootRun      # :8080

# 3. Dashboard (React + Vite)
cd dashboard && npm install && npm run dev   # :5173
```

Open the dashboard at <http://localhost:5173>. For real computer vision instead
of the simulation, set `artguard.mode=camera` in
`gateway/src/main/resources/application.yml`, add a `video`/`rtsp` source, and
start the inference service (see `inference/README.md`).

## API

- `GET  /api/cameras` — zones / camera stats
- `GET  /api/incidents`, `/api/incidents/open` — incident records
- `GET  /api/stats` — pipeline totals
- `POST /api/facility-alert` — declare/clear a facility-wide alert (broadcast)
- `POST /api/benign` — mark a subject's alert benign (clear the flag)
- `POST /api/resolve` — resolve an incident (remove the subject)
- `WS   /ws/alerts` — scene + alert + facility envelopes (`{type, data}`)

## Modules

| Path | What |
|------|------|
| `gateway/` | Spring Boot service: simulation, ingest, Kafka, analysis, incidents, WebSocket, REST |
| `dashboard/` | React + Vite operator control room |
| `inference/` | Python YOLOv8 gRPC server (camera mode) |
| `proto/` | Shared gRPC contract |
| `docker-compose.yml` | Kafka + PostgreSQL + Redis |

## Requirements

- JDK 21 (the Gradle build pins a Java 21 toolchain)
- Docker (Kafka / Postgres / Redis)
- Node 18+ (dashboard); Python 3.10+ (camera-mode inference)
