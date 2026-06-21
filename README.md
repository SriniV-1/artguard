# ArtGuard — Distributed Surveillance Backend

A distributed, real-time computer-vision surveillance backend. Concurrent camera
streams are ingested with backpressure, frames are fanned out to a YOLOv8
inference service, and threats are persisted, deduplicated, and pushed to a live
dashboard — engineered for **sub-200 ms end-to-end latency** from frame capture
to alert.

**Stack:** Java 21 (virtual threads) · Spring Boot 3.3 · Apache Kafka · gRPC ·
PostgreSQL · Redis · Python · YOLOv8 (Ultralytics) · WebSocket · Docker

## Architecture

```
 RTSP / simulated cameras
        │  (concurrent capture, one virtual thread per camera)
        ▼
 CameraIngestService ──► Kafka  (frames topic)
   bounded per-camera        │   byte[] JPEG frames, keyed by camera
   permits = backpressure    ▼
                       FrameConsumer
                         │  one VIRTUAL THREAD per in-flight frame
                         ▼
                    gRPC  ──►  Python YOLOv8 inference service
                         ◄──    detections (label, confidence, bbox)
                         │
                         ▼
                   IncidentService
                    ├─ Redis  : open-incident window per (camera,label) — dedup/correlate
                    ├─ Postgres: durable incident store (Flyway-managed)
                    └─ WebSocket: push alert (+ end-to-end latency) to dashboards
```

### Why each piece

- **Virtual threads (Java 21).** The frame→inference fan-out is naturally
  one-blocking-call-per-frame. Virtual threads let thousands of frames be
  in-flight concurrently without a thread-per-frame platform-thread cost — the
  blocking gRPC call just parks the virtual thread.
- **Backpressure.** Each camera holds a bounded permit set; a frame takes a
  permit before publishing and releases it on the Kafka ack. Over-limit frames
  are *dropped*, not queued — a slow downstream can't balloon memory, and the
  freshest frames always win.
- **Kafka.** Decouples capture from inference and gives partitioned parallelism
  across inference consumers; short retention because frames are a live signal.
- **gRPC.** Strongly-typed, low-overhead Java↔Python boundary for the model.
- **Redis + Postgres.** Redis is the hot dedup/correlation window (TTL'd
  open-incident state); Postgres is the durable system of record.

## Modules

| Path | What |
|------|------|
| `gateway/` | Spring Boot service: ingest, Kafka, gRPC client, incidents, WebSocket, REST |
| `inference/` | Python YOLOv8 gRPC server |
| `proto/` | Shared gRPC contract (`inference.proto`) |
| `docker-compose.yml` | Kafka (KRaft) + PostgreSQL + Redis |

## Run it

```bash
# 1. Infra
docker compose up -d

# 2. Inference service (Python/YOLOv8)
cd inference
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
./gen_stubs.sh
python inference_server.py            # :50051

# 3. Gateway (Java 21)
./gradlew :gateway:bootRun            # :8080
```

The simulated cameras (no hardware needed) loop bundled sample frames, so YOLOv8
produces real `person` detections and the pipeline lights up immediately. To use
real cameras, add an RTSP source in `gateway/.../application.yml`:

```yaml
artguard:
  cameras:
    sources:
      - id: cam-front
        name: Front Door
        type: rtsp
        url: rtsp://user:pass@192.168.1.50:554/stream
```

## API

- `GET  /api/cameras` — per-camera ingest stats (published / dropped)
- `GET  /api/incidents` — recent incidents
- `GET  /api/incidents/open` — currently-open incidents
- `GET  /api/stats` — pipeline totals
- `WS   /ws/alerts` — live alert stream (JSON), each tagged with `latencyMs`

## Requirements

- JDK 21 (the Gradle build pins a Java 21 toolchain)
- Docker (for Kafka/Postgres/Redis) or native equivalents
- Python 3.10+ (inference service)
