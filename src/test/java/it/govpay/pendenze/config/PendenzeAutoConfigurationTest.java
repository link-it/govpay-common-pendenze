package it.govpay.pendenze.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PendenzeAutoConfigurationTest {

    private final ApplicationContextRunner contesto = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PendenzeAutoConfiguration.class));

    @Test
    @DisplayName("l'orologio e' sul fuso Europe/Rome, non su quello della JVM")
    void orologioSulFusoItaliano() {
        contesto.run(c -> assertThat(c.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Europe/Rome")));
    }

    @Test
    @DisplayName("l'orologio dell'applicazione sostituisce quello della libreria")
    void orologioDellApplicazione() {
        Clock proprio = Clock.system(ZoneOffset.UTC);

        contesto.withBean(Clock.class, () -> proprio)
                .run(c -> assertThat(c.getBean(Clock.class)).isSameAs(proprio));
    }

    @Test
    @DisplayName("la libreria non configura Hibernate")
    void nessunCustomizerDiHibernate() {
        contesto.run(c -> assertThat(c).doesNotHaveBean(HibernatePropertiesCustomizer.class));
    }
}
