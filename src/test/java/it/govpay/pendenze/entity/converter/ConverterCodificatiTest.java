package it.govpay.pendenze.entity.converter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import it.govpay.pendenze.model.TipoBollo;
import it.govpay.pendenze.model.TipoContabilita;
import it.govpay.pendenze.model.TipoSoggetto;

/**
 * Verifica i converter delle colonne codificate. Politica comune: un valore non
 * riconosciuto non fa fallire la lettura, diventa {@code null} (con un log a WARN).
 */
class ConverterCodificatiTest {

    @Nested
    class Incasso {

        private final IncassoConverter converter = new IncassoConverter();

        @Test
        @DisplayName("t e f sono i valori persistiti")
        void codificaEDecodifica() {
            assertThat(converter.convertToDatabaseColumn(true)).isEqualTo("t");
            assertThat(converter.convertToDatabaseColumn(false)).isEqualTo("f");
            assertThat(converter.convertToEntityAttribute("t")).isTrue();
            assertThat(converter.convertToEntityAttribute("f")).isFalse();
        }

        @Test
        @DisplayName("il null e' significativo e viene preservato")
        void preservaIlNull() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
            assertThat(converter.convertToEntityAttribute(null)).isNull();
            assertThat(converter.convertToEntityAttribute("  ")).isNull();
        }

        @Test
        @DisplayName("un valore non riconosciuto e' trattato come assente")
        void valoreIgnoto() {
            assertThat(converter.convertToEntityAttribute("x")).isNull();
        }
    }

    @Nested
    class Soggetto {

        private final TipoSoggettoConverter converter = new TipoSoggettoConverter();

        @Test
        void codificaEDecodifica() {
            assertThat(converter.convertToDatabaseColumn(TipoSoggetto.PERSONA_FISICA)).isEqualTo("F");
            assertThat(converter.convertToDatabaseColumn(TipoSoggetto.PERSONA_GIURIDICA)).isEqualTo("G");
            assertThat(converter.convertToEntityAttribute("F")).isEqualTo(TipoSoggetto.PERSONA_FISICA);
            assertThat(converter.convertToEntityAttribute("G")).isEqualTo(TipoSoggetto.PERSONA_GIURIDICA);
        }

        @Test
        void valoreIgnotoEAssente() {
            assertThat(converter.convertToEntityAttribute("Z")).isNull();
            assertThat(converter.convertToEntityAttribute(null)).isNull();
        }
    }

    @Nested
    class Contabilita {

        private final TipoContabilitaConverter converter = new TipoContabilitaConverter();

        @Test
        @DisplayName("tutte le codifiche dello schema, comprese quelle non contigue")
        void tutteLeCodifiche() {
            assertThat(converter.convertToEntityAttribute("0")).isEqualTo(TipoContabilita.CAPITOLO);
            assertThat(converter.convertToEntityAttribute("1")).isEqualTo(TipoContabilita.SPECIALE);
            assertThat(converter.convertToEntityAttribute("2")).isEqualTo(TipoContabilita.SIOPE);
            assertThat(converter.convertToEntityAttribute("6"))
                    .isEqualTo(TipoContabilita.SRTP_ESCLUSA_RAVV_OPEROSO);
            assertThat(converter.convertToEntityAttribute("7"))
                    .isEqualTo(TipoContabilita.SRTP_ESCLUSA_ALTRO_OPERATORE);
            assertThat(converter.convertToEntityAttribute("8")).isEqualTo(TipoContabilita.SRTP_ESCLUSA);
            assertThat(converter.convertToEntityAttribute("9")).isEqualTo(TipoContabilita.ALTRO);
        }

        @Test
        @DisplayName("3, 4 e 5 non sono codifiche valide")
        void codificheNonAssegnate() {
            assertThat(converter.convertToEntityAttribute("3")).isNull();
            assertThat(converter.convertToEntityAttribute("4")).isNull();
            assertThat(converter.convertToEntityAttribute("5")).isNull();
        }

        @Test
        void andataERitorno() {
            for (TipoContabilita tipo : TipoContabilita.values()) {
                assertThat(converter.convertToEntityAttribute(
                        converter.convertToDatabaseColumn(tipo))).isEqualTo(tipo);
            }
        }
    }

    @Nested
    class Bollo {

        private final TipoBolloConverter converter = new TipoBolloConverter();

        @Test
        @DisplayName("in colonna si usa la codifica pagoPA, non quella JSON")
        void codificaPagoPa() {
            assertThat(converter.convertToDatabaseColumn(TipoBollo.IMPOSTA_BOLLO)).isEqualTo("01");
            assertThat(converter.convertToEntityAttribute("01")).isEqualTo(TipoBollo.IMPOSTA_BOLLO);
            assertThat(TipoBollo.IMPOSTA_BOLLO.codificaJson()).isEqualTo("Imposta di bollo");
        }

        @Test
        void valoreIgnotoEAssente() {
            assertThat(converter.convertToEntityAttribute("99")).isNull();
        }
    }
}
