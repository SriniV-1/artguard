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

    public record Alerting(float threshold, List<String> alertLabels) {}

    public record Cameras(List<Source> sources) {}

    public record Source(String id, String name, String type, String url) {}
}
