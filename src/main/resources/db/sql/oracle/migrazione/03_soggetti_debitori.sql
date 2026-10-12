-- ---------------------------------------------------------------------------
-- Tabella nuova (non esiste nel legacy): tutti i debitori della posizione,
-- incluso il primo (ordine 0) — in v2 il debitore vive solo denormalizzato su
-- versamenti.debitore_*, un solo soggetto per versamento. Nessuna riga
-- esistente da migrare: tabella vuota alla creazione.
--
-- id/sequenza/trigger seguono lo stesso pattern gia' in uso nei patch del
-- core per una nuova tabella con chiave generata (vedi
-- src/main/resources/db/sql/oracle/patch/3.9.sql, tabella jppa_config).
-- ---------------------------------------------------------------------------

CREATE SEQUENCE seq_soggetti_debitori MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 INCREMENT BY 1 CACHE 2 NOCYCLE;

CREATE TABLE soggetti_debitori
(
	ordine NUMBER NOT NULL,
	tipo VARCHAR2(1) NOT NULL,
	identificativo VARCHAR2(35) NOT NULL,
	anagrafica VARCHAR2(70),
	indirizzo VARCHAR2(70),
	civico VARCHAR2(16),
	cap VARCHAR2(16),
	localita VARCHAR2(35),
	provincia VARCHAR2(35),
	nazione VARCHAR2(2),
	email VARCHAR2(256),
	-- fk/pk columns
	id NUMBER NOT NULL,
	id_documento NUMBER NOT NULL,
	-- unique constraints
	CONSTRAINT unique_soggetti_debitori_1 UNIQUE (id_documento, ordine),
	-- fk/pk keys constraints
	CONSTRAINT fk_sgd_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
	CONSTRAINT pk_soggetti_debitori PRIMARY KEY (id)
);

CREATE TRIGGER trg_soggetti_debitori
BEFORE
insert on soggetti_debitori
for each row
begin
   IF (:new.id IS NULL) THEN
      SELECT seq_soggetti_debitori.nextval INTO :new.id
                FROM DUAL;
   END IF;
end;
/
