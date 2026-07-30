package it.govpay.pendenze.model;

import java.util.Optional;

/**
 * Tipo di soglia di pagamento di una pendenza. Non ha una colonna propria: e' codificato
 * insieme al numero di rata nella colonna {@code versamenti.cod_rata} (vedi
 * {@code CodRataCodec}).
 *
 * <p>{@link #ENTRO} e {@link #OLTRE} sono espressi con un numero di giorni,
 * {@link #RIDOTTO} e {@link #SCONTATO} no.</p>
 */
public enum TipoSoglia {

    ENTRO(true),
    OLTRE(true),
    RIDOTTO(false),
    SCONTATO(false);

    private final boolean richiedeGiorni;

    TipoSoglia(boolean richiedeGiorni) {
        this.richiedeGiorni = richiedeGiorni;
    }

    /**
     * @return {@code true} se il tipo va accompagnato da un numero di giorni
     */
    public boolean richiedeGiorni() {
        return richiedeGiorni;
    }

    /**
     * Risolve la costante dal nome, senza sollevare eccezioni.
     *
     * @param nome nome della costante, eventualmente {@code null}
     * @return la costante corrispondente, vuoto se assente o non riconosciuta
     */
    public static Optional<TipoSoglia> daNome(String nome) {
        if (nome == null || nome.isBlank()) {
            return Optional.empty();
        }
        for (TipoSoglia tipo : values()) {
            if (tipo.name().equals(nome.trim())) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }
}
