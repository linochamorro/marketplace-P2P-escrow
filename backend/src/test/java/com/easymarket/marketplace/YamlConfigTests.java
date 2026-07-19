package com.easymarket.marketplace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitario para verificar la validez y consistencia de los archivos YAML de configuración.
 *
 * <p>Este test lee y parsea directamente los archivos {@code application-dev.yml}
 * y {@code application-prod.yml} sin arrancar el contexto completo de Spring Boot,
 * lo que permite verificar la coexistencia de propiedades y la ausencia de claves
 * duplicadas que causen sobreescritura, sin necesidad de dependencias externas como
 * la base de datos o el SDK de Stripe.</p>
 */
class YamlConfigTests {

    /**
     * Verifica que en {@code application-dev.yml} las propiedades {@code app.jwt}
     * y {@code app.cors} coexistan tras la fusión del nodo raíz.
     */
    @Test
    void testDevYamlPropertiesCoexist() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-dev.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();

        // Verificar que ambas propiedades bajo la rama "app" coexistan y no se hayan sobreescrito
        assertThat(properties.getProperty("app.jwt.secret"))
                .isEqualTo("${JWT_SECRET:dev-secret-key-replace-in-production-min-32-chars}");
        assertThat(properties.getProperty("app.jwt.expiration-ms"))
                .isEqualTo("86400000");
        assertThat(properties.getProperty("app.cors.allowed-origins"))
                .isEqualTo("${FRONTEND_URL:http://localhost:3000}");
    }

    /**
     * Verifica que en {@code application-prod.yml} las propiedades {@code app.jwt}
     * y {@code app.cors} también coexistan correctamente.
     */
    @Test
    void testProdYamlPropertiesCoexist() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();

        assertThat(properties.getProperty("app.jwt.secret"))
                .isEqualTo("${JWT_SECRET}");
        assertThat(properties.getProperty("app.jwt.expiration-ms"))
                .isEqualTo("86400000");
        assertThat(properties.getProperty("app.cors.allowed-origins"))
                .isEqualTo("${FRONTEND_URL}");
    }
}
