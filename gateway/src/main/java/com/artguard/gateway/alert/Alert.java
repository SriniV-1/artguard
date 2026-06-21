package com.artguard.gateway.alert;

import java.time.Instant;

/**
 * An alert pushed to the dashboard when an incident opens or is corroborated.
 *
 * <p>The bounding box ({@code bx,by,bw,bh}) is in the analyzed frame's pixel
 * coordinates; the dashboard scales it against the snapshot image's natural
 * size to overlay it accurately.
 *
 * @param latencyMs end-to-end millis from frame capture to this alert (the
 *                  &lt;200ms SLO the system targets)
 */
public record Alert(
        long incidentId,
        String cameraId,
        String cameraName,
        String label,
        float confidence,
        String status,        // OPENED | CORROBORATED
        int detectionCount,
        long latencyMs,
        float bx, float by, float bw, float bh,
        Instant timestamp) {}
