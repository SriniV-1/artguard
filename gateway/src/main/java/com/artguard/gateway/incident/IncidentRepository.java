package com.artguard.gateway.incident;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findByCameraIdAndLabelAndStatus(
            String cameraId, String label, Incident.Status status);

    List<Incident> findTop50ByOrderByOpenedAtDesc();

    List<Incident> findByStatusOrderByOpenedAtDesc(Incident.Status status);
}
