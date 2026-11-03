-- Spec §9. Runs as accounts_owner (Flyway); the application connects as accounts_app.

CREATE TABLE account (
    account_id  varchar(64)   NOT NULL,
    currency    char(3)       NOT NULL,
    type        varchar(16)   NOT NULL,
    status      varchar(8)    NOT NULL,
    balance     numeric(19,2),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT account_pk PRIMARY KEY (account_id),
    -- Target of entry_line's composite FK: a line's currency must match its account's.
    CONSTRAINT account_id_currency_uk UNIQUE (account_id, currency),
    CONSTRAINT account_type_ck CHECK (type IN ('CLIENT', 'COBRE_POSITION', 'COBRE_FUNDING')),
    CONSTRAINT account_status_ck CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    -- CLIENT: stored balance, never negative. Cobre accounts: computed from lines (D10).
    CONSTRAINT account_balance_ck CHECK (
        (type = 'CLIENT' AND balance IS NOT NULL AND balance >= 0)
        OR (type <> 'CLIENT' AND balance IS NULL))
);

CREATE TABLE journal_entry (
    entry_id         uuid          NOT NULL,
    source_event_id  varchar(128)  NOT NULL,
    posted_at        timestamptz   NOT NULL,
    recorded_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT journal_entry_pk PRIMARY KEY (entry_id),
    CONSTRAINT journal_entry_source_event_uk UNIQUE (source_event_id)
);

CREATE TABLE entry_line (
    entry_id    uuid           NOT NULL,
    line_no     smallint       NOT NULL,
    account_id  varchar(64)    NOT NULL,
    direction   varchar(6)     NOT NULL,
    amount      numeric(19,2)  NOT NULL,
    currency    char(3)        NOT NULL,
    posted_at   timestamptz    NOT NULL,
    CONSTRAINT entry_line_pk PRIMARY KEY (entry_id, line_no),
    CONSTRAINT entry_line_entry_fk FOREIGN KEY (entry_id) REFERENCES journal_entry (entry_id),
    CONSTRAINT entry_line_account_currency_fk FOREIGN KEY (account_id, currency)
        REFERENCES account (account_id, currency),
    CONSTRAINT entry_line_direction_ck CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT entry_line_amount_ck CHECK (amount > 0)
);

CREATE INDEX entry_line_account_posted_idx ON entry_line (account_id, posted_at, entry_id);

-- Only CBMM events get a decision; opening entries do not (spec §6.3).
CREATE TABLE settlement_decision (
    source_event_id  varchar(128)  NOT NULL,
    decision         varchar(8)    NOT NULL,
    reason_code      varchar(32),
    reason_detail    varchar(64),
    entry_id         uuid,
    payload_hash     varchar(128)  NOT NULL,
    load_id          uuid          NOT NULL,
    decided_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT settlement_decision_pk PRIMARY KEY (source_event_id),
    CONSTRAINT settlement_decision_entry_fk FOREIGN KEY (entry_id) REFERENCES journal_entry (entry_id),
    -- One entry, one decision.
    CONSTRAINT settlement_decision_entry_uk UNIQUE (entry_id),
    CONSTRAINT settlement_decision_decision_ck CHECK (decision IN ('APPLIED', 'REJECTED')),
    -- APPLIED has an entry and no reason; REJECTED has a reason and no entry.
    CONSTRAINT settlement_decision_outcome_ck CHECK (
        (decision = 'APPLIED' AND entry_id IS NOT NULL AND reason_code IS NULL)
        OR (decision = 'REJECTED' AND reason_code IS NOT NULL AND entry_id IS NULL))
);

-- D13: the journal is append-only for the application.
GRANT USAGE ON SCHEMA public TO accounts_app;
-- Only the CLIENT balance changes; currency, type and status never do (spec §3.4, §9).
-- Column-level UPDATE is also what allows SELECT ... FOR UPDATE on account (D12).
GRANT SELECT, INSERT ON account TO accounts_app;
GRANT UPDATE (balance) ON account TO accounts_app;
GRANT SELECT, INSERT ON journal_entry, entry_line, settlement_decision TO accounts_app;
