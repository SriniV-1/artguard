package com.artguard.gateway.incident;

import jakarta.persistence.*;
import java.time.Instant;

/** Durable record of a flagged surveillance event on a camera. */
@Entity
@Table(name = "incidents")
public class Incident {

    public enum Status { OPEN, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "camera_id", nullable = false)
    private String cameraId;

    @Column(name = "camera_name", nullable = false)
    private String cameraName;

    @Column(nullable = false)
    private String label;

    @Column(name = "max_confidence", nullable = false)
    private float maxConfidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "detection_count", nullable = false)
    private int detectionCount = 1;

    @Column(name = "first_frame_id", nullable = false)
    private long firstFrameId;

    @Column(name = "last_frame_id", nullable = false)
    private long lastFrameId;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected Incident() {}

    public Incident(String cameraId, String cameraName, String label, float confidence, long frameId) {
        this.cameraId = cameraId;
        this.cameraName = cameraName;
        this.label = label;
        this.maxConfidence = confidence;
        this.firstFrameId = frameId;
        this.lastFrameId = frameId;
    }

    /** Fold in a corroborating detection on the same open incident. */
    public void corroborate(float confidence, long frameId) {
        this.detectionCount++;
        this.lastFrameId = frameId;
        if (confidence > this.maxConfidence) this.maxConfidence = confidence;
        this.updatedAt = Instant.now();
    }

    public void resolve() {
        this.status = Status.RESOLVED;
        this.resolvedAt = Instant.now();
        this.updatedAt = this.resolvedAt;
    }

    public Long getId() { return id; }
    public String getCameraId() { return cameraId; }
    public String getCameraName() { return cameraName; }
    public String getLabel() { return label; }
    public float getMaxConfidence() { return maxConfidence; }
    public Status getStatus() { return status; }
    public int getDetectionCount() { return detectionCount; }
    public long getFirstFrameId() { return firstFrameId; }
    public long getLastFrameId() { return lastFrameId; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
