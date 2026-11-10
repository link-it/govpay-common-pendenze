package it.govpay.pendenze.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pendenze.entity.Debitore;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.PdDebitore;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * Il filtro {@code idDebitore} di {@link PendenzaRepository#cercaFiltrate} e di
 * {@link RptRepository#cercaFiltrate} agisce sul debitore dell'opzione della pendenza, ma non
 * deve escludere le pendenze v2 senza opzione quando il filtro non e' indicato.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = {"it.govpay.pendenze.entity", "it.govpay.common.entity"})
@EnableJpaRepositories(basePackages = {"it.govpay.pendenze.repository", "it.govpay.common.repository"})
@ActiveProfiles("test")
class RicercaFiltrataDebitoreTest {

    private static final OffsetDateTime ADESSO =
            OffsetDateTime.of(2026, 11, 8, 10, 0, 0, 0, ZoneOffset.ofHours(1));
    private static final String MARIO = "RSSMRA80A01H501U";
    private static final String GIOVANNA = "VRDGNN80A01H501W";

    @Autowired
    private TestEntityManager em;
    @Autowired
    private PendenzaRepository pendenzaRepository;
    @Autowired
    private RptRepository rptRepository;

    @Test
    @DisplayName("cercaFiltrate pendenze: senza idDebitore include anche le pendenze senza opzione")
    void pendenzeSenzaFiltroDebitoreIncludonoQuelleSenzaOpzione() {
        scenario();

        var tutte = pendenzaRepository.cercaFiltrate(1L, null, null, null, null, null, null, null, null, null,
                null, ADESSO, null, PageRequest.of(0, 10));
        assertThat(tutte.getContent()).extracting(Pendenza::getIdPendenza)
                .containsExactlyInAnyOrder("legacy", "mario", "giovanna");

        var senzaConteggio = pendenzaRepository.cercaFiltrateSenzaConteggio(1L, null, null, null, null, null,
                null, null, null, null, null, ADESSO, null, PageRequest.of(0, 10));
        assertThat(senzaConteggio).hasSize(3);
    }

    @Test
    @DisplayName("cercaFiltrate pendenze: con idDebitore trova solo le pendenze del debitore dell'opzione")
    void pendenzeConFiltroDebitore() {
        scenario();

        var trovate = pendenzaRepository.cercaFiltrate(1L, null, "rssmra80a01h501u", null, null, null, null,
                null, null, null, null, ADESSO, null, PageRequest.of(0, 10));
        assertThat(trovate.getContent()).extracting(Pendenza::getIdPendenza).containsExactly("mario");
    }

    @Test
    @DisplayName("cercaFiltrate ricevute: senza idDebitore include anche le ricevute di pendenze senza opzione")
    void ricevuteSenzaFiltroDebitoreIncludonoQuelleSenzaOpzione() {
        scenario();

        var tutte = rptRepository.cercaFiltrate(1L, null, null, null, null, null, null, null, null, null, null,
                PageRequest.of(0, 10));
        assertThat(tutte.getContent()).extracting(r -> r.pendenza().getIdPendenza())
                .containsExactlyInAnyOrder("legacy", "mario", "giovanna");

        var senzaConteggio = rptRepository.cercaFiltrateSenzaConteggio(1L, null, null, null, null, null, null,
                null, null, null, null, PageRequest.of(0, 10));
        assertThat(senzaConteggio).hasSize(3);
    }

    @Test
    @DisplayName("cercaFiltrate ricevute: con idDebitore trova solo le ricevute del debitore dell'opzione")
    void ricevuteConFiltroDebitore() {
        scenario();

        var trovate = rptRepository.cercaFiltrate(1L, null, null, null, null, GIOVANNA, null, null, null, null,
                null, PageRequest.of(0, 10));
        assertThat(trovate.getContent()).extracting(r -> r.pendenza().getIdPendenza())
                .containsExactly("giovanna");
    }

    @Test
    @DisplayName("le pendenze e le ricevute di un debitore rimosso non compaiono nelle ricerche, nemmeno senza filtro")
    void debitoreRimossoEscluso() {
        scenario(true);

        var pendenze = pendenzaRepository.cercaFiltrate(1L, null, null, null, null, null, null, null, null, null,
                null, ADESSO, null, PageRequest.of(0, 10));
        assertThat(pendenze.getContent()).extracting(Pendenza::getIdPendenza)
                .containsExactlyInAnyOrder("legacy", "mario");
        var perDebitore = pendenzaRepository.cercaFiltrate(1L, null, GIOVANNA, null, null, null, null, null, null,
                null, null, ADESSO, null, PageRequest.of(0, 10));
        assertThat(perDebitore.getContent()).isEmpty();

        var ricevute = rptRepository.cercaFiltrate(1L, null, null, null, null, null, null, null, null, null, null,
                PageRequest.of(0, 10));
        assertThat(ricevute.getContent()).extracting(r -> r.pendenza().getIdPendenza())
                .containsExactlyInAnyOrder("legacy", "mario");
    }

    /** Una pendenza v2 senza opzione e una posizione con due debitori, ciascuno con la propria pendenza. */
    private void scenario() {
        scenario(false);
    }

    private void scenario(boolean giovannaRimossa) {
        pendenza("legacy", "300000000000000001");

        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(1L);
        posizione.setCodDocumento("doc-1");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        posizione.setDataPubblicazione(LocalDate.of(2026, 11, 8));
        posizione.setDataCreazione(ADESSO);
        posizione.setDataUltimoAggiornamento(ADESSO);
        aggiungi(posizione, 0, MARIO, "mario", "300000000000000002");
        aggiungi(posizione, 1, GIOVANNA, "giovanna", "300000000000000003");
        if (giovannaRimossa) {
            posizione.getPdDebitoriInclusiRimossi().get(1).setDataRimozione(ADESSO);
        }
        em.persist(posizione);
        em.flush();

        for (String id : new String[] {"legacy", "mario", "giovanna"}) {
            Pendenza p = em.getEntityManager()
                    .createQuery("select p from Pendenza p where p.idPendenza = :id", Pendenza.class)
                    .setParameter("id", id).getSingleResult();
            ricevuta(p);
        }
        em.flush();
        em.clear();
    }

    private void aggiungi(PosizioneDebitoria posizione, int ordine, String identificativo, String idPendenza,
            String numeroAvviso) {
        Debitore debitore = new Debitore();
        debitore.setTipo(TipoSoggetto.F);
        debitore.setIdentificativo(identificativo);
        debitore.setAnagrafica("Debitore " + ordine);
        PdDebitore pd = new PdDebitore();
        pd.setPosizione(ordine);
        pd.setDebitore(debitore);
        posizione.addPdDebitore(pd);

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setIdOpzionePagamento(UUID.randomUUID());
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
        opzione.setDataCreazione(ADESSO);
        opzione.setDataUltimoAggiornamento(ADESSO);
        pd.addOpzionePagamento(opzione);
        opzione.addPendenza(nuovaPendenza(idPendenza, numeroAvviso));
    }

    private void pendenza(String idPendenza, String numeroAvviso) {
        em.persist(nuovaPendenza(idPendenza, numeroAvviso));
    }

    private Pendenza nuovaPendenza(String idPendenza, String numeroAvviso) {
        Pendenza pendenza = new Pendenza();
        pendenza.setIdDominio(1L);
        pendenza.setIdApplicazione(1L);
        pendenza.setIdPendenza(idPendenza);
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setNumeroRata(1);
        pendenza.setImporto(100.50);
        pendenza.setNumeroAvviso(numeroAvviso);
        pendenza.setIuv(numeroAvviso);
        pendenza.setSrcIuv(numeroAvviso);
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        pendenza.setDataCreazione(ADESSO);
        pendenza.setDataUltimoAggiornamento(ADESSO);
        return pendenza;
    }

    private void ricevuta(Pendenza pendenza) {
        Rpt rpt = new Rpt();
        rpt.setIdVersamento(pendenza.getId());
        rpt.setIuv(pendenza.getIuv());
        rpt.setIur("iur-" + pendenza.getIdPendenza());
        rpt.setCodDominio("DOMINIO_1");
        rpt.setCodStazione("STAZIONE_1");
        rpt.setCodMsgRichiesta("msg-" + pendenza.getIdPendenza());
        rpt.setStato("RT_ACCETTATA_NODO");
        rpt.setXmlRt("<Receipt/>".getBytes(StandardCharsets.UTF_8));
        rpt.setDataMsgRichiesta(ADESSO);
        rpt.setDataMsgRicevuta(ADESSO);
        rpt.setDataAggiornamentoStato(ADESSO);
        rpt.setVersione("RPTV2_RTV1");
        em.persist(rpt);
    }
}
