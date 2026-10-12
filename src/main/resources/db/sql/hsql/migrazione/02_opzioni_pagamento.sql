-- ---------------------------------------------------------------------------
-- Tabella nuova (non esiste nel legacy): la macchina a stati delle opzioni di
-- pagamento alternative (DISPONIBILE/ATTIVATA/ANNULLATA) — v2 non ha alcuna
-- forma di mutua esclusione tra versamenti "fratelli", nemmeno manuale.
-- Nessuna riga esistente da migrare: tabella vuota alla creazione.
--
-- id_opzione_pagamento e' BINARY(16): e' la rappresentazione binaria in cui
-- Hibernate 7 serializza un campo Java UUID per il dialetto HSQL (verificato
-- generando lo schema con SchemaManagementToolCoordinator per HSQLDialect
-- contro l'esatta versione di Hibernate usata dal progetto, non per lettura
-- di documentazione) — NON e' una stringa testuale.
--
-- L'id usa una sequenza esplicita (CREATE SEQUENCE + colonna BIGINT semplice,
-- senza clausola GENERATED), non IDENTITY: l'entity JPA dichiara
-- GenerationType.SEQUENCE (@SequenceGenerator su seq_opzioni_pagamento) —
-- Hibernate quindi interroga la sequenza e fornisce l'id esplicito
-- nell'INSERT, che una colonna IDENTITY (auto-generante in proprio)
-- rifiuterebbe o ignorerebbe. Verificato generando lo schema con
-- SchemaManagementToolCoordinator per la stessa combinazione entity+dialetto.
-- ---------------------------------------------------------------------------

CREATE SEQUENCE seq_opzioni_pagamento START WITH 1 INCREMENT BY 1;

CREATE TABLE opzioni_pagamento
(
	id_opzione_pagamento BINARY(16) NOT NULL,
	tipologia VARCHAR(35) NOT NULL,
	giorni INT,
	stato VARCHAR(35) NOT NULL,
	versione BIGINT NOT NULL,
	data_inizio_validita DATE,
	data_scadenza DATE,
	data_creazione TIMESTAMP NOT NULL,
	data_ultimo_aggiornamento TIMESTAMP NOT NULL,
	-- fk/pk columns
	id BIGINT NOT NULL,
	id_documento BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_opzioni_pagamento_id_opzione UNIQUE (id_opzione_pagamento),
	-- fk/pk keys constraints
	CONSTRAINT fk_opz_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
	CONSTRAINT pk_opzioni_pagamento PRIMARY KEY (id)
);
