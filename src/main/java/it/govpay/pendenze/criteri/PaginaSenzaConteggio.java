package it.govpay.pendenze.criteri;

import java.util.List;

/**
 * Pagina di risultati senza conteggio totale: usata sia in modalita' offset con
 * {@code total=false} (niente {@code COUNT(*)}), sia in modalita' cursore (dove un totale
 * non avrebbe comunque senso rispetto a un keyset). In entrambi i casi il chiamante ha
 * interrogato il repository con {@code limit + 1} risultati: {@code haAltriRisultati()}
 * riflette se la riga in piu' e' stata effettivamente trovata, {@link #risultati} e' gia'
 * troncato alla dimensione richiesta.
 *
 * @param risultati        contenuto di questa pagina, gia' troncato a {@code limit}
 * @param haAltriRisultati {@code true} se esistono altri risultati oltre questa pagina
 */
public record PaginaSenzaConteggio<T>(List<T> risultati, boolean haAltriRisultati) {

    /**
     * Costruisce la pagina da una lista grezza di al piu' {@code limit + 1} elementi
     * (quanti ne restituisce una query interrogata con quel {@code maxResults}),
     * troncando all'effettivo {@code limit} e derivando {@link #haAltriRisultati} dalla
     * presenza della riga in eccesso.
     */
    public static <T> PaginaSenzaConteggio<T> daRisultatiGrezzi(List<T> grezzi, int limit) {
        boolean haAltriRisultati = grezzi.size() > limit;
        List<T> risultati = haAltriRisultati ? grezzi.subList(0, limit) : grezzi;
        return new PaginaSenzaConteggio<>(risultati, haAltriRisultati);
    }
}
