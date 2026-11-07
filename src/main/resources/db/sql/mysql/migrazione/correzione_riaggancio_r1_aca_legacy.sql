-- ---------------------------------------------------------------------------
-- Stessa logica di postgresql/migrazione/correzione_riaggancio_r1_aca_legacy.sql
-- (leggere quel file per il ragionamento completo e per l'avviso sulla
-- finestra di esecuzione obbligatoria, qui riassunto solo in breve) — qui la
-- sintassi MySQL, NON verificata contro un'istanza reale.
--
-- Correttivo one-shot per il gap del passo R1 di riallineamento-v3.sql (nel
-- repository centralizzato, non modificabile da qui): un versamento
-- riagganciato da R1 a un'opzione gia' esistente puo' essere stato
-- comunicato ad ACA sotto v1 prima del riaggancio, senza che aca_legacy
-- venga impostato.
--
-- ATTENZIONE - FINESTRA DI ESECUZIONE OBBLIGATORIA: sicuro SOLO una volta,
-- dopo che riallineamento-v3.sql (incluso R1) e' stato completato, e PRIMA
-- che lo step v3 di govpay-aca-batch invii qualcosa per la prima volta. Il
-- writer v3 marca anch'esso data_ultima_comunicazione_aca sui propri invii
-- legittimi: eseguito dopo, questo criterio rimetterebbe erroneamente in
-- legacy posizioni gia' correttamente instradate in v3.
--
-- Deliberatamente non numerato nella sequenza 01-06. Idempotente.
-- ---------------------------------------------------------------------------

UPDATE documenti d
SET d.aca_legacy = true
WHERE d.aca_legacy = false
  AND EXISTS (
      SELECT 1
        FROM versamenti v
       WHERE v.id_documento = d.id
         AND v.id_opzione_pagamento IS NOT NULL
         AND v.data_ultima_comunicazione_aca IS NOT NULL
  );
