package it.govpay.pendenze.model;

/**
 * Discriminatore della forma di una {@code VocePendenza} (M3 di
 * {@code proposta-modello-nativo-v3.md}): le 3 varianti dello YAML v3
 * ({@code NuovaVocePendenzaRiferimentoEntrata}/{@code NuovaVocePendenzaEntrata}/
 * {@code NuovaVocePendenzaBollo}) condividono la stessa tabella, con colonne
 * mutuamente esclusive a seconda di questo valore.
 */
public enum TipoRiferimentoVocePendenza {
    RIFERIMENTO_ENTRATA,
    ENTRATA,
    BOLLO
}
