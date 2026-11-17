package finance.shilling.shared.data

// Published v1 schema fixture: preserves the actual pre-space upgrade layout.
internal val legacySchemaSql = """
CREATE TABLE accounts(
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    balance REAL NOT NULL
);
CREATE TABLE categories(
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    color TEXT
);
CREATE TABLE schedules(
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    amount REAL NOT NULL,
    type TEXT NOT NULL,
    account_id TEXT,
    counter_account_id TEXT,
    category_id TEXT,
    start_date INTEGER NOT NULL,
    end_date INTEGER,
    freq TEXT NOT NULL,
    interval_ INTEGER NOT NULL DEFAULT 1,
    by_day_mask INTEGER,
    by_month_day INTEGER,
    nth_weekday INTEGER,
    last_day_flag INTEGER NOT NULL DEFAULT 0,
    auto_pay INTEGER NOT NULL DEFAULT 0,
    notes TEXT
);

CREATE INDEX schedules_date_idx ON schedules(start_date, end_date);
CREATE TABLE schedule_exceptions(
    schedule_id TEXT NOT NULL,
    date INTEGER NOT NULL,
    skip INTEGER NOT NULL DEFAULT 0,
    override_amount REAL,
    override_account_id TEXT,
    override_counter_account_id TEXT,
    PRIMARY KEY(schedule_id, date),
    FOREIGN KEY(schedule_id) REFERENCES schedules(id) ON DELETE CASCADE
);
CREATE TABLE postings(
    id TEXT NOT NULL PRIMARY KEY,
    schedule_id TEXT,
    type TEXT NOT NULL,
    account_id TEXT NOT NULL,
    date INTEGER NOT NULL,
    amount REAL NOT NULL,
    pair_id TEXT,
    title TEXT,
    category_id TEXT,
    FOREIGN KEY(schedule_id) REFERENCES schedules(id)
);

CREATE INDEX postings_date_idx ON postings(date);
CREATE TABLE receipts(
    id TEXT NOT NULL PRIMARY KEY,
    posting_id TEXT,
    file_path TEXT NOT NULL,
    original_name TEXT NOT NULL,
    added_at INTEGER NOT NULL,
    receipt_date INTEGER,
    amount REAL,
    notes TEXT,
    FOREIGN KEY(posting_id) REFERENCES postings(id) ON DELETE SET NULL
);
CREATE TABLE receipt_files(
    receipt_id TEXT NOT NULL PRIMARY KEY,
    file_bytes BLOB NOT NULL,
    size_bytes INTEGER NOT NULL,
    mime_type TEXT,
    FOREIGN KEY(receipt_id) REFERENCES receipts(id) ON DELETE CASCADE
);
CREATE TABLE bookkeeping(
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    PRIMARY KEY(entity_type, entity_id)
);
CREATE TABLE change_log (
  household_id TEXT NOT NULL,
  change_id TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  op TEXT NOT NULL,
  timestamp INTEGER NOT NULL,
  payload_json TEXT,
  PRIMARY KEY (household_id, change_id)
);

CREATE INDEX idx_change_log_household_timestamp ON change_log(household_id, timestamp);

""".trimIndent()
