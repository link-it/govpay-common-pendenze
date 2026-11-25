-- ---------------------------------------------------------------------------
-- Ampliamento di documenti.descrizione e sintesi di un documento per ogni
-- versamento "orfano" (id_documento NULL).
--
-- Stesse motivazioni/precondizioni di postgresql/migrazione/05_documenti_orfani.sql
-- (leggere quel file per il dettaglio) — qui solo la sintassi Oracle.
--
-- Decodifica base64 adattata da govpay-aca-batch (sql/oracle/create-db.sql,
-- vista v_versamenti_gpd, gia' in uso presso i clienti) — NON riverificata
-- qui contro un'istanza Oracle reale, va riconfermata prima dell'uso in
-- produzione (stessa cautela di 01_documenti.sql).
-- ---------------------------------------------------------------------------

ALTER TABLE documenti MODIFY (descrizione VARCHAR2(1024 CHAR));

-- NVL, non "COALESCE con stringa vuota": Oracle non distingue una stringa
-- vuota da NULL, quindi una causale NULL produrrebbe comunque NULL e
-- violerebbe il NOT NULL su descrizione. Un singolo spazio e' il piu' vicino
-- a "vuoto" rappresentabile.
INSERT INTO documenti (id, id_dominio, id_applicazione, cod_documento, descrizione,
                       id_unita_operativa, data_creazione, data_ultimo_aggiornamento)
SELECT
    seq_documenti.NEXTVAL,
    v.id_dominio,
    v.id_applicazione,
    'MIGR-' || v.id,
    NVL(
        CASE WHEN v.causale_versamento IS NOT NULL THEN
            UTL_RAW.CAST_TO_VARCHAR2(
                UTL_ENCODE.BASE64_DECODE(
                    UTL_RAW.CAST_TO_RAW(SUBSTR(v.causale_versamento, 4))
                )
            )
        END,
        ' '
    ),
    -- Il documento e il suo unico versamento hanno lo stesso beneficiario e le
    -- stesse date: la UO si copia com'e' (l'id della UO EC resta l'id della UO EC).
    -- Le date ACA del documento restano NULL: non si ricavano dalle date dei
    -- versamenti (vedi 06).
    v.id_uo,
    v.data_creazione,
    v.data_ora_ultimo_aggiornamento
FROM versamenti v
WHERE v.id_documento IS NULL;

UPDATE versamenti v
SET id_documento = (
    SELECT d.id FROM documenti d WHERE d.cod_documento = 'MIGR-' || v.id
)
WHERE v.id_documento IS NULL;
