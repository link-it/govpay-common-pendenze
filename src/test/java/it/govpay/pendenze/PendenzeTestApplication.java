package it.govpay.pendenze;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Punto di ingresso della configurazione per i test di integrazione.
 *
 * <p>Serve perche' questa e' una libreria e non un'applicazione: gli slice test di Spring
 * Boot ({@code @DataJpaTest}) cercano una classe {@code @SpringBootConfiguration} risalendo
 * i package, e senza di essa non partirebbero. Sta in {@code it.govpay.pendenze} cosi' le
 * entita' dei sotto-package vengono trovate dallo scan predefinito.</p>
 *
 * <p>Niente {@code @EntityScan}/{@code @EnableJpaRepositories} su {@code it.govpay.common} qui:
 * resta a ogni singolo test il compito di dichiararle se ne ha bisogno (es.
 * {@code PosizioneDebitoriaServiceTest} per {@code ApplicazioneRepository}/
 * {@code DominioRepository}, {@code GeneratoreIuvStandardTest} per l'anagrafica completa). Il
 * bean {@code @ConditionalOnBean(DominioRepository.class)} di
 * {@code GeneratoreIuvAutoConfiguration} (il {@code GeneratoreIuv} di default, classe separata
 * da {@code PendenzeAutoConfiguration} apposta per questo) non scatta comunque da solo in
 * nessun test: con {@code @DataJpaTest}, {@code @EnableAutoConfiguration} qui sopra non ha
 * effetto — contano solo le classi che ogni test elenca esplicitamente in
 * {@code @ImportAutoConfiguration}.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class PendenzeTestApplication {
}
