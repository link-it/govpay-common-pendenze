-- ---------------------------------------------------------------------------
-- Stessa logica di postgresql/migrazione/06_opzioni_pagamento_documenti_esistenti.sql
-- (leggere quel file per il ragionamento completo sui tre pattern A/B/C e
-- sulla cascata di mutua esclusione) — qui la sintassi HSQLDB, verificata
-- eseguendo lo script contro HSQLDB 2.7.3.
--
-- HSQLDB non supporta CTE scrivibili (niente "WITH cte AS (INSERT ...
-- RETURNING ...) UPDATE ..." come in PostgreSQL): la classificazione viene
-- materializzata in tabelle di appoggio (CREATE TABLE ... AS (SELECT ...)
-- WITH DATA — la clausola WITH DATA e' obbligatoria in HSQLDB, altrimenti la
-- tabella viene creata vuota; DROP alla fine).
--
-- Senza funzioni finestra (ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)
-- non e' accettato da questa versione/configurazione di HSQLDB — verificato
-- empiricamente: solo OVER () vuoto viene accettato): la numerazione
-- posizionale per il pattern A (SOLUZIONE_UNICA, cod_rata NULL) usa una
-- COUNT(*) correlata su id <= id corrente, stessa tecnica gia' in uso nella
-- traduzione Oracle.
--
-- documenti.aca_legacy: stessa UPDATE finale di Oracle/MySQL/SQL Server,
-- DOPO che entrambi i pattern A/B e C hanno gia' agganciato i rispettivi
-- versamenti — vedi il commento su quei file per il perche'.
-- ---------------------------------------------------------------------------

CREATE TABLE tmp_vrs_rata AS (
    SELECT
        v.id,
        v.id_documento,
        v.cod_rata,
        v.stato_versamento,
        v.data_ultima_comunicazione_aca,
        CASE WHEN v.cod_rata IS NULL THEN 1 ELSE 0 END AS flag_null,
        CASE WHEN REGEXP_MATCHES(v.cod_rata, '^[1-9][0-9]*$') THEN 1 ELSE 0 END AS flag_numerico,
        CASE WHEN REGEXP_MATCHES(v.cod_rata, '^[1-9][0-9]*$') THEN CAST(v.cod_rata AS INTEGER) END AS valore_numerico,
        CASE WHEN REGEXP_MATCHES(v.cod_rata, '^(RIDOTTO|SCONTATO|ENTRO[0-9]+|OLTRE[0-9]+)$') THEN 1 ELSE 0 END AS flag_soglia
    FROM versamenti v
    WHERE v.id_documento IS NOT NULL
      AND v.id_opzione_pagamento IS NULL
) WITH DATA;

-- ---------------------------------------------------------------------------
-- Pattern A/B: un'opzione per documento. Stato: DISPONIBILE e' il caso di
-- default — copre sia "tutte NON_ESEGUITO" sia il mix NON_ESEGUITO/ANNULLATO
-- senza alcuna pendenza eseguita (annullamento indipendente della singola
-- pendenza, possibile solo in v2).
-- ---------------------------------------------------------------------------

CREATE TABLE tmp_classificazione AS (
    SELECT
        id_documento,
        CASE WHEN n_null = totale THEN 'SOLUZIONE_UNICA'
             WHEN n_numerico = totale AND n_numerico_distinti = totale THEN 'PIANO_RATEALE'
        END AS tipologia,
        CASE WHEN n_annullato = totale THEN 'ANNULLATA'
             WHEN n_altro > 0 THEN 'ATTIVATA'
             ELSE 'DISPONIBILE'
        END AS stato
    FROM (
        SELECT
            id_documento,
            COUNT(*) AS totale,
            SUM(flag_null) AS n_null,
            SUM(flag_numerico) AS n_numerico,
            COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) AS n_numerico_distinti,
            SUM(CASE WHEN stato_versamento = 'ANNULLATO' THEN 1 ELSE 0 END) AS n_annullato,
            SUM(CASE WHEN stato_versamento NOT IN ('NON_ESEGUITO', 'ANNULLATO') THEN 1 ELSE 0 END) AS n_altro
        FROM tmp_vrs_rata
        GROUP BY id_documento
    ) aggr
) WITH DATA;

INSERT INTO opzioni_pagamento
    (id_opzione_pagamento, id_documento, tipologia, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT
    UUID(),
    c.id_documento,
    c.tipologia,
    c.stato,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM tmp_classificazione c
WHERE c.tipologia IS NOT NULL;

-- numero_rata: valore dichiarato preservato (PIANO_RATEALE) oppure, per
-- SOLUZIONE_UNICA (cod_rata NULL), posizione 1-based per id crescente dentro
-- il documento. Limitato ai documenti A/B: il pattern C sotto puo' produrre
-- piu' opzioni con lo stesso id_documento (una per versamento), per cui la
-- sottoquery scalare qui sotto (un'opzione per id_documento) resterebbe
-- ambigua se applicata anche a quelli.
UPDATE versamenti v
SET (id_opzione_pagamento, numero_rata) = (
    SELECT o.id,
           COALESCE(
               (SELECT vr.valore_numerico FROM tmp_vrs_rata vr WHERE vr.id = v.id),
               (SELECT COUNT(*) FROM tmp_vrs_rata vr2
                WHERE vr2.id_documento = v.id_documento AND vr2.id <= v.id)
           )
    FROM opzioni_pagamento o
    WHERE o.id_documento = v.id_documento
)
WHERE v.id_documento IN (
    SELECT id_documento FROM tmp_classificazione WHERE tipologia IS NOT NULL
);

-- ---------------------------------------------------------------------------
-- Pattern C: ENTRO/OLTRE espliciti, inclusa la coppia RIDOTTO/SCONTATO. Una
-- opzione PER VERSAMENTO (cardinalita' massima 1 per queste tipologie), non
-- un'aggregazione per documento come A/B — solo se TUTTO il documento e'
-- fatto di valori-soglia riconosciuti e senza duplicati. id_opzione_pagamento
-- e' generato qui, in tmp_vrs_soglia, prima dell'insert: una volta
-- materializzato e' stabile e correla la riga di opzioni_pagamento appena
-- inserita al versamento originale (vc.id) — un documento con piu' soglie
-- (es. ENTRO+OLTRE) ha piu' opzioni sotto lo stesso id_documento, il solo
-- id_documento non basta a distinguerle.
-- ---------------------------------------------------------------------------

CREATE TABLE tmp_doc_soglia AS (
    SELECT id_documento
    FROM tmp_vrs_rata
    GROUP BY id_documento
    HAVING SUM(flag_soglia) = COUNT(*) AND COUNT(DISTINCT cod_rata) = COUNT(*) AND COUNT(*) > 0
) WITH DATA;

CREATE TABLE tmp_vrs_soglia AS (
    SELECT
        vc.id,
        vc.id_documento,
        UUID() AS id_opzione_pagamento,
        CASE WHEN vc.cod_rata LIKE 'ENTRO%' THEN 'SOLUZIONE_UNICA_ENTRO'
             WHEN vc.cod_rata LIKE 'OLTRE%' THEN 'SOLUZIONE_UNICA_OLTRE'
             WHEN vc.cod_rata = 'SCONTATO' THEN 'SOLUZIONE_UNICA_SCONTATO'
             ELSE 'SOLUZIONE_UNICA_RIDOTTO'
        END AS tipologia,
        CASE WHEN vc.cod_rata LIKE 'ENTRO%' OR vc.cod_rata LIKE 'OLTRE%'
             THEN CAST(SUBSTRING(vc.cod_rata FROM 6) AS INTEGER)
        END AS giorni,
        CASE WHEN vc.stato_versamento = 'NON_ESEGUITO' THEN 'DISPONIBILE'
             WHEN vc.stato_versamento = 'ANNULLATO' THEN 'ANNULLATA'
             ELSE 'ATTIVATA'
        END AS stato
    FROM tmp_vrs_rata vc
    JOIN tmp_doc_soglia ds ON ds.id_documento = vc.id_documento
) WITH DATA;

INSERT INTO opzioni_pagamento
    (id_opzione_pagamento, id_documento, tipologia, giorni, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT
    vs.id_opzione_pagamento,
    vs.id_documento,
    vs.tipologia,
    vs.giorni,
    vs.stato,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM tmp_vrs_soglia vs;

UPDATE versamenti v
SET (id_opzione_pagamento, numero_rata) = (
    SELECT o.id, 1
    FROM tmp_vrs_soglia vs
    JOIN opzioni_pagamento o ON o.id_opzione_pagamento = vs.id_opzione_pagamento
    WHERE vs.id = v.id
)
WHERE v.id IN (SELECT id FROM tmp_vrs_soglia);

-- Vedi nota in testa al file: unico punto in cui si legge la provenienza
-- legacy, dopo l'aggancio A/B e C, prima che il batch ACA possa aver
-- scritto altro su data_ultima_comunicazione_aca.
UPDATE documenti d
SET aca_legacy = TRUE
WHERE d.aca_legacy = FALSE
  AND EXISTS (
      SELECT 1 FROM tmp_vrs_rata vr
      JOIN versamenti v ON v.id = vr.id
       WHERE vr.id_documento = d.id
         AND v.id_opzione_pagamento IS NOT NULL
         AND vr.data_ultima_comunicazione_aca IS NOT NULL
  );

-- ---------------------------------------------------------------------------
-- Cascata della mutua esclusione: un'opzione ancora DISPONIBILE sulla stessa
-- posizione di un'opzione ATTIVATA e' un'alternativa non piu' applicabile —
-- stessa semantica di PosizioneDebitoriaService.attiva() della libreria.
-- Vale su tutta la tabella, non solo sulle righe appena migrate. Prima i
-- versamenti: dopo, le opzioni da annullare non sarebbero piu'
-- riconoscibili (non sarebbero piu' DISPONIBILE).
-- ---------------------------------------------------------------------------

UPDATE versamenti v
SET stato_versamento = 'ANNULLATO',
    data_ora_ultimo_aggiornamento = CURRENT_TIMESTAMP
WHERE stato_versamento = 'NON_ESEGUITO'
  AND id_opzione_pagamento IN (
      SELECT op.id FROM opzioni_pagamento op
       WHERE op.stato = 'DISPONIBILE'
         AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
                      WHERE altra.id_documento = op.id_documento
                        AND altra.stato = 'ATTIVATA'));

UPDATE opzioni_pagamento op
SET stato = 'ANNULLATA',
    data_ultimo_aggiornamento = CURRENT_TIMESTAMP
WHERE op.stato = 'DISPONIBILE'
  AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
               WHERE altra.id_documento = op.id_documento
                 AND altra.stato = 'ATTIVATA');

-- ---------------------------------------------------------------------------
-- Report diagnostico sui documenti esclusi dalla sintesi automatica sopra —
-- da eseguire ed esaminare DOPO lo script, non parte dell'automazione.
-- Documenti con cod_rata genuinamente anomalo: non tutti NULL, non tutti
-- numerici validi senza duplicati, e non tutti valori-soglia riconosciuti
-- senza duplicati — pattern misto tra le tre famiglie, duplicato di
-- valore-soglia nello stesso documento, o dato corrotto. Da rivedere a mano.
-- ---------------------------------------------------------------------------

SELECT
    id_documento,
    COUNT(*) AS totale,
    SUM(flag_null) AS n_null,
    SUM(flag_numerico) AS n_numerico,
    COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) AS n_numerico_distinti,
    SUM(flag_soglia) AS n_soglia,
    COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) AS n_soglia_distinti
FROM tmp_vrs_rata
GROUP BY id_documento
HAVING NOT (SUM(flag_null) = COUNT(*))
   AND NOT (SUM(flag_numerico) = COUNT(*) AND COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) = COUNT(*))
   AND NOT (SUM(flag_soglia) = COUNT(*) AND COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) = COUNT(*));

DROP TABLE tmp_vrs_rata;
DROP TABLE tmp_classificazione;
DROP TABLE tmp_doc_soglia;
DROP TABLE tmp_vrs_soglia;
