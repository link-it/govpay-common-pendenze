# F1 — Modello e entità: disegno di dettaglio

Prima fase di [proposta-libreria-pendenze.md](proposta-libreria-pendenze.md).
Package base: `it.govpay.pendenze` (B5).

## 1. Obiettivo e Definition of Done

Mappare in JPA l'aggregato pendenza sullo schema GovPay 3.10.x, **senza modificarlo**,
e fornire i tipi di dominio (enum, value object, codec) su cui costruiranno F2-F5.

### Decisioni di F1

| ID | Decisione |
|---|---|
| F1-1 | **DDL di test dalla variante PostgreSQL**, in H2 `MODE=PostgreSQL` (§12.1) |
| F1-2 | **`OffsetDateTime`**, con **fuso orario letto dalle properties dell'applicazione** (§7.1) |
| F1-3 | **`cod_anno_tributario`: colonna grezza** `String` + accessor derivato tollerante (§7.2) |
| F1-4 | **Valori ignoti:** enum di stato → eccezione, enum codificati → `null` + WARN (§5) |
| F1-5 | **`orphanRemoval` disattivato** sulle voci (§7.3) |
| F1-6 | **Nessuna relazione JPA verso l'anagrafica:** le FK sono colonne `Long` (§3) |

F1 è chiusa quando:

- [ ] `Versamento` mappa tutte le **65** colonne di `versamenti` (D6)
- [ ] `SingoloVersamento` mappa tutte le **20** colonne di `singoli_versamenti`
- [ ] `Documento` mappa tutte le **5** colonne di `documenti`
- [ ] gli importi sono `BigDecimal` con converter e normalizzazione a 2 decimali (§6.1)
- [ ] `cod_rata`, `causale_versamento`, `incasso` hanno codec con round-trip testato
- [ ] `spring.jpa.hibernate.ddl-auto=validate` passa contro il DDL reale (§12.1)
- [ ] nessuna dipendenza da Spring nei package `model` e `codec`

## 2. Perimetro

**Dentro:** entità, enum, converter JPA, value object, codec, test di mapping.

**Fuori:** repository e query (F2), servizi di aggiornamento (F3), motore di
caricamento (F4), query di batch (F5), autoconfigurazione (F2, quando servono i
repository).

## 3. Confine con l'anagrafica: solo colonne FK (F1-6)

L'aggregato pendenza **non dichiara relazioni JPA verso l'anagrafica**. Tutte le FK
verso tabelle non appartenenti all'aggregato sono mappate come colonne `Long`:

| Entità | Colonne FK come `Long` |
|---|---|
| `Versamento` | `id_applicazione`, `id_dominio`, `id_uo`, `id_tipo_versamento`, `id_tipo_versamento_dominio` |
| `SingoloVersamento` | `id_tributo`, `id_iban_accredito`, `id_iban_appoggio`, `id_dominio` |

Dentro l'aggregato restano relazioni vere: `Versamento` ↔ `SingoloVersamento`
(`@OneToMany`/`@ManyToOne`) e `Versamento` → `Documento` (`@ManyToOne`).

**Perché.** Una relazione `@ManyToOne` verso `DominioEntity` di `govpay-common`
accoppierebbe il grafo delle entità e la persistence unit: ogni consumatore della
libreria dovrebbe avere quelle entità sul classpath **e** nell'entity scan. La
verifica ha mostrato che non serve:

- il **predicato di visibilità/ACL** lavora su `id_dominio`, `id_uo`,
  `id_tipo_versamento` — colonne FK pure (lo conferma `idx_vrs_auth`);
- i **filtri principali del legacy sono già ID-based**: `VersamentoFilter` ha
  `idDomini: List<Long>`, `idTipiVersamento: List<Long>`, `idUo`, ed è quello che
  `PendenzeDAO` gli passa, perché arrivano dall'autorizzazione;
- **nessun campo di anagrafica è ordinabile** (whitelist: `dataCaricamento`,
  `dataValidita`, `dataScadenza`, `stato`);
- le **query dei batch** filtrano solo colonne di `versamenti`;
- il **calcolo delle date di avvisatura** ha bisogno di 6 valori del
  `TipoVersamentoDominio`, non dell'entità da 57 colonne: arrivano nel comando di
  caricamento come record `ConfigurazioneAvvisatura` (F4), il che rende
  `CalcoloAvvisatura` una funzione pura.

**Prezzo, dichiarato:** niente fetch-join sull'anagrafica. Un consumatore che deve
mostrare `codDominio` in una lista risolve gli id distinti a parte (i domini sono
pochi e cacheabili — il legacy li tiene in `AnagraficaManager` con cache). I criteri di
ricerca accettano `idDominio`/`idTipiVersamento` come `Long`: chi ha un codice lo
risolve prima, con una query che di norma deve fare comunque per autorizzare.

**Conseguenza:** l'issue `link-it/govpay-common#9` (6 entità di anagrafica) **non è un
prerequisito** di F1. Resta utile per altri motivi (permette a `govpay-console-api` di
dismettere le proprie copie) ed è slegata da questa fase.

**Unica dipendenza verso `govpay-common`** oltre alle utility: l'enum
`TipoContabilita` (§5), condiviso con `tributi`/`tipi_tributo`. È una dipendenza di
tipo, non di mapping. Se al momento di implementare F1 non è ancora rilasciato lì, si
definisce localmente e si allinea al primo rilascio utile.

## 4. File da creare

```
it.govpay.pendenze
├── model/
│   ├── StatoVersamento.java              enum
│   ├── StatoPagamento.java               enum
│   ├── StatoSingoloVersamento.java       enum
│   ├── TipologiaTipoVersamento.java      enum
│   ├── StatoPendenzaApplicativo.java     enum (derivato, non persistito)
│   ├── TipoSoggetto.java                 enum F/G
│   ├── TipoSoglia.java                   enum
│   ├── TipoContabilita.java              enum
│   ├── TipoBollo.java                    enum
│   ├── IdentificativoPendenza.java       record
│   ├── RataOSoglia.java                  sealed interface + record NumeroRata, Soglia
│   ├── Causale.java                      sealed interface + record Semplice, Spezzoni, SpezzoniConImporto
│   └── ProprietaPendenza.java            record (JSON)
├── codec/
│   ├── CausaleCodec.java
│   ├── CodRataCodec.java
│   └── ProprietaPendenzaCodec.java
├── entity/
│   ├── Versamento.java
│   ├── SingoloVersamento.java
│   ├── Documento.java
│   └── converter/
│       ├── ImportoConverter.java
│       ├── IncassoConverter.java
│       ├── TipoSoggettoConverter.java
│       ├── TipoContabilitaConverter.java
│       └── TipoBolloConverter.java
└── (test)
    ├── entity/VersamentoMappingTest.java
    ├── entity/converter/ImportoConverterTest.java
    ├── codec/CausaleCodecTest.java
    ├── codec/CodRataCodecTest.java
    └── model/StatoPendenzaApplicativoTest.java
```

## 5. Enum e codifiche

| Enum | Valori | Colonna | Codifica DB | Strategia |
|---|---|---|---|---|
| `StatoVersamento` | `NON_ESEGUITO`, `ESEGUITO`, `PARZIALMENTE_ESEGUITO`, `ANNULLATO`, `ESEGUITO_ALTRO_CANALE`, `ANOMALO`, `ESEGUITO_SENZA_RPT`, `INCASSATO` | `stato_versamento` | = nome enum | `@Enumerated(STRING)` |
| `StatoPagamento` | `NON_PAGATO`, `PAGATO`, `INCASSATO` | `stato_pagamento` | = nome enum | `@Enumerated(STRING)` |
| `StatoSingoloVersamento` | `NON_ESEGUITO`, `ESEGUITO` | `stato_singolo_versamento` | = nome enum | `@Enumerated(STRING)` |
| `TipologiaTipoVersamento` | `DOVUTO`, `SPONTANEO` | `tipo` | = nome enum | `@Enumerated(STRING)` |
| `TipoSoggetto` | `PERSONA_FISICA`, `PERSONA_GIURIDICA` | `debitore_tipo` | `F`, `G` | converter |
| `TipoContabilita` | `CAPITOLO`, `SPECIALE`, `SIOPE`, `SRTP_ESCLUSA_RAVV_OPEROSO`, `SRTP_ESCLUSA_ALTRO_OPERATORE`, `SRTP_ESCLUSA`, `ALTRO` | `tipo_contabilita` | `0`,`1`,`2`,`6`,`7`,`8`,`9` | converter |
| `TipoBollo` | `IMPOSTA_BOLLO` | `tipo_bollo` | `01` (JSON: `"Imposta di bollo"`) | converter |
| `TipoSoglia` | `ENTRO`, `OLTRE`, `RIDOTTO`, `SCONTATO` | dentro `cod_rata` | vedi §10.2 | codec |
| `StatoPendenzaApplicativo` | `NON_PAGATA`, `PAGATA`, `PAGATA_PARZIALE`, `RICONCILIATA`, `ANNULLATA`, `SCADUTA`, `ANOMALA` | — | **non persistito** | derivato (§10.4) |

Le codifiche di `TipoContabilita` e `TipoBollo` vengono da
`core-beans/…/commons/Versamento.java:389-396` e `model/SingoloVersamento.java:37-38`.

**Comportamento su valore ignoto (F1-4, deciso)** — differenziato:

- enum di **stato** (`StatoVersamento`, `StatoPagamento`, `StatoSingoloVersamento`,
  `TipologiaTipoVersamento`): fallire con eccezione. Un valore fuori dominio in queste
  colonne è corruzione di dati, non va mascherato.
- enum **codificati** (`TipoSoggetto`, `TipoContabilita`, `TipoBollo`): `null` + log a
  `WARN`, così una riga anomala non fa fallire un'intera lista.

## 6. Converter JPA

### 6.1 `ImportoConverter` — `BigDecimal` ↔ colonna

Colonne interessate: `versamenti.importo_totale`, `importo_pagato`,
`importo_incassato`, `singoli_versamenti.importo_singolo_versamento`.
Tipo in DB: `DOUBLE PRECISION` (PostgreSQL), `BINARY_DOUBLE` (Oracle), `DOUBLE`
(MySQL), `DECIMAL(15,2)` (SQL Server).

```java
@Converter
public class ImportoConverter implements AttributeConverter<BigDecimal, Double> {

    public static final int SCALA = 2;
    public static final RoundingMode ARROTONDAMENTO = RoundingMode.HALF_UP;

    @Override
    public Double convertToDatabaseColumn(BigDecimal importo) {
        return importo == null ? null : normalizza(importo).doubleValue();
    }

    @Override
    public BigDecimal convertToEntityAttribute(Double valore) {
        // BigDecimal.valueOf, mai new BigDecimal(double): valueOf passa da
        // Double.toString e recupera la rappresentazione decimale piu' corta.
        return valore == null ? null : normalizza(BigDecimal.valueOf(valore));
    }

    public static BigDecimal normalizza(BigDecimal importo) {
        return importo.setScale(SCALA, ARROTONDAMENTO);
    }
}
```

Regole d'uso nel resto della libreria:

- confronti sempre con `compareTo`, mai `equals` (che confronta anche lo scale);
- somme e differenze su `BigDecimal` normalizzati, mai su `double`;
- l'API pubblica accetta ed espone `BigDecimal`; un importo con più di 2 decimali in
  ingresso viene arrotondato `HALF_UP` (comportamento da documentare in F4, dove
  arriva l'input esterno).

### 6.2 `IncassoConverter` — `Boolean` ↔ `'t'`/`'f'`

`versamenti.incasso` è `VARCHAR(1)` con le costanti legacy
`INCASSO_TRUE = "t"` / `INCASSO_FALSE = "f"` (`model/Versamento.java:64-65`).
`null` resta `null` (tri-stato: l'assenza è significativa).

### 6.3 Converter degli enum codificati

`TipoSoggettoConverter` (`F`/`G`), `TipoContabilitaConverter` (`0`…`9`),
`TipoBolloConverter` (`01`), tutti con la politica "ignoto → `null` + WARN" di §5.

## 7. Entità `Versamento` — 65 colonne

```java
@Entity
@Table(name = "versamenti", uniqueConstraints = @UniqueConstraint(
        name = "unique_versamenti_1", columnNames = {"cod_versamento_ente", "id_applicazione"}))
@SequenceGenerator(name = "seq_versamenti", sequenceName = "seq_versamenti", allocationSize = 1)
public class Versamento {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_versamenti")
    @Column(name = "id")
    private Long id;
    ...
}
```

Elenco completo dei campi, per gruppo. `NN` = colonna `NOT NULL`.

**Identità e riferimenti (7)**

| Campo | Colonna | Tipo Java | Note |
|---|---|---|---|
| `id` | `id` | `Long` | sequenza `seq_versamenti` |
| `idApplicazione` | `id_applicazione` NN | `Long` | FK, nessuna relazione (F1-6) |
| `idDominio` | `id_dominio` NN | `Long` | FK, nessuna relazione |
| `idUo` | `id_uo` | `Long` | FK, nessuna relazione |
| `idTipoVersamento` | `id_tipo_versamento` NN | `Long` | FK, nessuna relazione |
| `idTipoVersamentoDominio` | `id_tipo_versamento_dominio` NN | `Long` | FK, nessuna relazione |
| `documento` | `id_documento` | `Documento` (LAZY) | **relazione**: appartiene all'aggregato |

**Anagrafica pendenza (13)**

| Campo | Colonna | Tipo | Note |
|---|---|---|---|
| `codVersamentoEnte` | `cod_versamento_ente` NN | `String(35)` | idPendenza |
| `nome` | `nome` | `String(35)` | |
| `causaleVersamento` | `causale_versamento` | `String(1024)` | grezza; `Causale` via codec (§10.1) |
| `importoTotale` | `importo_totale` NN | `BigDecimal` | `ImportoConverter` |
| `tassonomia` | `tassonomia` | `String(35)` | |
| `tassonomiaAvviso` | `tassonomia_avviso` | `String(35)` | |
| `codLotto` | `cod_lotto` | `String(35)` | |
| `codVersamentoLotto` | `cod_versamento_lotto` | `String(35)` | |
| `codAnnoTributario` | `cod_anno_tributario` | `String(35)` | grezza; `Integer` derivato (§13 F1-3) |
| `codBundlekey` | `cod_bundlekey` | `String(256)` | |
| `datiAllegati` | `dati_allegati` | `String` TEXT | JSON opaco |
| `proprieta` | `proprieta` | `String` TEXT | JSON, codec §10.3 |
| `codRata` | `cod_rata` | `String(35)` | grezza; `RataOSoglia` via codec §10.2 |

**Classificazione (3)**: `divisione`, `direzione` (`String(35)`),
`tipo` (`tipo` NN, `TipologiaTipoVersamento`).

**Debitore (12)**

| Campo | Colonna | Tipo |
|---|---|---|
| `debitoreTipo` | `debitore_tipo` | `TipoSoggetto` (converter) |
| `debitoreIdentificativo` | `debitore_identificativo` NN | `String(35)` |
| `debitoreAnagrafica` | `debitore_anagrafica` NN | `String(70)` |
| `debitoreIndirizzo` | `debitore_indirizzo` | `String(70)` |
| `debitoreCivico` | `debitore_civico` | `String(16)` |
| `debitoreCap` | `debitore_cap` | `String(16)` |
| `debitoreLocalita` | `debitore_localita` | `String(35)` |
| `debitoreProvincia` | `debitore_provincia` | `String(35)` |
| `debitoreNazione` | `debitore_nazione` | `String(2)` |
| `debitoreEmail` | `debitore_email` | `String(256)` |
| `debitoreTelefono` | `debitore_telefono` | `String(35)` |
| `debitoreCellulare` | `debitore_cellulare` | `String(35)` |
| `debitoreFax` | `debitore_fax` | `String(35)` |

**Stato (6)**: `statoVersamento` (NN, `StatoVersamento`), `descrizioneStato`
(`String(255)`), `anomalo` (NN, `boolean`), `anomalie` (TEXT `String`), `ack` (NN,
`boolean`), `aggiornabile` (NN, `boolean`).

**Avviso e IUV (5)**: `iuvVersamento`, `numeroAvviso`, `iuvPagamento`, `srcIuv`,
`srcDebitoreIdentificativo` (NN) — tutti `String(35)`. Le due `src*` sono derivate e
sempre in **uppercase** (B4): la valorizzazione è responsabilità di F3/F4, F1 le mappa.

**Pagamento (5)**: `statoPagamento` (NN, `StatoPagamento`), `importoPagato` (NN,
`BigDecimal`), `importoIncassato` (NN, `BigDecimal`), `dataPagamento`
(`OffsetDateTime`), `incasso` (`Boolean` via `IncassoConverter`).

**Date (4)**

| Campo | Colonna | Tipo | Note |
|---|---|---|---|
| `dataCreazione` | `data_creazione` NN | `OffsetDateTime` | immutabile; ordinamento di default delle liste (B6) |
| `dataValidita` | `data_validita` | `OffsetDateTime` | |
| `dataScadenza` | `data_scadenza` | `OffsetDateTime` | |
| `dataOraUltimoAggiornamento` | `data_ora_ultimo_aggiornamento` NN | `OffsetDateTime` | `@UpdateTimestamp` (§8) |

### 7.1 Fuso orario (F1-2)

Tutte le colonne temporali sono `TIMESTAMP` **senza** time zone
(`TIMESTAMP` su PostgreSQL e Oracle, `DATETIME(3)` su MySQL, `DATETIME2` su
SQL Server). Mappandole su `OffsetDateTime` serve una regola esplicita su quale fuso
applicare in lettura e scrittura, altrimenti si eredita quello della JVM — che è
esattamente il motivo per cui il legacy forza `-Duser.timezone=Europe/Rome` nella
pipeline.

**Decisione:** `OffsetDateTime`, con il fuso preso da una property
dell'applicazione, non dalla JVM.

```yaml
govpay:
  pendenze:
    fuso-orario: Europe/Rome   # default della libreria
```

Realizzazione, due pezzi:

1. **Lato Hibernate** — un `HibernatePropertiesCustomizer` contribuito
   dall'autoconfigurazione imposta `hibernate.jdbc.time_zone` al fuso configurato,
   così la conversione JDBC ↔ `OffsetDateTime` è deterministica e indipendente dalla
   JVM. Se il consumatore ha già impostato
   `spring.jpa.properties.hibernate.jdbc.time_zone`, il suo valore vince (nessun
   override silenzioso).
2. **Lato applicativo** — un bean `Clock` (`Clock.system(zoneId)`) esposto dalla
   libreria e usato da ogni punto che genera un istante (`data_creazione`,
   `data_ora_ultimo_aggiornamento` all'insert, calcolo delle date di avvisatura,
   `statoApplicativo`). Nessun `new Date()`, nessun `OffsetDateTime.now()` senza
   argomenti: i test iniettano un `Clock.fixed`.

Nota per F2/F5: le query dei batch confrontano date con "adesso"
(`data_notifica_avviso <= now`); anche quel `now` viene dal `Clock`, non dal database,
per non introdurre una seconda sorgente di tempo.

### 7.2 `cod_anno_tributario` (F1-3)

Colonna `VARCHAR(35)`, dominio `Integer` (`VersamentoConverter:102-103,239`).
Mappiamo la **colonna grezza** più un accessor derivato tollerante:

```java
@Column(name = "cod_anno_tributario", length = 35)
private String codAnnoTributario;                 // valore persistito, invariato

public Optional<Integer> annoTributario() {       // derivato, tollerante
    // valore non numerico (dato legacy) -> Optional.empty() + log WARN
}
```

Così un valore non conforme già presente in banca dati non fa fallire la lettura della
pendenza, e la scrittura resta letterale.

### 7.3 Relazione con le voci (F1-5)

**Avvisatura (6)**

| Campo | Colonna | Tipo |
|---|---|---|
| `dataNotificaAvviso` | `data_notifica_avviso` | `OffsetDateTime` |
| `avvisoNotificato` | `avviso_notificato` | `Boolean` **tri-stato** |
| `avvMailDataPromemoriaScadenza` | `avv_mail_data_prom_scadenza` | `OffsetDateTime` |
| `avvMailPromemoriaScadenzaNotificato` | `avv_mail_prom_scad_notificato` | `Boolean` tri-stato |
| `avvAppIoDataPromemoriaScadenza` | `avv_app_io_data_prom_scadenza` | `OffsetDateTime` |
| `avvAppIoPromemoriaScadenzaNotificato` | `avv_app_io_prom_scad_notificat` | `Boolean` tri-stato |

> Il nome colonna `avv_app_io_prom_scad_notificat` è troncato a 30 caratteri (limite
> Oracle): va scritto così, senza la `o` finale.
>
> Tri-stato: `null` = nessuna notifica prevista, `false` = da notificare, `true` =
> notificata. Usare `Boolean`, non `boolean`.

**ACA (2)**: `dataUltimaModificaAca`, `dataUltimaComunicazioneAca`
(`OffsetDateTime`). La seconda è scritta **solo** dal batch ACA esterno: in `../govpay`
non viene mai valorizzata (D1).

**Sessione (1)**: `idSessione` (`String(35)`).

```java
@OneToMany(mappedBy = "versamento", cascade = CascadeType.ALL)   // niente orphanRemoval
@OrderBy("indiceDati ASC")
private List<SingoloVersamento> singoliVersamenti = new ArrayList<>();
```

`orphanRemoval` **disattivato**, a differenza della console. Motivo: la regola di
dominio non ammette la rimozione di voci esistenti in aggiornamento
(`validazioneSemanticaAggiornamento` fallisce se le voci nuove sono meno di quelle
lette, e riassegna `indiceDati`). Con `orphanRemoval = true` un semplice
`pendenza.getSingoliVersamenti().remove(v)` — o una lista ricostruita male da un
mapper — cancellerebbe righe in silenzio, aggirando quella regola.

Conseguenze da tenere presenti:

- togliere una voce dalla collezione **non** la cancella dal database: resta orfana
  con `id_versamento` valorizzato. Chi manipola la collezione deve saperlo, e in F3/F4
  la validazione intercetta il caso prima del flush;
- `CascadeType.ALL` resta, quindi `persist`/`merge` propagano alle voci e la
  cancellazione della pendenza cancella le voci (`CascadeType.REMOVE`);
- se in futuro servisse cancellare una voce, si fa esplicitamente dal repository delle
  voci, non manipolando la collezione.

## 8. `data_ora_ultimo_aggiornamento` nell'entità

```java
@UpdateTimestamp
@Column(name = "data_ora_ultimo_aggiornamento", nullable = false)
private OffsetDateTime dataOraUltimoAggiornamento;
```

`@UpdateTimestamp` copre ogni flush di entità dirty. Poiché la colonna è `NOT NULL` e
l'annotazione non interviene alla creazione, l'insert la valorizza esplicitamente
(`= dataCreazione`). Il resto della regola trasversale (voci, documento, allegati,
divieto di update bulk, `Clock` iniettato) è in proposta §6.4 e si realizza in F3/F4.

## 9. `SingoloVersamento` (20 colonne) e `Documento` (5)

`singoli_versamenti` **non ha colonne di data**: nessun timestamp, nessun campo di
aggiornamento (è la ragione per cui `aggiornaStatoVoce` tocca la pendenza padre).

| Campo | Colonna | Tipo |
|---|---|---|
| `id` | `id` | `Long`, sequenza `seq_singoli_versamenti` |
| `versamento` | `id_versamento` NN | `Versamento` (LAZY) |
| `codSingoloVersamentoEnte` | `cod_singolo_versamento_ente` NN | `String(70)` |
| `statoSingoloVersamento` | `stato_singolo_versamento` NN | `StatoSingoloVersamento` |
| `importoSingoloVersamento` | `importo_singolo_versamento` NN | `BigDecimal` |
| `descrizione` | `descrizione` | `String(256)` |
| `descrizioneCausaleRpt` | `descrizione_causale_rpt` | `String(140)` |
| `datiAllegati` | `dati_allegati` | `String` TEXT (JSON opaco) |
| `indiceDati` | `indice_dati` NN | `Integer` |
| `contabilita` | `contabilita` | `String` TEXT (JSON opaco) |
| `metadata` | `metadata` | `String` TEXT (JSON opaco) |
| `tipoBollo` | `tipo_bollo` | `TipoBollo` (converter) |
| `hashDocumento` | `hash_documento` | `String(70)` |
| `provinciaResidenza` | `provincia_residenza` | `String(2)` |
| `tipoContabilita` | `tipo_contabilita` | `TipoContabilita` (converter) |
| `codiceContabilita` | `codice_contabilita` | `String(255)` |
| `idTributo` | `id_tributo` | `Long` (FK, nessuna relazione) |
| `idIbanAccredito` | `id_iban_accredito` | `Long` (FK) |
| `idIbanAppoggio` | `id_iban_appoggio` | `Long` (FK) |
| `idDominio` | `id_dominio` | `Long` (FK) — dominio della voce, per il multibeneficiario |

`contabilita`, `metadata` e `dati_allegati` restano stringhe JSON opache: il legacy le
passa attraverso senza interpretarle (`SingoloVersamentoConverter:70-78`). Non
introduciamo parsing in F1.

`Documento`: `id` (sequenza `seq_documenti`), `codDocumento` (`String(35)` NN),
`descrizione` (`String(255)` NN), `idDominio` NN, `idApplicazione` NN. Unicità
`(cod_documento, id_applicazione, id_dominio)`.

## 10. Value object e codec

### 10.1 `Causale`

Formato persistito: `"<tipo> <base64>[ <base64>…]"` —
`01` semplice, `02` spezzoni, `03` spezzoni con importo.

```java
sealed interface Causale permits Semplice, Spezzoni, SpezzoniConImporto {}
record Semplice(String testo) implements Causale {}
record Spezzoni(List<String> spezzoni) implements Causale {}
record SpezzoniConImporto(List<VoceCausale> voci) implements Causale {}
record VoceCausale(String testo, BigDecimal importo) {}
```

`CausaleCodec`: `decodifica(String) → Optional<Causale>`, `codifica(Causale) → String`,
`sintesi(String) → String` (la `getSimple()` del legacy: per `03` restituisce
`"<importo>: <spezzone>"`). Comportamento da preservare dal
`CausaleVersamentoDecoder` della console: valore non riconosciuto o base64 malformato
→ **restituito verbatim**, mai eccezione (ci sono dati legacy in chiaro).

### 10.2 `RataOSoglia` — `cod_rata` (D8)

```java
sealed interface RataOSoglia permits NumeroRata, Soglia {}
record NumeroRata(int numero) implements RataOSoglia {}
record Soglia(TipoSoglia tipo, Integer giorni) implements RataOSoglia {}
```

| Valore colonna | Decodifica |
|---|---|
| `"3"` | `NumeroRata(3)` |
| `"ENTRO15"` | `Soglia(ENTRO, 15)` |
| `"OLTRE30"` | `Soglia(OLTRE, 30)` |
| `"RIDOTTO"` | `Soglia(RIDOTTO, null)` |
| `"SCONTATO"` | `Soglia(SCONTATO, null)` |
| `null` / vuoto | `Optional.empty()` |
| altro | `Optional.empty()` + WARN (dato legacy non conforme) |

Regole di codifica (validate in ingresso): `ENTRO`/`OLTRE` richiedono `giorni` non
nullo e positivo; `RIDOTTO`/`SCONTATO` non ammettono `giorni`; il risultato deve
stare in 35 caratteri. Riferimento legacy:
`bd/model/converter/VersamentoConverter.java:143-157` (lettura) e `:285-290`
(scrittura).

### 10.3 `ProprietaPendenza` — JSON in `proprieta`

Record con gli 8 campi del bean legacy
(`core-beans/…/tracciati/ProprietaPendenza.java:45-67`): `linguaSecondaria`,
`descrizioneImporto`, `lineaTestoRicevuta1`, `lineaTestoRicevuta2`,
`linguaSecondariaCausale`, `informativaImportoAvviso`,
`linguaSecondariaInformativaImportoAvviso`, `dataScandenzaAvviso`.

> **I nomi delle proprietà JSON vanno preservati alla lettera, refuso incluso:**
> l'ultimo campo è `@JsonProperty("dataScandenzaAvviso")` — "Scandenza", non
> "Scadenza". Rinominarlo renderebbe illeggibili i record già persistiti.

Serializzazione con Jackson 3 (`tools.jackson.databind`), coerente con il BOM
(`jackson.version=3.2.1`) e con `govpay-console-api`; i bean legacy usano Jackson 2
(`com.fasterxml.jackson`), quindi va verificato che i nomi prodotti coincidano.

### 10.4 `StatoPendenzaApplicativo` — derivato (A6)

Non persistito. Regola già in uso nella console (`PendenzaMapper.mapStato`):

```java
StatoPendenzaApplicativo statoApplicativo(OffsetDateTime riferimento)
```

- `stato_versamento` → mappatura diretta (`ESEGUITO`→`PAGATA`,
  `PARZIALMENTE_ESEGUITO`→`PAGATA_PARZIALE`, `INCASSATO`→`RICONCILIATA`,
  `ANNULLATO`→`ANNULLATA`, `ANOMALO`→`ANOMALA`, `NON_ESEGUITO`→`NON_PAGATA`);
- eccezione: `NON_ESEGUITO` con `data_scadenza` precedente al riferimento → `SCADUTA`.

`riferimento` è un parametro (non `now()` interno) per rendere il calcolo testabile e
coerente con il `Clock` della libreria.

### 10.5 `IdentificativoPendenza`

```java
record IdentificativoPendenza(String idA2A, String idPendenza) {}
```

Chiave logica della pendenza (`unique_versamenti_1`), usata come parametro delle API di
F2/F3 al posto della coppia di stringhe sciolte.

## 11. Convenzioni di codifica

- **Nessun Lombok sulle entità:** getter/setter espliciti come in
  `govpay-console-api`. `@Data` genererebbe `equals`/`hashCode` su tutti i campi, con i
  noti problemi su entità JPA e relazioni LAZY.
- `equals`/`hashCode` sulle entità: solo su `id`, con `id == null` → identità di
  istanza. `toString` senza relazioni LAZY (niente `dominio`, `applicazione`,
  `singoliVersamenti`) per non innescare caricamenti o `LazyInitializationException`.
- `model` e `codec`: solo `java.*` (record, sealed interface, enum). Nessun import
  Spring, JPA o Jackson **nei record** — la serializzazione sta nei codec.
- Nessun `@Version`: non introduciamo optimistic locking, che richiederebbe una colonna
  nuova (Q7/Q12 già decise).
- Nomi dei campi allineati alle colonne (`avvAppIoPromemoriaScadenzaNotificato` per
  `avv_app_io_prom_scad_notificat`), doppio vocabolario mantenuto (A2): entità
  `Versamento`/`SingoloVersamento` su tabelle `versamenti`/`singoli_versamenti`.
- **Nessun istante generato in linea:** mai `new Date()`, `Instant.now()` o
  `OffsetDateTime.now()` senza argomento. L'istante viene sempre dal `Clock` iniettato
  (§7.1), così il fuso è quello configurato e i test sono deterministici.
- `PendenzeProperties` (`@ConfigurationProperties("govpay.pendenze")`) è l'unico punto
  di configurazione della libreria; in F1 contiene solo `fuso-orario`.

## 12. Test di F1

### 12.1 Validazione del mapping contro il DDL reale

Il test che dà valore a F1 non è un round-trip su schema generato da Hibernate: è la
**validazione contro il DDL di produzione**. Con `ddl-auto=create-drop` (come fa la
console) un errore di nome colonna non emerge, perché lo schema viene generato dalle
entità stesse.

Proposta: profilo di test che carica il DDL reale in H2 e usa
`spring.jpa.hibernate.ddl-auto=validate`.

```properties
spring.datasource.url=jdbc:h2:mem:pendenze;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
spring.jpa.hibernate.ddl-auto=validate
spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:db/schema-pendenze-test.sql
```

**Sorgente del DDL: la variante PostgreSQL** (era F1-1, ora risolto). `govpay`
distribuisce anche un DDL HSQL, ma usa sintassi non supportata da H2:

```sql
-- hsql/gov_pay.sql — HSQLDB-specifico, H2 non lo accetta
id BIGINT GENERATED BY DEFAULT AS SEQUENCE seq_versamenti,

-- postgresql/gov_pay.sql — funziona in H2 con MODE=PostgreSQL
CREATE SEQUENCE seq_versamenti start 1 increment 1 ...;
id BIGINT DEFAULT nextval('seq_versamenti') NOT NULL,
```

Si estrae quindi dal DDL PostgreSQL il sottoinsieme necessario (`versamenti`,
`singoli_versamenti`, `documenti`, `domini`, `applicazioni`, `uo`,
`tipi_versamento`, `tipi_vers_domini`, `iban_accredito`, `tributi`, `tipi_tributo`
+ le rispettive sequenze) e si versiona in
`src/test/resources/db/schema-pendenze-test.sql`, con provenienza e versione GovPay
annotate in testa al file. È anche la scelta già fatta da `govpay-console-api`
(`MODE=PostgreSQL`).

### 12.2 Casi di test

| Test | Casi |
|---|---|
| `VersamentoMappingTest` | persist + reload con **tutte** le colonne valorizzate; insert con i soli campi `NOT NULL`; verifica che `avv_app_io_prom_scad_notificat` sia scritta; unicità `(cod_versamento_ente, id_applicazione)` |
| `ImportoConverterTest` | round-trip di `10.20`, `0.01`, `1234567.89`, `0.00`, `null`; `9.995` → `10.00` (HALF_UP); confronto `compareTo` fra valore salvato e riletto; regressione: `new BigDecimal(0.1)` ≠ `BigDecimal.valueOf(0.1)` |
| `IncassoConverterTest` | `true`→`t`, `false`→`f`, `null`→`null`, valore ignoto in colonna |
| `CausaleCodecTest` | round-trip `01`, `02`, `03`; `sintesi` per i tre tipi; base64 malformato → verbatim; testo in chiaro legacy → verbatim; `null` |
| `CodRataCodecTest` | `"3"`, `"ENTRO15"`, `"OLTRE30"`, `"RIDOTTO"`, `"SCONTATO"`, `null`, `""`, `"PIPPO"`; codifica con `giorni` mancante su `ENTRO` → errore; `RIDOTTO` con giorni → errore; round-trip |
| `ProprietaPendenzaCodecTest` | round-trip; **verifica del nome `dataScandenzaAvviso`** nel JSON prodotto; deserializzazione di un JSON di esempio prodotto dal legacy |
| `StatoPendenzaApplicativoTest` | tutte le mappature dirette; `NON_ESEGUITO` + scadenza passata → `SCADUTA`; `NON_ESEGUITO` + scadenza futura → `NON_PAGATA`; scadenza `null` → `NON_PAGATA`; scadenza uguale al riferimento (caso limite: quale verso?) |
| `EnumConverterTest` | `F`/`G`; `0`,`1`,`2`,`6`,`7`,`8`,`9`; `01`; valore ignoto → `null` + WARN; stato ignoto → eccezione (F1-4) |
| `FusoOrarioTest` (F1-2) | scrittura e rilettura di un istante con `Clock.fixed` e `fuso-orario=Europe/Rome`: il valore riletto coincide; ripetizione con la JVM su `UTC` per dimostrare che il risultato **non** dipende dal fuso della JVM; verifica che una `hibernate.jdbc.time_zone` già impostata dal consumatore non venga sovrascritta |
| `AnnoTributarioTest` (F1-3) | `"2026"` → `Optional.of(2026)`; `"XX"` → `Optional.empty()` + WARN; `null` → `Optional.empty()`; la colonna riletta è identica a quella scritta (nessuna normalizzazione) |
| `VociNonCancellabiliTest` (F1-5) | rimuovendo una voce dalla collezione e facendo flush, la riga **resta** in `singoli_versamenti` (assenza di `orphanRemoval`); cancellando la pendenza le voci vengono cancellate (`CascadeType.REMOVE`) |

### 12.3 Come procurarsi dati realistici

`govpay-console-api` ha `src/test/resources/data-pendenze-test.sql`: fixture già
scritte contro le stesse tabelle, riusabili come base per i test di mapping.

## 13. Punti aperti di F1

Nessuno: F1-1…F1-6 sono chiusi (tabella in §1). Il disegno è implementabile e **non ha
prerequisiti esterni**.

`link-it/govpay-common#9` (6 entità di anagrafica) resta aperta come miglioria a sé,
slegata da questa fase (§3). L'unico contatto è l'enum `TipoContabilita`, con fallback
locale se non ancora disponibile.

**Decisioni rinviate alle fasi successive**, elencate qui per non perderle:

| Fase | Da decidere in fase |
|---|---|
| F2 | composizione dei `ProfiloFetch` in entity graph (solo voci e documento: l'anagrafica non è nel grafo); firma dei criteri di ricerca con `idDominio`/`idTipiVersamento` come `Long` |
| F3 | dove intercettare la regola "voci non rimovibili" prima del flush (§7.3); arrotondamento `HALF_UP` sugli importi in ingresso (§6.1) |
| F4 | valorizzazione di `src_iuv`/`src_debitore_identificativo` in uppercase (B4); record `ConfigurazioneAvvisatura` in ingresso al comando di caricamento (§3) e calcolo delle date di avvisatura (proposta §4.6) |
