package com.artguard.gateway.scene;

import com.artguard.gateway.incident.IncidentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes suspicious-behavior track events from Kafka and, on its own virtual
 * thread per event, persists them as incidents (Postgres + Redis dedup) and
 * raises a dashboard alert. The analysis half of the simulation pipeline.
 *
 * <p>Active when {@code artguard.mode=simulation} (the default).
 */
@Component
@ConditionalOnProperty(name = "artguard.mode", havingValue = "simulation", matchIfMissing = true)
public class TrackConsumer {

    private static final Logger log = LoggerFactory.getLogger(TrackConsumer.class);

    private final IncidentService incidents;
    private final ObjectMapper mapper;
    private final ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor();

    public TrackConsumer(IncidentService incidents, ObjectMapper mapper) {
        this.incidents = incidents;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${artguard.kafka.frames-topic}")
    public void onTrack(ConsumerRecord<String, byte[]> rec) {
        vthreads.submit(() -> {
            try {
                TrackEvent ev = mapper.readValue(rec.value(), TrackEvent.class);
                incidents.onAlertingDetection(
                        ev.cameraId(), ev.cameraName(), ev.behavior(), (float) ev.confidence(),
                        ev.personId(), ev.captureTsMs(),
                        (float) ev.x(), (float) ev.y(), 0f, 0f);
            } catch (Exception e) {
                log.debug("track analysis failed: {}", e.getMessage());
            }
        });
    }
}
