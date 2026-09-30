-- ---------------------------------------------------------------------------
-- Tabella nuova (non esiste nel legacy): la macchina a stati delle opzioni di
-- pagamento alternative (DISPONIBILE/ATTIVATA/ANNULLATA) — v2 non ha alcuna
-- forma di mutua esclusione tra versamenti "fratelli", nemmeno manuale.
-- Nessuna riga esistente da migrare: tabella vuota alla creazione.
--
-- id_opzione_pagamento e' RAW(16): e' la rappresentazione binaria in cui
-- Hibernate 7 serializza un campo Java UUID per il dialetto Oracle (verificato
-- generando lo schema con SchemaManagementToolCoordinator per
-- OracleDialect contro l'esatta versione di Hibernate usata dal progetto,
-- non per lettura di documentazione) — NON e' una stringa testuale.
--
-- id/sequenza/trigger seguono lo stesso pattern gia' in uso nei patch del
-- core per una nuova tabella con chiave generata (vedi
-- src/main/resources/db/sql/oracle/patch/3.9.sql, tabella jppa_config).
-- ---------------------------------------------------------------------------

CREATE SEQUENCE seq_opzioni_pagamento MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 INCREMENT BY 1 CACHE 2 NOCYCLE;

CREATE TABLE opzioni_pagamento
(
	id_opzione_pagamento RAW(16) NOT NULL,
	tipologia VARCHAR2(35) NOT NULL,
	giorni NUMBER,
	stato VARCHAR2(35) NOT NULL,
	versione NUMBER NOT NULL,
	data_inizio_validita DATE,
	data_scadenza DATE,
	data_creazione TIMESTAMP NOT NULL,
	data_ultimo_aggiornamento TIMESTAMP NOT NULL,
	-- fk/pk columns
	id NUMBER NOT NULL,
	id_documento NUMBER NOT NULL,
	-- unique constraints
	CONSTRAINT unique_opzioni_pagamento_id_opzione UNIQUE (id_opzione_pagamento),
	-- fk/pk keys constraints
	CONSTRAINT fk_opz_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
	CONSTRAINT pk_opzioni_pagamento PRIMARY KEY (id)
);

CREATE TRIGGER trg_opzioni_pagamento
BEFORE
insert on opzioni_pagamento
for each row
begin
   IF (:new.id IS NULL) THEN
      SELECT seq_opzioni_pagamento.nextval INTO :new.id
                FROM DUAL;
   END IF;
end;
/
