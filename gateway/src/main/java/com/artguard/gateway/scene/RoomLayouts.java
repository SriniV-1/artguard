package com.artguard.gateway.scene;

import java.util.List;

/** Overhead floor plans (structures) for each zone, in normalized [0,1] coords. */
final class RoomLayouts {
    private RoomLayouts() {}

    /** Pick a layout by zone index (cycles if there are more zones than layouts). */
    static List<Rect> forIndex(int i) {
        return switch (i % 4) {
            case 0 -> concourse();
            case 1 -> gallery();
            case 2 -> entrance();
            default -> dock();
        };
    }

    // Open concourse: perimeter pillars + a central island installation.
    private static List<Rect> concourse() {
        return List.of(
            new Rect(0.16, 0.18, 0.06, 0.06, "pillar"),
            new Rect(0.78, 0.18, 0.06, 0.06, "pillar"),
            new Rect(0.16, 0.74, 0.06, 0.06, "pillar"),
            new Rect(0.78, 0.74, 0.06, 0.06, "pillar"),
            new Rect(0.40, 0.40, 0.20, 0.20, "fixture"));
    }

    // Gallery: display cases along the top/bottom walls + two center cases.
    private static List<Rect> gallery() {
        return List.of(
            new Rect(0.10, 0.08, 0.30, 0.07, "case"),
            new Rect(0.55, 0.08, 0.32, 0.07, "case"),
            new Rect(0.10, 0.85, 0.34, 0.07, "case"),
            new Rect(0.58, 0.85, 0.30, 0.07, "case"),
            new Rect(0.30, 0.42, 0.16, 0.10, "case"),
            new Rect(0.58, 0.46, 0.16, 0.10, "case"));
    }

    // Entrance: reception desk + a wall and two turnstile barriers.
    private static List<Rect> entrance() {
        return List.of(
            new Rect(0.36, 0.12, 0.28, 0.10, "desk"),
            new Rect(0.00, 0.46, 0.30, 0.05, "wall"),
            new Rect(0.70, 0.46, 0.30, 0.05, "wall"),
            new Rect(0.40, 0.60, 0.04, 0.16, "barrier"),
            new Rect(0.56, 0.60, 0.04, 0.16, "barrier"));
    }

    // Loading dock: a dock platform + scattered crates/pallets.
    private static List<Rect> dock() {
        return List.of(
            new Rect(0.04, 0.06, 0.10, 0.88, "wall"),
            new Rect(0.30, 0.20, 0.12, 0.12, "crate"),
            new Rect(0.52, 0.16, 0.10, 0.10, "crate"),
            new Rect(0.70, 0.30, 0.14, 0.14, "crate"),
            new Rect(0.40, 0.58, 0.16, 0.14, "crate"),
            new Rect(0.66, 0.66, 0.12, 0.12, "crate"));
    }
}
