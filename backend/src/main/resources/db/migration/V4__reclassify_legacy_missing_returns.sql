-- Legacy data fix. Older returns applied condition MISSING to units that were actually
-- returned (present) — e.g. "returned 13" was stored as "13 (Missing)". Under the current
-- model MISSING means "not returned / lost", entered as a separate quantity. Reclassify
-- those legacy lines as GOOD so history reflects that they were returned. Any genuinely
-- missing units can be recorded again via the return form. On a fresh database this
-- affects no rows.
UPDATE return_line SET item_condition = 'GOOD' WHERE item_condition = 'MISSING';
