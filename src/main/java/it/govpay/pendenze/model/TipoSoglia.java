package it.govpay.pendenze.model;

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
}
