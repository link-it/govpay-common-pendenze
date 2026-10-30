package it.govpay.pendenze.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.model.StatoSpedizioneNotifica;
import it.govpay.pendenze.model.TipoNotifica;

/**
 * Verifica il mapping di {@link Notifica} contro lo schema legacy reale (tabella
 * {@code notifiche}, riuso diretto) — stesso principio di {@link RicevutaRendicontazioneMappingTest}
 * per {@link Rpt}/{@link Pagamento}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@EntityScan(basePackages = {"it.govpay.pendenze.entity", "it.govpay.common.entity"})
@EnableJpaRepositories(basePackages = {"it.govpay.pendenze.repository", "it.govpay.common.repository"})
@ActiveProfiles("test")
class NotificaMappingTest {

    private static final OffsetDateTime ADESSO =
            OffsetDateTime.of(2026, 7, 29, 10, 30, 0, 0, ZoneOffset.ofHours(2));

    @Autowired
    private TestEntityManager em;

    private Long applicazionePersistita(String codApplicazione) {
        ApplicazioneEntity applicazione = ApplicazioneEntity.builder()
                .codApplicazione(codApplicazione).autoIuv(true).firmaRicevuta("N").trusted(true).idUtenza(1L).build();
        em.persistAndFlush(applicazione);
        return applicazione.getId();
    }

    @Test
    @DisplayName("una notifica viene salvata e riletta, con tipo/stato intatti")
    void notificaSalvataERiletta() {
        Long idApplicazione = applicazionePersistita("A2A-NOTIFICA");

        Notifica notifica = new Notifica();
        notifica.setIdApplicazione(idApplicazione);
        notifica.setTipo(TipoNotifica.ATTIVAZIONE);
        notifica.setDataCreazione(ADESSO);
        notifica.setStato(StatoSpedizioneNotifica.DA_SPEDIRE);
        notifica.setDataAggiornamentoStato(ADESSO);
        notifica.setDataProssimaSpedizione(ADESSO);
        em.persistAndFlush(notifica);
        em.clear();

        Notifica riletta = em.find(Notifica.class, notifica.getId());

        assertThat(riletta.getIdApplicazione()).isEqualTo(idApplicazione);
        assertThat(riletta.getTipo()).isEqualTo(TipoNotifica.ATTIVAZIONE);
        assertThat(riletta.getStato()).isEqualTo(StatoSpedizioneNotifica.DA_SPEDIRE);
        assertThat(riletta.getIdRpt()).isNull();
    }
}
