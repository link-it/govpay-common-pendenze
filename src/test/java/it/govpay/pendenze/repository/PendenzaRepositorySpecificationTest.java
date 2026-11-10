package it.govpay.pendenze.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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

import it.govpay.pendenze.entity.Debitore;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.PdDebitore;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
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
    @DisplayName("una Specification su idDominio e sul debitore dell'opzione filtra le pendenze")
    void specificationSuDominioEDebitore() {
        // Posizione del dominio A con due debitori, ciascuno con la propria pendenza:
        // il filtro su Mario trova solo la pendenza di Mario.
        PosizioneDebitoria a1 = posizione(DOMINIO_A, "doc-a1", GIOVANNA, MARIO);
        aggiungiPendenza(a1.getPdDebitori().get(0), "pend-a1-giovanna", "300000000000000001");
        aggiungiPendenza(a1.getPdDebitori().get(1), "pend-a1-mario", "300000000000000002");
        em.persist(a1);
        // Stesso dominio, altro debitore: esclusa dal filtro sul debitore.
        PosizioneDebitoria a2 = posizione(DOMINIO_A, "doc-a2", GIOVANNA);
        aggiungiPendenza(a2.getPdDebitori().get(0), "pend-a2", "300000000000000003");
        em.persist(a2);
        // Stesso debitore, altro dominio: esclusa dal filtro sul dominio.
        PosizioneDebitoria b1 = posizione(DOMINIO_B, "doc-b1", MARIO);
        aggiungiPendenza(b1.getPdDebitori().get(0), "pend-b1", "300000000000000004");
        em.persist(b1);
        em.flush();
        em.clear();

        Specification<Pendenza> spec = dominio(DOMINIO_A).and(debitore(MARIO));

        List<Pendenza> trovate = pendenzaRepository.findAll(spec);
        assertThat(trovate).extracting(Pendenza::getIdPendenza).containsExactly("pend-a1-mario");

        Page<Pendenza> pagina = pendenzaRepository.findAll(dominio(DOMINIO_A),
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("dataCreazione"), Sort.Order.desc("id"))));
        assertThat(pagina.getTotalElements()).isEqualTo(3);
        assertThat(pendenzaRepository.count(spec)).isEqualTo(1);
    }

    private static Specification<Pendenza> dominio(long idDominio) {
        return (root, query, cb) -> cb.equal(root.get("idDominio"), idDominio);
    }

    /** Come il filtro {@code idDebitore} di {@code cercaFiltrate}: il debitore dell'opzione della pendenza. */
    private static Specification<Pendenza> debitore(String identificativo) {
        return (root, query, cb) -> cb.equal(
                root.get("opzionePagamento").get("pdDebitore").get("debitore").get("identificativo"),
                identificativo);
    }

    private void aggiungiPendenza(PdDebitore pdDebitore, String idPendenza, String numeroAvviso) {
        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setIdOpzionePagamento(UUID.randomUUID());
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
        opzione.setDataCreazione(ADESSO);
        opzione.setDataUltimoAggiornamento(ADESSO);
        pdDebitore.addOpzionePagamento(opzione);

        Pendenza pendenza = new Pendenza();
        pendenza.setIdDominio(pdDebitore.getPosizioneDebitoria().getIdDominio());
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
    }

    private static PosizioneDebitoria posizione(long idDominio, String codDocumento, String... debitori) {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(1L);
        posizione.setCodDocumento(codDocumento);
        posizione.setIdDominio(idDominio);
        posizione.setDescrizione("Posizione " + codDocumento);
        posizione.setDataPubblicazione(LocalDate.of(2026, 11, 8));
        posizione.setDataCreazione(ADESSO);
        posizione.setDataUltimoAggiornamento(ADESSO);
        for (int i = 0; i < debitori.length; i++) {
            Debitore debitore = new Debitore();
            debitore.setTipo(TipoSoggetto.F);
            debitore.setIdentificativo(debitori[i]);
            debitore.setAnagrafica("Debitore " + i);
            PdDebitore pd = new PdDebitore();
            pd.setPosizione(i);
            pd.setDebitore(debitore);
            posizione.addPdDebitore(pd);
        }
        return posizione;
    }
}
