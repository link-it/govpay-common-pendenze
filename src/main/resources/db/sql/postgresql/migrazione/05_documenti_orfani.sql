-- ---------------------------------------------------------------------------
-- Ampliamento di documenti.descrizione e sintesi di un documento per ogni
-- versamento "orfano" (id_documento NULL).
--
-- In v2 un versamento poteva restare senza documento (nessun raggruppamento,
-- avviso singolo) — concetto che v3 non ammette: ogni Pendenza appartiene
-- sempre a una PosizioneDebitoria/documento. La causale_versamento del
-- versamento diventa la descrizione del documento sintetizzato: e' l'unico
-- testo equivalente disponibile, e in v3 descrizione e' un campo
-- genuinamente funzionale (letto da AvvisoPdfPayloadMapper/
-- RicevutaPdfPayloadMapper per la stampa dell'avviso/della ricevuta), non un
-- sentinel da ignorare.
--
-- cod_documento sintetizzato = 'MIGR-'||id del VERSAMENTO (non del
-- documento): univoco per costruzione (versamenti.id e' una PK globale),
-- breve, autoesplicativo, tracciabile alla riga di origine.
--
-- Dialetto: PostgreSQL — verificato (stessa base su cui girano i test di
-- govpay-common-pendenze/govpay-pendenze-api). La decodifica base64 riusa
-- esattamente l'espressione gia' in produzione in govpay-aca-batch
-- (sql/postgresql/create-db.sql, vista v_versamenti_gpd).
--
-- Precondizioni da verificare A MANO prima di eseguire questo script (nessun
-- controllo automatico — questo script non si ferma da solo se non sono
-- rispettate):
--
-- 1) Nessun documento reale deve già usare il pattern riservato 'MIGR-%'
--    (riservato ai documenti sintetizzati qui sotto):
--    SELECT cod_documento, id_applicazione, id_dominio FROM documenti
--    WHERE cod_documento LIKE 'MIGR-%';
--
-- 2) Nessun versamento orfano deve avere una causale codificata in un
--    formato diverso da "01" (CausaleSemplice): "02"/"03" (CausaleSpezzoni/
--    CausaleSpezzoniStrutturati) non sono mai prodotti da alcun path di
--    scrittura oggi attivo (compaiono solo dentro Versamento.decode() stesso,
--    su dati storici) e questo script non li decodifica:
--    SELECT id, causale_versamento FROM versamenti
--    WHERE id_documento IS NULL AND causale_versamento IS NOT NULL
--      AND substring(causale_versamento, 1, 2) <> '01';
--    Le eventuali righe trovate vanno risolte a mano (es. causale riportata
--    al formato "01") prima di procedere.
-- ---------------------------------------------------------------------------

-- Ampliata da 255 a 1024 (stessa lunghezza di causale_versamento): la
-- causale decodificata qui sotto non va troncata, e il cruscotto la vuole
-- intera, non tagliata a video.
ALTER TABLE documenti ALTER COLUMN descrizione TYPE VARCHAR(1024);

WITH nuovi_documenti AS (
    INSERT INTO documenti (id, id_dominio, id_applicazione, cod_documento, descrizione)
    SELECT
        nextval('seq_documenti'),
        v.id_dominio,
        v.id_applicazione,
        'MIGR-' || v.id,
        COALESCE(
            CONVERT_FROM(decode(substring(v.causale_versamento, 3), 'base64'), 'UTF8'),
            ''
        )
    FROM versamenti v
    WHERE v.id_documento IS NULL
    RETURNING id, cod_documento
)
UPDATE versamenti v
SET id_documento = nd.id
FROM nuovi_documenti nd
WHERE v.id_documento IS NULL
  AND nd.cod_documento = 'MIGR-' || v.id;
