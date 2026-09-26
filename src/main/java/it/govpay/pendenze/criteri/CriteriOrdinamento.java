package it.govpay.pendenze.criteri;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;

import it.govpay.pendenze.exception.ValidazioneNonSuperataException;

/**
 * Analizza il parametro di query {@code sort} nel formato usato dallo YAML v3 (elenco
 * separato da virgole di nomi di campo, ciascuno preceduto opzionalmente da {@code +}
 * ascendente o {@code -} discendente — es. {@code +dataCreazione,-importo}).
 *
 * <p>Nessun campo e' ordinabile per default: il chiamante fornisce una mappa esplicita da
 * nome esterno (quello nell'URL) a percorso JPA effettivo — cosi' l'endpoint decide quali
 * campi esporre come ordinabili (ed eventualmente li rinomina), non questa libreria, e un
 * nome non riconosciuto e' un errore esplicito invece di un ordinamento arbitrario su un
 * percorso interno non previsto.</p>
 */
public final class CriteriOrdinamento {

    private CriteriOrdinamento() {
    }

    /**
     * @param sort            valore del parametro di query {@code sort}, o {@code null}/vuoto
     *                        per nessun ordinamento esplicito
     * @param campiOrdinabili nomi esterni ammessi, ciascuno mappato al percorso JPA (es.
     *                        {@code "dataCreazione"} o {@code "opzionePagamento.tipologia"})
     * @return l'ordinamento risultante, {@link Sort#unsorted()} se {@code sort} e' assente
     * @throws ValidazioneNonSuperataException se un token nomina un campo non presente in
     *                                          {@code campiOrdinabili}
     */
    public static Sort parse(String sort, Map<String, String> campiOrdinabili) {
        if (sort == null || sort.isBlank()) {
            return Sort.unsorted();
        }

        List<Sort.Order> ordini = new ArrayList<>();
        for (String token : sort.split(",")) {
            String pulito = token.trim();
            if (pulito.isEmpty()) {
                continue;
            }

            Sort.Direction direzione = Sort.Direction.ASC;
            String nomeCampo = pulito;
            if (pulito.startsWith("+")) {
                nomeCampo = pulito.substring(1);
            } else if (pulito.startsWith("-")) {
                direzione = Sort.Direction.DESC;
                nomeCampo = pulito.substring(1);
            }

            String percorso = campiOrdinabili.get(nomeCampo);
            if (percorso == null) {
                throw new ValidazioneNonSuperataException(
                        "campo di ordinamento [" + nomeCampo + "] non riconosciuto: ammessi "
                                + campiOrdinabili.keySet());
            }
            ordini.add(new Sort.Order(direzione, percorso));
        }

        return ordini.isEmpty() ? Sort.unsorted() : Sort.by(ordini);
    }
}
