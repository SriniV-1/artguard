package com.artguard.gateway.scene;

import com.artguard.gateway.alert.AlertSocketHandler;
import com.artguard.gateway.alert.Envelope;
import com.artguard.gateway.config.ArtGuardProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Overhead surveillance simulation. Each camera is a zone of people (points)
 * walking between waypoints. Occasionally a person behaves suspiciously: the
 * simulator turns that person "alert" (red) and publishes a {@link TrackEvent}
 * to Kafka, which the {@link TrackConsumer} persists as an incident and alerts
 * on. The full scene (all positions + statuses) is streamed to dashboards ~10×/s
 * for rendering as green/red dots.
 *
 * <p>Active when {@code artguard.mode=simulation} (the default).
 */
@Service
@ConditionalOnProperty(name = "artguard.mode", havingValue = "simulation", matchIfMissing = true)
public class SceneSimulator {

    private static final Logger log = LoggerFactory.getLogger(SceneSimulator.class);
    private static final int PEOPLE_PER_CAMERA = 7;
    private static final long TICK_MS = 100;            // 10 fps scene updates
    private static final double SUSPICION_RATE = 0.0006; // rare: mostly-green scene, occasional red
    private static final long ALERT_HOLD_MS = 5000;

    // Mostly person-movement behaviors; object/weapon-style events are rare.
    private static final String[] BEHAVIORS = {
        "Loitering", "Loitering", "Loitering",
        "Suspicious Movement", "Suspicious Movement", "Suspicious Movement",
        "Erratic Movement",
        "Intrusion",            // rare
        "Abandoned Object",     // rare
    };

    private final ArtGuardProperties props;
    private final KafkaTemplate<String, byte[]> kafka;
    private final AlertSocketHandler socket;
    private final ObjectMapper mapper;

    private final List<Zone> zones = new ArrayList<>();
    private volatile boolean running = true;
    private Thread loop;

    public SceneSimulator(ArtGuardProperties props, KafkaTemplate<String, byte[]> kafka,
                          AlertSocketHandler socket, ObjectMapper mapper) {
        this.props = props;
        this.kafka = kafka;
        this.socket = socket;
        this.mapper = mapper;
    }

    private record Zone(String id, String name, List<Person> people) {}

    @PostConstruct
    void start() {
        int pid = 0;
        for (var s : props.cameras().sources()) {
            var people = new ArrayList<Person>();
            for (int i = 0; i < PEOPLE_PER_CAMERA; i++) people.add(new Person(pid++));
            zones.add(new Zone(s.id(), s.name(), people));
        }
        loop = Thread.ofVirtual().name("scene-sim").start(this::run);
        log.info("Scene simulator started: {} zones x {} people", zones.size(), PEOPLE_PER_CAMERA);
    }

    private void run() {
        while (running) {
            long t0 = System.currentTimeMillis();
            for (Zone z : zones) {
                for (Person p : z.people()) {
                    p.step();
                    if (!p.isAlert() && ThreadLocalRandom.current().nextDouble() < SUSPICION_RATE) {
                        trigger(z, p);
                    }
                }
            }
            broadcastScene();
            long sleep = TICK_MS - (System.currentTimeMillis() - t0);
            if (sleep > 0) try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
        }
    }

    private void trigger(Zone z, Person p) {
        String behavior = BEHAVIORS[ThreadLocalRandom.current().nextInt(BEHAVIORS.length)];
        double conf = 0.6 + ThreadLocalRandom.current().nextDouble() * 0.39;
        p.raiseAlert(System.currentTimeMillis() + ALERT_HOLD_MS);
        var ev = new TrackEvent(z.id(), z.name(), p.id, behavior, conf, p.x, p.y, System.currentTimeMillis());
        try {
            kafka.send(props.kafka().framesTopic(), z.id(), mapper.writeValueAsBytes(ev));
        } catch (Exception e) {
            log.debug("track publish failed: {}", e.getMessage());
        }
    }

    private void broadcastScene() {
        var cams = new ArrayList<Map<String, Object>>();
        for (Zone z : zones) {
            var people = new ArrayList<Map<String, Object>>();
            for (Person p : z.people()) {
                people.add(Map.of(
                    "id", p.id,
                    "x", round(p.x), "y", round(p.y),
                    "status", p.status));
            }
            cams.add(Map.of("id", z.id(), "name", z.name(), "people", people));
        }
        socket.send(new Envelope("scene", Map.of("cameras", cams)));
    }

    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }

    @PreDestroy
    void stop() {
        running = false;
        if (loop != null) loop.interrupt();
    }
}
