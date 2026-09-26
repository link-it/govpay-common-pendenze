package it.govpay.pendenze.config;

import java.time.Clock;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;

import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.pendenze.iuv.GeneratoreIuvStandard;
import it.govpay.pendenze.iuv.GeneratoreProgressivoIuv;
import it.govpay.pendenze.spi.GeneratoreIuv;

/**
 * Autoconfigurazione della libreria: fuso orario e orologio.
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
     * Implementazione standard di {@link GeneratoreIuv}, condivisa fra i consumatori (vedi
     * Javadoc di {@link GeneratoreIuv} e {@link GeneratoreIuvStandard}). Registrata solo se il
     * consumatore ha gia' un {@link DominioRepository} di {@code govpay-common} nel contesto
     * (per le proprie esigenze di anagrafica) e non ha gia' fornito una propria
     * implementazione di {@link GeneratoreIuv}: se manca l'uno o l'altro, questa libreria non
     * prova a fornire generazione IUV, esattamente come prima di questo bean.
     *
     * @param dominioRepository      repository dell'anagrafica dominio di govpay-common
     * @param applicazioneRepository repository dell'anagrafica applicazione di govpay-common,
     *                               per risolvere il placeholder {@code %(a)} del prefisso IUV
     * @param generatoreProgressivo  allocatore dei progressivi IUV con buffer per chiave
     * @param clock                  orologio della libreria, per risolvere {@code %(Y)}/{@code %(y)}
     * @return l'implementazione standard di {@link GeneratoreIuv}
     */
    @Bean
    @ConditionalOnMissingBean(GeneratoreIuv.class)
    @ConditionalOnBean(DominioRepository.class)
    public GeneratoreIuv generatoreIuvStandard(DominioRepository dominioRepository,
            ApplicazioneRepository applicazioneRepository, GeneratoreProgressivoIuv generatoreProgressivo,
            Clock clock) {
        return new GeneratoreIuvStandard(dominioRepository, applicazioneRepository, generatoreProgressivo, clock);
    }
}
