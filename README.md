<p align="center">
<img src="https://www.link.it/wp-content/uploads/2025/01/logo-govpay.svg" alt="GovPay Logo" width="200"/>
</p>

# GovPay - Porta di accesso al sistema pagoPA - Common Pendenze

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=link-it_govpay-common-pendenze&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=link-it_govpay-common-pendenze)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://raw.githubusercontent.com/link-it/govpay-common-pendenze/main/LICENSE)
[![Spring Boot](https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fraw.githubusercontent.com%2Flink-it%2Fgovpay-bom%2Fmain%2Fpom.xml&query=%2F%2F*%5Blocal-name()%3D'spring-boot.version'%5D&label=Spring%20Boot&color=brightgreen)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

Libreria Spring Boot con il modello dati v3 delle pendenze di GovPay: entità JPA delle posizioni debitorie, servizi di creazione, aggiornamento e ricerca, validazioni, generazione di IUV e numeri avviso, lettura di ricevute e rendicontazioni.

## Sommario

La libreria non è un'applicazione: è un jar che i componenti GovPay includono come dipendenza Maven per leggere e scrivere le pendenze sul database di GovPay con il modello v3. Il modello v3 riusa le tabelle del core (`documenti`, `versamenti`, `singoli_versamenti`, `rpt`, `pagamenti`, `fr`, `rendicontazioni`, `notifiche`) e aggiunge `opzioni_pagamento` e `soggetti_debitori`: il core v2 e i componenti che usano la libreria lavorano sulle stesse tabelle.

Il modello è una posizione debitoria (`documenti`) con una o più opzioni di pagamento (soluzione unica o piano rateale), ognuna con le sue pendenze (`versamenti`) e le loro voci (`singoli_versamenti`), più l'elenco dei soggetti debitori.

| Modulo | Package | Contenuto |
|--------|---------|-----------|
| [Servizi](#servizi) | `it.govpay.pendenze.service` | `PosizioneDebitoriaService` (creazione, aggiornamento, annullamento, ripristino, ricerca), `RicevutaRendicontazioneService` (ricevute, RPT, rendicontazioni) |
| [Comandi](#servizi) | `it.govpay.pendenze.comando` | Comandi a valore per le scritture (`DatiPosizioneDebitoria`, `DatiOpzionePagamento`, `DatiPendenza`, `AggiornamentoPendenza`, ...) |
| [Entità e repository](#entità-e-repository) | `it.govpay.pendenze.entity`, `it.govpay.pendenze.repository` | Entità JPA e repository Spring Data delle tabelle delle pendenze |
| Modello | `it.govpay.pendenze.model` | Enum degli stati e delle tipologie, `DettaglioContabile` e il suo converter JSON |
| [Validazioni](#validazioni-ed-eccezioni) | `it.govpay.pendenze.validazione` | `ValidatorePosizioneDebitoria`: vincoli semantici dell'aggregato |
| [Eccezioni](#validazioni-ed-eccezioni) | `it.govpay.pendenze.exception` | Eccezioni runtime che il livello REST traduce in codici HTTP |
| [IUV e numero avviso](#iuv-e-numero-avviso) | `it.govpay.pendenze.spi`, `it.govpay.pendenze.iuv` | SPI `GeneratoreIuv` e implementazione standard con i progressivi di `id_messaggio_relativo` |
| [Ricerca e paginazione](#ricerca-e-paginazione) | `it.govpay.pendenze.criteri` | Filtri, ordinamento, paginazione offset/limit e a cursore |
| Identificativo della posizione | `it.govpay.pendenze.identificativo` | Composizione e scomposizione dell'IUPD (`idA2A` + `idDominio` + codice documento) |
| [Ricevute pagoPA](#ricevute-pagopa) | `it.govpay.pendenze.ricevuta.pagopa` | Ricostruzione della richiesta di pagamento dalla ricevuta quando l'XML della RPT non c'è |
| [Configurazione](#configurazione) | `it.govpay.pendenze.config` | Auto-configurazioni: fuso orario, orologio, generatore IUV standard |

## Integrazione con GovPay

| Componente | Quando serve | Obbligatorio | Ruolo |
|------------|--------------|--------------|-------|
| [govpay-bom](https://github.com/link-it/govpay-bom) | build | sì | Parent Maven (`2.0.5-SNAPSHOT`): versioni delle dipendenze e dei plugin, compreso Spring Boot |
| [govpay-common](https://github.com/link-it/govpay-common) | build e runtime | sì | Dipendenza diretta (`govpay-common.version`, oggi `2.0.4-SNAPSHOT`): `ApplicazioneRepository` e `DominioRepository` per risolvere `idA2A` e dominio, `IuvUtils` e `RisolutoreSegnaposto` per gli IUV, `DateTimePatterns` per il fuso orario di default, `TipoContabilita` per le voci |
| Database di GovPay, schema 3.11.0 o successivo | runtime | sì | Tabelle scritte: `documenti`, `opzioni_pagamento`, `soggetti_debitori`, `versamenti`, `singoli_versamenti`, `id_messaggio_relativo` (progressivi IUV). Tabelle lette: `rpt`, `pagamenti`, `fr`, `rendicontazioni`, `notifiche` (i repository permettono anche di scriverle) e l'anagrafica `domini`, `applicazioni`. Lo schema è creato e aggiornato dagli script del core: vedi [Schema del database](#schema-del-database) |
| Progetti che usano la libreria | build | - | Dipendenza diretta nel `pom.xml` (proprietà `govpay-common-pendenze.version`): [govpay-pendenze-api](https://github.com/link-it/govpay-pendenze-api), [govpay-pafornode-api](https://github.com/link-it/govpay-pafornode-api); [govpay-notify-batch](https://github.com/link-it/govpay-notify-batch) sul ramo `30-modello-dati-v3` |

## Compilazione

Requisiti: JDK 21 e Maven 3.9 o successivo.

```bash
mvn clean install
```

Il risultato è `target/govpay-common-pendenze-<versione>.jar`, installato nel repository Maven locale. La build genera i bean JAXB dei tracciati pagoPA dagli XSD in [src/main/resources/xsd/pagopa](src/main/resources/xsd/pagopa) (package `it.gov.digitpa.schemas._2011.pagamenti` e `it.gov.pagopa.pagopa_api.pa.pafornode`, inclusi nel jar), esegue i test con il report di copertura JaCoCo (`target/site/jacoco/`) e, nella fase `verify`, l'analisi OWASP Dependency-Check delle dipendenze (esecuzione `check-owasp` di govpay-bom). Per escludere l'analisi OWASP, per esempio in sviluppo:

```bash
mvn clean install -Dowasp.phase=none
```

Altri comandi utili:

```bash
# Solo i test
mvn clean test

# Test singolo
mvn test -Dtest=PosizioneDebitoriaServiceTest
```

### Test

I test usano H2 in memoria in modalità PostgreSQL con `spring.jpa.hibernate.ddl-auto=validate`: lo schema non è generato dalle entità ma caricato da [src/test/resources/db/schema-pendenze-test.sql](src/test/resources/db/schema-pendenze-test.sql), così una colonna mappata che manca nello schema fa fallire i test. Vedi [Schema di test](#schema-di-test).

### CI/CD

GitHub Actions esegue build, test, copertura, OWASP Dependency-Check, SonarCloud, analisi delle licenze, OSV scan e SBOM CycloneDX su ogni push e PR; pubblica gli SNAPSHOT su Maven Central dai push su `main` e le release dai tag `X.Y.Z`. Dettagli, secret richiesti e risoluzione dei problemi in [.github/PIPELINE.md](.github/PIPELINE.md).

## Utilizzo

Coordinate Maven:

```xml
<dependency>
    <groupId>org.gov4j.govpay</groupId>
    <artifactId>govpay-common-pendenze</artifactId>
    <version>${govpay-common-pendenze.version}</version>
</dependency>
```

La versione corrente di sviluppo è `1.0.0-SNAPSHOT` (pom su `main`); i progetti GovPay la impostano con la proprietà `govpay-common-pendenze.version`. Le versioni SNAPSHOT sono pubblicate su `https://central.sonatype.com/repository/maven-snapshots`:

```xml
<repositories>
    <repository>
        <id>central-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots</url>
        <releases><enabled>false</enabled></releases>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>
```

La libreria porta con sé `spring-boot-starter-data-jpa`, `govpay-common`, Jackson 3 e il runtime JAXB. Il driver JDBC lo fornisce il progetto che la usa.

### Attivazione in un'applicazione Spring Boot

Le auto-configurazioni registrate in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` si attivano da sole:

- `PendenzeAutoConfiguration`: proprietà `govpay.pendenze.*`, il bean `Clock` della libreria (`pendenzeClock`, sul fuso configurato) e un `HibernatePropertiesCustomizer` che imposta `hibernate.jdbc.time_zone` allo stesso fuso, se l'applicazione non l'ha già impostato;
- `GeneratoreIuvAutoConfiguration`: il `GeneratoreIuv` standard, registrato se nel contesto c'è un `DominioRepository` e nessun altro `GeneratoreIuv`.

Servizi, entità e repository vanno invece inclusi dall'applicazione:

| Cosa | Package | Come |
|------|---------|------|
| Servizi | `it.govpay.pendenze.service`, `it.govpay.pendenze.iuv` | Scansione dei componenti |
| Entità | `it.govpay.pendenze.entity` | Scansione delle entità, più `ApplicazioneEntity` e `DominioEntity` di govpay-common e le entità che queste referenziano (`StazioneEntity`, `IntermediarioEntity`; `ConnettoreEntity` per le query di `ApplicazioneRepository`) |
| Repository | `it.govpay.pendenze.repository` | `@EnableJpaRepositories`, più `ApplicazioneRepository` e `DominioRepository` di `it.govpay.common.repository` |

Esempio, sul modello di govpay-pafornode-api:

```java
@SpringBootApplication
@ComponentScan(basePackages = { "it.govpay.mioprogetto", "it.govpay.pendenze" })
@EnableJpaRepositories(basePackages = { "it.govpay.mioprogetto", "it.govpay.pendenze.repository",
        "it.govpay.common.repository" },
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = { IntermediarioRepository.class, StazioneRepository.class }))
public class MiaApplicazione {

    @Bean
    public PersistenceManagedTypes persistenceManagedTypes(ResourceLoader resourceLoader) {
        PersistenceManagedTypes proprie = new PersistenceManagedTypesScanner(resourceLoader)
                .scan("it.govpay.mioprogetto", "it.govpay.pendenze.entity");
        List<String> classi = new ArrayList<>(proprie.getManagedClassNames());
        classi.add(ApplicazioneEntity.class.getName());
        classi.add(DominioEntity.class.getName());
        classi.add(StazioneEntity.class.getName());
        classi.add(IntermediarioEntity.class.getName());
        classi.add(ConnettoreEntity.class.getName());
        // ... altre entita' di govpay-common usate dai repository attivi
        return PersistenceManagedTypes.of(classi, List.of("it.govpay.mioprogetto", "it.govpay.pendenze.entity"));
    }
}
```

L'elenco esplicito evita `@EntityScan` sull'intero `it.govpay.common.entity`, che includerebbe anche le entità Spring Batch di `it.govpay.common.entity.batch`: Hibernate le validerebbe contro lo schema all'avvio. I repository di govpay-common attivi devono avere la loro entità nell'elenco (nell'esempio sono esclusi quelli di intermediari e stazioni, che servono solo come entità referenziate da `DominioEntity`).

Se la scansione non include `it.govpay.pendenze.iuv`, `GeneratoreIuvAutoConfiguration` non trova `GeneratoreProgressivoIuv` e l'avvio fallisce: per non usare il generatore standard la si esclude con `@ImportAutoConfiguration(exclude = GeneratoreIuvAutoConfiguration.class)` o con `spring.autoconfigure.exclude`.

### Servizi

`PosizioneDebitoriaService` (transazionale) è il punto di ingresso delle scritture. Riceve comandi a valore del package `it.govpay.pendenze.comando`, costruisce e modifica le entità, applica le validazioni e gestisce gli stati:

| Metodo | Operazione |
|--------|------------|
| `crea(DatiPosizioneDebitoria)` | Crea una posizione debitoria con opzioni, pendenze, voci e soggetti debitori; assegna IUV e numero avviso dove mancano |
| `aggiorna`, `aggiornaPendenza` | Aggiornamento parziale della posizione o di una pendenza (`ModificaCampo` distingue "non indicato" da "indicato a null") |
| `aggiungiOpzionePagamento`, `aggiungiPendenza` | Aggiunta di un'opzione o di una pendenza a una posizione esistente |
| `annulla`, `ripristina` | Annullamento e ripristino di un'opzione di pagamento |
| `registraEsitoPendenza`, `registraImportoSend` | Esito di una pendenza; importo delle spese di notifica SEND |
| `trovaPerId`, `trovaPerIdentificativo`, `trovaPendenzaPerIdentificativo` | Lettura per chiave interna o per identificativo pubblico (`idA2A` + `idPosizioneDebitoria` o `idPendenza`) |
| `cercaPerDebitore*`, `cercaPendenze*`, `cercaPendenzeFiltrate*` | Ricerche con o senza conteggio e a cursore |
| `idPosizioneDebitoriaPubblica` | Identificativo pubblico (IUPD) di una posizione |

`RicevutaRendicontazioneService` (sola lettura) restituisce ricevute (`cercaRicevute*`, `trovaRicevuta`), XML della richiesta di pagamento (`trovaXmlRichiestaPagamento`), rendicontazioni (`cercaRendicontazioni*`) e RPT filtrate (`cercaRptFiltrate*`).

Tutti gli istanti scritti dalla libreria vengono dal bean `Clock`: un test o un'applicazione può sostituirlo con un proprio bean `Clock`.

### Entità e repository

| Entità | Tabella | Repository |
|--------|---------|------------|
| `PosizioneDebitoria` | `documenti` | `PosizioneDebitoriaRepository` |
| `OpzionePagamento` | `opzioni_pagamento` | `OpzionePagamentoRepository` |
| `SoggettoDebitore` | `soggetti_debitori` | (tramite `PosizioneDebitoria`) |
| `Pendenza` | `versamenti` | `PendenzaRepository` |
| `VocePendenza` | `singoli_versamenti` | (tramite `Pendenza`) |
| `Rpt`, `RptXmlRptEntity` | `rpt` | `RptRepository`, `RptXmlRptRepository` |
| `Pagamento` | `pagamenti` | `PagamentoRepository` |
| `FlussoRendicontazione` | `fr` | `FlussoRendicontazioneRepository` |
| `Rendicontazione` | `rendicontazioni` | `RendicontazioneRepository` |
| `Notifica` | `notifiche` | `NotificaRepository` |
| `ProgressivoIuv` | `id_messaggio_relativo` | `ProgressivoIuvRepository` |

Le colonne verso l'anagrafica (dominio, applicazione, tipo pendenza, unità operativa, tributo, IBAN) sono chiavi numeriche, non relazioni JPA. Gli importi di `versamenti` e `singoli_versamenti` sono `Double`, come le colonne `DOUBLE PRECISION` del core. Il lock ottimistico usa le colonne `documenti.versione` e `opzioni_pagamento.versione`.

### Validazioni ed eccezioni

`ValidatorePosizioneDebitoria` controlla i vincoli semantici dell'aggregato (numero di pendenze per tipologia di opzione, importo della pendenza uguale alla somma delle voci, stati coerenti). Le validazioni strutturali (campi obbligatori, lunghezze, formati) restano al livello che espone le API.

Le eccezioni sono tutte runtime; il livello REST le traduce (la colonna "Traduzione tipica" è quella di govpay-pendenze-api):

| Eccezione | Quando | Traduzione tipica |
|-----------|--------|-------------------|
| `RisorsaNonTrovataException` | Posizione, opzione o pendenza inesistente | 404 |
| `RisorsaGiaEsistenteException` | Chiave logica già usata (es. `idA2A` + `idPosizioneDebitoria`) | 409 |
| `ModificaConcorrenteException` | Lock ottimistico fallito sulla posizione | 409 |
| `TransizioneStatoNonAmmessaException` | Transizione di stato non ammessa (es. annullare un'opzione attivata) | 409 |
| `ValidazioneNonSuperataException` | Vincoli semantici non rispettati | 400 |
| `RicercaNonUnivocaException` | Una ricerca che deve restituire al più una riga ne trova più di una | 409 |
| `CausaleNonLeggibileException` | `versamenti.causale_versamento` in un formato storico non decodificabile | 500 (dato non leggibile) |

### IUV e numero avviso

`GeneratoreIuv` (`it.govpay.pendenze.spi`) genera IUV e numero avviso per le pendenze che ne sono prive, o ricava lo IUV da un numero avviso fornito dal chiamante. L'implementazione standard `GeneratoreIuvStandard` segue le regole pagoPA (aux digit, codice di segregazione, prefisso IUV del dominio con i segnaposto `%(a)`, `%(p)`, `%(t)`, `%(Y)`, `%(y)`) e prende i progressivi dalla tabella `id_messaggio_relativo` (protocollo `GovPay`), la stessa del core: `GeneratoreProgressivoIuv` riserva blocchi di 100 valori per chiave e li tiene in memoria, quindi un riavvio lascia buchi nella sequenza ma non duplicati. Un'applicazione può registrare un proprio bean `GeneratoreIuv`; senza alcun generatore `crea` rifiuta le pendenze senza IUV e numero avviso.

### Ricerca e paginazione

- `FiltriPendenze`, `FiltriRpt`: filtri di ricerca, un campo `null` disattiva il filtro.
- `CriteriOrdinamento`: legge il parametro `sort` (`campo:asc,campo:desc`) con una mappa esplicita dei campi ordinabili.
- `OffsetPageRequest` e `PaginaRisultati`: paginazione `offset`/`limit` con offset arbitrario.
- `PaginaSenzaConteggio` e `CursorCodec`: pagine senza `COUNT(*)` e paginazione a cursore (base64 URL-safe di `<timestamp>|<id>`, non firmato).

### Ricevute pagoPA

`RicostruttoreRichiestaPagamento` ricostruisce `CtPaymentPA` e `CtPaymentPAV2` da `paSendRT`/`paSendRTV2` e dalla pendenza quando `rpt.xml_rpt` non è disponibile (per esempio pagamenti avviati dal Nodo). `JaxbUtils` e gli adapter del package servono a leggere e scrivere i tracciati.

### Documenti di disegno

In [docs](docs) ci sono i documenti di analisi e disegno del modello v3: [proposta-modello-nativo-v3.md](docs/proposta-modello-nativo-v3.md) e [riconciliazione-legacy-v3.md](docs/riconciliazione-legacy-v3.md) sono quelli di riferimento; gli altri sono archiviati e lo dichiarano in testa.

## Schema del database

La libreria non crea né modifica lo schema: richiede il database di GovPay alla versione **3.11.0** o successiva, quella che introduce il modello v3 delle posizioni debitorie ([Issue #901](https://github.com/link-it/govpay/issues/901) del core, ramo `3.11.x`). Lo schema è definito dagli script del core in [`src/main/resources/db/sql`](https://github.com/link-it/govpay/tree/3.11.x/src/main/resources/db/sql), per PostgreSQL, MySQL/MariaDB, Oracle, SQL Server e HSQLDB:

- `gov_pay.sql`: installazione nuova;
- `patch/3.11.0.sql`: aggiornamento di un database esistente. Aggiunge a `documenti` le colonne `id_unita_operativa`, `data_pubblicazione`, `notifica_send`, `nav_notifica`, `data_ultima_modifica_aca`, `data_ultima_comunicazione_aca`, `data_creazione`, `data_ultimo_aggiornamento`, `versione` e porta `descrizione` a 1024 caratteri; aggiunge a `versamenti` le colonne `numero_rata` e `id_opzione_pagamento`; crea `opzioni_pagamento` e `soggetti_debitori`; migra i dati esistenti al modello v3;
- `migrazione/riallineamento-v3.sql`: da eseguire una volta, al passaggio all'API v3, su un database già aggiornato alla 3.11.0 su cui il core v2 ha continuato a scrivere.

Lo schema SQL del core è la fonte: il mapping delle entità si adegua alle colonne del core (nomi, tipi, nullabilità), non il contrario.

Gli script in [src/main/resources/db/sql](src/main/resources/db/sql) (`<database>/migrazione/0N_*.sql`) sono la prima versione della migrazione al modello v3, scritta in questo repository prima della patch 3.11.0 del core che la sostituisce: per installare o aggiornare un database si usano gli script del core.

### Schema di test

[src/test/resources/db/schema-pendenze-test.sql](src/test/resources/db/schema-pendenze-test.sql) riporta le tabelle mappate dalla libreria con tutte le colonne di `gov_pay.sql` (PostgreSQL) del core 3.11.x, anche quelle che le entità non mappano, con gli stessi tipi, nullabilità e default: un `INSERT` che dimentica una colonna obbligatoria fallisce nei test come in produzione. Le differenze sono di sintassi H2 e di vincoli (niente foreign key verso l'anagrafica, niente indici non univoci). Le tabelle dell'anagrafica di govpay-common (`domini`, `applicazioni`, `stazioni`, `intermediari`, `connettori`, `configurazione`, tabelle Spring Batch) hanno le sole colonne che le entità di govpay-common mappano, con tipi e nullabilità del core, tranne `configurazione.valore` (`VARCHAR` invece di `TEXT`, perché `ConfigurazioneEntity` la mappa come `LONGVARCHAR` e H2 rifiuta un `CLOB` in validazione). Le tabelle Spring Batch non sono negli script di installazione del core (`gov_pay.sql`).

I progetti che usano la libreria e hanno un proprio schema H2 di test (oggi `src/test/resources/db/schema-pendenze-test.sql` in govpay-pendenze-api e govpay-pafornode-api) devono contenere tutte le colonne che le entità della libreria mappano, altrimenti con `ddl-auto=validate` l'avvio dei test fallisce (e senza `validate` falliscono le query). Quando si aggiorna la versione di `govpay-common-pendenze`:

1. si confrontano le tabelle dello schema di test del progetto con quelle di [schema-pendenze-test.sql](src/test/resources/db/schema-pendenze-test.sql) della versione usata, e si riportano le colonne mancanti con lo stesso tipo;
2. si tiene `spring.jpa.hibernate.ddl-auto=validate` nel profilo di test, così una colonna mancante emerge subito;
3. le colonne nuove nascono nello schema del core (`gov_pay.sql` e patch): se una modifica della libreria richiede una colonna che il core non ha, si aggiunge prima al core.

Un esempio: lo snapshot che mappa `rendicontazioni.anomalie` e `rendicontazioni.id_pagamento` richiede quelle colonne anche negli schemi di test dei progetti utilizzatori.

## Configurazione

La libreria non ha un proprio `application.properties`: le proprietà si impostano nell'applicazione che la usa (`application.properties`, `-D<proprietà>=<valore>` o variabili d'ambiente equivalenti di Spring Boot).

| Proprietà | Variabile d'ambiente | Default | Descrizione |
|-----------|----------------------|---------|-------------|
| `govpay.pendenze.fuso-orario` | `GOVPAY_PENDENZE_FUSOORARIO` | `Europe/Rome` | Fuso orario con cui la libreria interpreta le colonne `TIMESTAMP` (senza fuso) dello schema GovPay. Usato dal bean `Clock` e, se l'applicazione non lo imposta, per `hibernate.jdbc.time_zone` |

Proprietà dell'applicazione che influenzano la libreria:

| Proprietà | Effetto |
|-----------|---------|
| `spring.jpa.properties.hibernate.jdbc.time_zone` | Se impostata, la libreria non la sovrascrive con `govpay.pendenze.fuso-orario` |
| `spring.autoconfigure.exclude` | Con `it.govpay.pendenze.config.GeneratoreIuvAutoConfiguration` disattiva il generatore IUV standard |

La libreria non legge altre proprietà; le configurazioni di govpay-common (connettori, GDE, tracciatura dei log) sono descritte nel [README di govpay-common](https://github.com/link-it/govpay-common#configurazione).

## Licenza

GovPay Common Pendenze è distribuito con licenza [GNU General Public License v3.0](LICENSE).

Copyright (c) 2014-2026 Link.it srl.
