-- ---------------------------------------------------------------------------
-- Tabella nuova (non esiste nel legacy): la macchina a stati delle opzioni di
-- pagamento alternative (DISPONIBILE/ATTIVATA/ANNULLATA) — v2 non ha alcuna
-- forma di mutua esclusione tra versamenti "fratelli", nemmeno manuale.
-- Nessuna riga esistente da migrare: tabella vuota alla creazione.
--
-- id_opzione_pagamento e' UNIQUEIDENTIFIER: e' il tipo nativo in cui
-- Hibernate 7 serializza un campo Java UUID per il dialetto SQL Server
-- (verificato generando lo schema con SchemaManagementToolCoordinator per
-- SQLServerDialect contro l'esatta versione di Hibernate usata dal progetto,
-- non per lettura di documentazione).
--
-- L'id usa una sequenza esplicita (CREATE SEQUENCE + colonna BIGINT
-- semplice), non IDENTITY: l'entity JPA dichiara GenerationType.SEQUENCE
-- (@SequenceGenerator su seq_opzioni_pagamento) — Hibernate quindi interroga
-- la sequenza e fornisce l'id esplicito nell'INSERT, mentre una colonna
-- IDENTITY rifiuta un INSERT che specifichi un valore esplicito per quella
-- colonna (a meno di SET IDENTITY_INSERT, che Hibernate non usa). SQL Server
-- supporta CREATE SEQUENCE nativamente dalla versione 2012.
-- ---------------------------------------------------------------------------

CREATE SEQUENCE seq_opzioni_pagamento START WITH 1 INCREMENT BY 1;

CREATE TABLE opzioni_pagamento
(
	id_opzione_pagamento UNIQUEIDENTIFIER NOT NULL,
	tipologia VARCHAR(35) NOT NULL,
	giorni INT,
	stato VARCHAR(35) NOT NULL,
	versione BIGINT NOT NULL,
	data_inizio_validita DATE,
	data_scadenza DATE,
	data_creazione DATETIME2 NOT NULL,
	data_ultimo_aggiornamento DATETIME2 NOT NULL,
	-- fk/pk columns
	id BIGINT NOT NULL,
	id_documento BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_opzioni_pagamento_id_opzione UNIQUE (id_opzione_pagamento),
	-- fk/pk keys constraints
	CONSTRAINT fk_opz_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
	CONSTRAINT pk_opzioni_pagamento PRIMARY KEY (id)
);
