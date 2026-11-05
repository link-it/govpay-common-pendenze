package it.govpay.pendenze.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.OpzionePagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;

/**
 * Verifica la lettura delle opzioni create dalla migrazione dei dati della patch
 * {@code 3.11.0.sql} del core (passo C, link-it/govpay#901): i versamenti v2 con
 * {@code cod_rata} {@code RIDOTTO} o {@code SCONTATO} diventano un'opzione
 * {@code SOLUZIONE_UNICA_RIDOTTO}/{@code SOLUZIONE_UNICA_SCONTATO} con {@code giorni}
 * {@code NULL} e la sola pendenza in {@code numero_rata} 1.
 *
 * <p>Le righe sono inserite via SQL, come le scrive la patch, e non tramite le entita':
 * cosi' il test fallisce se l'enum non conosce un valore scritto dalla migrazione
 * (conversione di {@code @Enumerated(EnumType.STRING)}).</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@ActiveProfiles("test")
class OpzioneMigrataMappingTest {

    private static final long ID_DOCUMENTO = 9001L;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private OpzionePagamentoRepository opzionePagamentoRepository;

    @Autowired
    private PendenzaRepository pendenzaRepository;

    @ParameterizedTest(name = "{0} da cod_rata {1}")
    @CsvSource({
            "SOLUZIONE_UNICA_RIDOTTO, RIDOTTO, ANNULLATA, ANNULLATO",
            "SOLUZIONE_UNICA_SCONTATO, SCONTATO, ATTIVATA, ESEGUITO"
    })
    @DisplayName("un'opzione migrata e la sua pendenza vengono lette")
    void opzioneMigrataLetta(TipologiaOpzionePagamento tipologia, String codRata,
            StatoOpzionePagamento statoOpzione, StatoPendenza statoPendenza) {
        long idOpzione = 9101L;
        long idPendenza = 9201L;
        inserisciDocumento();
        inserisciOpzione(idOpzione, tipologia.name(), statoOpzione.name());
        inserisciVersamento(idPendenza, idOpzione, codRata, statoPendenza.name());
        em.clear();

        OpzionePagamento opzione = opzionePagamentoRepository.findById(idOpzione).orElseThrow();
        assertThat(opzione.getTipologia()).isEqualTo(tipologia);
        assertThat(opzione.getGiorni()).isNull();
        assertThat(opzione.getStato()).isEqualTo(statoOpzione);
        assertThat(opzione.getTipologia().cardinalitaPendenzeMassima()).isEqualTo(1);
        assertThat(opzione.getTipologia().richiedeGiorni()).isFalse();
        assertThat(opzione.getPendenze()).extracting(Pendenza::getId).containsExactly(idPendenza);
        em.clear();

        Pendenza pendenza = pendenzaRepository.findById(idPendenza).orElseThrow();
        assertThat(pendenza.getStato()).isEqualTo(statoPendenza);
        assertThat(pendenza.getNumeroRata()).isEqualTo(1);
        assertThat(pendenza.getOpzionePagamento().getTipologia()).isEqualTo(tipologia);
        assertThat(pendenza.getOpzionePagamento().getPosizioneDebitoria().getId()).isEqualTo(ID_DOCUMENTO);
    }

    private void inserisciDocumento() {
        em.getEntityManager().createNativeQuery("""
                INSERT INTO documenti (id, cod_documento, descrizione, notifica_send, anomalo, data_creazione,
                        data_ultimo_aggiornamento, versione, id_dominio, id_applicazione)
                VALUES (?, 'VERBALE-CDS-1', 'Verbale CdS', FALSE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1, 1)
                """).setParameter(1, ID_DOCUMENTO).executeUpdate();
    }

    /** Stesse colonne e valori dell'{@code INSERT INTO opzioni_pagamento} del passo C della patch. */
    private void inserisciOpzione(long id, String tipologia, String stato) {
        em.getEntityManager().createNativeQuery("""
                INSERT INTO opzioni_pagamento (id, id_opzione_pagamento, id_documento, tipologia, giorni, stato,
                        versione, data_creazione, data_ultimo_aggiornamento)
                VALUES (?, ?, ?, ?, NULL, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)
                .setParameter(1, id)
                .setParameter(2, UUID.randomUUID())
                .setParameter(3, ID_DOCUMENTO)
                .setParameter(4, tipologia)
                .setParameter(5, stato)
                .executeUpdate();
    }

    /** Versamento v2 dopo l'{@code UPDATE versamenti} del passo C: opzione assegnata e {@code numero_rata} 1. */
    private void inserisciVersamento(long id, long idOpzione, String codRata, String stato) {
        em.getEntityManager().createNativeQuery("""
                INSERT INTO versamenti (id, cod_versamento_ente, importo_totale, stato_versamento, aggiornabile,
                        data_creazione, data_ora_ultimo_aggiornamento, debitore_tipo, debitore_identificativo,
                        debitore_anagrafica, iuv_versamento, numero_avviso, ack, anomalo, importo_pagato,
                        importo_incassato, stato_pagamento, src_debitore_identificativo, cod_rata, numero_rata,
                        tipo, send_abilitato, id_tipo_versamento_dominio, id_tipo_versamento, id_dominio,
                        id_applicazione, id_documento, id_opzione_pagamento)
                VALUES (?, ?, 100.0, ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'F', 'RSSMRA80A01H501U',
                        'Mario Rossi', '10000000001', '310000000000000001', FALSE, FALSE, 0.0, 0.0, 'NON_PAGATO',
                        'RSSMRA80A01H501U', ?, 1, 'DOVUTO', FALSE, 1, 1, 1, 1, ?, ?)
                """)
                .setParameter(1, id)
                .setParameter(2, "VRS-" + codRata)
                .setParameter(3, stato)
                .setParameter(4, codRata)
                .setParameter(5, ID_DOCUMENTO)
                .setParameter(6, idOpzione)
                .executeUpdate();
    }
}
