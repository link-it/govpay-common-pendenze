package it.govpay.pendenze.iuv;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;

import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.entity.ProgressivoIuv;
import it.govpay.pendenze.entity.ProgressivoIuvId;

/**
 * Verifica {@link GeneratoreProgressivoIuv}/{@link AllocatoreBloccoProgressivoIuv} contro la
 * tabella reale {@code id_messaggio_relativo} (schema di {@code schema-pendenze-test.sql}):
 * stessa tabella fisica del generatore IUV legacy (vedi {@link ProgressivoIuv}), non una
 * tabella di test isolata.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@Import({AllocatoreBloccoProgressivoIuv.class, GeneratoreProgressivoIuv.class})
@ActiveProfiles("test")
class GeneratoreProgressivoIuvTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private GeneratoreProgressivoIuv generatore;

    @Test
    @DisplayName("il primo valore per una chiave nuova e' 1, poi cresce di 1 in 1")
    void primoValorePerChiaveNuova() {
        long primo = generatore.prossimoValore("DOMINIO-NUOVO");
        long secondo = generatore.prossimoValore("DOMINIO-NUOVO");
        long terzo = generatore.prossimoValore("DOMINIO-NUOVO");

        assertThat(primo).isEqualTo(1);
        assertThat(secondo).isEqualTo(2);
        assertThat(terzo).isEqualTo(3);
    }

    @Test
    @DisplayName("chiavi diverse hanno contatori indipendenti")
    void chiaviIndipendenti() {
        long a = generatore.prossimoValore("DOMINIO-A");
        long b1 = generatore.prossimoValore("DOMINIO-B");
        long b2 = generatore.prossimoValore("DOMINIO-B");

        assertThat(a).isEqualTo(1);
        assertThat(b1).isEqualTo(1);
        assertThat(b2).isEqualTo(2);
    }

    @Test
    @DisplayName("continua dal valore gia' presente nella riga: non riparte da 1 se la chiave esiste gia' "
            + "(e' il punto centrale della scelta di riusare id_messaggio_relativo invece di una tabella nuova)")
    void continuaDaRigaEsistente() {
        ProgressivoIuvId id = new ProgressivoIuvId("GovPay", "DOMINIO-ESISTENTE");
        em.persist(new ProgressivoIuv(id, 41L));
        // Commit esplicito: l'allocatore legge con una transazione REQUIRES_NEW, su una
        // connessione diversa da quella del test — senza commit non vedrebbe la riga appena
        // scritta nella transazione di test ancora aperta (stesso motivo per cui il legacy
        // apre una connessione separata quando e' gia' in una transazione).
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        long prossimo = generatore.prossimoValore("DOMINIO-ESISTENTE");

        assertThat(prossimo).isEqualTo(42);
    }

    @Test
    @DisplayName("il buffer riserva un blocco alla volta: il contatore in DB avanza di un blocco intero, non a ogni chiamata")
    void ilBufferRiservaUnBloccoAllaVolta() {
        String chiave = "DOMINIO-BUFFER";
        for (int i = 0; i < GeneratoreProgressivoIuv.AMPIEZZA_BLOCCO; i++) {
            generatore.prossimoValore(chiave);
        }
        em.clear();

        ProgressivoIuv riletto = em.find(ProgressivoIuv.class, new ProgressivoIuvId("GovPay", chiave));
        assertThat(riletto.getCounter()).isEqualTo((long) GeneratoreProgressivoIuv.AMPIEZZA_BLOCCO);

        // la chiamata numero AMPIEZZA_BLOCCO+1 esaurisce il buffer e ne riserva un secondo blocco
        generatore.prossimoValore(chiave);
        em.clear();

        ProgressivoIuv rilettoDopo = em.find(ProgressivoIuv.class, new ProgressivoIuvId("GovPay", chiave));
        assertThat(rilettoDopo.getCounter()).isEqualTo((long) GeneratoreProgressivoIuv.AMPIEZZA_BLOCCO * 2);
    }
}
