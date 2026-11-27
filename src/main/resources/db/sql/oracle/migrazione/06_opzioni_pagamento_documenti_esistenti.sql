-- ---------------------------------------------------------------------------
-- Stessa logica di postgresql/migrazione/06_opzioni_pagamento_documenti_esistenti.sql
-- (leggere quel file per il ragionamento completo: raggruppamento dei
-- versamenti per debitore, tre pattern A/B/C, cascata di mutua esclusione) —
-- qui la sintassi Oracle, NON verificata contro un'istanza Oracle reale.
--
-- Atomico: ogni DDL (CREATE/DROP TABLE) fa commit implicito, quindi le
-- tabelle d'appoggio sono create TUTTE in testa e il blocco DML che segue e'
-- una sola transazione, chiusa da un unico COMMIT prima del report;
-- WHENEVER SQLERROR (SQL*Plus, impostato in testa) annulla tutto al primo
-- errore. Le tabelle di appoggio si eliminano in fondo, e all'inizio di ogni
-- esecuzione se un errore precedente le ha lasciate.
--
-- Oltre ai gruppi, lo script ricava dai versamenti le colonne di documenti che v2
-- non ha (date, UO): vedi il blocco dopo il pattern C. Il report in fondo elenca
-- anche i documenti rimasti senza UO per UO discordanti.
--
-- Oracle non supporta CTE scrivibili (niente "WITH cte AS (INSERT ...
-- RETURNING ...) UPDATE ..." come in PostgreSQL): la classificazione viene
-- materializzata in tabelle di appoggio (DROP alla fine).
--
-- Fuso orario: la sessione che esegue lo script ha fuso Europe/Rome
-- (ALTER SESSION SET TIME_ZONE = 'Europe/Rome'): le date di creazione e di
-- aggiornamento sono quelle di LOCALTIMESTAMP.
-- ---------------------------------------------------------------------------

-- Al primo errore SQL*Plus annulla la transazione e termina: impostato prima di
-- qualunque istruzione, cosi' nessun passo prosegue su dati di appoggio incerti.
WHENEVER SQLERROR EXIT FAILURE ROLLBACK

-- Pulizia iniziale delle SOLE tabelle di appoggio di questo script (tmp_vrs_rata,
-- tmp_gruppi, tmp_vrs_soglia): dopo un errore il DROP finale non e' stato
-- eseguito e le tabelle restano; senza questa pulizia una riesecuzione
-- fallirebbe nei CREATE TABLE, o userebbe dati di una esecuzione precedente.
-- ORA-00942 (tabella assente) e' il caso normale e si ignora.
BEGIN
    FOR t IN (SELECT column_value AS nome
                FROM TABLE(sys.odcivarchar2list('TMP_VRS_RATA', 'TMP_GRUPPI', 'TMP_VRS_SOGLIA'))) LOOP
        BEGIN
            EXECUTE IMMEDIATE 'DROP TABLE ' || t.nome || ' PURGE';
        EXCEPTION
            WHEN OTHERS THEN
                IF SQLCODE <> -942 THEN RAISE; END IF;
        END;
    END LOOP;
END;
/

CREATE TABLE tmp_vrs_rata AS
SELECT
    v.id,
    v.id_documento,
    UPPER(v.debitore_identificativo) AS chiave,
    v.cod_rata,
    v.stato_versamento,
    CASE WHEN v.cod_rata IS NULL THEN 1 ELSE 0 END AS flag_null,
    CASE WHEN REGEXP_LIKE(v.cod_rata, '^[1-9][0-9]*$') THEN 1 ELSE 0 END AS flag_numerico,
    CASE WHEN REGEXP_LIKE(v.cod_rata, '^[1-9][0-9]*$') THEN TO_NUMBER(v.cod_rata) END AS valore_numerico,
    CASE WHEN REGEXP_LIKE(v.cod_rata, '^(RIDOTTO|SCONTATO|ENTRO[0-9]+|OLTRE[0-9]+)$') THEN 1 ELSE 0 END AS flag_soglia
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

CREATE TABLE tmp_gruppi AS
SELECT
    x.*,
    CAST(NULL AS NUMBER) AS posizione,
    CAST(NULL AS NUMBER) AS id_debitore,
    CAST(NULL AS NUMBER) AS id_pd_debitore
FROM (
    SELECT
        aggr.*,
        CASE WHEN n_null = totale THEN 'SOLUZIONE_UNICA'
             WHEN n_numerico = totale AND n_numerico_distinti = totale THEN 'PIANO_RATEALE'
        END AS tipologia,
        CASE WHEN n_soglia = totale AND n_soglia_distinti = totale THEN 1 ELSE 0 END AS e_soglia,
        CASE WHEN n_annullato = totale THEN 'ANNULLATA'
             WHEN n_altro > 0 THEN 'ATTIVATA'
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
            COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) AS n_numerico_distinti,
            SUM(flag_soglia) AS n_soglia,
            COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) AS n_soglia_distinti,
            SUM(CASE WHEN stato_versamento = 'ANNULLATO' THEN 1 ELSE 0 END) AS n_annullato,
            SUM(CASE WHEN stato_versamento NOT IN ('NON_ESEGUITO', 'ANNULLATO') THEN 1 ELSE 0 END) AS n_altro
        FROM tmp_vrs_rata
        GROUP BY id_documento, chiave
    ) aggr
) x
WHERE x.tipologia IS NOT NULL OR x.e_soglia = 1;

-- Pattern C: le righe da migrare (una per versamento), preparate qui perche' e' un DDL.
-- Il collegamento al pd_debitori si completa piu' sotto, quando gli id sono assegnati.
CREATE TABLE tmp_vrs_soglia AS
SELECT
    vc.id,
    CAST(NULL AS NUMBER) AS id_pd_debitore,
    g.id_documento,
    g.chiave,
    SYS_GUID() AS id_opzione_pagamento,
    CASE WHEN vc.cod_rata LIKE 'ENTRO%' THEN 'SOLUZIONE_UNICA_ENTRO'
         WHEN vc.cod_rata LIKE 'OLTRE%' THEN 'SOLUZIONE_UNICA_OLTRE'
         WHEN vc.cod_rata = 'SCONTATO' THEN 'SOLUZIONE_UNICA_SCONTATO'
         ELSE 'SOLUZIONE_UNICA_RIDOTTO'
    END AS tipologia,
    CASE WHEN vc.cod_rata LIKE 'ENTRO%' OR vc.cod_rata LIKE 'OLTRE%'
         THEN TO_NUMBER(SUBSTR(vc.cod_rata, 6))
    END AS giorni,
    CASE WHEN vc.stato_versamento = 'NON_ESEGUITO' THEN 'DISPONIBILE'
         WHEN vc.stato_versamento = 'ANNULLATO' THEN 'ANNULLATA'
         ELSE 'ATTIVATA'
    END AS stato
FROM tmp_vrs_rata vc
JOIN tmp_gruppi g ON g.id_documento = vc.id_documento AND g.chiave = vc.chiave
WHERE g.tipologia IS NULL AND g.e_soglia = 1;

-- Da qui in poi solo DML: una sola transazione, chiusa dal COMMIT prima del report.

-- Un gruppo gia' presente come pd_debitori dello stesso documento (stesso
-- identificativo) non si duplica: protegge da una riesecuzione dopo una
-- correzione manuale parziale.
DELETE FROM tmp_gruppi g
 WHERE EXISTS (SELECT 1 FROM pd_debitori pd JOIN debitori d ON d.id = pd.id_debitore
                WHERE pd.id_documento = g.id_documento AND UPPER(d.identificativo) = g.chiave);

-- Ordine dei debitori nel documento: prima comparsa. Gli id vengono dalle
-- sequenze delle due tabelle (NEXTVAL e' ammesso nella SET di una UPDATE,
-- valutato una volta per riga).
UPDATE tmp_gruppi g
   -- Dopo i debitori gia' migrati del documento: una riesecuzione non duplica l'ordine.
   SET posizione = COALESCE((SELECT MAX(pd.posizione) + 1 FROM pd_debitori pd
                              WHERE pd.id_documento = g.id_documento), 0)
                   + (SELECT COUNT(*) FROM tmp_gruppi g2
                       WHERE g2.id_documento = g.id_documento
                         AND g2.primo_versamento < g.primo_versamento),
       id_debitore = seq_debitori.NEXTVAL,
       id_pd_debitore = seq_pd_debitori.NEXTVAL;

-- Collegamento delle righe pattern C al pd_debitori del loro gruppo; quelle di
-- gruppi gia' migrati (tolti da tmp_gruppi) non hanno corrispondenza e si scartano.
UPDATE tmp_vrs_soglia vs
   SET id_pd_debitore = (SELECT g.id_pd_debitore FROM tmp_gruppi g
                          WHERE g.id_documento = vs.id_documento AND g.chiave = vs.chiave);
DELETE FROM tmp_vrs_soglia WHERE id_pd_debitore IS NULL;

-- Anagrafica del debitore: copiata dal primo versamento del gruppo.
-- debitore_tipo e' nullable in v2, tipo no: se manca, G per una partita IVA
-- (11 cifre), F altrimenti.
INSERT INTO debitori
    (id, tipo, identificativo, anagrafica, indirizzo, civico, cap, localita,
     provincia, nazione, email, cellulare)
SELECT
    g.id_debitore,
    COALESCE(v.debitore_tipo, CASE WHEN REGEXP_LIKE(v.debitore_identificativo, '^[0-9]{11}$') THEN 'G' ELSE 'F' END),
    v.debitore_identificativo, v.debitore_anagrafica,
    v.debitore_indirizzo, v.debitore_civico, v.debitore_cap, v.debitore_localita,
    v.debitore_provincia, v.debitore_nazione, v.debitore_email, v.debitore_cellulare
FROM tmp_gruppi g
JOIN versamenti v ON v.id = g.primo_versamento;

INSERT INTO pd_debitori (id, id_documento, id_debitore, posizione)
SELECT g.id_pd_debitore, g.id_documento, g.id_debitore, g.posizione
FROM tmp_gruppi g;

-- Data di pubblicazione: il documento prende la data piu' tarda tra quelle dei suoi versamenti,
-- troncata al giorno, cosi' nessuna pendenza e' pagabile prima della propria data. Una data
-- gia' presente sul documento non viene toccata.
UPDATE documenti d SET data_pubblicazione =
    (SELECT TRUNC(MAX(v.data_pubblicazione)) FROM versamenti v WHERE v.id_documento = d.id)
WHERE d.data_pubblicazione IS NULL
  AND d.id IN (SELECT g.id_documento FROM tmp_gruppi g)
  AND EXISTS (SELECT 1 FROM versamenti v WHERE v.id_documento = d.id AND v.data_pubblicazione IS NOT NULL);

INSERT INTO opzioni_pagamento
    (id, id_opzione_pagamento, id_pd_debitore, tipologia, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT
    seq_opzioni_pagamento.NEXTVAL,
    SYS_GUID(),
    g.id_pd_debitore,
    g.tipologia,
    g.stato,
    0,
    LOCALTIMESTAMP,
    LOCALTIMESTAMP
FROM tmp_gruppi g
WHERE g.tipologia IS NOT NULL;

-- numero_rata: valore dichiarato preservato (PIANO_RATEALE) oppure, per
-- SOLUZIONE_UNICA (cod_rata NULL), posizione 1-based per id crescente dentro
-- il gruppo. Limitato ai gruppi A/B: un gruppo con pattern C ha piu' opzioni
-- sotto lo stesso pd_debitori (una per versamento) — la sottoquery scalare
-- qui sotto resterebbe ambigua (ORA-01427) se applicata anche a quelli.
UPDATE versamenti v
SET (id_opzione_pagamento, numero_rata) = (
    SELECT o.id,
           COALESCE(
               vr.valore_numerico,
               (SELECT COUNT(*) FROM tmp_vrs_rata vr2
                WHERE vr2.id_documento = vr.id_documento AND vr2.chiave = vr.chiave AND vr2.id <= vr.id)
           )
    FROM tmp_vrs_rata vr
    JOIN tmp_gruppi g ON g.id_documento = vr.id_documento AND g.chiave = vr.chiave
    JOIN opzioni_pagamento o ON o.id_pd_debitore = g.id_pd_debitore
    WHERE vr.id = v.id AND g.tipologia IS NOT NULL
)
WHERE v.id IN (
    SELECT vr.id FROM tmp_vrs_rata vr
    JOIN tmp_gruppi g ON g.id_documento = vr.id_documento AND g.chiave = vr.chiave
    WHERE g.tipologia IS NOT NULL
);

-- ---------------------------------------------------------------------------
-- Pattern C: ENTRO/OLTRE espliciti, inclusa la coppia RIDOTTO/SCONTATO. Una
-- opzione PER VERSAMENTO (cardinalita' massima 1 per queste tipologie), non
-- un'aggregazione per gruppo come A/B — solo se TUTTO il gruppo e' fatto di
-- valori-soglia riconosciuti e senza duplicati. Il GUID si genera prima,
-- come colonna: serve a ritrovare l'id della singola opzione appena
-- inserita per collegarla al SUO versamento (un debitore con piu' soglie,
-- es. ENTRO+OLTRE, ha piu' opzioni sotto lo stesso pd_debitori — il solo
-- id_pd_debitore non basta a distinguerle).
-- ---------------------------------------------------------------------------

INSERT INTO opzioni_pagamento
    (id, id_opzione_pagamento, id_pd_debitore, tipologia, giorni, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT
    seq_opzioni_pagamento.NEXTVAL,
    vs.id_opzione_pagamento,
    vs.id_pd_debitore,
    vs.tipologia,
    vs.giorni,
    vs.stato,
    0,
    LOCALTIMESTAMP,
    LOCALTIMESTAMP
FROM tmp_vrs_soglia vs;

UPDATE versamenti v
SET (id_opzione_pagamento, numero_rata) = (
    SELECT o.id, 1
    FROM tmp_vrs_soglia vs
    JOIN opzioni_pagamento o ON o.id_opzione_pagamento = vs.id_opzione_pagamento
    WHERE vs.id = v.id
)
WHERE v.id IN (SELECT id FROM tmp_vrs_soglia);

-- ---------------------------------------------------------------------------
-- Colonne di documenti che v2 ha solo sui versamenti. Valgono per ogni
-- documento con versamenti, anche per quelli che i passi sopra non migrano.
--
-- Date: data_creazione e' la minima dei versamenti, data_ultimo_aggiornamento la
-- massima. Si sostituisce solo la sentinella 1970-01-01 di 01_documenti.sql: un
-- documento scritto da v3 ha date proprie e non si tocca.
--
-- Date ACA (data_ultima_modifica_aca, data_ultima_comunicazione_aca): NON si
-- migrano, restano NULL. Il massimo dei versamenti nasconderebbe quelli ancora
-- da inviare, e la comunicazione di un versamento v2 non dimostra che la
-- posizione aggregata sia gia' stata comunicata: le date vanno decise insieme
-- alla migrazione di govpay-aca-batch. Finche' la modifica e' NULL la posizione
-- non e' selezionata per l'invio.
--
-- Unita' operativa: v2 la salva sempre su versamenti.id_uo (quella di default e'
-- la riga uo con codice EC, con il suo id reale) e la copia e' fedele, senza
-- trasformare l'id. Si copia solo se tutti i versamenti del documento hanno la
-- stessa UO: con UO diverse, o con qualche versamento senza UO, la scelta
-- decide chi vede la posizione e non si fa a caso; il documento resta con UO
-- NULL e finisce nel report in fondo al file. I documenti scritti da v3 non
-- cambiano: i loro versamenti non hanno id_uo.
-- ---------------------------------------------------------------------------

UPDATE documenti d
SET (data_creazione, data_ultimo_aggiornamento) = (
    SELECT MIN(v.data_creazione), MAX(v.data_ora_ultimo_aggiornamento)
    FROM versamenti v
    WHERE v.id_documento = d.id
)
WHERE d.data_creazione = TO_TIMESTAMP('1970-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')
  AND EXISTS (SELECT 1 FROM versamenti v WHERE v.id_documento = d.id);

UPDATE documenti d
SET id_unita_operativa = (SELECT MAX(v.id_uo) FROM versamenti v WHERE v.id_documento = d.id)
WHERE d.id_unita_operativa IS NULL
  AND d.id IN (SELECT v.id_documento FROM versamenti v
                WHERE v.id_documento IS NOT NULL
                GROUP BY v.id_documento
               HAVING COUNT(v.id_uo) = COUNT(*) AND COUNT(DISTINCT v.id_uo) = 1);

-- ---------------------------------------------------------------------------
-- Cascata della mutua esclusione: un'opzione ancora DISPONIBILE sulla stessa
-- posizione (di qualunque suo debitore) di un'opzione ATTIVATA e' un'alternativa non piu' applicabile —
-- stessa semantica di PosizioneDebitoriaService.attiva() della libreria.
-- Vale su tutta la tabella, non solo sulle righe appena migrate. Prima i
-- versamenti: dopo, le opzioni da annullare non sarebbero piu'
-- riconoscibili (non sarebbero piu' DISPONIBILE).
-- ---------------------------------------------------------------------------

UPDATE versamenti v
SET stato_versamento = 'ANNULLATO',
    data_ora_ultimo_aggiornamento = LOCALTIMESTAMP
WHERE stato_versamento = 'NON_ESEGUITO'
  AND id_opzione_pagamento IN (
      SELECT op.id FROM opzioni_pagamento op
       JOIN pd_debitori pd ON pd.id = op.id_pd_debitore
       WHERE op.stato = 'DISPONIBILE'
         AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
                      JOIN pd_debitori pd_altra ON pd_altra.id = altra.id_pd_debitore
                      WHERE pd_altra.id_documento = pd.id_documento
                        AND altra.stato = 'ATTIVATA'));

UPDATE opzioni_pagamento op
SET stato = 'ANNULLATA',
    data_ultimo_aggiornamento = LOCALTIMESTAMP
WHERE op.stato = 'DISPONIBILE'
  AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
               JOIN pd_debitori pd_altra ON pd_altra.id = altra.id_pd_debitore
               WHERE altra.stato = 'ATTIVATA'
                 AND pd_altra.id_documento = (SELECT pd_op.id_documento FROM pd_debitori pd_op
                                               WHERE pd_op.id = op.id_pd_debitore));

COMMIT;

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
    COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) AS n_numerico_distinti,
    SUM(flag_soglia) AS n_soglia,
    COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) AS n_soglia_distinti
FROM tmp_vrs_rata
GROUP BY id_documento, chiave
HAVING NOT (SUM(flag_null) = COUNT(*))
   AND NOT (SUM(flag_numerico) = COUNT(*) AND COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) = COUNT(*))
   AND NOT (SUM(flag_soglia) = COUNT(*) AND COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) = COUNT(*));

-- Documenti rimasti senza unita' operativa perche' i loro versamenti ne hanno
-- di diverse, o solo alcuni ne hanno una: la UO va scelta a mano. Un documento
-- scritto da v3 con UO NULL non compare: i suoi versamenti non hanno id_uo.
SELECT v.id_documento, COUNT(*) AS totale_versamenti, COUNT(v.id_uo) AS con_uo,
       COUNT(DISTINCT v.id_uo) AS uo_distinte, MIN(v.id_uo) AS id_uo_minimo, MAX(v.id_uo) AS id_uo_massimo
FROM versamenti v
JOIN documenti d ON d.id = v.id_documento
WHERE d.id_unita_operativa IS NULL
GROUP BY v.id_documento
HAVING COUNT(DISTINCT v.id_uo) > 1
    OR (COUNT(v.id_uo) > 0 AND COUNT(v.id_uo) < COUNT(*));

-- Debitori migrati senza alcuna opzione di pagamento: non dovrebbero esistere
-- (la transazione lo impedisce); se compaiono, derivano da dati scritti fuori
-- da questo script e vanno completati a mano.
SELECT pd.id AS id_pd_debitori, pd.id_documento, d.identificativo
FROM pd_debitori pd
JOIN debitori d ON d.id = pd.id_debitore
WHERE NOT EXISTS (SELECT 1 FROM opzioni_pagamento op WHERE op.id_pd_debitore = pd.id);

DROP TABLE tmp_vrs_rata;
DROP TABLE tmp_gruppi;
DROP TABLE tmp_vrs_soglia;
