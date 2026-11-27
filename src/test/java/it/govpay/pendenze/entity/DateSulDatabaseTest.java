package it.govpay.pendenze.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.common.persistence.config.DatabaseTimezoneAutoConfiguration;
import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * Una data senza orario, espressa come mezzanotte italiana, resta lo stesso giorno nella
 * colonna {@code TIMESTAMP} e in rilettura.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:hsqldb:mem:pendenze-date",
        "spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
        "spring.datasource.username=SA",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.HSQLDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({ PendenzeAutoConfiguration.class, DatabaseTimezoneAutoConfiguration.class })
@ActiveProfiles("test")
class DateSulDatabaseTest {

    private static final ZoneId FUSO_ITALIANO = ZoneId.of("Europe/Rome");

    private static final OffsetDateTime ADESSO =
            OffsetDateTime.of(2026, 7, 29, 10, 30, 0, 0, ZoneOffset.ofHours(2));

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "2027-01-15", "2027-07-15", "2027-03-28", "2027-10-31" })
    @DisplayName("dataValidita e dataScadenzaAvviso conservano il giorno richiesto")
    void conservanoIlGiorno(LocalDate giorno) {
        OffsetDateTime inizioGiorno = giorno.atStartOfDay(clock.getZone()).toOffsetDateTime();

        PosizioneDebitoria posizione = posizioneMinima();
        PdDebitore pdDebitore = pdDebitore(0, "RSSMRA80A01H501U", "Mario Rossi");
        posizione.addPdDebitore(pdDebitore);
        OpzionePagamento opzione = opzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        pdDebitore.addOpzionePagamento(opzione);
        Pendenza pendenza = pendenza(posizione.getIdDominio(), "10000000001", "300000000000000001");
        pendenza.setDataValidita(inizioGiorno);
        pendenza.setDataScadenzaAvviso(inizioGiorno);
        opzione.addPendenza(pendenza);
        em.persistAndFlush(posizione);
        em.clear();

        for (String colonna : new String[] { "data_validita", "data_scadenza" }) {
            assertThat(jdbcTemplate.queryForObject("SELECT " + colonna + " FROM versamenti WHERE id = ?",
                    LocalDateTime.class, pendenza.getId()))
                    .as(colonna)
                    .isEqualTo(giorno.atStartOfDay());
        }

        Pendenza riletta = em.find(Pendenza.class, pendenza.getId());
        for (OffsetDateTime data : new OffsetDateTime[] { riletta.getDataValidita(), riletta.getDataScadenzaAvviso() }) {
            assertThat(data.toInstant()).isEqualTo(inizioGiorno.toInstant());
            assertThat(data.atZoneSameInstant(FUSO_ITALIANO).toLocalDate()).isEqualTo(giorno);
        }
    }

    private PosizioneDebitoria posizioneMinima() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(1L);
        posizione.setCodDocumento("abcdef12345");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("Sanzione CdS n. abc00000");
        posizione.setDataPubblicazione(LocalDate.of(2026, 8, 1));
        posizione.setDataCreazione(ADESSO);
        posizione.setDataUltimoAggiornamento(ADESSO);
        return posizione;
    }

    private PdDebitore pdDebitore(int posizione, String identificativo, String anagrafica) {
        Debitore debitore = new Debitore();
        debitore.setTipo(TipoSoggetto.F);
        debitore.setIdentificativo(identificativo);
        debitore.setAnagrafica(anagrafica);
        PdDebitore pd = new PdDebitore();
        pd.setPosizione(posizione);
        pd.setDebitore(debitore);
        return pd;
    }

    private OpzionePagamento opzione(TipologiaOpzionePagamento tipologia) {
        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setIdOpzionePagamento(UUID.randomUUID());
        opzione.setTipologia(tipologia);
        opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
        opzione.setDataCreazione(ADESSO);
        opzione.setDataUltimoAggiornamento(ADESSO);
        return opzione;
    }

    private Pendenza pendenza(Long idDominio, String idPendenza, String numeroAvviso) {
        Pendenza pendenza = new Pendenza();
        pendenza.setIdDominio(idDominio);
        pendenza.setIdApplicazione(1L);
        pendenza.setIdPendenza(idPendenza);
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setNumeroRata(1);
        pendenza.setImporto(100.50);
        pendenza.setNumeroAvviso(numeroAvviso);
        pendenza.setIuv(numeroAvviso);
        pendenza.setSrcIuv(numeroAvviso.toUpperCase());
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        pendenza.setDataCreazione(ADESSO);
        pendenza.setDataUltimoAggiornamento(ADESSO);
        return pendenza;
    }
}
