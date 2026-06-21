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

    private record Zone(String id, String name, List<Person> people, List<Rect> obstacles) {}

    @PostConstruct
    void start() {
        int pid = 0, zi = 0;
        for (var s : props.cameras().sources()) {
            var obstacles = RoomLayouts.forIndex(zi++);
            var people = new ArrayList<Person>();
            for (int i = 0; i < PEOPLE_PER_CAMERA; i++) people.add(new Person(pid++, obstacles));
            zones.add(new Zone(s.id(), s.name(), people, obstacles));
        }
        loop = Thread.ofVirtual().name("scene-sim").start(this::run);
        log.info("Scene simulator started: {} zones x {} people (with room structures)", zones.size(), PEOPLE_PER_CAMERA);
    }

    private void run() {
        while (running) {
            long t0 = System.currentTimeMillis();
            for (Zone z : zones) {
                for (Person p : z.people()) {
                    p.step(z.obstacles());
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
        var r = ThreadLocalRandom.current();
        String behavior = BEHAVIORS[r.nextInt(BEHAVIORS.length)];
        double conf = 0.6 + r.nextDouble() * 0.39;
        // organic, not fixed: each alert holds for a random 4–9s
        p.raiseAlert(System.currentTimeMillis() + 4000 + r.nextInt(5000));
        var ev = new TrackEvent(z.id(), z.name(), p.id, behavior, conf, p.x, p.y, System.currentTimeMillis());
        try {
            kafka.send(props.kafka().framesTopic(), z.id(), mapper.writeValueAsBytes(ev));
        } catch (Exception e) {
            log.debug("track publish failed: {}", e.getMessage());
        }
    }

    // structures don't change, so serialize each zone's layout once
    private final Map<String, List<Map<String, Object>>> obstacleJson = new java.util.HashMap<>();

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
            cams.add(Map.of(
                "id", z.id(), "name", z.name(),
                "people", people,
                "structures", obstacleJson.computeIfAbsent(z.id(), k -> z.obstacles().stream()
                    .map(o -> Map.<String, Object>of("x", o.x(), "y", o.y(), "w", o.w(), "h", o.h(), "kind", o.kind()))
                    .toList())));
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
