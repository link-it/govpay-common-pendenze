-- ---------------------------------------------------------------------------
-- Migrazione di un DB GovPay v2 esistente alla struttura v3 (api-pendenze-v3).
--
-- Dialetto: Oracle — traduzione NON verificata contro un'istanza reale
-- (solo il dialetto postgresql, sorella di questa cartella, e' stato
-- verificato: e' quello su cui girano i test dei moduli
-- govpay-common-pendenze/govpay-pendenze-api). Sintassi allineata ai pattern
-- gia' in uso nei patch del core per Oracle (vedi
-- src/main/resources/db/sql/oracle/patch/3.9.sql e 3.10.0.sql): ALTER TABLE
-- senza la parola chiave COLUMN, NUMBER al posto di BOOLEAN/BIGINT/INT.
--
-- Script a se stanti, NON parte della catena di patch versionate del core
-- GovPay — vanno eseguiti a parte su un DB esistente, in ordine di
-- numerazione (01..04), una sola volta. Oracle non supporta "ADD COLUMN IF
-- NOT EXISTS": se uno script va rieseguito, va reso manualmente idempotente
-- o verificato a mano prima di rilanciarlo.
--
-- Convenzione dei valori sentinella: dove serve un default per righe v2 gia'
-- esistenti su colonne NOT NULL nuove, si usa un valore ovviamente non
-- plausibile come dato reale (1970-01-01 per le date, -1 per numero_rata),
-- cosi' e' sempre riconoscibile "questo record e' stato inserito dalla v2" —
-- mai un valore verosimile (es. CURRENT_TIMESTAMP) che potrebbe passare per un
-- dato genuino. Nessun codice v3 legge mai questi valori per righe v2 (un
-- cliente e' sempre o v2 o v3, mai entrambi sullo stesso record).
--
-- rpt/pagamenti/fr/rendicontazioni non hanno bisogno di alcuna modifica: le
-- colonne che servono a v3 esistono gia' tutte in produzione (vedi
-- docs/proposta-modello-nativo-v3.md).
-- ---------------------------------------------------------------------------

ALTER TABLE documenti ADD id_unita_operativa NUMBER;

-- Nullable, nessuna sentinella necessaria: NULL significa "pubblicata subito"
-- (semantica dello YAML v3). Per i documenti esistenti 06 la ricava dalla data
-- di pubblicazione dei versamenti, dove c'e'.
ALTER TABLE documenti ADD data_pubblicazione DATE;

-- Booleano rappresentato come NUMBER (0/1), come da convenzione del core per
-- Oracle (vedi es. utenze.abilitato, tipi_versamento.autorizzazione_*_star).
ALTER TABLE documenti ADD data_ultima_modifica_aca TIMESTAMP;
ALTER TABLE documenti ADD data_ultima_comunicazione_aca TIMESTAMP;

-- Sentinella 1970-01-01: v2 non traccia una data di creazione/aggiornamento
-- del documento. 05 e 06 la sostituiscono, per i documenti con versamenti, con
-- le date dei versamenti (la minima di creazione, la massima di aggiornamento);
-- resta solo sui documenti senza versamenti.
ALTER TABLE documenti ADD data_creazione TIMESTAMP DEFAULT TO_TIMESTAMP('1970-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') NOT NULL;
ALTER TABLE documenti ADD data_ultimo_aggiornamento TIMESTAMP DEFAULT TO_TIMESTAMP('1970-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') NOT NULL;

-- Posizione anomala: analoghi di versamenti.anomalo e versamenti.descrizione_stato
-- a livello di posizione. Il DEFAULT resta dopo lo script: chi inserisce
-- documenti senza conoscere le colonne (05 compreso) continua a funzionare.
ALTER TABLE documenti ADD anomalo NUMBER DEFAULT 0 NOT NULL;
ALTER TABLE documenti ADD descrizione_stato VARCHAR2(255 CHAR);

-- Lock ottimistico senza questa colonna, aggiungere un'opzione di pagamento e
-- attivarne un'altra sulla stessa posizione possono correre in parallelo
-- senza che nessuno dei due veda le modifiche dell'altro.
ALTER TABLE documenti ADD versione NUMBER DEFAULT 0 NOT NULL;

-- Nessun vincolo aggiuntivo da aggiungere qui: l'identita' pubblica (idPosizioneDebitoria,
-- l'IUPD standard pagoPA GPD) si deriva al volo da idA2A+idDominio+cod_documento, che
-- incorpora gia' tutte e tre le colonne del vincolo legacy unique_documenti_1
-- (cod_documento+id_applicazione+id_dominio) — nessun duplicato pubblico puo' esistere senza
-- che quel vincolo lo impedisca gia'.
