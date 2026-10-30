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
--
-- Su un db del core, v_versamenti (gov_pay.sql) legge documenti.descrizione:
-- PostgreSQL rifiuta l'ALTER di una colonna letta da una vista ("cannot
-- alter type of a column used by a view or rule"). Va eliminata e
-- ricreata con la stessa definizione di gov_pay.sql — non e' una vista di
-- questa libreria, quindi se non esiste (deployment senza core) il DROP
-- IF EXISTS e' un no-op e la CREATE la aggiunge comunque, senza effetti
-- indesiderati (la stessa definizione del core, sulle stesse colonne).
DROP VIEW IF EXISTS v_versamenti;
ALTER TABLE documenti ALTER COLUMN descrizione TYPE VARCHAR(1024);
CREATE VIEW v_versamenti AS
SELECT versamenti.id,
    versamenti.cod_versamento_ente,
    versamenti.nome,
    versamenti.importo_totale,
    versamenti.stato_versamento,
    versamenti.descrizione_stato,
    versamenti.aggiornabile,
    versamenti.tassonomia,
    versamenti.tassonomia_avviso,
    versamenti.data_creazione,
    versamenti.data_validita,
    versamenti.data_scadenza,
    versamenti.data_ora_ultimo_aggiornamento,
    versamenti.causale_versamento,
    versamenti.debitore_identificativo,
    versamenti.debitore_tipo,
    versamenti.debitore_anagrafica,
    versamenti.debitore_indirizzo,
    versamenti.debitore_civico,
    versamenti.debitore_cap,
    versamenti.debitore_localita,
    versamenti.debitore_provincia,
    versamenti.debitore_nazione,
    versamenti.debitore_email,
    versamenti.debitore_telefono,
    versamenti.debitore_cellulare,
    versamenti.debitore_fax,
    versamenti.cod_lotto,
    versamenti.cod_versamento_lotto,
    versamenti.cod_anno_tributario,
    versamenti.cod_bundlekey,
    versamenti.dati_allegati,
    versamenti.incasso,
    versamenti.anomalie,
    versamenti.iuv_versamento,
    versamenti.numero_avviso,
    versamenti.ack,
    versamenti.anomalo,
    versamenti.direzione,
    versamenti.divisione,
    versamenti.id_sessione,
    versamenti.importo_pagato,
    versamenti.data_pagamento,
    versamenti.importo_incassato,
    versamenti.stato_pagamento,
    versamenti.iuv_pagamento,
    versamenti.src_debitore_identificativo,
    versamenti.src_iuv,
    versamenti.cod_rata,
    versamenti.tipo,
    versamenti.data_notifica_avviso,
    versamenti.avviso_notificato,
    versamenti.avv_mail_data_prom_scadenza,
    versamenti.avv_mail_prom_scad_notificato,
    versamenti.avv_app_io_data_prom_scadenza,
    versamenti.avv_app_io_prom_scad_notificat,
    versamenti.id_applicazione,
    versamenti.id_dominio,
    versamenti.id_uo,
    versamenti.id_tipo_versamento,
    versamenti.id_tipo_versamento_dominio,
    versamenti.proprieta,
    versamenti.data_ultima_modifica_aca,
    versamenti.data_ultima_comunicazione_aca,
    versamenti.id_documento,
    documenti.cod_documento,
    documenti.descrizione AS doc_descrizione
    FROM versamenti LEFT JOIN documenti ON versamenti.id_documento = documenti.id;

-- Decodifica sicura: decode(...,'base64')/convert_from(...,'UTF8') lanciano
-- un'eccezione su un base64 o un UTF-8 non validi, interrompendo l'intero
-- script — un controllo di formato prima non basterebbe comunque a
-- escludere un base64 sintatticamente valido che decodifica a byte non
-- UTF-8. La funzione intercetta ENTRAMBI i casi e torna NULL (poi diventa
-- '' col COALESCE sotto, come per una causale assente); vive in pg_temp,
-- sparisce da sola a fine sessione.
CREATE OR REPLACE FUNCTION pg_temp.gp_decodifica_causale(causale TEXT) RETURNS TEXT AS $$
BEGIN
    RETURN convert_from(decode(substring(causale, 3), 'base64'), 'UTF8');
EXCEPTION
    WHEN OTHERS THEN
        RETURN NULL;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

WITH nuovi_documenti AS (
    INSERT INTO documenti (id, id_dominio, id_applicazione, cod_documento, descrizione)
    SELECT
        nextval('seq_documenti'),
        v.id_dominio,
        v.id_applicazione,
        'MIGR-' || v.id,
        COALESCE(pg_temp.gp_decodifica_causale(v.causale_versamento), '')
    FROM versamenti v
    WHERE v.id_documento IS NULL
    RETURNING id, cod_documento
)
UPDATE versamenti v
SET id_documento = nd.id
FROM nuovi_documenti nd
WHERE v.id_documento IS NULL
  AND nd.cod_documento = 'MIGR-' || v.id;

DROP FUNCTION IF EXISTS pg_temp.gp_decodifica_causale(TEXT);
