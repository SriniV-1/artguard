package com.artguard.gateway.scene;

/** A suspicious-behavior event published to Kafka for analysis/persistence. */
public record TrackEvent(
        String cameraId,
        String cameraName,
        int personId,
        String behavior,     // Loitering | Suspicious Movement | Running | Intrusion | Abandoned Object
        double confidence,
        double x,
        double y,
        long captureTsMs) {}
