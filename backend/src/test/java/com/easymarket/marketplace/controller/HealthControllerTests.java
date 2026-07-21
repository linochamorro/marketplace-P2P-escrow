package com.easymarket.marketplace.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test unitario de controller para {@link HealthController}.
 *
 * <p>Verifica que el endpoint {@code GET /health} responda públicamente
 * con estado HTTP 200 OK y el cuerpo JSON esperado {@code {"status": "UP"}}.</p>
 */
class HealthControllerTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController()).build();
    }

    /**
     * Verifica que {@code GET /health} responde HTTP 200 OK y JSON {@code {"status": "UP"}}.
     *
     * @throws Exception si ocurre un error durante la ejecución de la petición mock
     */
    @Test
    @DisplayName("GET /health debe retornar 200 OK y status UP")
    void shouldReturnHealthStatusOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
