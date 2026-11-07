-- ---------------------------------------------------------------------------
-- Correttivo one-shot per il gap del passo R1 (riaggancio di un versamento
-- a un'OpzionePagamento gia' esistente, dentro riallineamento-v3.sql del
-- repository centralizzato governapay-381/governapay - non modificabile da
-- qui): un versamento creato da v2 dopo che un'opzione sorella esisteva
-- gia', e poi riagganciato da R1 a quell'opzione, puo' essere stato
-- comunicato ad ACA sotto v1 (vecchio IUPD per-pendenza) prima del
-- riaggancio, senza che nulla lo segnali - R1 non passa per
-- 06_opzioni_pagamento_documenti_esistenti.sql, quindi non valuta mai se il
-- versamento riagganciato era gia' comunicato.
--
-- Non esiste un criterio chirurgico per isolare solo i versamenti toccati da
-- R1. A differenza del caso gestito in 06 (dove la comunicazione precede
-- sempre la creazione dell'opzione, perche' l'opzione nasce in quello stesso
-- script), qui la comunicazione avviene DOPO che l'opzione esisteva gia'
-- (altrimenti R1 non avrebbe nulla da riagganciare) - la stessa identica
-- forma temporale di un invio v3 legittimo avvenuto dopo l'assegnazione
-- dell'opzione. Il confronto data_ultima_comunicazione_aca < data_creazione
-- (usato altrove) non distingue i due casi. Serve quindi il criterio
-- generico, "qualunque versamento con opzione gia' comunicato implica
-- documento legacy" - lo stesso invariante che 06 applica al momento
-- dell'aggancio, qui ri-applicato a tutta la tabella.
--
-- ATTENZIONE - FINESTRA DI ESECUZIONE OBBLIGATORIA, NON RIPETIBILE A
-- PIACERE: sicuro SOLO se eseguito una volta, dopo che riallineamento-v3.sql
-- (incluso il passo R1) e' stato completato per l'ambiente, e PRIMA che lo
-- step v3 di govpay-aca-batch venga eseguito per la prima volta su questi
-- dati. Il writer v3 (PosizioneDebitoriaGpdWriter) valorizza
-- data_ultima_comunicazione_aca anche sui propri invii legittimi: se questo
-- script viene eseguito DOPO che v3 ha gia' comunicato con successo una
-- posizione, lo stesso criterio la farebbe rientrare erroneamente in
-- legacy, interrompendo un instradamento gia' corretto.
--
-- Deliberatamente non numerato nella sequenza 01-06: non fa parte della
-- migrazione ordinaria di questo modulo, va riportato nella procedura
-- operativa di rilascio come passo distinto, nel punto esatto descritto
-- sopra. Idempotente: una volta che aca_legacy e' true non viene piu'
-- toccato da una ripetizione.
--
-- Tradotto anche per oracle/mysql/sqlserver/hsql (stessa cartella
-- migrazione/, stesso nome file) - stesso identico statement, nessuna
-- differenza strutturale tra dialetti per questo script. HSQLDB e'
-- verificato contro un'istanza reale (2.7.3), oracle/mysql/sqlserver no.
-- ---------------------------------------------------------------------------

UPDATE documenti d
SET aca_legacy = true
WHERE d.aca_legacy = false
  AND EXISTS (
      SELECT 1
        FROM versamenti v
       WHERE v.id_documento = d.id
         AND v.id_opzione_pagamento IS NOT NULL
         AND v.data_ultima_comunicazione_aca IS NOT NULL
  );
