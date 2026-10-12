package it.govpay.pendenze.criteri;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PaginaRisultati} non ha stato ne' dipendenze: verifica {@code haAltriRisultati()}
 * con offset non allineati a {@code limit}, il caso in cui {@code Page.hasNext()} di Spring
 * Data risulterebbe scorretto (vedi Javadoc della classe).
 */
class PaginaRisultatiTest {

    @Test
    @DisplayName("3 risultati totali, offset 1, limit 2: restituiti gli ultimi due, nessun altro risultato")
    void offsetNonAllineatoEsauriscelRisultati() {
        PaginaRisultati<String> pagina = new PaginaRisultati<>(List.of("b", "c"), 1, 2, 3);

        assertThat(pagina.haAltriRisultati()).isFalse();
    }

    @Test
    @DisplayName("offset + risultati < totale: ci sono altri risultati")
    void ciSonoAltriRisultati() {
        PaginaRisultati<String> pagina = new PaginaRisultati<>(List.of("a", "b"), 0, 2, 3);

        assertThat(pagina.haAltriRisultati()).isTrue();
    }

    @Test
    @DisplayName("offset + risultati == totale: nessun altro risultato, anche con offset allineato")
    void nessunAltroRisultatoConOffsetAllineato() {
        PaginaRisultati<String> pagina = new PaginaRisultati<>(List.of("c"), 2, 2, 3);

        assertThat(pagina.haAltriRisultati()).isFalse();
    }
}
