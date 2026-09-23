package it.govpay.pendenze.iuv;

import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.spi.IdentificativiPagamento;

/**
 * Algoritmo pagoPA di costruzione/decodifica IUV e numero avviso (NAV), porting fedele di
 * {@code it.govpay.bd.pagamento.IuvBD#generaIuv} e
 * {@code it.govpay.core.utils.VersamentoUtils#getIuvFromNumeroAvviso} del legacy — stessa
 * formattazione per AuxDigit, stesso check digit mod-93, cosi' gli identificativi generati
 * dalla v3 sono indistinguibili da quelli generati dal vecchio GovPay.
 *
 * <p>Nessuna dipendenza da Spring/JPA/govpay-common: prende in input i soli valori di
 * configurazione del dominio gia' estratti dal chiamante (stesso principio di
 * {@link it.govpay.pendenze.validazione.ValidatorePosizioneDebitoria}), cosi' resta
 * testabile senza contesto.</p>
 */
public final class CostruttoreIdentificativiPagamento {

    private CostruttoreIdentificativiPagamento() {
    }

    /**
     * @param auxDigit        AuxDigit configurato sul dominio (0, 1, 2 o 3)
     * @param iuvPrefix       prefisso IUV del dominio, o {@code null}/vuoto se assente
     * @param segregationCode codice di segregazione del dominio, richiesto solo per AuxDigit 3
     * @param applicationCode application code della stazione, richiesto solo per AuxDigit 0
     * @param progressivo     valore progressivo da incorporare nell'IUV (da
     *                        {@link GeneratoreProgressivoIuv})
     * @return la coppia IUV/numero avviso
     */
    public static IdentificativiPagamento genera(int auxDigit, String iuvPrefix, Integer segregationCode,
            Integer applicationCode, long progressivo) {
        String prefix = iuvPrefix != null ? iuvPrefix : "";
        String iuv;
        String numeroAvviso;

        switch (auxDigit) {
            case 0 -> {
                if (applicationCode == null) {
                    throw new IllegalStateException(
                            "AuxDigit 0 richiede l'application code della stazione del dominio, assente");
                }
                String reference = costruisciReference(prefix, progressivo, 13);
                String check = checkDigit93(reference, auxDigit, applicationCode);
                iuv = reference + check;
                numeroAvviso = "0" + String.format("%02d", applicationCode) + iuv;
            }
            case 1, 2 -> {
                String reference = costruisciReference(prefix, progressivo, 15);
                String check = checkDigit93(reference, auxDigit);
                iuv = reference + check;
                numeroAvviso = auxDigit + iuv;
            }
            case 3 -> {
                if (segregationCode == null) {
                    throw new IllegalStateException(
                            "AuxDigit 3 richiede il codice di segregazione del dominio, assente");
                }
                String reference = costruisciReference(prefix, progressivo, 13);
                String check = checkDigit93(reference, auxDigit, segregationCode);
                iuv = String.format("%02d", segregationCode) + reference + check;
                numeroAvviso = "3" + iuv;
            }
            default -> throw new IllegalStateException("AuxDigit [" + auxDigit + "] non supportato per la generazione");
        }

        return new IdentificativiPagamento(iuv, numeroAvviso);
    }

    /**
     * Ricava e valida l'IUV da un numero avviso fornito dal chiamante: nessun progressivo
     * consumato, e' una decodifica di formato, non una generazione (vedi
     * {@code proposta-modello-nativo-v3.md}, distinzione generazione/conversione).
     *
     * @param numeroAvviso    numero avviso a 18 cifre fornito dal chiamante
     * @param auxDigit        AuxDigit configurato sul dominio
     * @param segregationCode codice di segregazione del dominio (per AuxDigit 3)
     * @param applicationCode application code della stazione (per AuxDigit 0)
     * @return l'IUV incorporato nel numero avviso
     * @throws ValidazioneNonSuperataException se il formato non e' un numero avviso valido, se
     *                                          l'AuxDigit non corrisponde alla configurazione
     *                                          del dominio, o se il check digit non torna
     */
    public static String convertiDaNumeroAvviso(String numeroAvviso, int auxDigit, Integer segregationCode,
            Integer applicationCode) {
        validaFormato(numeroAvviso);

        int auxDigitEffettivo = Character.getNumericValue(numeroAvviso.charAt(0));
        if (auxDigitEffettivo != auxDigit) {
            throw new ValidazioneNonSuperataException(
                    "numeroAvviso [" + numeroAvviso + "] ha AuxDigit [" + auxDigitEffettivo
                            + "] ma il dominio e' configurato per AuxDigit [" + auxDigit + "]");
        }

        return switch (auxDigitEffettivo) {
            case 0 -> convertiAuxDigit0(numeroAvviso, applicationCode);
            case 1, 2 -> convertiAuxDigit1o2(numeroAvviso, auxDigitEffettivo);
            case 3 -> convertiAuxDigit3(numeroAvviso, segregationCode);
            default -> throw new ValidazioneNonSuperataException(
                    "numeroAvviso [" + numeroAvviso + "] ha AuxDigit [" + auxDigitEffettivo + "] non supportato");
        };
    }

    private static void validaFormato(String numeroAvviso) {
        if (numeroAvviso == null || numeroAvviso.length() != 18 || !numeroAvviso.chars().allMatch(Character::isDigit)) {
            throw new ValidazioneNonSuperataException(
                    "numeroAvviso [" + numeroAvviso + "] non e' un identificativo pagoPA valido (18 cifre numeriche)");
        }
    }

    private static String convertiAuxDigit0(String numeroAvviso, Integer applicationCode) {
        if (applicationCode == null) {
            throw new IllegalStateException(
                    "AuxDigit 0 richiede l'application code della stazione del dominio, assente");
        }
        String applicationCodeAtteso = String.format("%02d", applicationCode);
        String applicationCodeNumeroAvviso = numeroAvviso.substring(1, 3);
        if (!applicationCodeAtteso.equals(applicationCodeNumeroAvviso)) {
            throw new ValidazioneNonSuperataException(
                    "numeroAvviso [" + numeroAvviso + "] ha application code [" + applicationCodeNumeroAvviso
                            + "] ma il dominio e' configurato per application code [" + applicationCodeAtteso + "]");
        }
        String iuv = numeroAvviso.substring(3);
        String reference = iuv.substring(0, iuv.length() - 2);
        verificaCheckDigit(numeroAvviso, iuv, reference, checkDigit93(reference, 0, applicationCode));
        return iuv;
    }

    private static String convertiAuxDigit1o2(String numeroAvviso, int auxDigit) {
        String iuv = numeroAvviso.substring(1);
        String reference = iuv.substring(0, iuv.length() - 2);
        verificaCheckDigit(numeroAvviso, iuv, reference, checkDigit93(reference, auxDigit));
        return iuv;
    }

    private static String convertiAuxDigit3(String numeroAvviso, Integer segregationCode) {
        String iuv = numeroAvviso.substring(1);
        if (iuv.length() < 5) {
            throw new ValidazioneNonSuperataException("numeroAvviso [" + numeroAvviso + "] troppo corto per AuxDigit 3");
        }
        String segregationCodeNumeroAvviso = iuv.substring(0, 2);
        if (segregationCode != null) {
            String segregationCodeAtteso = String.format("%02d", segregationCode);
            if (!segregationCodeAtteso.equals(segregationCodeNumeroAvviso)) {
                throw new ValidazioneNonSuperataException(
                        "numeroAvviso [" + numeroAvviso + "] ha codice di segregazione [" + segregationCodeNumeroAvviso
                                + "] ma il dominio e' configurato per codice di segregazione [" + segregationCodeAtteso
                                + "]");
            }
        }
        String reference = iuv.substring(2, iuv.length() - 2);
        int segregationCodeEffettivo = Integer.parseInt(segregationCodeNumeroAvviso);
        verificaCheckDigit(numeroAvviso, iuv, reference, checkDigit93(reference, 3, segregationCodeEffettivo));
        return iuv;
    }

    private static void verificaCheckDigit(String numeroAvviso, String iuv, String reference, String checkAtteso) {
        String check = iuv.substring(iuv.length() - 2);
        if (!check.equals(checkAtteso)) {
            throw new ValidazioneNonSuperataException(
                    "numeroAvviso [" + numeroAvviso + "] ha check digit [" + check + "] non coerente con il valore "
                            + "atteso [" + checkAtteso + "] per reference [" + reference + "]");
        }
    }

    private static String costruisciReference(String prefix, long progressivo, int lunghezzaTotale) {
        int cifreProgressivo = lunghezzaTotale - prefix.length();
        String reference = prefix + String.format("%0" + cifreProgressivo + "d", progressivo);
        // Il limite e' lunghezzaTotale (13 per AuxDigit 0/3, 15 per 1/2), non sempre 15: un
        // progressivo che eccede le cifre disponibili (es. prefisso quasi al limite) produce
        // altrimenti una reference piu' lunga del previsto, mai rifiutata, che si propaga in
        // un numero avviso di 19 cifre invece di 18 (difetto ereditato da IuvBD.generaIuv, che
        // controlla sempre ">15" anche per i casi a 13 cifre — non riportato qui).
        if (reference.length() > lunghezzaTotale) {
            throw new IllegalStateException(
                    "superato il numero massimo di IUV generabili con prefisso [" + prefix + "]");
        }
        return reference;
    }

    private static String checkDigit93(String reference, int auxDigit, int code) {
        long resto93 = Long.parseLong(auxDigit + String.format("%02d", code) + reference) % 93;
        return String.format("%02d", resto93);
    }

    private static String checkDigit93(String reference, int auxDigit) {
        long resto93 = Long.parseLong(auxDigit + reference) % 93;
        return String.format("%02d", resto93);
    }
}
