package it.govpay.pendenze.entity.converter;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converte gli importi monetari fra {@link BigDecimal} (in Java) e la colonna in banca
 * dati.
 *
 * <p><b>Perche' serve un converter.</b> Le colonne degli importi non sono decimali su
 * tutti i database supportati: {@code DOUBLE PRECISION} su PostgreSQL,
 * {@code BINARY_DOUBLE} su Oracle, {@code DOUBLE} su MySQL, e solo su SQL Server
 * {@code DECIMAL(15,2)}. In Java gli importi devono restare {@link BigDecimal}, perche'
 * la libreria li somma e li confronta (la validazione verifica che la somma delle voci
 * sia pari al totale della pendenza) e con {@code double} quel confronto puo' fallire per
 * errore di rappresentazione.</p>
 *
 * <p><b>Due regole non negoziabili.</b> In lettura si usa
 * {@link BigDecimal#valueOf(double)} e mai {@code new BigDecimal(double)}:
 * {@code new BigDecimal(0.1)} vale {@code 0.1000000000000000055511151231257827…} mentre
 * {@code BigDecimal.valueOf(0.1)} vale {@code 0.1}, perche' passa da
 * {@link Double#toString(double)} che garantisce il round-trip alla rappresentazione
 * decimale piu' corta. In lettura il valore viene normalizzato a due decimali con
 * {@link RoundingMode#HALF_UP}, cosi' il giro attraverso il {@code double} restituisce lo
 * stesso importo su tutti i database.</p>
 *
 * <p><b>In scrittura non si arrotonda.</b> Un importo con piu' di due decimali
 * significativi (per esempio un totale diviso in tre rate) e' un errore del chiamante:
 * arrotondarlo qui lo altererebbe in silenzio, lasciando l'entita' in memoria con un
 * valore diverso da quello scritto in colonna, e l'invariante &laquo;somma delle voci
 * pari al totale&raquo; potrebbe valere in memoria e non sulle righe persistite. La
 * conversione lo rifiuta; chi riceve importi dall'esterno li normalizza esplicitamente
 * con {@link #normalizza(BigDecimal)} prima di assegnarli all'entita'.</p>
 */
@Converter
public class ImportoConverter implements AttributeConverter<BigDecimal, Double> {

    /** Decimali di un importo monetario. */
    public static final int SCALA = 2;

    /** Arrotondamento applicato alla normalizzazione. */
    public static final RoundingMode ARROTONDAMENTO = RoundingMode.HALF_UP;

    @Override
    public Double convertToDatabaseColumn(BigDecimal importo) {
        if (importo == null) {
            return null;
        }
        try {
            // UNNECESSARY: accetta gli zeri non significativi (10.200 -> 10.20) e rifiuta
            // solo gli importi che perderebbero informazione.
            return importo.setScale(SCALA, RoundingMode.UNNECESSARY).doubleValue();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "importo con piu' di " + SCALA + " decimali significativi: "
                            + importo.toPlainString()
                            + "; normalizzarlo con ImportoConverter.normalizza prima di"
                            + " assegnarlo all'entita'", e);
        }
    }

    @Override
    public BigDecimal convertToEntityAttribute(Double valore) {
        return valore == null ? null : normalizza(BigDecimal.valueOf(valore));
    }

    /**
     * Normalizza un importo a due decimali, arrotondando {@link RoundingMode#HALF_UP}.
     * E' il punto in cui chi riceve importi dall'esterno rende esplicita la perdita di
     * precisione, prima di assegnarli all'entita'.
     *
     * @param importo importo da normalizzare, non nullo
     * @return l'importo con scala 2
     */
    public static BigDecimal normalizza(BigDecimal importo) {
        return importo.setScale(SCALA, ARROTONDAMENTO);
    }
}
