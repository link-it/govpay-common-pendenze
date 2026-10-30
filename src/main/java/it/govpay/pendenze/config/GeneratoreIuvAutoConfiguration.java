package it.govpay.pendenze.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.pendenze.iuv.GeneratoreIuvStandard;
import it.govpay.pendenze.iuv.GeneratoreProgressivoIuv;
import it.govpay.pendenze.spi.GeneratoreIuv;

/**
 * Autoconfigurazione separata da {@link PendenzeAutoConfiguration} (classe diversa, non solo un
 * {@code @Bean} nella stessa) apposta per restare escludibile indipendentemente: un consumatore
 * che non vuole la generazione IUV automatica deve poterla escludere
 * ({@code @ImportAutoConfiguration(exclude = GeneratoreIuvAutoConfiguration.class)}) senza
 * perdere fuso orario/orologio, che restano in {@link PendenzeAutoConfiguration}.
 */
@AutoConfiguration
public class GeneratoreIuvAutoConfiguration {

    /**
     * Implementazione standard di {@link GeneratoreIuv}, condivisa fra i consumatori (vedi
     * Javadoc di {@link GeneratoreIuv} e {@link GeneratoreIuvStandard}). Registrata solo se il
     * consumatore ha gia' un {@link DominioRepository} di {@code govpay-common} nel contesto
     * (per le proprie esigenze di anagrafica) e non ha gia' fornito una propria implementazione
     * di {@link GeneratoreIuv}: se manca l'uno o l'altro, questa libreria non prova a fornire
     * generazione IUV.
     *
     * <p><b>{@code DominioRepository} non e' piu' un segnale di opt-in affidabile</b> da quando
     * {@code PosizioneDebitoriaService} lo richiede sempre per conto suo (vedi Javadoc di
     * {@link PendenzeAutoConfiguration}): sara' quasi sempre presente. Resta comunque la
     * condizione piu' sensata disponibile (un consumatore senza anagrafica dominio non potrebbe
     * usare {@link GeneratoreIuvStandard} in ogni caso), ed e' per questo che questa classe e'
     * separata — un consumatore che non vuole proprio questo comportamento la esclude per
     * nome, senza dover rinunciare a un {@code DominioRepository}.</p>
     *
     * @param dominioRepository      repository dell'anagrafica dominio di govpay-common
     * @param applicazioneRepository repository dell'anagrafica applicazione di govpay-common,
     *                               per risolvere il placeholder {@code %(a)} del prefisso IUV
     * @param generatoreProgressivo  allocatore dei progressivi IUV con buffer per chiave
     * @param clock                  orologio della libreria, per risolvere {@code %(Y)}/{@code %(y)}
     * @return l'implementazione standard di {@link GeneratoreIuv}
     */
    @Bean
    @ConditionalOnMissingBean(GeneratoreIuv.class)
    @ConditionalOnBean(DominioRepository.class)
    public GeneratoreIuv generatoreIuvStandard(DominioRepository dominioRepository,
            ApplicazioneRepository applicazioneRepository, GeneratoreProgressivoIuv generatoreProgressivo,
            Clock clock) {
        return new GeneratoreIuvStandard(dominioRepository, applicazioneRepository, generatoreProgressivo, clock);
    }
}
