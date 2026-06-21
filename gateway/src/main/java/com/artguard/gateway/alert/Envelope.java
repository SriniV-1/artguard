package com.artguard.gateway.alert;

/** Tagged message sent over the dashboard WebSocket: {type, data}. */
public record Envelope(String type, Object data) {}
