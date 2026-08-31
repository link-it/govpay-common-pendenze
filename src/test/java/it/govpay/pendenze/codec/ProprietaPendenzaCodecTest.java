package it.govpay.pendenze.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import it.govpay.pendenze.model.LinguaSecondaria;
import it.govpay.pendenze.model.ProprietaPendenza;

class ProprietaPendenzaCodecTest {

    private final ProprietaPendenzaCodec codec =
            new ProprietaPendenzaCodec(JsonMapper.builder().build());

    @Test
    @DisplayName("round-trip di tutte le proprieta'")
    void roundTrip() {
        ProprietaPendenza proprieta = new ProprietaPendenza(
                LinguaSecondaria.DE,
                List.of(new ProprietaPendenza.VoceDescrizioneImporto("Quota", new BigDecimal("10.50"))),
                "prima riga",
                "seconda riga",
                "causale in tedesco",
                "informativa",
                "informativa in tedesco",
                OffsetDateTime.of(2026, 3, 31, 12, 0, 0, 0, ZoneOffset.ofHours(2)));

        String json = codec.codifica(proprieta);
        ProprietaPendenza rilette = codec.decodifica(json).orElseThrow();

        assertThat(rilette.linguaSecondaria()).isEqualTo(LinguaSecondaria.DE);
        assertThat(rilette.lineaTestoRicevuta1()).isEqualTo("prima riga");
        assertThat(rilette.lineaTestoRicevuta2()).isEqualTo("seconda riga");
        assertThat(rilette.linguaSecondariaCausale()).isEqualTo("causale in tedesco");
        assertThat(rilette.informativaImportoAvviso()).isEqualTo("informativa");
        assertThat(rilette.linguaSecondariaInformativaImportoAvviso()).isEqualTo("informativa in tedesco");
        assertThat(rilette.dataScandenzaAvviso()).isEqualTo(proprieta.dataScandenzaAvviso());
        assertThat(rilette.descrizioneImporto()).hasSize(1);
        assertThat(rilette.descrizioneImporto().get(0).voce()).isEqualTo("Quota");
        assertThat(rilette.descrizioneImporto().get(0).importo())
                .isEqualByComparingTo(new BigDecimal("10.50"));
    }

    @Test
    @DisplayName("un importo non numerico annulla la sola voce, non l'intera proprieta'")
    void importoNonNumericoNonScartaTutto() {
        String json = """
                {"lineaTestoRicevuta1":"riga",
                 "dataScandenzaAvviso":"2026-03-31T12:00+02:00",
                 "descrizioneImporto":[{"voce":"a","importo":"n.d."},
                                       {"voce":"b","importo":"5.00"}]}""";

        ProprietaPendenza rilette = codec.decodifica(json).orElseThrow();

        assertThat(rilette.lineaTestoRicevuta1()).isEqualTo("riga");
        assertThat(rilette.dataScandenzaAvviso()).isNotNull();
        assertThat(rilette.descrizioneImporto()).hasSize(2);
        assertThat(rilette.descrizioneImporto().get(0).voce()).isEqualTo("a");
        assertThat(rilette.descrizioneImporto().get(0).importo()).isNull();
        assertThat(rilette.descrizioneImporto().get(1).importo())
                .isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    @DisplayName("il nome della data di scadenza conserva il refuso storico")
    void nomeConRefusoPreservato() {
        ProprietaPendenza proprieta = new ProprietaPendenza(null, null, null, null, null, null, null,
                OffsetDateTime.of(2026, 3, 31, 12, 0, 0, 0, ZoneOffset.UTC));

        String json = codec.codifica(proprieta);

        // "Scandenza", non "Scadenza": e' il nome presente nei record gia' persistiti.
        assertThat(json).contains("dataScandenzaAvviso");
        assertThat(json).doesNotContain("dataScadenzaAvviso");
    }

    @Test
    @DisplayName("legge un JSON scritto dalla 3.x")
    void compatibilitaConIlFormatoLegacy() {
        String json = """
                {"linguaSecondaria":"en",\
                "lineaTestoRicevuta1":"riga 1",\
                "dataScandenzaAvviso":"2026-03-31T12:00:00Z",\
                "descrizioneImporto":[{"voce":"Tributo","importo":25.00}]}""";

        ProprietaPendenza proprieta = codec.decodifica(json).orElseThrow();

        assertThat(proprieta.linguaSecondaria()).isEqualTo(LinguaSecondaria.EN);
        assertThat(proprieta.lineaTestoRicevuta1()).isEqualTo("riga 1");
        assertThat(proprieta.dataScandenzaAvviso()).isNotNull();
        assertThat(proprieta.descrizioneImporto().get(0).importo())
                .isEqualByComparingTo(new BigDecimal("25.00"));
    }

    @Test
    @DisplayName("i campi assenti sono omessi dal JSON, non scritti come null")
    void campiAssentiOmessi() {
        String json = codec.codifica(
                new ProprietaPendenza(null, null, "solo questa", null, null, null, null, null));

        assertThat(json).isEqualTo("{\"lineaTestoRicevuta1\":\"solo questa\"}");
    }

    @Test
    @DisplayName("un JSON non leggibile e' trattato come assente, senza eccezioni")
    void jsonNonLeggibile() {
        assertThat(codec.decodifica("{non-json")).isEmpty();
        assertThat(codec.decodifica("[1,2,3]")).isEmpty();
        assertThat(codec.decodifica(null)).isEmpty();
        assertThat(codec.decodifica("  ")).isEmpty();
        assertThat(codec.codifica(null)).isNull();
    }

    @Test
    @DisplayName("una lingua secondaria non riconosciuta non fa fallire la lettura")
    void linguaSecondariaIgnota() {
        ProprietaPendenza proprieta = codec.decodifica("{\"linguaSecondaria\":\"xx\"}").orElseThrow();

        assertThat(proprieta.linguaSecondaria()).isNull();
    }
}
