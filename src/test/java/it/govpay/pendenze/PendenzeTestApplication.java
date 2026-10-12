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
 * <p>Niente {@code @EntityScan}/{@code @EnableJpaRepositories} su {@code it.govpay.common}
 * qui: renderebbero {@code DominioRepository} disponibile in OGNI test di questa libreria,
 * facendo scattare ovunque il bean {@code @ConditionalOnBean(DominioRepository.class)} di
 * {@code PendenzeAutoConfiguration} (il {@code GeneratoreIuv} di default) anche nei test che
 * non importano {@code GeneratoreProgressivoIuv} — contesto che fallisce ad avviarsi per
 * tutti. I test che hanno davvero bisogno dell'anagrafica di govpay-common
 * ({@code GeneratoreIuvStandardTest}) dichiarano quelle annotazioni localmente.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class PendenzeTestApplication {
}
