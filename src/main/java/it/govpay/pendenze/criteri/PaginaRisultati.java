package it.govpay.pendenze.criteri;

import java.util.List;

/**
 * Pagina di risultati a scorrimento libero (offset/limit AGID), non a pagine allineate.
 *
 * <p><b>Non {@link org.springframework.data.domain.Page}</b>: i suoi metodi derivati
 * ({@code hasNext}, {@code isLast}, {@code getTotalPages}, ...) si basano su un numero di
 * pagina, che {@link OffsetPageRequest} può solo approssimare (`offset / limit` troncato) per
 * un offset arbitrario non allineato a `limit` — con offset non multiplo di limit,
 * {@code Page.hasNext()} può risultare {@code true} anche quando i risultati sono esauriti
 * (riprodotto: 3 risultati totali, offset 1, limit 2 → restituiti gli ultimi due, ma
 * {@code hasNext()} vale {@code true}), producendo un collegamento {@code prossimiRisultati}
 * verso una pagina vuota. {@link #haAltriRisultati()} usa invece direttamente
 * {@code offset + risultati.size() < numeroRisultatiTotali}, corretto per qualunque offset.
 *
 * @param risultati              contenuto di questa pagina
 * @param offset                 offset richiesto per produrre questa pagina
 * @param limit                  limite massimo di risultati richiesto per questa pagina
 * @param numeroRisultatiTotali  numero totale di risultati che rispettano i filtri di ricerca
 */
public record PaginaRisultati<T>(List<T> risultati, long offset, int limit, long numeroRisultatiTotali) {

    /**
     * @return {@code true} se esistono altri risultati oltre quelli di questa pagina
     */
    public boolean haAltriRisultati() {
        return offset + risultati.size() < numeroRisultatiTotali;
    }
}
