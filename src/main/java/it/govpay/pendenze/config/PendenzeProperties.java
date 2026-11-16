package it.govpay.pendenze.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import it.govpay.common.utils.DateTimePatterns;

/**
 * Configurazione della libreria di gestione delle pendenze.
 *
 * @param fusoOrario fuso orario applicativo. Le colonne temporali dello schema GovPay sono
 *                   {@code TIMESTAMP} **senza** time zone: questo valore stabilisce quale
 *                   fuso usare per interpretarle, invece di ereditare quello della JVM.
 *                   Default {@code Europe/Rome}.
 * @param giorniMassimiDataPubblicazione giorni oltre i quali una data di pubblicazione futura e'
 *                   rifiutata. Default 7: la data deve restare entro la finestra con cui il batch ACA
 *                   prende in carico le posizioni.
 */
@ConfigurationProperties("govpay.pendenze")
public record PendenzeProperties(ZoneId fusoOrario, Integer giorniMassimiDataPubblicazione) {

    /** Giorni massimi tra oggi e una data di pubblicazione futura, se la property non e' valorizzata. */
    public static final int GIORNI_MASSIMI_DATA_PUBBLICAZIONE_DEFAULT = 7;

    /**
     * Fuso applicato quando la property non e' valorizzata. Stesso valore di
     * {@link DateTimePatterns#DEFAULT_TIME_ZONE} (di {@code govpay-common}): riusato,
     * non duplicato come letterale a se stante.
     */
    public static final ZoneId FUSO_ORARIO_DEFAULT = ZoneId.of(DateTimePatterns.DEFAULT_TIME_ZONE);

    /** Solo il fuso orario: gli altri valori assumono il default. */
    public PendenzeProperties(ZoneId fusoOrario) {
        this(fusoOrario, null);
    }

    @ConstructorBinding
    public PendenzeProperties {
        fusoOrario = fusoOrario == null ? FUSO_ORARIO_DEFAULT : fusoOrario;
        giorniMassimiDataPubblicazione = giorniMassimiDataPubblicazione == null
                ? GIORNI_MASSIMI_DATA_PUBBLICAZIONE_DEFAULT : giorniMassimiDataPubblicazione;
    }
}
