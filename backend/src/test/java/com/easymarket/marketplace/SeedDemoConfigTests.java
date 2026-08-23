package com.easymarket.marketplace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitario de la configuración de ubicaciones de Flyway del seed demostrativo (PHA06TSK08;
 * plan.md, sección "Integración funcional y operación (PHA06)", fila "Datos demostrativos").
 *
 * <p>Verifica por contrato de configuración (sin levantar el contexto completo, mismo patrón que
 * {@code YamlConfigTests}) que el seed {@code R__seed_demo.sql} solo puede activarse con el perfil
 * {@code dev}:
 * <ul>
 *   <li>{@code application-dev.yml} define {@code spring.flyway.locations} con
 *       {@code classpath:db/migration,classpath:db/dev} — la ubicación exclusiva de desarrollo
 *       donde vive el seed repeatable.</li>
 *   <li>{@code application-prod.yml} NO define {@code spring.flyway.locations}, por lo que hereda
 *       el valor base de {@code application.yml} ({@code classpath:db/migration}) y el seed de
 *       {@code db/dev} nunca se escanea ni se ejecuta en producción.</li>
 *   <li>{@code application.yml} (base, compartida por todos los perfiles) mantiene solo
 *       {@code classpath:db/migration}.</li>
 * </ul>
 * </p>
 *
 * <p>Alcance declarado: esta prueba valida la configuración que impide la activación del seed en
 * prod (la ausencia de la location en el perfil prod). Es una verificación de contrato de
 * configuración, no un arranque del perfil prod completo (que exigiría inyectar todas las
 * variables obligatorias de prod). La garantía de que Flyway no ejecuta el seed en prod se sigue
 * de este contrato: Flyway solo escanea las ubicaciones configuradas en {@code spring.flyway.locations}.</p>
 */
class SeedDemoConfigTests {

    /**
     * Verifica que {@code application-dev.yml} configura {@code spring.flyway.locations} con
     * AMBAS carpetas ({@code db/migration} + {@code db/dev}), porque en Spring Boot la propiedad
     * de perfil reemplaza el valor base (no lo fusiona) y el seed repeatable solo existe en
     * {@code db/dev}.
     */
    @Test
    void testDevYamlDefineLaLocationDelSeedConAmbasCarpetas() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-dev.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("spring.flyway.locations"))
                .isEqualTo("classpath:db/migration,classpath:db/dev");
    }

    /**
     * Verifica que {@code application-prod.yml} NO define {@code spring.flyway.locations} (la
     * propiedad es {@code null}), por lo que el perfil prod hereda el valor base y el seed de
     * {@code db/dev} nunca se activa en producción.
     */
    @Test
    void testProdYamlNoDefineLaLocationDelSeed() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("spring.flyway.locations"))
                .as("el perfil prod no debe configurar la ubicación del seed dev")
                .isNull();
    }

    /**
     * Verifica que {@code application.yml} (base, compartida por todos los perfiles) mantiene
     * únicamente {@code classpath:db/migration}, de modo que el perfil prod — que no la
     * sobrescribe — ejecuta solo las migraciones versionadas y nunca el seed repeatable dev.
     */
    @Test
    void testBaseYamlMantieneSoloLaCarpetaDeMigraciones() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("spring.flyway.locations"))
                .isEqualTo("classpath:db/migration");
    }

    /**
     * Verifica que el perfil local {@code dev} es el perfil predeterminado cuando el proceso no
     * recibe un perfil explícito, sin modificar el comportamiento de perfiles explícitos como
     * {@code test} o {@code prod}.
     */
    @Test
    void testBaseYamlSeleccionaDevComoPerfilPorDefecto() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("spring.profiles.default"))
                .as("el arranque sin perfil explícito debe usar dev")
                .isEqualTo("dev");
    }
}
