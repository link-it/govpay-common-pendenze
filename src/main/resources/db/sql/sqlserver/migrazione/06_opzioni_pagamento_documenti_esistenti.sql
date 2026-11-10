-- ---------------------------------------------------------------------------
-- Stessa logica di postgresql/migrazione/06_opzioni_pagamento_documenti_esistenti.sql
-- (leggere quel file per il ragionamento completo: raggruppamento dei
-- versamenti per debitore, tre pattern A/B/C, cascata di mutua esclusione) —
-- qui la sintassi SQL Server, NON verificata contro un'istanza reale.
--
-- Niente REGEXP nativo in T-SQL: i pattern su cod_rata sono espressi con
-- LIKE/SUBSTRING. La classificazione viene materializzata in tabelle
-- temporanee locali (#tmp, droppate automaticamente a fine sessione,
-- comunque droppate esplicitamente in fondo).
--
-- Gli id di debitori e pd_debitori sono assegnati a mano (MAX(id) + numero di
-- riga) con SET IDENTITY_INSERT: il contatore IDENTITY si riallinea da solo.
-- Lo script va eseguito a sistema fermo.
--
-- Atomico: tutto lo script gira in un'unica transazione (SET XACT_ABORT ON,
-- BEGIN TRANSACTION ... COMMIT prima del report): al primo errore la
-- transazione e' annullata, non restano gruppi migrati a meta'.
--
-- id e' IDENTITY (issue #10): non piu' pre-calcolabile con NEXT VALUE FOR
-- come con la sequenza esplicita. L'id fisico generato dall'insert si
-- recupera con la clausola OUTPUT, dentro una tabella temporanea creata
-- apposta (OUTPUT ... INTO richiede che la tabella di destinazione esista
-- gia', a differenza di SELECT ... INTO).
-- ---------------------------------------------------------------------------

-- Pulizia iniziale delle sole tabelle di appoggio di questo script: se la sessione
-- prosegue dopo un errore, il DROP finale non e' stato eseguito.
IF OBJECT_ID('tempdb..#tmp_vrs_rata') IS NOT NULL DROP TABLE #tmp_vrs_rata;
IF OBJECT_ID('tempdb..#tmp_gruppi') IS NOT NULL DROP TABLE #tmp_gruppi;
IF OBJECT_ID('tempdb..#tmp_nuove_opzioni') IS NOT NULL DROP TABLE #tmp_nuove_opzioni;
IF OBJECT_ID('tempdb..#tmp_nuove_opzioni_id') IS NOT NULL DROP TABLE #tmp_nuove_opzioni_id;
IF OBJECT_ID('tempdb..#tmp_vrs_soglia') IS NOT NULL DROP TABLE #tmp_vrs_soglia;
IF OBJECT_ID('tempdb..#tmp_vrs_soglia_id') IS NOT NULL DROP TABLE #tmp_vrs_soglia_id;

SET XACT_ABORT ON;
BEGIN TRANSACTION;

SELECT
    v.id,
    v.id_documento,
    UPPER(v.debitore_identificativo) AS chiave,
    v.cod_rata,
    v.stato_versamento,
    CASE WHEN v.cod_rata IS NULL THEN 1 ELSE 0 END AS flag_null,
    CASE WHEN v.cod_rata IS NOT NULL AND v.cod_rata NOT LIKE '%[^0-9]%' AND v.cod_rata NOT LIKE '0%'
         THEN 1 ELSE 0 END AS flag_numerico,
    CASE WHEN v.cod_rata IS NOT NULL AND v.cod_rata NOT LIKE '%[^0-9]%' AND v.cod_rata NOT LIKE '0%'
         THEN CAST(v.cod_rata AS INT) END AS valore_numerico,
    CASE WHEN v.cod_rata IN ('RIDOTTO', 'SCONTATO')
           OR (v.cod_rata LIKE 'ENTRO[0-9]%' AND SUBSTRING(v.cod_rata, 6, 8000) NOT LIKE '%[^0-9]%')
           OR (v.cod_rata LIKE 'OLTRE[0-9]%' AND SUBSTRING(v.cod_rata, 6, 8000) NOT LIKE '%[^0-9]%')
         THEN 1 ELSE 0 END AS flag_soglia
INTO #tmp_vrs_rata
FROM versamenti v
WHERE v.id_documento IS NOT NULL
  AND v.id_opzione_pagamento IS NULL;

-- ---------------------------------------------------------------------------
-- Gruppi (documento, debitore) da migrare, con la loro classificazione.
-- Pattern A/B: un'opzione per gruppo. Stato: DISPONIBILE e' il caso di
-- default — copre sia "tutte NON_ESEGUITO" sia il mix NON_ESEGUITO/ANNULLATO
-- senza alcuna pendenza eseguita (annullamento indipendente della singola
-- pendenza, possibile solo in v2). I gruppi non classificabili non entrano:
-- restano nel report in fondo.
-- ---------------------------------------------------------------------------

DECLARE @base_debitori BIGINT = (SELECT COALESCE(MAX(id), 0) FROM debitori);
DECLARE @base_pd_debitori BIGINT = (SELECT COALESCE(MAX(id), 0) FROM pd_debitori);

SELECT
    x.*,
    -- Dopo i debitori gia' migrati del documento: una riesecuzione non duplica l'ordine.
    COALESCE((SELECT MAX(pd.posizione) + 1 FROM pd_debitori pd WHERE pd.id_documento = x.id_documento), 0)
      + DENSE_RANK() OVER (PARTITION BY x.id_documento ORDER BY x.primo_versamento) - 1 AS posizione,
    @base_debitori + ROW_NUMBER() OVER (ORDER BY x.id_documento, x.chiave) AS id_debitore,
    @base_pd_debitori + ROW_NUMBER() OVER (ORDER BY x.id_documento, x.chiave) AS id_pd_debitore
INTO #tmp_gruppi
FROM (
    SELECT
        aggr.*,
        CASE WHEN aggr.n_null = aggr.totale THEN 'SOLUZIONE_UNICA'
             WHEN aggr.n_numerico = aggr.totale AND aggr.n_numerico_distinti = aggr.totale THEN 'PIANO_RATEALE'
        END AS tipologia,
        CASE WHEN aggr.n_soglia = aggr.totale AND aggr.n_soglia_distinti = aggr.totale THEN 1 ELSE 0 END AS e_soglia,
        CASE WHEN aggr.n_annullato = aggr.totale THEN 'ANNULLATA'
             WHEN aggr.n_altro > 0 THEN 'ATTIVATA'
             ELSE 'DISPONIBILE'
        END AS stato
    FROM (
        SELECT
            id_documento,
            chiave,
            MIN(id) AS primo_versamento,
            COUNT(*) AS totale,
            SUM(flag_null) AS n_null,
            SUM(flag_numerico) AS n_numerico,
            COUNT(DISTINCT valore_numerico) AS n_numerico_distinti,
            SUM(flag_soglia) AS n_soglia,
            COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) AS n_soglia_distinti,
            SUM(CASE WHEN stato_versamento = 'ANNULLATO' THEN 1 ELSE 0 END) AS n_annullato,
            SUM(CASE WHEN stato_versamento NOT IN ('NON_ESEGUITO', 'ANNULLATO') THEN 1 ELSE 0 END) AS n_altro
        FROM #tmp_vrs_rata
        GROUP BY id_documento, chiave
    ) aggr
) x
WHERE x.tipologia IS NOT NULL OR x.e_soglia = 1;

-- Un gruppo gia' presente come pd_debitori dello stesso documento (stesso
-- identificativo) non si duplica: protegge da una riesecuzione dopo una
-- correzione manuale parziale.
DELETE g FROM #tmp_gruppi g
 WHERE EXISTS (SELECT 1 FROM pd_debitori pd JOIN debitori d ON d.id = pd.id_debitore
                WHERE pd.id_documento = g.id_documento AND UPPER(d.identificativo) = g.chiave);

-- Anagrafica del debitore: copiata dal primo versamento del gruppo.
-- debitore_tipo e' nullable in v2, tipo no: i dati senza tipo diventano 'F'.
SET IDENTITY_INSERT debitori ON;
INSERT INTO debitori
    (id, tipo, identificativo, anagrafica, indirizzo, civico, cap, localita,
     provincia, nazione, email, cellulare)
SELECT
    g.id_debitore, COALESCE(v.debitore_tipo, 'F'), v.debitore_identificativo, v.debitore_anagrafica,
    v.debitore_indirizzo, v.debitore_civico, v.debitore_cap, v.debitore_localita,
    v.debitore_provincia, v.debitore_nazione, v.debitore_email, v.debitore_cellulare
FROM #tmp_gruppi g
JOIN versamenti v ON v.id = g.primo_versamento;
SET IDENTITY_INSERT debitori OFF;

SET IDENTITY_INSERT pd_debitori ON;
INSERT INTO pd_debitori (id, id_documento, id_debitore, posizione, notifica_send_payed)
SELECT g.id_pd_debitore, g.id_documento, g.id_debitore, g.posizione, 0
FROM #tmp_gruppi g;
SET IDENTITY_INSERT pd_debitori OFF;

SELECT id_pd_debitore, tipologia, stato
INTO #tmp_nuove_opzioni
FROM #tmp_gruppi
WHERE tipologia IS NOT NULL;

CREATE TABLE #tmp_nuove_opzioni_id (id BIGINT, id_pd_debitore BIGINT);

INSERT INTO opzioni_pagamento
    (id_opzione_pagamento, id_pd_debitore, tipologia, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
OUTPUT inserted.id, inserted.id_pd_debitore INTO #tmp_nuove_opzioni_id (id, id_pd_debitore)
SELECT NEWID(), id_pd_debitore, tipologia, stato, 0, SYSDATETIME(), SYSDATETIME()
FROM #tmp_nuove_opzioni;

-- numero_rata: valore dichiarato preservato (PIANO_RATEALE) oppure, per
-- SOLUZIONE_UNICA (cod_rata NULL), posizione 1-based per id crescente dentro
-- il gruppo — solo per garantire indici distinti. Il join parte da
-- #tmp_nuove_opzioni_id (solo gruppi A/B): nessuna ambiguita' con le opzioni
-- del pattern C sotto, che restano sotto lo stesso pd_debitori ma non
-- finiscono in questa tabella.
UPDATE v
SET v.id_opzione_pagamento = no.id,
    v.numero_rata = vn.numero_rata_calcolato
FROM versamenti v
JOIN #tmp_gruppi g ON g.id_documento = v.id_documento AND g.chiave = UPPER(v.debitore_identificativo)
JOIN #tmp_nuove_opzioni_id no ON no.id_pd_debitore = g.id_pd_debitore
JOIN (
    SELECT id,
           COALESCE(valore_numerico, ROW_NUMBER() OVER (PARTITION BY id_documento, chiave ORDER BY id)) AS numero_rata_calcolato
    FROM #tmp_vrs_rata
) vn ON vn.id = v.id
WHERE g.tipologia IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Pattern C: ENTRO/OLTRE espliciti, inclusa la coppia RIDOTTO/SCONTATO. Una
-- opzione PER VERSAMENTO (cardinalita' massima 1 per queste tipologie), non
-- un'aggregazione per gruppo come A/B — solo se TUTTO il gruppo e' fatto di
-- valori-soglia riconosciuti e senza duplicati. Il GUID
-- id_opzione_pagamento e' generato qui, in #tmp_vrs_soglia, prima
-- dell'insert: una volta materializzato e' stabile e correla la riga di
-- opzioni_pagamento (via OUTPUT) al versamento originale (vc.id).
-- ---------------------------------------------------------------------------

SELECT
    vc.id,
    g.id_pd_debitore,
    CASE WHEN vc.cod_rata LIKE 'ENTRO%' THEN 'SOLUZIONE_UNICA_ENTRO'
         WHEN vc.cod_rata LIKE 'OLTRE%' THEN 'SOLUZIONE_UNICA_OLTRE'
         WHEN vc.cod_rata = 'SCONTATO' THEN 'SOLUZIONE_UNICA_SCONTATO'
         ELSE 'SOLUZIONE_UNICA_RIDOTTO'
    END AS tipologia,
    CASE WHEN vc.cod_rata LIKE 'ENTRO%' OR vc.cod_rata LIKE 'OLTRE%'
         THEN CAST(SUBSTRING(vc.cod_rata, 6, 8000) AS INT)
    END AS giorni,
    CASE WHEN vc.stato_versamento = 'NON_ESEGUITO' THEN 'DISPONIBILE'
         WHEN vc.stato_versamento = 'ANNULLATO' THEN 'ANNULLATA'
         ELSE 'ATTIVATA'
    END AS stato,
    NEWID() AS id_opzione_pagamento
INTO #tmp_vrs_soglia
FROM #tmp_vrs_rata vc
JOIN #tmp_gruppi g ON g.id_documento = vc.id_documento AND g.chiave = vc.chiave
WHERE g.tipologia IS NULL AND g.e_soglia = 1;

CREATE TABLE #tmp_vrs_soglia_id (id BIGINT, id_opzione_pagamento UNIQUEIDENTIFIER);

INSERT INTO opzioni_pagamento
    (id_opzione_pagamento, id_pd_debitore, tipologia, giorni, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
OUTPUT inserted.id, inserted.id_opzione_pagamento INTO #tmp_vrs_soglia_id (id, id_opzione_pagamento)
SELECT id_opzione_pagamento, id_pd_debitore, tipologia, giorni, stato, 0, SYSDATETIME(), SYSDATETIME()
FROM #tmp_vrs_soglia;

UPDATE v
SET v.id_opzione_pagamento = idc.id,
    v.numero_rata = 1
FROM versamenti v
JOIN #tmp_vrs_soglia vs ON vs.id = v.id
JOIN #tmp_vrs_soglia_id idc ON idc.id_opzione_pagamento = vs.id_opzione_pagamento;

-- ---------------------------------------------------------------------------
-- Cascata della mutua esclusione: un'opzione ancora DISPONIBILE sulla stessa
-- posizione (di qualunque suo debitore) di un'opzione ATTIVATA e' un'alternativa non piu' applicabile —
-- stessa semantica di PosizioneDebitoriaService.attiva() della libreria.
-- Vale su tutta la tabella, non solo sulle righe appena migrate. Prima i
-- versamenti: dopo, le opzioni da annullare non sarebbero piu'
-- riconoscibili (non sarebbero piu' DISPONIBILE).
-- ---------------------------------------------------------------------------

UPDATE v
SET v.stato_versamento = 'ANNULLATO',
    v.data_ora_ultimo_aggiornamento = SYSDATETIME()
FROM versamenti v
WHERE v.stato_versamento = 'NON_ESEGUITO'
  AND v.id_opzione_pagamento IN (
      SELECT op.id FROM opzioni_pagamento op
       JOIN pd_debitori pd ON pd.id = op.id_pd_debitore
       WHERE op.stato = 'DISPONIBILE'
         AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
                      JOIN pd_debitori pd_altra ON pd_altra.id = altra.id_pd_debitore
                      WHERE pd_altra.id_documento = pd.id_documento
                        AND altra.stato = 'ATTIVATA'));

UPDATE op
SET op.stato = 'ANNULLATA',
    op.data_ultimo_aggiornamento = SYSDATETIME()
FROM opzioni_pagamento op
JOIN pd_debitori pd ON pd.id = op.id_pd_debitore
WHERE op.stato = 'DISPONIBILE'
  AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
               JOIN pd_debitori pd_altra ON pd_altra.id = altra.id_pd_debitore
               WHERE pd_altra.id_documento = pd.id_documento
                 AND altra.stato = 'ATTIVATA');

COMMIT TRANSACTION;

-- ---------------------------------------------------------------------------
-- Report diagnostico sui documenti esclusi dalla sintesi automatica sopra —
-- da eseguire ed esaminare DOPO lo script, non parte dell'automazione.
-- Gruppi (documento + debitore) con cod_rata genuinamente anomalo: non tutti NULL, non tutti
-- numerici validi senza duplicati, e non tutti valori-soglia riconosciuti
-- senza duplicati — pattern misto tra le tre famiglie, duplicato di
-- valore-soglia nello stesso documento, o dato corrotto. Da rivedere a mano.
-- ---------------------------------------------------------------------------

SELECT
    id_documento,
    chiave AS debitore_identificativo,
    COUNT(*) AS totale,
    SUM(flag_null) AS n_null,
    SUM(flag_numerico) AS n_numerico,
    COUNT(DISTINCT valore_numerico) AS n_numerico_distinti,
    SUM(flag_soglia) AS n_soglia,
    COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) AS n_soglia_distinti
FROM #tmp_vrs_rata
GROUP BY id_documento, chiave
HAVING NOT (SUM(flag_null) = COUNT(*))
   AND NOT (SUM(flag_numerico) = COUNT(*) AND COUNT(DISTINCT valore_numerico) = COUNT(*))
   AND NOT (SUM(flag_soglia) = COUNT(*) AND COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) = COUNT(*));

-- Debitori migrati senza alcuna opzione di pagamento: non dovrebbero esistere
-- (la transazione lo impedisce); se compaiono, derivano da dati scritti fuori
-- da questo script e vanno completati a mano.
SELECT pd.id AS id_pd_debitori, pd.id_documento, d.identificativo
FROM pd_debitori pd
JOIN debitori d ON d.id = pd.id_debitore
WHERE NOT EXISTS (SELECT 1 FROM opzioni_pagamento op WHERE op.id_pd_debitore = pd.id);

DROP TABLE #tmp_vrs_rata;
DROP TABLE #tmp_gruppi;
DROP TABLE #tmp_nuove_opzioni;
DROP TABLE #tmp_nuove_opzioni_id;
DROP TABLE #tmp_vrs_soglia;
DROP TABLE #tmp_vrs_soglia_id;
