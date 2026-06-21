package com.artguard.gateway.scene;

/**
 * A structure in a zone's overhead floor plan, in normalized [0,1] coords.
 * {@code kind} drives how the dashboard styles it (wall, fixture, desk, crate…).
 * People route around these; the dashboard renders them as room structures.
 */
public record Rect(double x, double y, double w, double h, String kind) {

    /** True if the point (with a small body radius) is inside this rect. */
    boolean contains(double px, double py, double r) {
        return px > x - r && px < x + w + r && py > y - r && py < y + h + r;
    }
}
