package it.govpay.pendenze.model;

/**
 * Chiave logica di una pendenza: la coppia (applicazione gestrice, identificativo della
 * pendenza presso l'ente). Corrisponde al vincolo di unicita'
 * {@code unique_versamenti_1 (cod_versamento_ente, id_applicazione)}.
 *
 * @param idA2A      codice dell'applicazione gestrice ({@code applicazioni.cod_applicazione})
 * @param idPendenza identificativo della pendenza ({@code versamenti.cod_versamento_ente})
 */
public record IdentificativoPendenza(String idA2A, String idPendenza) {

    public IdentificativoPendenza {
        if (idA2A == null || idA2A.isBlank()) {
            throw new IllegalArgumentException("idA2A obbligatorio");
        }
        if (idPendenza == null || idPendenza.isBlank()) {
            throw new IllegalArgumentException("idPendenza obbligatorio");
        }
    }

    @Override
    public String toString() {
        return idA2A + "/" + idPendenza;
    }
}
