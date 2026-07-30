package it.govpay.pendenze.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;

class PendenzeAutoConfigurationTest {

    private final PendenzeAutoConfiguration autoConfiguration = new PendenzeAutoConfiguration();

    @Test
    @DisplayName("senza configurazione il fuso e' Europe/Rome")
    void fusoDefault() {
        assertThat(new PendenzeProperties(null).fusoOrario())
                .isEqualTo(PendenzeProperties.FUSO_ORARIO_DEFAULT)
                .isEqualTo(ZoneId.of("Europe/Rome"));
    }

    @Test
    @DisplayName("l'orologio usa il fuso configurato, non quello della JVM")
    void orologioSulFusoConfigurato() {
        Clock clock = autoConfiguration.pendenzeClock(
                new PendenzeProperties(ZoneId.of("America/New_York")));

        assertThat(clock.getZone()).isEqualTo(ZoneId.of("America/New_York"));
    }

    @Test
    @DisplayName("il customizer allinea Hibernate al fuso configurato")
    void customizerImpostaIlFuso() {
        HibernatePropertiesCustomizer customizer = autoConfiguration.pendenzeFusoOrarioCustomizer(
                new PendenzeProperties(ZoneId.of("Europe/Rome")));
        Map<String, Object> proprieta = new HashMap<>();

        customizer.customize(proprieta);

        assertThat(proprieta)
                .containsEntry(PendenzeAutoConfiguration.HIBERNATE_JDBC_TIME_ZONE, "Europe/Rome");
    }

    @Test
    @DisplayName("una configurazione esplicita del consumatore non viene sovrascritta")
    void nonSovrascriveIlConsumatore() {
        HibernatePropertiesCustomizer customizer = autoConfiguration.pendenzeFusoOrarioCustomizer(
                new PendenzeProperties(ZoneId.of("Europe/Rome")));
        Map<String, Object> proprieta = new HashMap<>();
        proprieta.put(PendenzeAutoConfiguration.HIBERNATE_JDBC_TIME_ZONE, "UTC");

        customizer.customize(proprieta);

        assertThat(proprieta)
                .containsEntry(PendenzeAutoConfiguration.HIBERNATE_JDBC_TIME_ZONE, "UTC");
    }
}
