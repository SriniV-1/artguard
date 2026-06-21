package com.artguard.gateway.camera;

import com.artguard.gateway.config.ArtGuardProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Owns the concurrent camera sources and publishes their frames onto Kafka.
 *
 * <p><b>Backpressure:</b> each camera holds a bounded permit set
 * ({@code max-in-flight-per-camera}). A frame acquires a permit before it is
 * sent and releases it on the broker ack; if no permit is available the frame
 * is dropped rather than queued unboundedly. This keeps a slow downstream
 * (inference) from ballooning memory while always serving the freshest frames.
 */
@Service
@ConditionalOnProperty(name = "artguard.mode", havingValue = "camera")
public class CameraIngestService {

    private static final Logger log = LoggerFactory.getLogger(CameraIngestService.class);

    private final ArtGuardProperties props;
    private final KafkaTemplate<String, byte[]> kafka;

    private final List<FrameSource> sources = new ArrayList<>();
    private final Map<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> published = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> dropped = new ConcurrentHashMap<>();
    // latest JPEG per camera, so the dashboard can show the live frame being analyzed
    private final Map<String, byte[]> latestFrame = new ConcurrentHashMap<>();

    public CameraIngestService(ArtGuardProperties props, KafkaTemplate<String, byte[]> kafka) {
        this.props = props;
        this.kafka = kafka;
    }

    @PostConstruct
    void start() {
        int maxInFlight = props.kafka().maxInFlightPerCamera();
        for (ArtGuardProperties.Source s : props.cameras().sources()) {
            permits.put(s.id(), new Semaphore(maxInFlight));
            published.put(s.id(), new AtomicLong());
            dropped.put(s.id(), new AtomicLong());

            FrameSource source = switch (s.type().toLowerCase()) {
                case "rtsp"  -> new RtspFrameSource(s.id(), s.name(), s.url());
                case "video" -> new VideoFileFrameSource(s.id(), s.name(), s.url());
                default      -> new SimulatedFrameSource(s.id(), s.name());
            };
            sources.add(source);
            source.start(this::publish);
        }
        log.info("Camera ingest started: {} cameras, {} in-flight/camera", sources.size(), maxInFlight);
    }

    private void publish(Frame frame) {
        latestFrame.put(frame.cameraId(), frame.jpeg()); // newest frame for the snapshot endpoint
        Semaphore permit = permits.get(frame.cameraId());
        if (permit == null || !permit.tryAcquire()) {
            dropped.get(frame.cameraId()).incrementAndGet(); // backpressure: drop, don't queue
            return;
        }
        var record = new ProducerRecord<>(props.kafka().framesTopic(), frame.cameraId(), frame.jpeg());
        record.headers().add(new RecordHeader("frameId", Long.toString(frame.frameId()).getBytes()));
        record.headers().add(new RecordHeader("captureTs", Long.toString(frame.captureTsMs()).getBytes()));
        record.headers().add(new RecordHeader("cameraName", camName(frame.cameraId()).getBytes()));

        kafka.send(record).whenComplete((res, ex) -> {
            permit.release();
            if (ex == null) published.get(frame.cameraId()).incrementAndGet();
        });
    }

    /** Latest JPEG frame for a camera (for the dashboard snapshot), or null. */
    public byte[] latestFrame(String cameraId) {
        return latestFrame.get(cameraId);
    }

    private String camName(String cameraId) {
        return props.cameras().sources().stream()
                .filter(s -> s.id().equals(cameraId)).map(ArtGuardProperties.Source::name)
                .findFirst().orElse(cameraId);
    }

    /** Per-camera ingest counters, surfaced via the REST API. */
    public List<Map<String, Object>> stats() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ArtGuardProperties.Source s : props.cameras().sources()) {
            out.add(Map.of(
                "cameraId", s.id(),
                "cameraName", s.name(),
                "type", s.type(),
                "published", published.get(s.id()).get(),
                "dropped", dropped.get(s.id()).get()));
        }
        return out;
    }

    @PreDestroy
    void stop() {
        sources.forEach(FrameSource::stop);
    }
}
