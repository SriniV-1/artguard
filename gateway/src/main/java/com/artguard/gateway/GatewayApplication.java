package com.artguard.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * ArtGuard gateway — a distributed surveillance backend.
 *
 * <p>Pipeline: concurrent RTSP camera streams are ingested with backpressure and
 * their frames published to Kafka; a consumer fans each frame out to a
 * Python/YOLOv8 inference service over gRPC using one Java virtual thread per
 * in-flight frame; alerting detections open incidents persisted in PostgreSQL
 * and cached in Redis, and alerts are streamed to the React dashboard over a
 * WebSocket.
 */
@SpringBootApplication
@EnableAsync
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
