package it.govpay.pendenze.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pendenze.config.PendenzeAutoConfiguration;

/**
 * Verifica il mapping di {@link UnitaOperativa} contro lo schema legacy reale
 * ({@code uo}, riuso diretto — vedi Javadoc di classe).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@ActiveProfiles("test")
class UnitaOperativaMappingTest {

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("un'unita' operativa viene salvata e riletta")
    void unitaOperativaSalvataERiletta() {
        UnitaOperativa uo = new UnitaOperativa();
        uo.setIdDominio(1L);
        uo.setCodUo("UO33132");
        uo.setAbilitato(true);
        uo.setDenominazione("Ufficio Tributi");
        uo.setIndirizzo("Via Roma");
        uo.setCivico("1");
        uo.setCap("00100");
        uo.setLocalita("Roma");
        uo.setProvincia("RM");
        uo.setNazione("IT");
        uo.setEmail("tributi@example.it");

        em.persistAndFlush(uo);
        em.clear();

        UnitaOperativa riletta = em.find(UnitaOperativa.class, uo.getId());

        assertThat(riletta.getIdDominio()).isEqualTo(1L);
        assertThat(riletta.getCodUo()).isEqualTo("UO33132");
        assertThat(riletta.isAbilitato()).isTrue();
        assertThat(riletta.getDenominazione()).isEqualTo("Ufficio Tributi");
    }
}
