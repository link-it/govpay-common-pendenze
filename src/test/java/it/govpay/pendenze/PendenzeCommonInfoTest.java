package it.govpay.pendenze;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PendenzeCommonInfoTest {

    @Test
    void coordinatesRestituisceGroupIdEArtifactId() {
        assertEquals("org.gov4j.govpay:govpay-common-pendenze", PendenzeCommonInfo.coordinates());
    }
}
