package it.govpay.pendenze;

/**
 * Metadati della libreria di gestione delle pendenze.
 *
 * <p>Classe segnaposto: esiste per dare alla pipeline di build sorgenti e test su cui
 * lavorare (compilazione, coverage JaCoCo, analisi SonarCloud) in attesa dei componenti
 * effettivi della libreria. Puo' essere rimossa quando il primo componente reale viene
 * introdotto.</p>
 */
public final class PendenzeCommonInfo {

    /** groupId Maven della libreria. */
    public static final String GROUP_ID = "org.gov4j.govpay";

    /** artifactId Maven della libreria. */
    public static final String ARTIFACT_ID = "govpay-common-pendenze";

    private PendenzeCommonInfo() {
        // classe di sole costanti
    }

    /**
     * Coordinate Maven della libreria nel formato {@code groupId:artifactId}.
     *
     * @return le coordinate della libreria
     */
    public static String coordinates() {
        return GROUP_ID + ":" + ARTIFACT_ID;
    }
}
