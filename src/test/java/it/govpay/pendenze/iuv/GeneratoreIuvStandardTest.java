package it.govpay.pendenze.iuv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.spi.IdentificativiPagamento;

/**
 * Verifica {@link GeneratoreIuvStandard} end-to-end contro l'anagrafica reale di
 * {@code govpay-common} (non contro valori finti): prova che la dipendenza dichiarata in
 * M4 ("nessuna relazione JPA verso l'anagrafica", non "nessuna dipendenza dalla libreria")
 * funziona davvero nel contesto Spring della libreria.
 *
 * <p>{@code @EntityScan}/{@code @EnableJpaRepositories} su {@code it.govpay.common} sono
 * dichiarati solo qui, non su {@code PendenzeTestApplication}: renderebbero altrimenti
 * {@code DominioRepository} disponibile in ogni test della libreria, facendo scattare
 * ovunque il bean {@code @ConditionalOnBean(DominioRepository.class)} di
 * {@code PendenzeAutoConfiguration} anche dove {@code GeneratoreProgressivoIuv} non e'
 * importato.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@Import({AllocatoreBloccoProgressivoIuv.class, GeneratoreProgressivoIuv.class, GeneratoreIuvStandard.class,
        GeneratoreIuvStandardTest.ClockFisso.class})
@EntityScan(basePackages = {"it.govpay.pendenze.entity", "it.govpay.common.entity"})
@EnableJpaRepositories(basePackages = {"it.govpay.pendenze.repository", "it.govpay.common.repository"})
@ActiveProfiles("test")
class GeneratoreIuvStandardTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private GeneratoreIuvStandard generatore;

    @Test
    @DisplayName("genera due IUV diversi per due pendenze dello stesso dominio, NAV coerente con l'AuxDigit")
    void generaIdentificativiCrescenti() {
        DominioEntity dominio = dominioAuxDigit1("ENTE-1");
        em.persistAndFlush(dominio);

        IdentificativiPagamento primo = generatore.genera(dominio.getId(), "A2A-1", "pend-1", null);
        IdentificativiPagamento secondo = generatore.genera(dominio.getId(), "A2A-1", "pend-2", null);

        assertThat(primo.iuv()).isNotEqualTo(secondo.iuv());
        assertThat(primo.numeroAvviso()).startsWith("1").hasSize(18);
        assertThat(secondo.numeroAvviso()).startsWith("1").hasSize(18);
    }

    @Test
    @DisplayName("converti ricava lo stesso iuv generato per lo stesso dominio, usando la sua configurazione")
    void convertiRoundTripConDominioReale() {
        DominioEntity dominio = dominioAuxDigit1("ENTE-2");
        em.persistAndFlush(dominio);

        IdentificativiPagamento generato = generatore.genera(dominio.getId(), "A2A-1", "pend-1", null);
        IdentificativiPagamento convertito = generatore.convertiDaNumeroAvviso(dominio.getId(), generato.numeroAvviso());

        assertThat(convertito.iuv()).isEqualTo(generato.iuv());
        assertThat(convertito.numeroAvviso()).isEqualTo(generato.numeroAvviso());
    }

    @Test
    @DisplayName("AuxDigit 0 usa l'application code della stazione collegata al dominio")
    void generaAuxDigit0UsaStazioneCollegata() {
        IntermediarioEntity intermediario = IntermediarioEntity.builder()
                .codIntermediario("INT-1").abilitato(true).build();
        em.persistAndFlush(intermediario);
        StazioneEntity stazione = StazioneEntity.builder()
                .codStazione("STAZ-1").abilitato(true).applicationCode(7).intermediario(intermediario).build();
        em.persistAndFlush(stazione);
        DominioEntity dominio = dominioAuxDigit1("ENTE-3");
        dominio.setAuxDigit(0);
        dominio.setStazione(stazione);
        em.persistAndFlush(dominio);

        IdentificativiPagamento identificativi = generatore.genera(dominio.getId(), "A2A-1", "pend-1", null);

        assertThat(identificativi.numeroAvviso()).startsWith("007");
    }

    @Test
    @DisplayName("genera rifiuta un dominio non censito invece di fallire con un errore JPA generico")
    void generaRifiutaDominioInesistente() {
        assertThatThrownBy(() -> generatore.genera(999L, "A2A-1", "pend-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("risolve %(y) nel prefisso di dominio con l'anno corrente a 2 cifre, prima di generare")
    void generaRisolveAnnoNelPrefisso() {
        DominioEntity dominio = dominioAuxDigit1("ENTE-PREFISSO-ANNO");
        dominio.setIuvPrefix("%(y)");
        em.persistAndFlush(dominio);

        // ClockFisso e' fissato al 2026: %(y) deve risolversi in "26", non restare letterale
        // (altrimenti "%(y)..." fa fallire il check digit con NumberFormatException, non
        // l'errore esplicito che ci si aspetterebbe da un prefisso mal configurato).
        IdentificativiPagamento identificativi = generatore.genera(dominio.getId(), "A2A-1", "pend-1", null);

        assertThat(identificativi.iuv()).startsWith("26");
    }

    @Test
    @DisplayName("risolve %(a) nel prefisso di dominio con il codApplicazioneIuv dell'applicazione idA2A")
    void generaRisolveApplicazioneNelPrefisso() {
        ApplicazioneEntity applicazione = ApplicazioneEntity.builder()
                .codApplicazione("A2A-APP").codApplicazioneIuv("007")
                .autoIuv(true).firmaRicevuta("N").trusted(true).build();
        em.persistAndFlush(applicazione);
        DominioEntity dominio = dominioAuxDigit1("ENTE-PREFISSO-APP");
        dominio.setIuvPrefix("%(a)");
        em.persistAndFlush(dominio);

        IdentificativiPagamento identificativi = generatore.genera(dominio.getId(), "A2A-APP", "pend-1", null);

        assertThat(identificativi.iuv()).startsWith("007");
    }

    @Test
    @DisplayName("risolve %(p) nel prefisso di dominio con la codificaIuvTipoPendenza fornita dal chiamante")
    void generaRisolveTipoPendenzaNelPrefisso() {
        DominioEntity dominio = dominioAuxDigit1("ENTE-PREFISSO-TIPO");
        dominio.setIuvPrefix("%(p)");
        em.persistAndFlush(dominio);

        IdentificativiPagamento identificativi = generatore.genera(dominio.getId(), "A2A-1", "pend-1", "42");

        assertThat(identificativi.iuv()).startsWith("42");
    }

    @Test
    @DisplayName("%(t) e' un alias storico di %(p): stesso valore fornito, stessa risoluzione")
    void generaRisolveAliasTDelTipoPendenza() {
        DominioEntity dominio = dominioAuxDigit1("ENTE-PREFISSO-TIPO-ALIAS");
        dominio.setIuvPrefix("%(t)");
        em.persistAndFlush(dominio);

        IdentificativiPagamento identificativi = generatore.genera(dominio.getId(), "A2A-1", "pend-1", "42");

        assertThat(identificativi.iuv()).startsWith("42");
    }

    @Test
    @DisplayName("rifiuta esplicitamente un prefisso con %(p) se il chiamante non fornisce la codificaIuvTipoPendenza, "
            + "invece di propagare il placeholder letterale fino a un NumberFormatException")
    void generaRifiutaPrefissoConTipoPendenzaSenzaValoreFornito() {
        DominioEntity dominio = dominioAuxDigit1("ENTE-PREFISSO-IGNOTO");
        dominio.setIuvPrefix("%(p)");
        em.persistAndFlush(dominio);

        assertThatThrownBy(() -> generatore.genera(dominio.getId(), "A2A-1", "pend-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("%(p)");
    }

    @Test
    @DisplayName("codificaIuvTipoPendenza diverse sullo stesso dominio hanno progressivi indipendenti, "
            + "la stessa codifica continua lo stesso progressivo (preserva la composizione storica della chiave)")
    void generaConCodificheDiverseUsaProgressiviIndipendenti() {
        DominioEntity dominio = dominioAuxDigit1("ENTE-PREFISSO-CHIAVE");
        dominio.setIuvPrefix("%(p)");
        em.persistAndFlush(dominio);

        IdentificativiPagamento tipo42Prima = generatore.genera(dominio.getId(), "A2A-1", "pend-1", "42");
        IdentificativiPagamento tipo43Prima = generatore.genera(dominio.getId(), "A2A-1", "pend-2", "43");
        IdentificativiPagamento tipo42Seconda = generatore.genera(dominio.getId(), "A2A-1", "pend-3", "42");

        assertThat(tipo42Prima.iuv()).startsWith("42" + "0".repeat(12) + "1");
        assertThat(tipo43Prima.iuv()).startsWith("43" + "0".repeat(12) + "1");
        assertThat(tipo42Seconda.iuv()).startsWith("42" + "0".repeat(12) + "2");
    }

    private DominioEntity dominioAuxDigit1(String codDominio) {
        DominioEntity dominio = new DominioEntity();
        dominio.setCodDominio(codDominio);
        dominio.setAbilitato(true);
        dominio.setRagioneSociale("Ente di prova");
        dominio.setAuxDigit(1);
        dominio.setIntermediato(false);
        dominio.setScaricaFr(false);
        return dominio;
    }

    @TestConfiguration
    static class ClockFisso {
        /** Anno fisso 2026, cosi' %(y)/%(Y) nei test hanno un valore atteso deterministico. */
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
