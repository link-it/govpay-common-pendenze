package it.govpay.pendenze.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.govpay.pendenze.model.Causale;

class CausaleCodecTest {

    @Test
    @DisplayName("causale semplice: round-trip e formato 01")
    void causaleSemplice() {
        Causale causale = new Causale.Semplice("Sanzione CDS n. 123/2026");

        String codificata = CausaleCodec.codifica(causale);

        assertThat(codificata).startsWith("01 ");
        assertThat(CausaleCodec.decodifica(codificata)).contains(causale);
    }

    @Test
    @DisplayName("spezzoni: round-trip e formato 02")
    void causaleASpezzoni() {
        Causale causale = new Causale.Spezzoni(List.of("Primo spezzone", "Secondo spezzone"));

        String codificata = CausaleCodec.codifica(causale);

        assertThat(codificata).startsWith("02 ");
        assertThat(codificata.split(" ")).hasSize(3);
        assertThat(CausaleCodec.decodifica(codificata)).contains(causale);
    }

    @Test
    @DisplayName("spezzoni con importo: round-trip e formato 03 a coppie")
    void causaleASpezzoniConImporto() {
        Causale causale = new Causale.SpezzoniConImporto(List.of(
                new Causale.VoceCausale("Quota capitale", new BigDecimal("100.00")),
                new Causale.VoceCausale("Interessi", new BigDecimal("5.50"))));

        String codificata = CausaleCodec.codifica(causale);

        assertThat(codificata).startsWith("03 ");
        assertThat(codificata.split(" ")).hasSize(5);
        assertThat(CausaleCodec.decodifica(codificata)).contains(causale);
    }

    @Test
    @DisplayName("la sintesi replica il getSimple della 3.x")
    void sintesi() {
        assertThat(CausaleCodec.sintesiDa(CausaleCodec.codifica(
                new Causale.Semplice("Causale libera"))))
                .isEqualTo("Causale libera");

        assertThat(CausaleCodec.sintesiDa(CausaleCodec.codifica(
                new Causale.Spezzoni(List.of("Primo", "Secondo")))))
                .isEqualTo("Primo");

        assertThat(CausaleCodec.sintesiDa(CausaleCodec.codifica(
                new Causale.SpezzoniConImporto(List.of(
                        new Causale.VoceCausale("Quota", new BigDecimal("100.00")))))))
                .isEqualTo("100.00: Quota");
    }

    @Test
    @DisplayName("una causale legacy in chiaro viene restituita verbatim, non fa fallire la lettura")
    void causaleInChiaroLegacy() {
        String inChiaro = "Pagamento spontaneo del cittadino";

        assertThat(CausaleCodec.decodifica(inChiaro))
                .contains(new Causale.Semplice(inChiaro));
        assertThat(CausaleCodec.sintesiDa(inChiaro)).isEqualTo(inChiaro);
    }

    @Test
    @DisplayName("un base64 malformato viene restituito verbatim, non solleva eccezioni")
    void base64Malformato() {
        String malformato = "01 !!!non-base64!!!";

        assertThat(CausaleCodec.decodifica(malformato))
                .contains(new Causale.Semplice(malformato));
    }

    @Test
    @DisplayName("colonna vuota: nessuna causale")
    void colonnaVuota() {
        assertThat(CausaleCodec.decodifica(null)).isEmpty();
        assertThat(CausaleCodec.decodifica("  ")).isEmpty();
        assertThat(CausaleCodec.sintesiDa(null)).isNull();
        assertThat(CausaleCodec.codifica(null)).isNull();
    }

    @Test
    @DisplayName("decodifica un valore prodotto dalla 3.x, non dal nostro encoder")
    void compatibilitaConIlFormatoLegacy() {
        // "02 <b64(Primo)> <b64(Secondo)>" costruito a mano, come lo scriverebbe la 3.x
        String primo = Base64.getEncoder().encodeToString("Primo".getBytes(StandardCharsets.UTF_8));
        String secondo = Base64.getEncoder().encodeToString("Secondo".getBytes(StandardCharsets.UTF_8));

        assertThat(CausaleCodec.decodifica("02 " + primo + " " + secondo))
                .contains(new Causale.Spezzoni(List.of("Primo", "Secondo")));
    }
}
