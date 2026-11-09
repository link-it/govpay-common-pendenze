package it.govpay.pendenze.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.criteria.Subquery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * Issue #22: {@link PendenzaRepository} accetta {@link Specification} costruite dal chiamante,
 * come fara' govpay-console-api per la propria ricerca.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = {"it.govpay.pendenze.entity", "it.govpay.common.entity"})
@EnableJpaRepositories(basePackages = {"it.govpay.pendenze.repository", "it.govpay.common.repository"})
@ActiveProfiles("test")
class PendenzaRepositorySpecificationTest {

    private static final OffsetDateTime ADESSO =
            OffsetDateTime.of(2026, 11, 8, 10, 0, 0, 0, ZoneOffset.ofHours(1));

    private static final long DOMINIO_A = 1L;
    private static final long DOMINIO_B = 2L;
    private static final String MARIO = "RSSMRA80A01H501U";
    private static final String GIOVANNA = "VRDGNN80A01H501W";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PendenzaRepository pendenzaRepository;

    @Test
    @DisplayName("una Specification su idDominio e su un soggetto debitore della posizione filtra le pendenze")
    void specificationSuDominioEDebitore() {
        // Posizione del dominio A con Mario come co-obbligato (ordine 1): deve essere trovata.
        persisti(posizione(DOMINIO_A, "doc-a1", GIOVANNA, MARIO), "pend-a1", "300000000000000001");
        // Stesso dominio, altro debitore: esclusa dal filtro sul debitore.
        persisti(posizione(DOMINIO_A, "doc-a2", GIOVANNA), "pend-a2", "300000000000000002");
        // Stesso debitore, altro dominio: esclusa dal filtro sul dominio.
        persisti(posizione(DOMINIO_B, "doc-b1", MARIO), "pend-b1", "300000000000000003");
        em.flush();
        em.clear();

        Specification<Pendenza> spec = dominio(DOMINIO_A).and(debitore(MARIO));

        List<Pendenza> trovate = pendenzaRepository.findAll(spec);
        assertThat(trovate).extracting(Pendenza::getIdPendenza).containsExactly("pend-a1");

        Page<Pendenza> pagina = pendenzaRepository.findAll(dominio(DOMINIO_A),
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("dataCreazione"), Sort.Order.desc("id"))));
        assertThat(pagina.getTotalElements()).isEqualTo(2);
        assertThat(pendenzaRepository.count(spec)).isEqualTo(1);
    }

    private static Specification<Pendenza> dominio(long idDominio) {
        return (root, query, cb) -> cb.equal(root.get("idDominio"), idDominio);
    }

    /** Come il filtro {@code idDebitore} di {@code cercaFiltrate}: qualsiasi soggetto della posizione. */
    private static Specification<Pendenza> debitore(String identificativo) {
        return (root, query, cb) -> {
            Subquery<Long> sq = query.subquery(Long.class);
            var sd = sq.from(SoggettoDebitore.class);
            sq.select(sd.get("id")).where(
                    cb.equal(sd.get("posizioneDebitoria"),
                            root.get("opzionePagamento").get("posizioneDebitoria")),
                    cb.equal(sd.get("identificativo"), identificativo));
            return cb.exists(sq);
        };
    }

    private void persisti(PosizioneDebitoria posizione, String idPendenza, String numeroAvviso) {
        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setIdOpzionePagamento(UUID.randomUUID());
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
        opzione.setDataCreazione(ADESSO);
        opzione.setDataUltimoAggiornamento(ADESSO);
        posizione.addOpzionePagamento(opzione);

        Pendenza pendenza = new Pendenza();
        pendenza.setIdDominio(posizione.getIdDominio());
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
        opzione.addPendenza(pendenza);

        em.persist(posizione);
    }

    private static PosizioneDebitoria posizione(long idDominio, String codDocumento, String... debitori) {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(1L);
        posizione.setCodDocumento(codDocumento);
        posizione.setIdDominio(idDominio);
        posizione.setDescrizione("Posizione " + codDocumento);
        posizione.setDataPubblicazione(LocalDate.of(2026, 11, 8));
        posizione.setNotificaSend(false);
        posizione.setDataCreazione(ADESSO);
        posizione.setDataUltimoAggiornamento(ADESSO);
        for (int i = 0; i < debitori.length; i++) {
            SoggettoDebitore soggetto = new SoggettoDebitore();
            soggetto.setOrdine(i);
            soggetto.setTipo(TipoSoggetto.F);
            soggetto.setIdentificativo(debitori[i]);
            soggetto.setAnagrafica("Debitore " + i);
            posizione.addSoggettoDebitore(soggetto);
        }
        return posizione;
    }
}
