package it.govpay.pendenze.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

import it.govpay.common.utils.DateTimePatterns;

/**
 * Configurazione della libreria di gestione delle pendenze.
 *
 * @param fusoOrario fuso orario applicativo. Le colonne temporali dello schema GovPay sono
 *                   {@code TIMESTAMP} **senza** time zone: questo valore stabilisce quale
 *                   fuso usare per interpretarle, invece di ereditare quello della JVM.
 *                   Default {@code Europe/Rome}.
 */
@ConfigurationProperties("govpay.pendenze")
public record PendenzeProperties(ZoneId fusoOrario) {

    /**
     * Fuso applicato quando la property non e' valorizzata. Stesso valore di
     * {@link DateTimePatterns#DEFAULT_TIME_ZONE} (di {@code govpay-common}): riusato,
     * non duplicato come letterale a se stante.
     */
    public static final ZoneId FUSO_ORARIO_DEFAULT = ZoneId.of(DateTimePatterns.DEFAULT_TIME_ZONE);

    public PendenzeProperties {
        fusoOrario = fusoOrario == null ? FUSO_ORARIO_DEFAULT : fusoOrario;
    }
}
