package it.govpay.pendenze.model;

/**
 * Stato di una {@code VocePendenza}.
 *
 * <p>Lo schema {@code StatoVocePendenza} dello YAML v3 usa i letterali {@code Eseguito}/
 * {@code Non eseguito}/{@code Anomalo} (maiuscole non uniformi, spazio incluso): non
 * rappresentabili come costanti Java. La colonna persiste questi 3 valori con nomi
 * puliti; la traduzione da/verso il letterale esatto dello YAML e' responsabilita' del
 * livello che espone il bean API (fuori dal perimetro di questa libreria), non
 * dell'entita'.</p>
 */
public enum StatoVocePendenza {
    NON_ESEGUITO,
    ESEGUITO,
    ANOMALO
}
