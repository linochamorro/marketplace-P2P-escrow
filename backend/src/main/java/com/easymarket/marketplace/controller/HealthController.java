package com.easymarket.marketplace.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller REST que expone el endpoint de verificación de estado (health check).
 *
 * <p>Utilizado por servicios de despliegue como Railway para monitoreo de salud
 * y verificación de disponibilidad del pod/contenedor.</p>
 */
@RestController
public class HealthController {

    /**
     * Endpoint público para verificar la disponibilidad operativa del backend.
     *
     * @return {@link ResponseEntity} con código HTTP 200 OK y cuerpo JSON
     *         {@code {"status": "UP"}}
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
