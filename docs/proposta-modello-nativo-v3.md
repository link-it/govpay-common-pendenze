# Proposta di disegno — modello nativo v3

Documento di proposta per il modello dati/entità JPA dell'evolutiva api-pendenze
v3, dopo l'abbandono del porting del modello legacy (issue #8, PR #9). Sostituisce
concettualmente `proposta-libreria-pendenze.md`/`f1-modello-e-entita.md` (che
restano come riferimento sul comportamento 3.10.x per la migrazione dati, non
come base di questo disegno).

- **Fonte v3**: `govpay-api-pendenze-v3.yaml` (schemi `NuovaPosizioneDebitoria`,
  `NuovaOpzionePagamento*`, `NuovaPendenza`, `NuovaVocePendenza*`, `Soggetto`,
  `PosizioneDebitoria`, `OpzionePagamento*`, `Pendenza*`, `VocePendenza*`)
- **Fonte principi di scrittura/lettura**: `riconciliazione-legacy-v3.md` §2
  (D10/D11/D3/A5/§4.2/§4.8/§7.4 del vecchio disegno, ancora validi)
- **Fonte ACA/GPD**: spec ufficiale pagoPA `pagopa/pagopa-api`,
  `openapi/gpd-4-aca.json` (verificata il 2026-09-22)

## 1. Struttura dell'aggregato

```
PosizioneDebitoria (root)
├── SoggettoDebitore[]     (ordinati, "in solido")
└── OpzionePagamento[]     (tipologia: PIANO_RATEALE | SOLUZIONE_UNICA |
    │                       SOLUZIONE_UNICA_ENTRO | SOLUZIONE_UNICA_OLTRE)
    │                       stato: DISPONIBILE → ATTIVATA | ANNULLATA
    └── Pendenza[]         (numeroRata, nav = numeroAvviso)
        └── VocePendenza[] (RiferimentoEntrata | Entrata | Bollo)
            └── DettaglioContabile[] (solo per Entrata/RiferimentoEntrata — non in questo primo disegno, vedi §6)
```

Un solo aggregato transazionale (coerente con D3/D9 del vecchio disegno):
caricare/modificare una `PosizioneDebitoria` tocca le sue opzioni, pendenze,
voci e soggetti in un'unica unità di lavoro.

## 2. Decisioni di mapping

| ID | Decisione |
|---|---|
| M1 | **Package/nomi nativi v3**, non il vocabolario legacy: `PosizioneDebitoria`, `OpzionePagamento`, `Pendenza`, `VocePendenza`, `SoggettoDebitore` — a differenza della vecchia A2 ("doppio vocabolario mantenuto"), qui non c'è un vocabolario legacy da conciliare: si parte nativi |
| M2 | **`OpzionePagamento` a tabella unica, non per-tipologia**: le 4 varianti differiscono solo per `tipologia` (enum), `giorni` (nullable, richiesto solo per ENTRO/OLTRE) e cardinalità di `pendenze` (validata in applicazione, non a DB). Una gerarchia JPA (`@Inheritance`) sarebbe sovradimensionata: colonna `tipologia` + `giorni` nullable bastano, validazione applicativa sulla cardinalità |
| M3 | **`VocePendenza` a tabella unica**, stesso ragionamento: le 3 varianti (`RiferimentoEntrata`/`Entrata`/`Bollo`) condividono `DatiComuniVocePendenza(Lettura)`, differiscono per un piccolo set di colonne mutuamente esclusive (`codEntrata` vs `ibanAccredito`/`ibanAppoggio`/`tassonomia` vs `tipoBollo`/`hashDocumento`/`provinciaResidenza`) — colonne nullable + discriminatore esplicito `tipoRiferimento`, non tre tabelle |
| M4 | **FK verso anagrafica come colonne `Long`, niente relazione JPA** (continuità con §4.8 del vecchio disegno): `idDominio`, `idUnitaOperativa`, `idTipoPendenza` risolti a `Long` al bordo dell'applicazione, mai `@ManyToOne` verso entità di `govpay-common`. **Diverso per le relazioni interne all'aggregato stesso** (`PosizioneDebitoria`→`SoggettoDebitore`/`OpzionePagamento`, `OpzionePagamento`→`Pendenza`, `Pendenza`→`VocePendenza`): qui la relazione JPA è appropriata, sono nella stessa persistence unit e nello stesso confine transazionale |
| M5 | **`SoggettoDebitore` è un'entità con identità propria**, non un `@Embeddable` nella lista: ha una PK e una FK esplicita `id_posizione_debitoria` |
| M6 | **Ordine di `soggettiDebitori` preservato** (colonna `ordine`, non `@OrderColumn` implicito): è la **sola** fonte di verità per "chi è il soggetto pagatore" — il primo per `ordine` (deciso dal lead, 2026-09-22, contro la mia proposta di uno snapshot esplicito, vedi nota sotto). Nessun campo aggiuntivo |

> **Nota su M6 (non più M7)**: avevo proposto uno snapshot esplicito (`soggetto_pagatore_id` su `OpzionePagamento`) per isolare il modello dati dal rischio noto (`updatePosizioneDebitoria` non impedisce di modificare `soggettiDebitori` dopo che un'opzione è ATTIVATA, quindi rileggere "live" l'ordine potrebbe cambiare retroattivamente chi risulta pagatore di una pendenza già chiusa). **Decisione del lead**: niente snapshot per ora — ci si attiene all'indicazione dello YAML (ordine + primo della lista), in attesa che pagoPA stessa evolva l'interfaccia includendo esplicitamente l'identità del soggetto pagatore nelle operazioni (RPT, ecc.). Il rischio resta noto e tracciato (punto 1 della riconciliazione), ma non viene ora assorbito dallo schema.
| M7 | **`iupd` non è una colonna**: verificato lo spec `gpd-4-aca.json`, è definito solo `type: string`, nessun formato imposto — "è responsabilità dell'EC garantirne l'unicità" (suggerito lì: `<codice fiscale ente + UUID>`, ma non vincolante). Dato che `idA2A`+`idPosizioneDebitoria` sono già garantiti univoci insieme (vincolo esplicito dello YAML v3), `iupd` si **deriva al volo** da questi (es. codice fiscale del dominio + `idA2A` + `idPosizioneDebitoria`) quando serve dialogare con ACA/GPD, senza persistere nulla di nuovo (correzione rispetto alla prima stesura di questo documento, su rilievo del lead) |
| M8 | **`nav` non è una colonna a sé**: coincide con `Pendenza.numeroAvviso`, già previsto dallo YAML. Nessun campo duplicato |
| M9 | **`StatoPendenza.SCADUTA` derivato, non persistito** (continuità con la vecchia A6): calcolato da `stato=NON_ESEGUITA` + `dataScadenza` (dell'opzione o della pendenza) nel passato, mai scritto su colonna. Le altre 6 costanti (`ESEGUITA`, `NON_ESEGUITA`, `ESEGUITA_PARZIALE`, `ANNULLATA`, `ANOMALA`, `INCASSATA`) sono lo stato persistito reale |
| M10 | **Audit e timestamp su ogni scrittura, senza eccezioni** (continuità con D10): `dataCreazione`/`dataUltimoAggiornamento` su tutte le entità dell'aggregato, non solo sulla radice |
| M11 | **Tabelle nuove**, non riuso di `versamenti`/`documenti`/`singoli_versamenti`: `posizioni_debitorie`, `soggetti_debitori`, `opzioni_pagamento`, `pendenze`, `voci_pendenza` |
| M12 | **Lock ottimistico (`@Version`) su `OpzionePagamento`** (rilievo del lead, 2026-09-22): senza un controllo di concorrenza, `attiva`/`annulla` chiamati concorrentemente sulla stessa opzione potrebbero sovrascriversi in silenzio — un annullamento basato su una lettura antecedente potrebbe vincere su un'attivazione appena registrata (o viceversa), corrompendo la macchina a stati. Con `@Version` chi scrive per secondo su una versione superata riceve un'eccezione invece di un aggiornamento perso |
| M13 | **`idDominio` denormalizzato su `Pendenza`** (rilievo del lead, 2026-09-22): IUV e NAV sono univoci **per dominio**, non globalmente — coerente con `idx_vrs_iuv_dominio` del legacy, già composto `(iuv_versamento, id_dominio)`. Un vincolo di unicità globale (come nella prima stesura di questo documento) impedirebbe a due enti creditori diversi di generare legittimamente lo stesso IUV/numero avviso, rompendo il multi-ente e la migrazione dei dati storici |

## 3. Entità proposte

### 3.1 `PosizioneDebitoria`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `idA2A` | `String` | identificativo del gestionale, con `idPosizioneDebitoria` forma la chiave logica univoca |
| `idPosizioneDebitoria` | `String` | business key, univoca per `idA2A` (409 se duplicata) — **da qui si deriva `iupd` per ACA/GPD, nessuna colonna dedicata (M7)** |
| `idDominio` | `Long` | FK, colonna (M4) |
| `idUnitaOperativa` | `Long` | FK, colonna, nullable |
| `descrizione` | `String` | max 140 |
| `dataPubblicazione` | `LocalDate` | nullable = pubblicata subito |
| `notificaSend` | `boolean` | default `false` |
| `navNotifica` | `String` | nullable, deve corrispondere al `numeroAvviso` di una pendenza figlia (vincolo applicativo, non FK — il riferimento è a un valore, non a una riga) |
| `dataUltimaModificaAca` / `dataUltimaComunicazioneAca` | `OffsetDateTime` | **nuovo, da §5** — marcatura ACA a livello posizione |
| `dataCreazione` / `dataUltimoAggiornamento` | `OffsetDateTime` | M10 |
| `soggettiDebitori` | `List<SoggettoDebitore>` | ordinata per `ordine` |
| `opzioniPagamento` | `List<OpzionePagamento>` | |

### 3.2 `SoggettoDebitore`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `posizioneDebitoria` | FK (`@ManyToOne`) | |
| `ordine` | `int` | posizione nell'array originale (M6) |
| `tipo` | enum `F`/`G` | |
| `identificativo` | `String` | CF/PIVA, 2-16 char |
| `anagrafica` | `String` | nullable |
| `indirizzo`, `civico`, `cap`, `localita`, `provincia`, `nazione`, `email` | `String` | tutti nullable |

### 3.3 `OpzionePagamento`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `versione` | `long` | **nuovo, M12** — lock ottimistico (`@Version`), protegge le transizioni di stato dalla concorrenza |
| `idOpzionePagamento` | `UUID` | esposto in API, generato da GovPay, stabile |
| `posizioneDebitoria` | FK | |
| `tipologia` | enum `PIANO_RATEALE`/`SOLUZIONE_UNICA`/`SOLUZIONE_UNICA_ENTRO`/`SOLUZIONE_UNICA_OLTRE` | M2 |
| `giorni` | `Integer` | nullable, richiesto solo per ENTRO/OLTRE (validazione applicativa) |
| `stato` | enum `DISPONIBILE`/`ATTIVATA`/`ANNULLATA` | macchina a stati |
| `dataInizioValidita` / `dataScadenza` | `LocalDate` | nullable |
| `dataCreazione` / `dataUltimoAggiornamento` | `OffsetDateTime` | M10 |
| `pendenze` | `List<Pendenza>` | ordinata (determina `numeroRata`) |

Vincoli applicativi (non a DB, coerenti con D3 "un metodo per evento di
dominio"): `PIANO_RATEALE` richiede ≥2 pendenze, le altre 3 tipologie
esattamente 1; `giorni` obbligatorio solo per ENTRO/OLTRE.

### 3.4 `Pendenza`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `opzionePagamento` | FK | |
| `idDominio` | `Long` | **nuovo (M13)** — denormalizzato dalla posizione: IUV/NAV sono univoci per dominio, non globalmente (rilievo del lead, 2026-09-22) |
| `idPendenza` | `String` | business key |
| `idTipoPendenza` | `Long` | FK, colonna (M4) |
| `numeroRata` | `int` | derivato dalla posizione nell'array (assegnato da GovPay) |
| `importo` | `BigDecimal` | continuità §4.2 del vecchio disegno (conversione dedicata, mai `new BigDecimal(double)`) |
| `numeroAvviso` | `String` | = NAV (M8), generato se assente; univoco insieme a `idDominio` (M13), non da solo |
| `iuv` | `String` | generato; univoco insieme a `idDominio` (M13), non da solo |
| `stato` | enum (6 valori persistiti, M9) | |
| `dataPagamento` | `LocalDate` | nullable |
| `dataCaricamento` | `LocalDate` | = data creazione, business-facing |
| `dataValidita` | `LocalDate` | nullable |
| `dataScadenzaAvviso` | `LocalDate` | nullable, override di stampa |
| `dataUltimaModificaAca` / `dataUltimaComunicazioneAca` | `OffsetDateTime` | **nuovo, da §5** — marcatura ACA a livello pendenza/avviso |
| `dataCreazione` / `dataUltimoAggiornamento` | `OffsetDateTime` | M10 |
| `voci` | `List<VocePendenza>` | |

### 3.5 `VocePendenza`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `pendenza` | FK | |
| `idVocePendenza` | `String` | business key |
| `importo` | `BigDecimal` | |
| `descrizione` | `String` | max 140 |
| `indice` | `int` | 1-5, ordine nella pendenza |
| `stato` | enum `Eseguito`/`Non eseguito`/`Anomalo` | valori così come nello YAML (maiuscole non uniformi, da preservare) |
| `idDominio` | `Long` | FK, nullable — override multi-beneficiario |
| `tipoRiferimento` | enum `RIFERIMENTO_ENTRATA`/`ENTRATA`/`BOLLO` | discriminatore (M3) |
| `codEntrata` | `String` | nullable, solo `RIFERIMENTO_ENTRATA` |
| `ibanAccredito`, `ibanAppoggio`, `tassonomia` | `String` | nullable, solo `ENTRATA` (e `tassonomia` anche per `BOLLO`) |
| `tipoBollo`, `hashDocumento`, `provinciaResidenza` | `String` | nullable, solo `BOLLO` |

`dettaglioContabile` (array, solo per `ENTRATA`/`RIFERIMENTO_ENTRATA`) **non è
in questo primo disegno** — vedi §6.

## 4. Come il modello accomoda i punti ancora aperti (senza deciderli)

- **Punto 1 (soggetto per RPT/stampa avviso)**: **non assorbito dallo schema,
  per decisione esplicita del lead** — niente snapshot dedicato (vedi nota su
  M6): si usa `soggettiDebitori` ordinato per `ordine`, primo elemento per
  convenzione, esattamente come indica lo YAML. Il rischio noto
  (`updatePosizioneDebitoria` non impedisce modifiche a `soggettiDebitori` dopo
  che un'opzione è ATTIVATA) resta quindi presente e non mitigato a livello di
  schema, in attesa che pagoPA evolva l'interfaccia includendo esplicitamente
  l'identità del soggetto pagatore nelle operazioni.
- **Punto 2 (avvisatura granulare)**: **non modellato in questo disegno** —
  nessun campo per giorni di preavviso mail/AppIO su `PosizioneDebitoria`/
  `Pendenza`. Se la decisione futura sarà di reintrodurla, servirà una
  migrazione di schema successiva.
- **Punto 4 (debitori incoerenti in migrazione)**: non è un problema di
  schema — è la migrazione a doverlo gestire (vedi `riconciliazione-legacy-v3.md`
  §4 punto 4).
- **ACA (chiarito, §5 di questo documento)**: `iupd` derivato da `idA2A`+
  `idPosizioneDebitoria` (M7, nessuna colonna), `nav` = `numeroAvviso` (M8) — le
  coppie `dataUltimaModificaAca`/`dataUltimaComunicazioneAca` restano su
  **entrambi** i livelli (posizione e pendenza), coerenti con
  `PaymentPositionModel`(`iupd`)/`PaymentOptionModel`(`nav`) dello spec pagoPA
  `gpd-4-aca.json`.

## 5. Cosa NON è in questo primo disegno (rimandato)

- `dettaglioContabile` completo (4 varianti: CorrispettivoDL118/IncassoTipico/
  Civilistico/ImportoNotifica) — solo abbozzato come collezione futura su
  `VocePendenza`
- Ricevute (`Ricevuta`, 3 formati XML), Rendicontazioni — fuori perimetro
  dell'aggregato pendenza in senso stretto, come nel vecchio disegno (A1)
- Repository, ricerca, criteri, paginazione (sarà una fase a sé, come F2 nel
  vecchio disegno)
- Caricamento/aggiornamento (servizi applicativi, macchina a stati
  implementata, validazioni) — fase successiva
- Migrazione dati vera e propria (script), oggetto di un documento a parte

## 6. Punti da validare prima di procedere all'implementazione

1. Conferma della struttura a tabella unica per `OpzionePagamento` (M2) e
   `VocePendenza` (M3) invece di gerarchie JPA per tipologia.
2. Conferma che le relazioni interne all'aggregato (M4, seconda parte) possano
   essere `@ManyToOne`/`@OneToMany` reali, a differenza delle FK verso
   l'anagrafica esterna.
3. Conferma di M8 (nav = numeroAvviso, senza colonna dedicata).
4. **`cardinalitaPendenzeMassima()` di `SOLUZIONE_UNICA` (M2) — punto aperto,
   lasciato invariato per ora.** Un commento sull'issue `govpay-pendenze-api#1`
   (non lo YAML, non il body) descrive un caso reale (dovuto con >5 voci,
   quindi >1 avviso) che richiederebbe più di una pendenza anche per
   `SOLUZIONE_UNICA`, in contraddizione con `maxItems: 1` dello YAML
   attuale — vedi `riconciliazione-legacy-v3.md` §4 punto 6. Il codice resta
   coerente con lo YAML as-is (`1` per le 3 tipologie "soluzione unica") finché
   non si decide come modellare il caso ">5 voci".

**Decisi** (non più da validare):
- **M6** — niente snapshot del soggetto pagatore, si usa `soggettiDebitori`
  ordinato + primo elemento, come da YAML (vedi nota su M6 in §2).
- **M7** — niente colonna `iupd`, derivato al volo da `idA2A`+
  `idPosizioneDebitoria` (verificato sullo spec `gpd-4-aca.json`: nessun
  vincolo di formato, l'unicità è responsabilità dell'EC).
