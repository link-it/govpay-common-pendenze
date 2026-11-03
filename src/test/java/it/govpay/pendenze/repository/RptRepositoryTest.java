package it.govpay.pendenze.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.pendenze.entity.Rpt;

/**
 * Verifica il caso aggiunto dall'issue #6/govpay-pafornode-api: una ricevuta senza pendenza
 * (stazione in broadcast) deve poter essere persistita con {@code idVersamento} nullo,
 * associata invece all'applicazione risolta dal dominio ({@code idApplicazione}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = {"it.govpay.pendenze.entity", "it.govpay.common.entity"})
@EnableJpaRepositories(basePackages = {"it.govpay.pendenze.repository", "it.govpay.common.repository"})
@ActiveProfiles("test")
class RptRepositoryTest {

    private static final OffsetDateTime ADESSO = OffsetDateTime.of(2026, 11, 3, 10, 0, 0, 0, ZoneOffset.ofHours(1));

    @Autowired
    private TestEntityManager em;

    @Autowired
    private RptRepository rptRepository;

    @Test
    @DisplayName("una ricevuta senza pendenza si persiste con idVersamento nullo e idApplicazione risolta dal dominio")
    void ricevutaSenzaPendenzaPersistibile() {
        Long idApplicazione = applicazionePersistita("APP-BROADCAST");

        Rpt rpt = new Rpt();
        rpt.setIuv("30000000000000001");
        rpt.setIur("receipt-broadcast-1");
        rpt.setCodDominio("12345678901");
        rpt.setCodStazione("STAZIONE_BROADCAST");
        rpt.setCodMsgRichiesta("msg-broadcast-1");
        rpt.setStato("RT_ACCETTATA_PA");
        rpt.setVersione("SANP_321_V2");
        rpt.setDataMsgRichiesta(ADESSO);
        rpt.setDataAggiornamentoStato(ADESSO);
        rpt.setIdApplicazione(idApplicazione);
        // idVersamento volutamente non impostato: nessuna pendenza esistente.

        Rpt salvata = rptRepository.save(rpt);
        em.flush();
        em.clear();

        Rpt ricaricata = rptRepository.findById(salvata.getId()).orElseThrow();
        assertThat(ricaricata.getIdVersamento()).isNull();
        assertThat(ricaricata.getIdApplicazione()).isEqualTo(idApplicazione);
    }

    private Long applicazionePersistita(String codApplicazione) {
        ApplicazioneEntity applicazione = ApplicazioneEntity.builder().codApplicazione(codApplicazione)
                .autoIuv(false).firmaRicevuta("N").trusted(false).idUtenza(System.nanoTime()).build();
        em.persistAndFlush(applicazione);
        return applicazione.getId();
    }
}
