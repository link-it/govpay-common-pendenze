package it.govpay.pendenze.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurazione della libreria di gestione delle pendenze.
 *
 * @param giorniMassimiDataPubblicazione giorni oltre i quali una data di pubblicazione futura e'
 *                   rifiutata. Default 7: la data deve restare entro la finestra con cui il batch ACA
 *                   prende in carico le posizioni.
 */
@ConfigurationProperties("govpay.pendenze")
public record PendenzeProperties(Integer giorniMassimiDataPubblicazione) {

    /** Giorni massimi tra oggi e una data di pubblicazione futura, se la property non e' valorizzata. */
    public static final int GIORNI_MASSIMI_DATA_PUBBLICAZIONE_DEFAULT = 7;

    public PendenzeProperties {
        giorniMassimiDataPubblicazione = giorniMassimiDataPubblicazione == null
                ? GIORNI_MASSIMI_DATA_PUBBLICAZIONE_DEFAULT : giorniMassimiDataPubblicazione;
    }
}
