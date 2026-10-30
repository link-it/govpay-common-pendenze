package it.govpay.pendenze.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import it.govpay.pendenze.exception.CausaleNonLeggibileException;

class CausaleVersamentoCodecTest {

    @Test
    void decodeNull() {
        assertThat(CausaleVersamentoCodec.decode(null)).isNull();
    }

    @Test
    void encodeNull() {
        assertThat(CausaleVersamentoCodec.encode(null)).isNull();
    }

    @Test
    void encodeEDecodeSonoInverse() {
        String causale = "Sanzione CdS n. abc00000 - città di Roma";
        String codificata = CausaleVersamentoCodec.encode(causale);
        assertThat(codificata).startsWith("01 ");
        assertThat(CausaleVersamentoCodec.decode(codificata)).isEqualTo(causale);
    }

    @Test
    void decodeFormatoNon01Lancia() {
        assertThatThrownBy(() -> CausaleVersamentoCodec.decode("02 QUFB IEJCQg=="))
                .isInstanceOf(CausaleNonLeggibileException.class);
    }
}
