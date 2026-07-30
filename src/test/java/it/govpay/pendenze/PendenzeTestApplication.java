package it.govpay.pendenze;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Punto di ingresso della configurazione per i test di integrazione.
 *
 * <p>Serve perche' questa e' una libreria e non un'applicazione: gli slice test di Spring
 * Boot ({@code @DataJpaTest}) cercano una classe {@code @SpringBootConfiguration} risalendo
 * i package, e senza di essa non partirebbero. Sta in {@code it.govpay.pendenze} cosi' le
 * entita' di {@code it.govpay.pendenze.entity} vengono trovate dallo scan predefinito.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class PendenzeTestApplication {
}
