package it.govpay.pendenze.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    /** Fuso applicato quando la property non e' valorizzata. */
    public static final ZoneId FUSO_ORARIO_DEFAULT = ZoneId.of("Europe/Rome");

    public PendenzeProperties {
        fusoOrario = fusoOrario == null ? FUSO_ORARIO_DEFAULT : fusoOrario;
    }
}
