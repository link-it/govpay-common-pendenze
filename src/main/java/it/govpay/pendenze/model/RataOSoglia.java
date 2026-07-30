package it.govpay.pendenze.model;

/**
 * Contenuto della colonna {@code versamenti.cod_rata}, che e' sovraccaricata: puo'
 * portare **o** il numero di rata **o** la soglia di pagamento, mai entrambi.
 *
 * <p>La codifica e la decodifica stanno in {@code CodRataCodec}.</p>
 */
public sealed interface RataOSoglia permits RataOSoglia.NumeroRata, RataOSoglia.Soglia {

    /**
     * Numero della rata a cui la pendenza si riferisce.
     *
     * @param numero numero di rata, positivo
     */
    record NumeroRata(int numero) implements RataOSoglia {

        public NumeroRata {
            if (numero <= 0) {
                throw new IllegalArgumentException("numero rata deve essere positivo: " + numero);
            }
        }
    }

    /**
     * Soglia di pagamento della pendenza.
     *
     * @param tipo   tipo di soglia, non nullo
     * @param giorni giorni della soglia: obbligatorio e positivo per {@code ENTRO} e
     *               {@code OLTRE}, deve essere assente per {@code RIDOTTO} e
     *               {@code SCONTATO}
     */
    record Soglia(TipoSoglia tipo, Integer giorni) implements RataOSoglia {

        public Soglia {
            if (tipo == null) {
                throw new IllegalArgumentException("tipo soglia obbligatorio");
            }
            if (tipo.richiedeGiorni()) {
                if (giorni == null || giorni <= 0) {
                    throw new IllegalArgumentException(
                            "la soglia " + tipo + " richiede un numero di giorni positivo");
                }
            } else if (giorni != null) {
                throw new IllegalArgumentException(
                        "la soglia " + tipo + " non ammette un numero di giorni");
            }
        }
    }
}
