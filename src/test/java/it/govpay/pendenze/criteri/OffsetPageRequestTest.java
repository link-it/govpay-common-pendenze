package it.govpay.pendenze.criteri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@link OffsetPageRequest} non ha stato ne' dipendenze da Spring/JPA: verifica lo
 * scorrimento a offset libero, non allineato a pagine di dimensione fissa (a differenza di
 * {@link org.springframework.data.domain.PageRequest}).
 */
class OffsetPageRequestTest {

    @Test
    @DisplayName("espone offset e limit esattamente come forniti, non ricalcolati da un numero di pagina")
    void offsetELimitEspliciti() {
        OffsetPageRequest pagina = OffsetPageRequest.of(7, 25);

        assertThat(pagina.getOffset()).isEqualTo(7);
        assertThat(pagina.getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("next() avanza l'offset esattamente di limit, non di una pagina allineata")
    void nextAvanzaDiLimit() {
        Pageable pagina = OffsetPageRequest.of(7, 25);

        Pageable successiva = pagina.next();

        assertThat(successiva.getOffset()).isEqualTo(32);
    }

    @Test
    @DisplayName("previousOrFirst() torna indietro di limit, o a offset 0 se gia' all'inizio")
    void previousOrFirst() {
        Pageable pagina = OffsetPageRequest.of(30, 25);
        assertThat(pagina.previousOrFirst().getOffset()).isEqualTo(5);

        Pageable primaPagina = OffsetPageRequest.of(0, 25);
        assertThat(primaPagina.hasPrevious()).isFalse();
        assertThat(primaPagina.previousOrFirst().getOffset()).isEqualTo(0);
    }

    @Test
    @DisplayName("rifiuta offset negativo o limit non positivo")
    void validaParametri() {
        assertThatThrownBy(() -> OffsetPageRequest.of(-1, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OffsetPageRequest.of(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("conserva l'ordinamento fornito")
    void conservaOrdinamento() {
        Sort sort = Sort.by(Sort.Direction.DESC, "dataCreazione");

        OffsetPageRequest pagina = OffsetPageRequest.of(0, 25, sort);

        assertThat(pagina.getSort()).isEqualTo(sort);
    }
}
