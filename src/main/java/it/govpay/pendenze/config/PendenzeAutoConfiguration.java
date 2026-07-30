package it.govpay.pendenze.config;

import java.time.Clock;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import it.govpay.pendenze.codec.ProprietaPendenzaCodec;

/**
 * Autoconfigurazione della libreria: fuso orario, orologio e codec.
 */
@AutoConfiguration
@EnableConfigurationProperties(PendenzeProperties.class)
public class PendenzeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PendenzeAutoConfiguration.class);

    static final String HIBERNATE_JDBC_TIME_ZONE = "hibernate.jdbc.time_zone";

    /**
     * Orologio della libreria, sul fuso configurato. Ogni istante generato dalla libreria
     * passa da qui: nessun {@code new Date()} o {@code OffsetDateTime.now()} sparso nel
     * codice, cosi' il fuso e' quello configurato e i test possono usare un orologio fisso.
     *
     * @param properties configurazione della libreria
     * @return l'orologio di sistema sul fuso configurato
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock pendenzeClock(PendenzeProperties properties) {
        return Clock.system(properties.fusoOrario());
    }

    /**
     * Allinea Hibernate al fuso configurato, cosi' la conversione fra le colonne
     * {@code TIMESTAMP} (senza time zone) e gli {@code OffsetDateTime} delle entita' non
     * dipende dal fuso della JVM.
     *
     * <p>Se il consumatore ha gia' impostato {@code hibernate.jdbc.time_zone}, il suo
     * valore viene lasciato intatto: la libreria non sovrascrive scelte esplicite.</p>
     *
     * @param properties configurazione della libreria
     * @return il customizer delle proprieta' Hibernate
     */
    @Bean
    public HibernatePropertiesCustomizer pendenzeFusoOrarioCustomizer(PendenzeProperties properties) {
        return (Map<String, Object> hibernateProperties) -> {
            Object esistente = hibernateProperties.get(HIBERNATE_JDBC_TIME_ZONE);
            if (esistente != null) {
                log.debug("{} gia' impostato a [{}]: lascio la configurazione del consumatore",
                        HIBERNATE_JDBC_TIME_ZONE, esistente);
                return;
            }
            hibernateProperties.put(HIBERNATE_JDBC_TIME_ZONE, properties.fusoOrario().getId());
            log.debug("{} impostato a [{}]", HIBERNATE_JDBC_TIME_ZONE, properties.fusoOrario());
        };
    }

    /**
     * Codec delle proprieta' della pendenza. Usa il mapper dell'applicazione se c'e',
     * altrimenti se ne costruisce uno proprio: la libreria non impone al consumatore di
     * configurare Jackson.
     *
     * @param objectMapper mapper JSON dell'applicazione, se disponibile
     * @return il codec
     */
    @Bean
    @ConditionalOnMissingBean
    public ProprietaPendenzaCodec proprietaPendenzaCodec(ObjectProvider<ObjectMapper> objectMapper) {
        return new ProprietaPendenzaCodec(
                objectMapper.getIfAvailable(() -> JsonMapper.builder().build()));
    }
}
