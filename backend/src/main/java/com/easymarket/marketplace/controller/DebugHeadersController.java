package com.easymarket.marketplace.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Endpoint temporal de diagnóstico para inspeccionar cabeceras de IP de red en despliegue.
 */
@RestController
@RequestMapping("/debug")
public class DebugHeadersController {

    /**
     * Endpoint {@code GET /debug/headers} que retorna los valores crudos de cabeceras IP.
     *
     * @param request solicitud HTTP
     * @return map en JSON con {@code xForwardedFor}, {@code xRealIp} y {@code remoteAddr}
     */
    @GetMapping("/headers")
    public ResponseEntity<Map<String, String>> debugHeaders(HttpServletRequest request) {
        Map<String, String> response = new HashMap<>();
        response.put("xForwardedFor", request.getHeader("X-Forwarded-For"));
        response.put("xRealIp", request.getHeader("X-Real-IP"));
        response.put("remoteAddr", request.getRemoteAddr());
        return ResponseEntity.ok(response);
    }
}
