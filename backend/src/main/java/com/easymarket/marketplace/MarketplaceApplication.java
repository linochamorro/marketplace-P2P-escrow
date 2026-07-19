package com.easymarket.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Clase principal de arranque de EasyMarket — Marketplace P2P con Escrow.
 *
 * <p>Esta clase inicia el contexto de Spring Boot, registra todos los beans
 * detectados por component-scan bajo el paquete {@code com.easymarket.marketplace},
 * y habilita la infraestructura de jobs programados ({@code @Scheduled})
 * necesaria para los timers de negocio (auto-confirmación de recepción a 48h,
 * notificaciones diarias de transacciones abiertas — ver {@code plan.md},
 * sección «Timers»).</p>
 *
 * <p><strong>Perfil activo por defecto:</strong> {@code dev}. Se cambia a
 * {@code prod} mediante la variable de entorno {@code SPRING_PROFILES_ACTIVE=prod}
 * en Railway.</p>
 *
 * <p><strong>Nota de alcance:</strong> esta clase no contiene lógica de negocio.
 * Toda la configuración de seguridad, datasource y Stripe se delega a clases
 * {@code @Configuration} en sus respectivos paquetes (implementadas en PHA01+).</p>
 */
@SpringBootApplication
@EnableScheduling
public class MarketplaceApplication {

    /**
     * Punto de entrada de la JVM.
     *
     * @param args argumentos de línea de comandos pasados por el runtime
     *             (ej. {@code --spring.profiles.active=prod})
     */
    public static void main(String[] args) {
        SpringApplication.run(MarketplaceApplication.class, args);
    }
}
