package it.govpay.pendenze.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Causale di una pendenza. In banca dati e' persistita codificata nella colonna
 * {@code versamenti.causale_versamento} (vedi {@code CausaleCodec}), in tre forme
 * alternative.
 */
public sealed interface Causale
        permits Causale.Semplice, Causale.Spezzoni, Causale.SpezzoniConImporto {

    /**
     * Causale in forma libera. Codifica {@code 01}.
     *
     * @param testo testo della causale, non nullo
     */
    record Semplice(String testo) implements Causale {

        public Semplice {
            if (testo == null) {
                throw new IllegalArgumentException("testo della causale obbligatorio");
            }
        }
    }

    /**
     * Causale suddivisa in spezzoni. Codifica {@code 02}.
     *
     * @param spezzoni spezzoni della causale, almeno uno
     */
    record Spezzoni(List<String> spezzoni) implements Causale {

        public Spezzoni {
            if (spezzoni == null || spezzoni.isEmpty()) {
                throw new IllegalArgumentException("almeno uno spezzone obbligatorio");
            }
            spezzoni = List.copyOf(spezzoni);
        }
    }

    /**
     * Causale suddivisa in spezzoni con importo. Codifica {@code 03}.
     *
     * @param voci voci della causale, almeno una
     */
    record SpezzoniConImporto(List<VoceCausale> voci) implements Causale {

        public SpezzoniConImporto {
            if (voci == null || voci.isEmpty()) {
                throw new IllegalArgumentException("almeno una voce obbligatoria");
            }
            voci = List.copyOf(voci);
        }
    }

    /**
     * Voce di una causale a spezzoni con importo.
     *
     * @param testo   testo dello spezzone, non nullo
     * @param importo importo associato, non nullo
     */
    record VoceCausale(String testo, BigDecimal importo) {

        public VoceCausale {
            if (testo == null) {
                throw new IllegalArgumentException("testo dello spezzone obbligatorio");
            }
            if (importo == null) {
                throw new IllegalArgumentException("importo dello spezzone obbligatorio");
            }
        }
    }
}
