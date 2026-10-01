#!/usr/bin/env python3
"""Verify real schema migration/upserts with an explicitly selected SQLite binary."""
import pathlib
import re
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[2]
sqlite = sys.argv[1] if len(sys.argv) > 1 else "sqlite3"
queries = root / "app/shared/src/commonMain/sqldelight/finance/shilling/shared/db"
legacy = (root / "app/shared/test@jvm/finance/shilling/shared/data/LegacySchemaFixture.kt").read_text().split('"""')[1]
commands = ["PRAGMA foreign_keys=ON;", legacy,
 "INSERT INTO accounts VALUES ('account','Before',42);",
 "INSERT INTO schedules VALUES ('schedule','Rent',5,'EXPENSE','account',NULL,NULL,1,NULL,'ONCE',1,NULL,NULL,NULL,0,0,NULL);",
 "INSERT INTO schedule_exceptions VALUES ('schedule',1,0,NULL,NULL,NULL);",
 "INSERT INTO postings VALUES ('posting','schedule','EXPENSE','account',1,5,NULL,'Rent',NULL);",
 "INSERT INTO receipts VALUES ('receipt','posting','file','file.jpg',1,NULL,NULL,NULL);",
 "INSERT INTO receipt_files VALUES ('receipt',X'010203',3,'image/jpeg');",
 "PRAGMA foreign_keys=OFF; BEGIN;", (queries / "1.sqm").read_text(),
 "PRAGMA user_version=2; COMMIT; PRAGMA foreign_keys=ON;"]
# First INSERT preserves the published positional argument order. All grouped writes
# must avoid replacing a parent row and dropping its children on old SQLite.
values = dict(space_id="'__local__'", id="'unused'", name="'Updated'", balance="99", color="NULL",
 title="'Updated'", amount="25", type="'EXPENSE'", account_id="'account'", counter_account_id="NULL",
 category_id="NULL", start_date="1", end_date="NULL", freq="'ONCE'", interval_="1", by_day_mask="NULL",
 by_month_day="NULL", nth_weekday="NULL", last_day_flag="0", auto_pay="0", notes="NULL", schedule_id="'schedule'",
 date="1", skip="0", override_amount="NULL", override_account_id="NULL", override_counter_account_id="NULL",
 pair_id="NULL", posting_id="'posting'", file_path="'file'", original_name="'file.jpg'", added_at="1", receipt_date="NULL",
 receipt_id="'receipt'", file_bytes="X'010203'", size_bytes="3", mime_type="'image/jpeg'", entity_type="'POSTING'",
 entity_id="'posting'", timestamp="1", kind="'home'", legacy_files="0")
for name, entity in [("Account","account"),("Category","category"),("Schedule","schedule"),
 ("ScheduleException","unused"),("Posting","posting"),("Receipt","receipt"),("ReceiptFile","unused"),
 ("Bookkeeping","unused"),("Space","unused")]:
 text = (queries / (name + ".sq")).read_text()
 statement = re.search(r"upsert\s*\{(.*?)\}", text, re.S).group(1)
 parameters = {**values, "id": "'" + entity + "'"}
 statement = re.sub(r":(\w+)", lambda match: parameters[match[1]], statement)
 commands += ["BEGIN;", statement, statement, "COMMIT;"]
commands += ["PRAGMA foreign_key_check;",
 "SELECT balance FROM accounts WHERE id='account' AND space_id='__local__';",
 "SELECT count(*) FROM schedule_exceptions;",
 "SELECT posting_id FROM receipts WHERE id='receipt' AND space_id='__local__';",
 "SELECT hex(file_bytes) FROM receipt_files WHERE receipt_id='receipt' AND space_id='__local__';",
 "SELECT legacy_files FROM local_spaces WHERE space_id='__local__';"]
# Claiming legacy data must cascade scoped foreign keys to the first hosted space.
commands += ["BEGIN;"]
for table in ["accounts", "categories", "schedules", "schedule_exceptions", "postings", "receipts", "receipt_files", "bookkeeping", "local_spaces"]:
 commands.append(f"UPDATE {table} SET space_id='home' WHERE space_id='__local__';")
commands += ["COMMIT; PRAGMA foreign_key_check;",
 "SELECT count(*) FROM receipt_files WHERE space_id='home';",
 "INSERT INTO accounts VALUES ('business','account','Separate',100);",
 "SELECT count(*) FROM accounts WHERE id='account';"]
result = subprocess.run([sqlite, "-batch", "-bail", ":memory:"], input="\n".join(commands), text=True, capture_output=True)
assert result.returncode == 0, result.stderr
assert result.stdout.splitlines() == ["99.0", "1", "posting", "010203", "1", "1", "2"], result.stdout
print(subprocess.check_output([sqlite, "--version"], text=True).split()[0], "migration, parent updates, receipt bytes, legacy claim and isolated keys passed")
