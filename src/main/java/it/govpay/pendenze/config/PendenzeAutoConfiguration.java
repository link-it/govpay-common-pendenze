package it.govpay.pendenze.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import it.govpay.common.utils.DateTimePatterns;

/**
 * Autoconfigurazione della libreria: proprieta' e orologio. La generazione IUV
 * ({@link GeneratoreIuvAutoConfiguration}) e' una classe di autoconfigurazione separata,
 * non un {@code @Bean} qui dentro: da quando {@link it.govpay.pendenze.service.PosizioneDebitoriaService}
 * richiede sempre {@code DominioRepository} (per comporre/decomporre l'IUPD pubblico, vedi
 * Javadoc di {@code PosizioneDebitoria}), la sua presenza non e' piu' un segnale valido di "il
 * consumatore vuole la generazione IUV automatica" — un consumatore che non la vuole deve poter
 * escludere solo quella, senza perdere l'orologio fornito qui.
 */
@AutoConfiguration
@EnableConfigurationProperties(PendenzeProperties.class)
public class PendenzeAutoConfiguration {

    /**
     * Orologio della libreria, sul fuso {@value DateTimePatterns#DEFAULT_TIME_ZONE}. Ogni
     * istante generato dalla libreria passa da qui: nessun {@code new Date()} o
     * {@code OffsetDateTime.now()} sparso nel codice, cosi' i test possono usare un
     * orologio fisso.
     *
     * @return l'orologio di sistema sul fuso {@value DateTimePatterns#DEFAULT_TIME_ZONE}
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock pendenzeClock() {
        return Clock.system(ZoneId.of(DateTimePatterns.DEFAULT_TIME_ZONE));
    }
}
