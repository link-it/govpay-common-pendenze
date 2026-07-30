package it.govpay.pendenze.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.model.StatoPagamento;
import it.govpay.pendenze.model.StatoVersamento;
import it.govpay.pendenze.model.TipologiaTipoVersamento;

/**
 * Verifica il trattamento degli istanti (F1-2).
 *
 * <p>Le colonne temporali dello schema sono {@code TIMESTAMP} **senza** time zone: se il
 * fuso non fosse fissato dalla configurazione, il valore scritto dipenderebbe da quello
 * della JVM. Qui si controlla sia che l'istante sopravviva al giro in banca dati, sia che
 * l'orario effettivamente scritto in colonna sia l'ora locale del fuso configurato.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@ActiveProfiles("test")
class IstantiPersistitiTest {

    private static final ZoneId ROMA = ZoneId.of("Europe/Rome");

    /** 2026-07-29T08:30:00Z: in Europe/Rome, d'estate, sono le 10:30. */
    private static final Instant ISTANTE = Instant.parse("2026-07-29T08:30:00Z");

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("l'istante sopravvive al giro in banca dati")
    void istanteConservato() {
        Versamento pendenza = pendenza();

        em.persist(pendenza);
        em.flush();
        em.clear();

        Versamento riletta = em.find(Versamento.class, pendenza.getId());

        assertThat(riletta.getDataCreazione().toInstant()).isEqualTo(ISTANTE);
    }

    @Test
    @DisplayName("in colonna finisce l'ora locale del fuso configurato, non quella della JVM")
    void oraLocaleDelFusoConfigurato() {
        Versamento pendenza = pendenza();
        em.persist(pendenza);
        em.flush();

        String scritto = jdbcTemplate.queryForObject(
                "select cast(data_creazione as varchar) from versamenti where id = ?",
                String.class, pendenza.getId());

        // 08:30Z in Europe/Rome sono le 10:30: e' cio' che deve stare nella colonna,
        // qualunque sia il fuso della JVM che esegue il test.
        assertThat(scritto).startsWith("2026-07-29 10:30:00");
    }

    private Versamento pendenza() {
        Versamento pendenza = new Versamento();
        pendenza.setIdApplicazione(1L);
        pendenza.setIdDominio(2L);
        pendenza.setIdTipoVersamento(3L);
        pendenza.setIdTipoVersamentoDominio(4L);
        pendenza.setCodVersamentoEnte("PENDENZA-ISTANTI");
        pendenza.setImportoTotale(new BigDecimal("1.00"));
        pendenza.setStatoVersamento(StatoVersamento.NON_ESEGUITO);
        pendenza.setTipo(TipologiaTipoVersamento.DOVUTO);
        pendenza.setAggiornabile(true);
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStatoPagamento(StatoPagamento.NON_PAGATO);
        pendenza.setImportoPagato(BigDecimal.ZERO);
        pendenza.setImportoIncassato(BigDecimal.ZERO);
        pendenza.setDataCreazione(ISTANTE.atZone(ROMA).toOffsetDateTime());
        pendenza.setDataOraUltimoAggiornamento(OffsetDateTime.now(ROMA));
        return pendenza;
    }
}
