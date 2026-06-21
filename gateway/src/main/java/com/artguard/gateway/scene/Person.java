package com.artguard.gateway.scene;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A simulated person tracked from overhead: a point that wanders randomly in a
 * camera's normalized [0,1]×[0,1] space, bouncing off the walls and routing
 * around room structures. Turns "alert" (red) for a window when it triggers
 * suspicious behavior.
 */
public class Person {
    private static final double BODY_R = 0.012;  // body radius for collision
    private static final double MARGIN = 0.03;   // keep off the outer wall

    final int id;
    double x, y;
    private double heading;       // radians
    private double speed;
    volatile String status = "normal";   // normal | alert (cleared only by an operator)

    Person(int id, List<Rect> obstacles) {
        this.id = id;
        var r = ThreadLocalRandom.current();
        // place in free space
        do { x = rand(); y = rand(); } while (hits(x, y, obstacles));
        this.heading = r.nextDouble(Math.PI * 2);
        this.speed = 0.004 + r.nextDouble() * 0.004;
    }

    /** Random-walk one step, bouncing off walls and structures. */
    void step(List<Rect> obstacles) {
        var r = ThreadLocalRandom.current();
        // wander: nudge the heading a little each tick
        heading += (r.nextDouble() - 0.5) * 0.5;

        double nx = x + Math.cos(heading) * speed;
        double ny = y + Math.sin(heading) * speed;

        // outer walls
        if (nx < MARGIN || nx > 1 - MARGIN) { heading = Math.PI - heading; nx = clamp(x + Math.cos(heading) * speed); }
        if (ny < MARGIN || ny > 1 - MARGIN) { heading = -heading;           ny = clamp(y + Math.sin(heading) * speed); }

        // structures: if the step would enter one, turn away and don't pass through
        if (hits(nx, ny, obstacles)) {
            heading += Math.PI + (r.nextDouble() - 0.5);  // roughly reverse + jitter
            double bx = x + Math.cos(heading) * speed, by = y + Math.sin(heading) * speed;
            if (!hits(bx, by, obstacles)) { nx = clamp(bx); ny = clamp(by); }
            else { nx = x; ny = y; }                       // cornered: hold this tick
        }

        x = clamp(nx); y = clamp(ny);
        // status no longer auto-clears: a flagged subject stays red until an
        // operator marks it benign or it's escorted out.
    }

    void raiseAlert() { this.status = "alert"; }
    void clearAlert() { this.status = "normal"; }
    boolean isAlert() { return "alert".equals(status); }

    private static boolean hits(double px, double py, List<Rect> obstacles) {
        for (Rect o : obstacles) if (o.contains(px, py, BODY_R)) return true;
        return false;
    }
    private static double rand() { return MARGIN + ThreadLocalRandom.current().nextDouble() * (1 - 2 * MARGIN); }
    private static double clamp(double v) { return Math.max(MARGIN, Math.min(1 - MARGIN, v)); }
}
