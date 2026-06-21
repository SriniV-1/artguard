package com.artguard.gateway.scene;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A simulated person tracked from overhead: a point moving in a camera's
 * normalized [0,1]×[0,1] space toward waypoints. Turns "alert" (red) for a
 * window when it triggers suspicious behavior.
 */
public class Person {
    final int id;
    double x, y, tx, ty;
    double speed;
    volatile String status = "normal";   // normal | alert
    volatile long alertUntilMs = 0;

    Person(int id) {
        this.id = id;
        var r = ThreadLocalRandom.current();
        this.x = r.nextDouble(); this.y = r.nextDouble();
        pickTarget();
        this.speed = 0.004 + r.nextDouble() * 0.006;
    }

    void pickTarget() {
        var r = ThreadLocalRandom.current();
        this.tx = r.nextDouble(); this.ty = r.nextDouble();
    }

    /** Advance toward the waypoint; pick a new one on arrival. */
    void step() {
        double dx = tx - x, dy = ty - y;
        double dist = Math.hypot(dx, dy);
        if (dist < 0.02) { pickTarget(); return; }
        x += dx / dist * speed;
        y += dy / dist * speed;
        x = Math.max(0, Math.min(1, x));
        y = Math.max(0, Math.min(1, y));
        if (System.currentTimeMillis() > alertUntilMs && "alert".equals(status)) status = "normal";
    }

    void raiseAlert(long untilMs) { this.status = "alert"; this.alertUntilMs = untilMs; }

    boolean isAlert() { return "alert".equals(status); }
}
