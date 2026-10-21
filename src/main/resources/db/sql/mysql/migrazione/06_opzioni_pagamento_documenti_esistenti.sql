-- ---------------------------------------------------------------------------
-- Stessa logica di postgresql/migrazione/06_opzioni_pagamento_documenti_esistenti.sql
-- (leggere quel file per il ragionamento completo, verificato con dati di
-- esempio su un'istanza PostgreSQL reale) — qui la sintassi MySQL, NON
-- verificata contro un'istanza MySQL reale. Richiede MySQL 8.0+ (funzioni
-- finestra, REGEXP compatibile).
--
-- MySQL non supporta CTE scrivibili: la classificazione viene materializzata
-- in tabelle temporanee di sessione (si eliminano da sole a fine sessione,
-- comunque droppate esplicitamente in fondo).
--
-- id_opzione_pagamento (BINARY(16)) e id (seq_opzioni_pagamento emulata con
-- una tabella a riga singola, vedi 02_opzioni_pagamento.sql di questa
-- cartella) seguono la stessa convenzione già in uso in questo script.
-- ---------------------------------------------------------------------------

CREATE TEMPORARY TABLE tmp_vrs_rata AS
SELECT
    v.id,
    v.id_documento,
    v.stato_versamento,
    CASE WHEN v.cod_rata IS NULL THEN 1 ELSE 0 END AS flag_null,
    CASE WHEN v.cod_rata REGEXP '^[1-9][0-9]*$' THEN 1 ELSE 0 END AS flag_numerico,
    CASE WHEN v.cod_rata REGEXP '^[1-9][0-9]*$' THEN CAST(v.cod_rata AS UNSIGNED) END AS valore_numerico,
    CASE WHEN v.cod_rata REGEXP '^(RIDOTTO|SCONTATO|ENTRO[0-9]+|OLTRE[0-9]+)$' THEN 1 ELSE 0 END AS flag_riconosciuto
FROM versamenti v
WHERE v.id_documento IS NOT NULL
  AND v.id_opzione_pagamento IS NULL;

CREATE TEMPORARY TABLE tmp_classificazione AS
SELECT
    aggr.*,
    CASE WHEN aggr.n_null = aggr.totale THEN 'SOLUZIONE_UNICA'
         WHEN aggr.n_numerico = aggr.totale AND aggr.n_numerico_distinti = aggr.totale THEN 'PIANO_RATEALE'
    END AS tipologia,
    -- Vedi tabella "derivazione dello stato" in proposta-modello-nativo-v3.md
    -- §28: il mix NON_ESEGUITO+ANNULLATO senza altro stato (n_altro=0) non
    -- rientra in nessuna delle tre categorie ammesse -> stato NULL.
    CASE WHEN aggr.n_non_eseguito = aggr.totale THEN 'DISPONIBILE'
         WHEN aggr.n_annullato = aggr.totale THEN 'ANNULLATA'
         WHEN aggr.n_altro > 0 THEN 'ATTIVATA'
    END AS stato
FROM (
    SELECT
        id_documento,
        COUNT(*) AS totale,
        SUM(flag_null) AS n_null,
        SUM(flag_numerico) AS n_numerico,
        COUNT(DISTINCT valore_numerico) AS n_numerico_distinti,
        SUM(CASE WHEN stato_versamento = 'NON_ESEGUITO' THEN 1 ELSE 0 END) AS n_non_eseguito,
        SUM(CASE WHEN stato_versamento = 'ANNULLATO' THEN 1 ELSE 0 END) AS n_annullato,
        SUM(CASE WHEN stato_versamento NOT IN ('NON_ESEGUITO', 'ANNULLATO') THEN 1 ELSE 0 END) AS n_altro,
        SUM(flag_riconosciuto) AS n_riconosciuto
    FROM tmp_vrs_rata
    GROUP BY id_documento
) aggr;

SET @cnt_nuove_opzioni = (SELECT COUNT(*) FROM tmp_classificazione WHERE tipologia IS NOT NULL AND stato IS NOT NULL);
SET @start_id = (SELECT next_val FROM seq_opzioni_pagamento);
UPDATE seq_opzioni_pagamento SET next_val = next_val + @cnt_nuove_opzioni;

CREATE TEMPORARY TABLE tmp_nuove_opzioni AS
SELECT
    @start_id + ROW_NUMBER() OVER (ORDER BY id_documento) - 1 AS id,
    id_documento, tipologia, stato
FROM tmp_classificazione
WHERE tipologia IS NOT NULL AND stato IS NOT NULL;

INSERT INTO opzioni_pagamento
    (id, id_opzione_pagamento, id_documento, tipologia, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT id, UUID_TO_BIN(UUID()), id_documento, tipologia, stato, 0, NOW(3), NOW(3)
FROM tmp_nuove_opzioni;

-- numero_rata: valore dichiarato preservato (PIANO_RATEALE) oppure, per
-- SOLUZIONE_UNICA (cod_rata NULL), posizione 1-based per id crescente dentro
-- il documento — solo per garantire indici distinti.
UPDATE versamenti v
JOIN tmp_nuove_opzioni no ON no.id_documento = v.id_documento
JOIN (
    SELECT id, id_documento,
           COALESCE(valore_numerico, ROW_NUMBER() OVER (PARTITION BY id_documento ORDER BY id)) AS numero_rata_calcolato
    FROM tmp_vrs_rata
) vn ON vn.id = v.id
SET v.id_opzione_pagamento = no.id,
    v.numero_rata = vn.numero_rata_calcolato;

-- ---------------------------------------------------------------------------
-- Report diagnostico sui documenti esclusi — da eseguire ed esaminare DOPO
-- lo script, non parte dell'automazione.
-- ---------------------------------------------------------------------------

-- 1) Pattern legacy riconosciuti ma non ancora mappati (RIDOTTO/SCONTATO,
--    ENTRO/OLTRE espliciti).
SELECT id_documento FROM tmp_classificazione
WHERE n_riconosciuto = totale AND tipologia IS NULL;

-- 2) Documenti con cod_rata genuinamente anomalo.
SELECT id_documento, totale, n_null, n_numerico, n_numerico_distinti, n_riconosciuto
FROM tmp_classificazione
WHERE tipologia IS NULL AND n_riconosciuto < totale;

-- 3) Documenti con pattern cod_rata riconosciuto ma stati storici
--    incompatibili con le 3 categorie ammesse.
SELECT id_documento, n_non_eseguito, n_annullato, n_altro, totale
FROM tmp_classificazione
WHERE tipologia IS NOT NULL AND stato IS NULL;

DROP TEMPORARY TABLE tmp_vrs_rata;
DROP TEMPORARY TABLE tmp_classificazione;
DROP TEMPORARY TABLE tmp_nuove_opzioni;
