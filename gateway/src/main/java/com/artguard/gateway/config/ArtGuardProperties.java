package com.artguard.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strongly-typed binding of the {@code artguard.*} config tree. */
@ConfigurationProperties(prefix = "artguard")
public record ArtGuardProperties(
        Inference inference,
        Kafka kafka,
        Alerting alerting,
        Cameras cameras) {

    public record Inference(String host, int port, long deadlineMs) {}

    public record Kafka(String framesTopic, int maxInFlightPerCamera) {}

    /**
     * @param threshold      base confidence for common alerts (person/movement)
     * @param alertLabels    every label that may raise an alert
     * @param rareLabels     labels treated as rare/high-bar (weapons, unattended items)
     * @param rareThreshold  the (higher) confidence rare labels must clear
     */
    public record Alerting(float threshold, List<String> alertLabels,
                           List<String> rareLabels, float rareThreshold) {}

    public record Cameras(List<Source> sources) {}

    public record Source(String id, String name, String type, String url) {}
}
