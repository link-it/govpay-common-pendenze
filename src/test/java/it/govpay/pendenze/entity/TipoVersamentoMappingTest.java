package it.govpay.pendenze.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.repository.TipoVersamentoDominioRepository;

/**
 * Verifica il mapping di {@link TipoVersamento}/{@link TipoVersamentoDominio} contro lo
 * schema legacy reale ({@code tipi_versamento}/{@code tipi_vers_domini}, proiezione minimale
 * — vedi Javadoc di classe di {@link TipoVersamento}) e la risoluzione via
 * {@link TipoVersamentoDominioRepository}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@EnableJpaRepositories(basePackages = "it.govpay.pendenze.repository")
@ActiveProfiles("test")
class TipoVersamentoMappingTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private TipoVersamentoDominioRepository tipoVersamentoDominioRepository;

    @Test
    @DisplayName("un tipo versamento con il suo override per dominio viene salvato e riletto")
    void tipoVersamentoESuoOverrideSalvatiERiletti() {
        TipoVersamento tipoVersamento = new TipoVersamento();
        tipoVersamento.setCodTipoVersamento("IMU");
        tipoVersamento.setDescrizione("Imposta Municipale Unica");
        tipoVersamento.setAbilitato(true);
        em.persistAndFlush(tipoVersamento);

        TipoVersamentoDominio override = new TipoVersamentoDominio();
        override.setTipoVersamento(tipoVersamento);
        override.setIdDominio(1L);
        em.persistAndFlush(override);
        em.clear();

        TipoVersamentoDominio riletto = em.find(TipoVersamentoDominio.class, override.getId());

        assertThat(riletto.getIdDominio()).isEqualTo(1L);
        assertThat(riletto.getTipoVersamento().getCodTipoVersamento()).isEqualTo("IMU");
        assertThat(riletto.getTipoVersamento().getDescrizione()).isEqualTo("Imposta Municipale Unica");
        assertThat(riletto.getTipoVersamento().isAbilitato()).isTrue();
    }

    @Test
    @DisplayName("findByCodTipoVersamentoAndIdDominio risolve entrambi gli ID richiesti da Pendenza "
            + "in un'unica interrogazione")
    void findByCodTipoVersamentoAndIdDominioRisolveEntrambiGliId() {
        TipoVersamento tipoVersamento = new TipoVersamento();
        tipoVersamento.setCodTipoVersamento("DIRITTI_SEGRETERIA");
        tipoVersamento.setDescrizione("Diritti di segreteria");
        tipoVersamento.setAbilitato(true);
        em.persistAndFlush(tipoVersamento);

        TipoVersamentoDominio override = new TipoVersamentoDominio();
        override.setTipoVersamento(tipoVersamento);
        override.setIdDominio(42L);
        em.persistAndFlush(override);
        em.clear();

        var risolto = tipoVersamentoDominioRepository
                .findByCodTipoVersamentoAndIdDominio("DIRITTI_SEGRETERIA", 42L);

        assertThat(risolto).isPresent();
        assertThat(risolto.get().getId()).isEqualTo(override.getId());
        assertThat(risolto.get().getTipoVersamento().getId()).isEqualTo(tipoVersamento.getId());
    }

    @Test
    @DisplayName("findByCodTipoVersamentoAndIdDominio non risolve un codice esistente nel catalogo "
            + "ma privo di override per quel dominio: nessun fallback su un dominio di default")
    void findByCodTipoVersamentoAndIdDominioNonTrovaSenzaOverridePerQuelDominio() {
        TipoVersamento tipoVersamento = new TipoVersamento();
        tipoVersamento.setCodTipoVersamento("TARI");
        tipoVersamento.setDescrizione("Tassa sui rifiuti");
        tipoVersamento.setAbilitato(true);
        em.persistAndFlush(tipoVersamento);

        TipoVersamentoDominio override = new TipoVersamentoDominio();
        override.setTipoVersamento(tipoVersamento);
        override.setIdDominio(1L);
        em.persistAndFlush(override);
        em.clear();

        var risolto = tipoVersamentoDominioRepository.findByCodTipoVersamentoAndIdDominio("TARI", 2L);

        assertThat(risolto).isEmpty();
    }
}
