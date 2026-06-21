package com.artguard.gateway.incident;

import com.artguard.gateway.alert.Alert;
import com.artguard.gateway.alert.AlertSocketHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns alerting detections into incidents.
 *
 * <p>Redis holds the <i>open-incident</i> state per {@code (camera,label)} with a
 * short TTL — the hot dedup/correlation window, so repeated detections of the
 * same threat fold into one incident instead of spamming new rows. PostgreSQL is
 * the durable system of record. Every opened/corroborated incident is pushed to
 * the dashboard over WebSocket, tagged with end-to-end latency.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    private static final Duration OPEN_TTL = Duration.ofSeconds(15);

    private final IncidentRepository repo;
    private final StringRedisTemplate redis;
    private final AlertSocketHandler alerts;
    // serialize per-camera+label so concurrent virtual threads don't double-open
    private final java.util.concurrent.ConcurrentHashMap<String, ReentrantLock> locks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public IncidentService(IncidentRepository repo, StringRedisTemplate redis, AlertSocketHandler alerts) {
        this.repo = repo;
        this.redis = redis;
        this.alerts = alerts;
    }

    // throttle: at most one CORROBORATED alert per incident per this window, so
    // a 5fps stream of the same person doesn't flood the dashboard.
    private static final long CORROBORATE_BROADCAST_MS = 1500;
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> lastBroadcast =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void onAlertingDetection(String cameraId, String cameraName, String label,
                                    float confidence, long frameId, long captureTsMs,
                                    float bx, float by, float bw, float bh) {
        String key = "incident:open:" + cameraId + ":" + label;
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            Incident incident;
            String status;
            String cachedId = redis.opsForValue().get(key);
            if (cachedId != null && (incident = corroborate(Long.parseLong(cachedId), confidence, frameId)) != null) {
                status = "CORROBORATED";
            } else {
                // no open incident in the window (or the cached one was gone) — open fresh
                incident = open(cameraId, cameraName, label, confidence, frameId);
                status = "OPENED";
            }
            // (re)set the hot-window TTL
            redis.opsForValue().set(key, incident.getId().toString(), OPEN_TTL);

            // Always announce a freshly OPENED incident; rate-limit corroborations.
            long now = System.currentTimeMillis();
            boolean isOpen = "OPENED".equals(status);
            Long prev = lastBroadcast.get(incident.getId());
            if (isOpen || prev == null || now - prev >= CORROBORATE_BROADCAST_MS) {
                lastBroadcast.put(incident.getId(), now);
                long latency = Math.max(0, now - captureTsMs);
                alerts.broadcast(new Alert(
                        incident.getId(), cameraId, cameraName, label, confidence,
                        status, incident.getDetectionCount(), latency,
                        bx, by, bw, bh, Instant.now()));
            }
        } finally {
            lock.unlock();
        }
    }

    // Note: not @Transactional — these are self-invoked (proxy AOP wouldn't apply)
    // and each Spring Data repository call manages its own transaction. save()
    // on a detached entity merges, which is what corroborate() relies on.
    private Incident open(String cameraId, String cameraName, String label, float confidence, long frameId) {
        Incident i = repo.save(new Incident(cameraId, cameraName, label, confidence, frameId));
        log.info("Incident OPENED #{} {} '{}' @ {}", i.getId(), cameraId, label, confidence);
        return i;
    }

    /** Fold a detection into an existing open incident; null if it's gone/resolved. */
    private Incident corroborate(long id, float confidence, long frameId) {
        Incident i = repo.findById(id).orElse(null);
        if (i == null || i.getStatus() == Incident.Status.RESOLVED) return null;
        i.corroborate(confidence, frameId);
        return repo.save(i);
    }

    @Transactional(readOnly = true)
    public List<Incident> recent() {
        return repo.findTop50ByOrderByOpenedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Incident> open() {
        return repo.findByStatusOrderByOpenedAtDesc(Incident.Status.OPEN);
    }
}
