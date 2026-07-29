# Proposta di disegno — `govpay-common-pendenze`

Documento di proposta. Presuppone l'analisi in
[analisi-legacy-pendenze.md](analisi-legacy-pendenze.md).

- **Sorgenti:** `../govpay` (3.10.x, `305365385`) per il comportamento e lo schema,
  `../govpay-console-api` (`main`, `f6634d1`) per l'implementazione JPA già esistente,
  `../govpay-common` (2.0.3-SNAPSHOT) per le entità di anagrafica e le utility
- **Data:** 2026-07-28

## 1. Decisioni consolidate

| ID | Decisione |
|---|---|
| D1 | **ACA:** la libreria valorizza solo le colonne; il batch ACA resta esterno |
| D2 | **Motore di caricamento** riportato e ammodernato |
| D3 | **Update** nella libreria, con metodi precisi per tipo |
| D4 | **Metodi ibridi** riportati e documentati |
| D5 | **Ricerche** di lista e dettaglio fornite dalla libreria |
| D6 | **Entità completa:** tutte le 65 colonne di `versamenti` mappate; l'analisi su cosa eliminare si fa in un secondo momento |
| D7 | **Flag di innesco batch:** in creazione vanno valorizzati tutti i flag che fanno prendere in carico la pendenza da un batch |
| D8 | **`cod_rata`:** la gestione complessa (rata / soglia) va riportata, serve in caricamento |
| D9 | **Aggregato** = pendenza + voci; documento e allegati come repository separati |
| D10 | **`data_ora_ultimo_aggiornamento` e audit** su tutte le scritture, nessuna eccezione (§6.4) — chiude le lacune U8/U9/U10 del legacy |
| D11 | **`@Transactional`** con propagazione, niente gestione manuale della connessione |
| D12 | **Ricerca su un'unica sorgente**, JPA/Hibernate |
| A1 | **RPT e pagamenti non nel dettaglio:** li richiede il consumatore |
| A2 | **Doppio vocabolario mantenuto:** entità `Versamento`/`SingoloVersamento` |
| A4 | **`src_*` come oggi**, con normalizzazione **uppercase** su tutti i percorsi (§4.5) |
| A6 | **Stato `SCADUTA` applicativo:** derivato, non persistito |
| B1 | **Anagrafica in `govpay-common`**, ma **senza relazioni JPA** da questa libreria: le FK sono colonne `Long` (§4.8). L'issue `govpay-common#9` non è un prerequisito |
| B2 | **`IuvUtils` consolidato in `govpay-common`**, qui solo la SPI di generazione (§3.1) |
| B5 | **Package:** `it.govpay.pendenze` |
| A5 | **Risveglio dei batch: evento Spring.** Il caricamento pubblica `PendenzaCaricata` via `ApplicationEventPublisher`; il consumatore decide cosa farne. Il dirty flag statico `Operazioni.setEseguiGestionePromemoria()` **non** viene riportato (§6.1) |
| B6 | **Ordinamento di default: `data_creazione DESC`**, come nel legacy — nessun indice nuovo (§7.2) |

## 2. Sorgente della ricerca: la vista `v_versamenti` non serve

Nella prima ipotesi avevo proposto di unificare le ricerche sulla vista; le evidenze
portano alla conclusione opposta.

**La vista è solo un LEFT JOIN.** Definizione in
`govpay/src/main/resources/db/sql/postgresql/gov_pay.sql:1649-1717` (le 65 colonne di
`versamenti` sono elencate una per una, qui abbreviate):

```sql
CREATE VIEW v_versamenti AS
SELECT versamenti.*, documenti.cod_documento, documenti.descrizione AS doc_descrizione
  FROM versamenti LEFT JOIN documenti ON versamenti.id_documento = documenti.id;
```

Nessuna aggregazione, nessun filtro: in JPA è `@ManyToOne documento` + fetch join.
`govpay-console-api` infatti **non usa la vista**: legge `versamenti` con
`@EntityGraph` (`VersamentoRepository:24-27`).

**Proposta:** unica sorgente = tabella `versamenti`, un solo oggetto criteri,
`documento` caricato via entity graph. Elimina la duplicazione dei due
`VersamentoFilter` (~2.140 righe) e la divergenza `countPendenze`/`listaPendenze`.

## 3. Riuso di codice esistente

### 3.1 Da `govpay-common` (dipendenza già disponibile)

`govpay-common` contiene già entità JPA e utility riusabili — non vanno duplicate:

| Elemento | Contenuto | Uso nella nuova libreria |
|---|---|---|
| `entity/DominioEntity` (`domini`) | include `aux_digit`, `segregation_code`, `iuv_prefix`, `gln`, `cbill` | **parametro** dei servizi che ne hanno bisogno (verifica numero avviso), **non** relazione JPA (§4.8) |
| `entity/ApplicazioneEntity` (`applicazioni`) | | idem |
| `entity/ConfigurazioneEntity`, `ConnettoreEntity`, `IntermediarioEntity`, `StazioneEntity` | | contesto |
| `repository/DominioRepository`, `ApplicazioneRepository`, … | Spring Data | lookup di anagrafica |
| `utils/IuvUtils` | `isIuvInterno(codDominio, auxDigit, segregationCode, iuv)`, `isIuvInterno(DominioEntity, iuv)`, `isNumeric` | verifica IUV |

**A3 — dove mettere `IuvUtils`:** in `govpay-common` esiste già, ma copre solo la
*verifica*. Le funzioni pure che mancano sono sparse in tre posti:

| Funzione | Oggi in |
|---|---|
| `toNumeroAvviso(iuv, dominio)`, `toIuv(numeroAvviso)`, `checkIuvNumerico` | `govpay/jars/core/…/utils/IuvUtils` |
| `buildQrCode002`, `buildBarCode` | `govpay-console-api/…/pendenza/IuvUtils` (riscritte "byte-per-byte" dalla V1) |
| `isIuvInterno`, `isNumeric` | `govpay-common/…/utils/IuvUtils` |

Proposta: **consolidare le funzioni pure in `govpay-common.utils.IuvUtils`**
(conversione IUV↔numero avviso, check, QR-code, bar-code: input primitivi +
`DominioEntity`, nessuna dipendenza da pendenza o DB). La **generazione** dell'IUV
(che richiede sequenze e configurazione dominio/applicazione) resta fuori: in questa
libreria è la porta SPI `GeneratoreIuv`. Così `govpay-common-pendenze` non duplica
nulla e la console può dismettere la sua copia.

### 3.2 Da `govpay-console-api` — riusabile con adattamenti

| File | Righe | Adattamento |
|---|---|---|
| `entity/Versamento.java` | 511 | **completare a 65 colonne** (§4.1), importi → `BigDecimal`, stati → enum |
| `entity/SingoloVersamento.java` | 245 | idem |
| `entity/Documento.java` | 48 | ok |
| `repository/VersamentoRepository.java` | 35 | estendere con le lookup per chiave (§7.1) |
| `pendenza/PendenzaSpecifications.java` | 50 | estendere ai ~20 criteri legacy (§7.2) |
| `pendenza/PendenzaSortParser.java` | 60 | **cambiare il default** in `-dataCaricamento` (`data_creazione DESC`, §7.2) |
| `pagination/CursorCodec.java` + `BadCursorException` | 68 | ok as-is; cambia il **significato** del timestamp nel cursore: `data_creazione` invece di `data_ora_ultimo_aggiornamento` |
| `common/CausaleVersamentoDecoder.java` | 59 | ok, aggiungere l'encoder (§4.4) |
| `PendenzaService.findSlice/findByCursor/summaryEntityGraph` | ~90 | spostare in repository custom |
| `security/VersamentoVisibilita.java` | ~90 | generalizzare via SPI (§10) |
| `PendenzaMapper.mapStato` | ~40 | diventa regola di dominio derivata (§4.7) |
| `audit/AuditService` + `entity/GpAudit` | ~120 | riusare il pattern (§10) |

Le entità di anagrafica della console (`Dominio`, `Applicazione`, …) **non** si
riportano: si usano quelle di `govpay-common` (§3.1).

Non si riportano: `PendenzaController`, `PendenzaLinksBuilder`,
`PendenzaExpandConverter`, la parte di `PendenzaMapper` verso i bean OpenAPI.

## 4. Modello dati

### 4.1 Entità `Versamento` completa — 65 colonne (D6)

La tabella ha 65 colonne; l'entità della console ne mappa 42. Mappatura proposta
(tipo DB da `gov_pay.sql` PostgreSQL):

**Identità e chiavi (7)**

| Colonna | DB | Java |
|---|---|---|
| `id` | `BIGINT` `seq_versamenti` | `Long` |
| `id_applicazione` | `BIGINT NOT NULL` | `Long` (FK, nessuna relazione — §4.8) |
| `id_dominio` | `BIGINT NOT NULL` | `Long` (FK) |
| `id_uo` | `BIGINT` | `Long` (FK) |
| `id_tipo_versamento` | `BIGINT NOT NULL` | `Long` (FK) |
| `id_tipo_versamento_dominio` | `BIGINT NOT NULL` | `Long` (FK) |
| `id_documento` | `BIGINT` | `Documento` (`@ManyToOne` LAZY — dentro l'aggregato) |

Vincolo di unicità logica: `unique_versamenti_1 (cod_versamento_ente, id_applicazione)`.

**Anagrafica pendenza (12)**

| Colonna | DB | Java | Nota |
|---|---|---|---|
| `cod_versamento_ente` | `VARCHAR(35) NOT NULL` | `String` | idPendenza |
| `nome` | `VARCHAR(35)` | `String` | non mappata dalla console |
| `causale_versamento` | `VARCHAR(1024)` | `String` grezza + `Causale` derivata | codec §4.4 |
| `importo_totale` | `DOUBLE PRECISION NOT NULL` | `BigDecimal` | §4.2 |
| `tassonomia`, `tassonomia_avviso` | `VARCHAR(35)` | `String` | |
| `cod_lotto`, `cod_versamento_lotto` | `VARCHAR(35)` | `String` | |
| `cod_anno_tributario` | `VARCHAR(35)` | `Integer` | conversione: il dominio usa `Integer` |
| `cod_bundlekey` | `VARCHAR(256)` | `String` | |
| `dati_allegati` | `TEXT` | `String` (JSON) | |
| `proprieta` | `TEXT` | `String` + `ProprietaPendenza` | JSON |
| `divisione`, `direzione` | `VARCHAR(35)` | `String` | |
| `cod_rata` | `VARCHAR(35)` | `String` + `numeroRata`/`tipoSoglia`/`giorniSoglia` | codec §4.3 |
| `tipo` | `VARCHAR(35) NOT NULL` | `TipologiaTipoVersamento` (`DOVUTO`/`SPONTANEO`) | |

**Debitore (12)** — `debitore_tipo` `VARCHAR(1)` → enum `F`/`G`;
`debitore_identificativo` e `debitore_anagrafica` `NOT NULL`;
`debitore_indirizzo`, `civico`, `cap`, `localita`, `provincia`, `nazione`, `email`,
`telefono`, `cellulare`, `fax` → `String`. `telefono` e `fax` non sono mappati dalla
console.

**Stato (6)**

| Colonna | DB | Java |
|---|---|---|
| `stato_versamento` | `VARCHAR(35) NOT NULL` | `StatoVersamento` (enum + converter) |
| `descrizione_stato` | `VARCHAR(255)` | `String` |
| `anomalo` | `BOOLEAN NOT NULL` | `boolean` |
| `anomalie` | `TEXT` | `String` |
| `ack` | `BOOLEAN NOT NULL` | `boolean` |
| `aggiornabile` | `BOOLEAN NOT NULL` | `boolean` (se decorsa `data_scadenza`, indica se aggiornare da remoto) |

**Avviso e IUV (5)** — `iuv_versamento`, `numero_avviso`, `iuv_pagamento` `VARCHAR(35)`
→ `String`; `src_iuv` e `src_debitore_identificativo` derivate (§4.5).

**Pagamento (5)**

| Colonna | DB | Java |
|---|---|---|
| `stato_pagamento` | `VARCHAR(35) NOT NULL` | `StatoPagamento` (`NON_PAGATO`/`PAGATO`/`INCASSATO`) |
| `importo_pagato`, `importo_incassato` | `DOUBLE PRECISION NOT NULL` | `BigDecimal` |
| `data_pagamento` | `TIMESTAMP` | `OffsetDateTime` |
| `incasso` | `VARCHAR(1)` | `Boolean` — codifica `'t'`/`'f'` (`Versamento.INCASSO_TRUE`/`INCASSO_FALSE`), converter dedicato |

**Date (4)** — `data_creazione` e `data_ora_ultimo_aggiornamento` `NOT NULL`,
`data_validita`, `data_scadenza` → `OffsetDateTime`.

**Avvisatura (6)** e **ACA (2)** e **sessione (1)**: vedi §4.6.

### 4.2 Importi: `BigDecimal`, con normalizzazione a 2 decimali

**Risposta:** `BigDecimal`. Nessuna novità di JDK 21 o 25 cambia questa scelta: la
JDK non ha un tipo monetario (JSR-354 `javax.money` non è nella piattaforma e
aggiungerebbe una dipendenza), e `double` resta inadeguato perché in base 2 non
rappresenta esattamente i decimali (`0.1`, `10.20`). La libreria somma le voci e le
confronta con il totale (`validazioneSemantica`): con `double` quel confronto può
fallire per errore di rappresentazione.

**Ma il tipo in DB non è decimale su 3 DB su 4** — questo è il punto da gestire:

| DB | `importo_totale` / `importo_pagato` / `importo_incassato` |
|---|---|
| PostgreSQL | `DOUBLE PRECISION` |
| Oracle | `BINARY_DOUBLE` |
| MySQL | `DOUBLE` |
| SQL Server | `DECIMAL(15,2)` |

Anche `singoli_versamenti.importo_singolo_versamento` è `DOUBLE PRECISION`.
Il legacy usa già `BigDecimal` nel modello e converte al confine, in modo però non
uniforme: `BigDecimal.valueOf(vo.getImportoTotale())` per il totale
(`VersamentoConverter:78`, corretto) e assegnazione diretta per pagato/incassato
(`:135-136`).

**Regola proposta:**

1. `BigDecimal` in dominio, entità e API pubblica.
2. `AttributeConverter` unico per gli importi, che in scrittura fa
   `valore.setScale(2, RoundingMode.HALF_UP)` e in lettura
   `BigDecimal.valueOf(double).setScale(2, RoundingMode.HALF_UP)`.
3. **Mai `new BigDecimal(double)`**: `new BigDecimal(0.1)` dà
   `0.1000000000000000055511151231257827…`, mentre `BigDecimal.valueOf(0.1)` dà `0.1`
   (usa `Double.toString`, che garantisce il round-trip alla rappresentazione decimale
   più corta).
4. Con scale 2 il round-trip attraverso `double` è affidabile per qualunque importo
   monetario realistico: la mantissa a 53 bit copre valori fino a ~9·10¹⁵.
5. Confronti sempre con `compareTo`, mai `equals` (che confronta anche lo scale).

Su SQL Server la colonna è già `DECIMAL(15,2)` e la conversione è identità.

### 4.3 `cod_rata`: colonna sovraccaricata (D8)

Una sola colonna `VARCHAR(35)` codifica **due informazioni alternative**
(`bd/model/converter/VersamentoConverter.java:143-157` in lettura, `:285-290` in
scrittura):

| Valore in colonna | Significato nel dominio |
|---|---|
| `"3"` (numerico) | `numeroRata = 3` |
| `"ENTRO<gg>"` es. `ENTRO15` | `tipoSoglia = ENTRO`, `giorniSoglia = 15` |
| `"OLTRE<gg>"` es. `OLTRE30` | `tipoSoglia = OLTRE`, `giorniSoglia = 30` |
| `"RIDOTTO"` | `tipoSoglia = RIDOTTO` (nessun giorno) |
| `"SCONTATO"` | `tipoSoglia = SCONTATO` (nessun giorno) |
| `null` | nessuna delle due |

Serve in caricamento perché il tipo soglia determina il comportamento di pagabilità
(cfr. `VersamentoUtils.getTipoSogliaPagamento`, `getGiorniSogliaPagamento`,
`isNumeroRata`). Proposta: `CodRataCodec` con

```java
sealed interface RataOSoglia permits NumeroRata, Soglia {}
record NumeroRata(int numero) implements RataOSoglia {}
record Soglia(TipoSoglia tipo, Integer giorni) implements RataOSoglia {}

Optional<RataOSoglia> decodifica(String codRata);
String codifica(RataOSoglia valore);
```

L'entità conserva la colonna grezza; il dominio espone il valore decodificato.
Validazione in ingresso: `ENTRO`/`OLTRE` richiedono i giorni, `RIDOTTO`/`SCONTATO`
non li ammettono, il numerico deve stare in `VARCHAR(35)`.

### 4.4 `causale_versamento`

Formato `"<tipo> <base64>[ <base64>…]"`:
`01` causale semplice, `02` spezzoni, `03` spezzoni con importo
(`CausaleVersamentoDecoder` della console documenta i tre casi). La console ha solo il
**decoder**; per il caricamento serve anche l'**encoder**, e la forma sintetica
`getSimple()` va mantenuta identica (per `03` restituisce `"<importo>: <spezzone>"`).
Valori non riconosciuti (dati legacy in chiaro) vanno restituiti verbatim senza
eccezioni, come fa oggi.

### 4.5 `src_iuv` e `src_debitore_identificativo` (A4)

Colonne denormalizzate per la ricerca case-insensitive. Regola canonica in
`VersamentoConverter.toVO`:

- `src_debitore_identificativo = debitore.codUnivoco.toUpperCase()` (`:233`) — `NOT NULL`
- `src_iuv = (iuvPagamento != null ? iuvPagamento : iuvVersamento).toUpperCase()` (`:272-274`)

Le ricerche confrontano sempre con `.toUpperCase()` del valore cercato
(`VersamentoFilter:240,248,388`). Riportiamo questa regola invariata.

> **Decisione (B4):** l'update puntuale `updateVersamentoIuvNav` (U7) scrive `src_iuv`
> **senza** uppercase (`VersamentiBD:744`), a differenza del converter e di U9
> (`:966`); poiché la ricerca cerca in uppercase, un IUV con lettere minuscole
> assegnato per quella via non è trovabile. La libreria applica la regola canonica
> (sempre uppercase) **su tutti i percorsi**, correggendo il difetto. È l'unica
> divergenza da "come oggi" su queste colonne.
>
> Entrambe le colonne sono indicizzate (`idx_vrs_deb_identificativo`, `idx_vrs_iuv`,
> §7.4): sono nate per questo.

### 4.6 Flag che innescano i batch, da valorizzare in creazione (D7)

Se uno di questi non viene impostato al caricamento, la pendenza **non viene mai
presa in carico** dal batch corrispondente. I flag `*_notificato` sono **tri-stato**:

| Valore | Significato |
|---|---|
| `null` | nessuna notifica prevista |
| `false` | **da notificare** → il batch la seleziona |
| `true` | già notificata |

Per questo l'annullamento li riporta a `null` e il ripristino li rimette a `false`
solo se la data corrispondente è presente.

| Colonna | Valore in creazione | Batch che la consuma | Query |
|---|---|---|---|
| `stato_versamento` | `NON_ESEGUITO` | tutti e tre i batch di avvisatura filtrano su questo | analisi §9.4 |
| `data_notifica_avviso` | `data_creazione` se l'avvisatura avviso è abilitata sul `TipoVersamentoDominio` (mail o AppIO) e il comando non la disabilita | spedizione avviso di pagamento | `avviso_notificato = false AND data_notifica_avviso <= now` |
| `avviso_notificato` | `false` se `data_notifica_avviso` valorizzata, altrimenti `null` | idem | idem |
| `avv_mail_data_prom_scadenza` | `(data_validita ?: data_scadenza) - giorniPreavvisoMail`, se abilitato | promemoria scadenza via mail | `avv_mail_prom_scad_notificato = false AND avv_mail_data_prom_scadenza <= now` |
| `avv_mail_prom_scad_notificato` | `false` se la data è valorizzata, altrimenti `null` | idem | idem |
| `avv_app_io_data_prom_scadenza` | `(data_validita ?: data_scadenza) - giorniPreavvisoAppIO`, se abilitato | promemoria scadenza via AppIO | `avv_app_io_prom_scad_notificat = false AND avv_app_io_data_prom_scadenza <= now` |
| `avv_app_io_prom_scad_notificat` | `false` se la data è valorizzata, altrimenti `null` | idem | idem |
| `data_ultima_modifica_aca` | `now` in creazione; ricalcolata in aggiornamento solo se cambiano campi significativi (`comunicaAggiornamentoPendenzaAllArchivioCentralizzato`) | **batch ACA esterno** (D1) | pendenze con modifica più recente dell'ultima comunicazione |
| `data_ultima_comunicazione_aca` | `null` | scritta **solo** dal batch ACA: in `../govpay` non è mai valorizzata | — |
| `id_sessione` | UUID senza `-` | usato per ritrovare la pendenza appena creata (`AvvisiDAO.checkDisponibilitaAvviso`) | filtro `idSessione` |
| `stato_pagamento` | `NON_PAGATO` | — | colonna `NOT NULL` |
| `importo_pagato`, `importo_incassato` | `0` | — | colonne `NOT NULL` |
| `ack`, `anomalo` | `false` | — | colonne `NOT NULL` |
| `aggiornabile` | dal comando | `VersamentoUtils.aggiornaVersamento` (aggiornamento da EC alla scadenza) | colonna `NOT NULL` |
| `src_debitore_identificativo` | derivata (§4.5) | ricerche | colonna `NOT NULL` |

I giorni di preavviso vengono dal `TipoVersamentoDominio` e, se assenti, dalla
configurazione globale (`Configurazione.getAvvisaturaViaMail().getPromemoriaScadenza().getPreavviso()`).

**Fuori dalla creazione** resta un solo innesco: il "dirty flag" in memoria
(vedi A5, §11).

### 4.7 Stato `SCADUTA` (A6)

`SCADUTA` non esiste in `stato_versamento`: è derivato. Regola già implementata nella
console (`PendenzaMapper.mapStato`): `NON_ESEGUITO` + `data_scadenza` nel passato →
`SCADUTA`. Resta applicativo, ma la libreria espone il valore derivato

```java
StatoPendenzaApplicativo statoApplicativo(OffsetDateTime riferimento);
```

così tutti i consumatori applicano la stessa regola invece di riscriverla, senza che
nulla venga persistito.

### 4.8 Confine con l'anagrafica: nessuna relazione JPA (B1, rivisto)

Le entità mappate da **questa** libreria sono solo quelle dell'aggregato:

| Tabella | Entità |
|---|---|
| `versamenti` | `Versamento` |
| `singoli_versamenti` | `SingoloVersamento` |
| `documenti` | `Documento` (raggruppamento di pendenze: appartiene all'aggregato) |

Tutte le FK verso l'anagrafica (`id_applicazione`, `id_dominio`, `id_uo`,
`id_tipo_versamento`, `id_tipo_versamento_dominio`, e per le voci `id_tributo`,
`id_iban_accredito`, `id_iban_appoggio`, `id_dominio`) sono mappate come **colonne
`Long`**, senza `@ManyToOne`.

**Perché.** Una relazione verso le entità di `govpay-common` accoppierebbe il grafo
delle entità e la persistence unit: ogni consumatore dovrebbe avere quelle entità sul
classpath e nell'entity scan. La verifica ha mostrato che non serve:

| Funzione | Cosa usa davvero |
|---|---|
| Predicato di visibilità/ACL | `id_dominio`, `id_uo`, `id_tipo_versamento` — colonne FK (indice `idx_vrs_auth`) |
| Filtri principali | già ID-based nel legacy: `idDomini`, `idTipiVersamento`, `idUo` (è ciò che `PendenzeDAO` passa, perché arriva dall'autorizzazione) |
| Ordinamenti | nessun campo di anagrafica nella whitelist |
| Query dei batch | solo colonne di `versamenti` |
| Calcolo date di avvisatura | 6 valori del `TipoVersamentoDominio`, che arrivano nel comando di caricamento come record `ConfigurazioneAvvisatura` — così il calcolo è una funzione pura |

**Prezzo, dichiarato:** niente fetch-join sull'anagrafica. Chi deve mostrare
`codDominio` in una lista risolve gli id distinti a parte (domini pochi e cacheabili:
il legacy li tiene in `AnagraficaManager` con cache). I criteri di ricerca accettano
`Long`; chi ha un codice lo risolve prima, con una query che di norma serve comunque
per autorizzare.

**Distinzione utile** fra i tre livelli di dipendenza, per non ricadere nell'equivoco:

| Livello | Comporta |
|---|---|
| Relazione JPA (`@ManyToOne`) | accoppiamento del grafo entità e della persistence unit — **evitato** |
| Dipendenza di tipo (parametro, enum) | nessun accoppiamento di mapping — usata per `TipoContabilita` e per le utility |
| Nessuna dipendenza | colonne `Long` e valori nei comandi — il caso normale |

**Issue `link-it/govpay-common#9`** (`uo`, `tipi_versamento`, `tipi_vers_domini`,
`iban_accredito`, `tributi`, `tipi_tributo`): resta aperta come miglioria a sé — serve a
`govpay-console-api` per dismettere le proprie copie e a chi vuole un accesso JPA
all'anagrafica — ma **non è un prerequisito** di questa libreria. L'unico contatto è
l'enum `TipoContabilita`, condiviso con `tributi`/`tipi_tributo`: sta in
`govpay-common` come dipendenza di tipo, con fallback locale se non ancora rilasciato.

## 5. Struttura dei package

```
it.govpay.pendenze
├── model/            enum, value object, record (no Spring, no JPA)
│   ├── StatoVersamento, StatoPagamento, StatoSingoloVersamento,
│   │   TipologiaTipoVersamento, TipoSoglia, TipoContabilita, TipoBollo,
│   │   StatoPendenzaApplicativo
│   ├── Causale + CausaleCodec, RataOSoglia + CodRataCodec, ProprietaPendenza,
│   │   AnagraficaSoggetto, Contabilita
│   └── IdentificativoPendenza (idA2A + idPendenza)
├── entity/           Versamento, SingoloVersamento, Documento + converter JPA
│                     (importi, incasso, enum). Anagrafica da govpay-common (§4.8)
├── repository/       VersamentoRepository, SingoloVersamentoRepository,
│                     DocumentoRepository, VersamentoQueryRepository (custom)
├── ricerca/          CriteriRicercaPendenze, VersamentoSpecifications,
│                     OrdinamentoPendenze, Paginazione, CursorCodec, ProfiloFetch
├── caricamento/      CaricamentoPendenzaService, ComandoCaricamentoPendenza,
│                     EsitoCaricamento, ValidazioneSemanticaPendenza,
│                     PoliticaCampiNonModificabili, CalcoloAvvisatura
├── aggiornamento/    AggiornamentoPendenzaService, TransizioniStatoVersamento
├── lettura/          RicercaPendenzeService, LetturaPendenzaService
├── spi/              GeneratoreIuv, VisibilitaPendenze, AuditPendenze
├── audit/            AuditPendenzeJpa (default su gp_audit)
└── config/           PendenzeAutoConfiguration, PendenzeProperties
```

`model` e `ricerca` senza dipendenze da Spring/JPA; `entity` non esposta oltre il
confine della libreria. Autoconfigurazione come in `govpay-common`
(`@AutoConfiguration` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`)
con `@EntityScan`/`@EnableJpaRepositories` sui package della libreria.

## 6. Scrittura

### 6.1 Caricamento (D2)

```java
EsitoCaricamento carica(ComandoCaricamentoPendenza comando);

record ComandoCaricamentoPendenza(
        Versamento pendenza,
        boolean generaIuv,
        boolean aggiornaSeEsiste,
        Boolean avvisatura,               // null = default del tipo pendenza
        OffsetDateTime dataAvvisatura,
        boolean soloInMemoria,
        boolean verificaNumeroAvvisoSuDominio,
        ConfigurazioneAvvisatura configurazioneAvvisatura) {}

// I 6 valori che servono al calcolo delle date, letti dal TipoVersamentoDominio
// dal consumatore: cosi' CalcoloAvvisatura e' una funzione pura (§4.8).
record ConfigurazioneAvvisatura(
        boolean promemoriaAvvisoMailAbilitato,
        boolean promemoriaAvvisoAppIoAbilitato,
        boolean promemoriaScadenzaMailAbilitato,
        Integer giorniPreavvisoMail,
        boolean promemoriaScadenzaAppIoAbilitato,
        Integer giorniPreavvisoAppIo) {}

record EsitoCaricamento(Versamento pendenza, boolean creata, String iuv, String numeroAvviso) {}
```

Passi invariati rispetto a `business.Versamento.caricaVersamento` (analisi §4.A.1),
con in più la valorizzazione completa dei flag di §4.6.

Differenze volute: `@Transactional`; IUV via SPI; nessun risveglio diretto dei batch;
nessuna generazione di PDF avviso.

### 6.2 Aggiornamenti precisi (D3)

I 15 metodi già elencati in analisi §11.2, con la mappa verso U1-U10. Regole
trasversali su **tutti**: `data_ora_ultimo_aggiornamento` (D10), audit (D10),
transizione verificata da `TransizioniStatoVersamento`.

### 6.3 Transizioni

```
NON_ESEGUITO → ANNULLATO | ESEGUITO | PARZIALMENTE_ESEGUITO | ANOMALO | ESEGUITO_SENZA_RPT
ANNULLATO    → NON_ESEGUITO
*            → INCASSATO
```

### 6.4 `data_ora_ultimo_aggiornamento`: aggiornata su ogni scrittura (D10, B3)

**Regola:** ogni operazione che modifica una pendenza — o una sua voce, o il suo
documento, o i suoi allegati — aggiorna `versamenti.data_ora_ultimo_aggiornamento`.
Nessuna eccezione.

Copertura completa delle operazioni di scrittura:

| Operazione | Legacy | La aggiornava? | In libreria |
|---|---|---|---|
| Caricamento — inserimento | `insertVersamento` | valorizzata alla creazione | ✅ = `data_creazione` |
| Caricamento — aggiornamento | `updateVersamento(deep)` | ✅ (via `_updateVersamento`) | ✅ |
| `aggiornaDatiPendenza` | `updateVersamento(deep)` | ✅ | ✅ |
| `annulla` | `updateVersamento` | ✅ | ✅ |
| `ripristina`, `aggiornaDescrizioneStato`, `aggiornaPresaInCarico` | `PendenzeDAO.patch` | ✅ (esplicita, `PendenzeDAO:631`) | ✅ |
| `aggiornaStato` | U1 | ✅ | ✅ |
| `marcaAvvisoNotificato` | U2 | ✅ | ✅ |
| `marcaPromemoriaScadenzaMailNotificato` | U3 | ✅ | ✅ |
| `marcaPromemoriaScadenzaAppIoNotificato` | U4 | ✅ | ✅ |
| `azzeraAvvisatura` | U5 | ✅ | ✅ |
| `marcaDaSincronizzareAca` | U6 | ✅ | ✅ |
| `assegnaIuvENumeroAvviso` | U7 | ✅ | ✅ |
| **`aggiornaStatoVoce`** | U8 | ❌ | ✅ **sulla pendenza padre** |
| **`registraEsitoPagamento`** | U9 | ❌ | ✅ |
| **`registraIncasso`** | U10 | ❌ | ✅ |
| Aggiornamento documento / allegati | dentro `updateVersamento(deep)` | ✅ solo perché segue `_updateVersamento` | ✅ esplicito |

Le tre righe in grassetto sono le lacune del legacy che questa regola chiude.

**Nota importante sulle voci:** `singoli_versamenti` **non ha alcuna colonna di
timestamp** (20 colonne, nessuna `data_*`). L'unico posto dove registrare la modifica
di una voce è quindi la pendenza padre: `aggiornaStatoVoce(idVoce, stato)` deve
risalire a `id_versamento` e toccare `versamenti.data_ora_ultimo_aggiornamento`.
Vale anche per gli aggiornamenti di documento e allegati.

**Implementazione proposta — doppia garanzia:**

1. `@UpdateTimestamp` (Hibernate) sul campo dell'entità `Versamento`: si valorizza a
   ogni flush dell'entità dirty, quindi nessuna operazione entity-based può
   dimenticarla.
2. Valorizzazione esplicita all'insert (la colonna è `NOT NULL` e `@UpdateTimestamp`
   non copre la creazione): `data_ora_ultimo_aggiornamento = data_creazione`.
3. Per le operazioni che non passano dall'entità (aggiornamento di una voce, del
   documento, degli allegati) il servizio carica la pendenza padre e la marca
   esplicitamente, così il punto 1 la intercetta.
4. **Divieto di update JPQL/nativi in bulk** senza `SET data_ora_ultimo_aggiornamento`:
   le query bulk bypassano i callback di Hibernate. Se in futuro servisse un update
   massivo, la colonna va messa nel `SET` a mano.
5. L'istante viene da un `Clock` iniettato, non da `new Date()`, così i test sono
   deterministici.

La stessa regola trasversale vale per l'**audit** (D10): tutte le operazioni della
tabella qui sopra emettono un record, non solo l'update completo come oggi
(`_updateVersamento` è l'unico chiamante di `emitAudit`, analisi §9.2 anomalia 2).

## 7. Lettura

### 7.1 Dettaglio (D5, A1)

```java
Optional<Versamento> trovaPerId(long id, ProfiloFetch fetch);
Optional<Versamento> trovaPerIdentificativo(long idApplicazione, String idPendenza, ProfiloFetch fetch);
Optional<Versamento> trovaPerDominioIuv(long idDominio, String iuv, ProfiloFetch fetch);
Optional<Versamento> trovaPerDominioNumeroAvviso(long idDominio, String numeroAvviso, ProfiloFetch fetch);
Optional<Versamento> trovaPerBundlekey(long idApplicazione, String bundlekey, Long idDominio, String cfDebitore);
boolean esiste(long idDominio, String iuv, String idSessione);

enum ProfiloFetch { SOLO_TESTATA, CON_VOCI, COMPLETO }
```

`COMPLETO` = voci + documento. **Nessuna anagrafica nel grafo** (§4.8) e **RPT e
pagamenti esclusi** (A1): li richiede il consumatore con le proprie query.

Le firme prendono `idApplicazione`/`idDominio` come `Long` invece dei codici: senza
relazioni JPA non c'è join su cui filtrare per `cod_dominio`. Chi parte da un codice lo
risolve prima (query cacheabile, che serve comunque per autorizzare). Se in F2 emerge
che questo pesa troppo sui chiamanti, l'alternativa non è aggiungere la relazione, ma
esporre un `RisolutoreAnagrafica` come SPI.

### 7.2 Lista (D5)

```java
PaginaPendenze cerca(CriteriRicercaPendenze criteri, Paginazione paginazione);
long conta(CriteriRicercaPendenze criteri);
long contaConLimite(CriteriRicercaPendenze criteri, int limite);
```

Criteri unificati (analisi §11.4), paginazione offset **e** keyset con cursore opaco.
I criteri su dominio, unità operativa, applicazione e tipo pendenza sono espressi con
`Long` (`idDomini`, `idUo`, `idApplicazione`, `idTipiVersamento`), come già fa il
legacy sui percorsi che contano — quelli alimentati dall'autorizzazione (§4.8). I
criteri per codice (`codDominio`, `codTipoVersamento`) non sono esposti: si risolvono
a monte.

**Ordinamento (B6).** Il legacy dichiara i campi ordinabili e il default in
`ListaPendenzeDTO:36-40`:

```java
this.addSortField("dataCaricamento", VistaVersamento.model().DATA_CREAZIONE);
this.addSortField("dataValidita",    VistaVersamento.model().DATA_VALIDITA);
this.addSortField("dataScadenza",    VistaVersamento.model().DATA_SCADENZA);
this.addSortField("stato",           VistaVersamento.model().STATO_VERSAMENTO);
this.addDefaultSort(VistaVersamento.model().DATA_CREAZIONE, SortOrder.DESC);
```

Quindi il default originale è **`data_creazione DESC`** e `dataUltimoAggiornamento`
**non è** un campo ordinabile del legacy: è stato introdotto dalla console come nuovo
default. Torniamo al default legacy, con tre vantaggi concreti:

1. **Indice già presente:** `idx_vrs_data_creaz ON versamenti (data_creazione DESC)` —
   nessuna modifica allo schema (il punto che aveva aperto B6).
2. **Cursore stabile:** `data_creazione` è immutabile dopo la creazione. Un cursore
   keyset su una colonna che muta (`data_ora_ultimo_aggiornamento`) è instabile: una
   riga aggiornata durante la paginazione si sposta in avanti nell'ordine e può essere
   restituita due volte o saltata. Con `data_creazione` non succede.
3. **Compatibilità:** le liste delle API v1/v2 e del backoffice mantengono l'ordine
   che hanno oggi.

Configurazione risultante:

| Voce | Valore |
|---|---|
| Ordinamento di default | `data_creazione DESC` |
| Campi ordinabili | `dataCaricamento` → `data_creazione`, `dataValidita`, `dataScadenza`, `stato` |
| Chiave del cursore keyset | `(data_creazione DESC, id DESC)` |

`dataUltimoAggiornamento` resta disponibile come campo ordinabile **opzionale** (per
non rompere i consumatori della console che lo usano già), documentando che non è
indicizzato e che non va usato come chiave di cursore.

### 7.3 Query dei batch

```java
List<Versamento> conAvvisoDaSpedire(Paginazione p);                 long contaConAvvisoDaSpedire();
List<Versamento> conPromemoriaScadenzaMailDaSpedire(Paginazione p);  long conta…();
List<Versamento> conPromemoriaScadenzaAppIoDaSpedire(Paginazione p); long conta…();
List<Versamento> diUnTracciato(long idTracciato, Paginazione p);     // join da Operazione
List<Versamento> diUnDocumento(long idDocumento);
List<Versamento> daSincronizzareConAca(Paginazione p);
```

Criteri esatti in analisi §9.4. `versamenti` non ha `id_tracciato`/`id_operazione`: la
relazione è inversa (`operazioni.id_versamento`, `operazioni.id_tracciato`).

### 7.4 Indici esistenti: confermano il disegno

Gli 11 indici su `versamenti` (`gov_pay.sql:641-651`) sono la prova che le ricerche
proposte sono quelle previste dallo schema. Ogni query della libreria deve colpirne uno:

| Indice | Colonne | Serve a |
|---|---|---|
| `idx_vrs_id_pendenza` | `cod_versamento_ente, id_applicazione` | `trovaPerIdentificativo` (chiave logica) |
| `idx_vrs_iuv_dominio` | `iuv_versamento, id_dominio` | `trovaPerDominioIuv`, `trovaPerAvviso` |
| `idx_vrs_deb_identificativo` | `src_debitore_identificativo` | ricerca per debitore/cittadino (§4.5) |
| `idx_vrs_iuv` | `src_iuv` | ricerca per IUV (§4.5) |
| `idx_vrs_auth` | `id_dominio, id_tipo_versamento, id_uo` | predicato di **visibilità ACL** (§10) |
| `idx_vrs_stato_vrs` | `stato_versamento` | filtro per stato |
| `idx_vrs_data_creaz` | `data_creazione DESC` | ordinamento `dataCaricamento` |
| `idx_vrs_prom_avviso` | `avviso_notificato, data_notifica_avviso DESC` | batch avviso di pagamento |
| `idx_vrs_avv_mail_prom_scad` | `avv_mail_prom_scad_notificato, avv_mail_data_prom_scadenza DESC` | batch promemoria mail |
| `idx_vrs_avv_io_prom_scad` | `avv_app_io_prom_scad_notificat, avv_app_io_data_prom_scadenza DESC` | batch promemoria AppIO |
| `idx_vrs_sped_aca` | `data_ultima_modifica_aca DESC, data_ultima_comunicazione_aca DESC` | **batch ACA esterno** — conferma la forma della query (D1) |

Tre conseguenze:

1. Le tre coppie `(*_notificato, *_data_*)` sono indicizzate: il "dirty flag" in
   memoria (A5) è un'ottimizzazione per evitare la query, non una necessità.
2. `idx_vrs_sped_aca` conferma che al batch ACA basta la coppia
   `data_ultima_modifica_aca` / `data_ultima_comunicazione_aca`: valorizzare la prima
   in creazione e in aggiornamento significativo è tutto ciò che serve (D1).
3. Non esiste indice su `data_ora_ultimo_aggiornamento`: è la ragione per cui
   l'ordinamento di default torna a `data_creazione DESC`, che ha
   `idx_vrs_data_creaz` (§7.2, B6). Nessuna modifica allo schema.

## 8. Transazioni (D11)

Scritture `@Transactional` (`REQUIRED`), letture `@Transactional(readOnly = true)`.
Un batch che elabora 1.000 righe di tracciato apre la propria transazione e chiama il
servizio: stesso effetto dell'attuale passaggio del `BasicBD`. Lock pessimistico
conservato dove c'è oggi (annullamento, upsert documento).

## 9. Documento e allegati (D9)

Repository separati orchestrati dal caricamento. Due comportamenti legacy da
riportare consapevolmente:

- **upsert del documento** con gestione della corsa fra thread (`exists` → `get` con
  `select for update` → `update`, oppure `create` con fallback su conflitto):
  riportato, incapsulato in `DocumentoRepository`;
- **allegati**: il legacy in `updateVersamento(deep)` li cancella tutti e li
  reinserisce. Nella libreria si aggiornano per identità (D9).

## 10. Trasversali

Tre porte SPI:

```java
interface GeneratoreIuv     { String generaIuv(long idApplicazione, long idDominio, String idPendenza); }
interface VisibilitaPendenze { /* predicato query-side + check post-fetch */ }
interface AuditPendenze     { void registra(String azione, long idOggetto, Map<String,Object> dettaglio); }
```

`AuditPendenze`: default su `gp_audit` (`data`, `id_oggetto`, `tipo_oggetto`,
`oggetto` JSON, `id_operatore`, `ip_richiedente`), scrittura asincrona, azioni
UPPER_SNAKE; se l'operatore non è risolvibile l'audit viene saltato con warning, come
fa oggi `AuditService`. `VisibilitaPendenze`: porting di `VersamentoVisibilita`
(domini interi OR UO visibili, AND tipi pendenza autorizzati; insiemi vuoti →
risultato vuoto, mai 403; 404 anti-leak sul dettaglio).

## 11. Fuori perimetro (v1)

Acquisizione/verifica da EC, inoltro modello 4, orchestrazione tracciati, invio
avvisatura, stampa avvisi, RPT/RT, incassi, anagrafica tipo pendenza. La libreria
fornisce i metodi che quei flussi chiameranno (D4).

## 12. Piano a fasi

| Fase | Contenuto |
|---|---|
| F1 | `model` + `entity` complete (65 colonne, enum, `BigDecimal`, codec causale/`cod_rata`/`incasso`) — **disegno di dettaglio: [f1-modello-e-entita.md](f1-modello-e-entita.md)** |
| F2 | `repository` + `ricerca` + lettura dettaglio/lista (porting console-api) |
| F3 | `aggiornamento`: 15 metodi + transizioni + audit + `data_ora_ultimo_aggiornamento` |
| F4 | `caricamento`: motore completo con tutti i flag di §4.6 |
| F5 | query di batch + eventuale adattatore legacy |

## 13. Punti aperti

- **A5 (chiarimento richiesto) — "eventi applicativi".** Oggi il caricamento chiama
  `Operazioni.setEseguiGestionePromemoria()` (`business/Versamento.java:309`), che
  imposta un `private static boolean` in memoria
  (`Operazioni.java:117,129`); il batch lo legge e lo azzera
  (`GestionePromemoriaCheck.java:34`). È un "dirty flag" **locale alla JVM**: dice al
  batch "c'è lavoro", evitandogli una query a vuoto. In multi-nodo funziona solo sul
  nodo che ha ricevuto la scrittura. Tre opzioni:
  1. **non riportarlo** — il batch fa la sua query, che è indicizzata
     (`idx_vrs_prom_avviso ON versamenti (avviso_notificato, data_notifica_avviso DESC)`);
  2. **evento Spring** — la libreria pubblica `PendenzaCaricata` con
     `ApplicationEventPublisher` e il consumatore decide cosa fare (impostare il suo
     flag, accodare, ignorare): la libreria non conosce i batch;
  3. replicare il flag statico — da escludere, non funziona multi-nodo.

  Raccomando la 2, che assorbe anche la 1. Confermi?
Chiusi: B1 (anagrafica in `govpay-common`, §4.8), B2 (`IuvUtils` consolidato, §3.1),
B3 (`data_ora_ultimo_aggiornamento` sempre aggiornata, §6.4), B4 (`src_*` sempre
uppercase, §4.5), B5 (package `it.govpay.pendenze`), B6 (default
`data_creazione DESC`, §7.2).

Con la chiusura di A5 la proposta è completa e si può passare al disegno di dettaglio
della fase F1.
