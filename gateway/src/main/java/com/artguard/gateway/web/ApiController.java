package com.artguard.gateway.web;

import com.artguard.gateway.alert.AlertSocketHandler;
import com.artguard.gateway.camera.CameraIngestService;
import com.artguard.gateway.config.ArtGuardProperties;
import com.artguard.gateway.incident.Incident;
import com.artguard.gateway.incident.IncidentService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** REST surface for the dashboard: cameras/zones, incidents, and pipeline stats. */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {

    private final ArtGuardProperties props;
    private final IncidentService incidents;
    private final AlertSocketHandler alerts;
    // present only in camera mode; absent in simulation mode
    private final ObjectProvider<CameraIngestService> cameras;

    public ApiController(ArtGuardProperties props, IncidentService incidents,
                         AlertSocketHandler alerts, ObjectProvider<CameraIngestService> cameras) {
        this.props = props;
        this.incidents = incidents;
        this.alerts = alerts;
        this.cameras = cameras;
    }

    @GetMapping("/cameras")
    public List<Map<String, Object>> cameras() {
        CameraIngestService ingest = cameras.getIfAvailable();
        if (ingest != null) return ingest.stats();
        // simulation mode: just the configured zones
        return props.cameras().sources().stream()
                .map(s -> Map.<String, Object>of("cameraId", s.id(), "cameraName", s.name(), "type", s.type()))
                .collect(Collectors.toList());
    }

    /** Latest analyzed frame for a camera (camera mode only). */
    @GetMapping("/cameras/{id}/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable String id) {
        CameraIngestService ingest = cameras.getIfAvailable();
        byte[] jpeg = ingest == null ? null : ingest.latestFrame(id);
        if (jpeg == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(jpeg);
    }

    @GetMapping("/incidents")
    public List<Incident> incidents() {
        return incidents.recent();
    }

    @GetMapping("/incidents/open")
    public List<Incident> openIncidents() {
        return incidents.open();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
            "cameras", props.cameras().sources().size(),
            "openIncidents", incidents.open().size(),
            "dashboardClients", alerts.connectedClients());
    }
}
