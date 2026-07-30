package it.govpay.pendenze.codec;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.govpay.pendenze.model.RataOSoglia;
import it.govpay.pendenze.model.TipoSoglia;

/**
 * Codifica e decodifica la colonna {@code versamenti.cod_rata}, che e' sovraccaricata:
 * porta il numero di rata **oppure** la soglia di pagamento.
 *
 * <pre>
 * "3"          numero di rata 3
 * "ENTRO15"    soglia ENTRO, 15 giorni
 * "OLTRE30"    soglia OLTRE, 30 giorni
 * "RIDOTTO"    soglia RIDOTTO, senza giorni
 * "SCONTATO"   soglia SCONTATO, senza giorni
 * </pre>
 *
 * <p>La decodifica e' tollerante: un valore non conforme presente in banca dati produce
 * un risultato vuoto e un log a {@code WARN}, non un errore.</p>
 */
public final class CodRataCodec {

    private static final Logger log = LoggerFactory.getLogger(CodRataCodec.class);

    /** Lunghezza massima della colonna {@code cod_rata}. */
    public static final int LUNGHEZZA_MASSIMA = 35;

    private CodRataCodec() {
    }

    /**
     * Decodifica il valore persistito.
     *
     * @param codRata contenuto della colonna, eventualmente {@code null}
     * @return numero di rata o soglia, vuoto se la colonna e' vuota o non conforme
     */
    public static Optional<RataOSoglia> decodifica(String codRata) {
        if (codRata == null || codRata.isBlank()) {
            return Optional.empty();
        }
        String valore = codRata.trim();

        for (TipoSoglia tipo : TipoSoglia.values()) {
            if (valore.startsWith(tipo.name())) {
                return decodificaSoglia(valore, tipo);
            }
        }

        try {
            return Optional.of(new RataOSoglia.NumeroRata(Integer.parseInt(valore)));
        } catch (IllegalArgumentException e) {
            log.warn("Valore di cod_rata non conforme, ignorato: [{}]", valore);
            return Optional.empty();
        }
    }

    /**
     * Codifica il valore da scrivere in colonna.
     *
     * @param valore numero di rata o soglia, eventualmente {@code null}
     * @return il valore da scrivere, {@code null} se l'argomento e' assente
     * @throws IllegalArgumentException se la codifica supera la lunghezza della colonna
     */
    public static String codifica(RataOSoglia valore) {
        if (valore == null) {
            return null;
        }
        String codificato = switch (valore) {
            case RataOSoglia.NumeroRata numeroRata -> Integer.toString(numeroRata.numero());
            case RataOSoglia.Soglia soglia -> soglia.tipo().richiedeGiorni()
                    ? soglia.tipo().name() + soglia.giorni()
                    : soglia.tipo().name();
        };
        if (codificato.length() > LUNGHEZZA_MASSIMA) {
            throw new IllegalArgumentException(
                    "cod_rata eccede " + LUNGHEZZA_MASSIMA + " caratteri: " + codificato);
        }
        return codificato;
    }

    private static Optional<RataOSoglia> decodificaSoglia(String valore, TipoSoglia tipo) {
        String resto = valore.substring(tipo.name().length());

        if (!tipo.richiedeGiorni()) {
            if (!resto.isEmpty()) {
                log.warn("Soglia [{}] con giorni non ammessi, valore ignorato: [{}]", tipo, valore);
                return Optional.empty();
            }
            return Optional.of(new RataOSoglia.Soglia(tipo, null));
        }

        try {
            return Optional.of(new RataOSoglia.Soglia(tipo, Integer.valueOf(resto)));
        } catch (IllegalArgumentException e) {
            log.warn("Giorni della soglia [{}] non validi, valore ignorato: [{}]", tipo, valore);
            return Optional.empty();
        }
    }
}
