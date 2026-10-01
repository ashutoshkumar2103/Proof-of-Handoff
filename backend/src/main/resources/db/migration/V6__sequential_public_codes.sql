-- Switch handoff public codes from random strings (e.g. HO-CJKRP17X) to readable,
-- sequential codes based on the record id: HO-1, HO-2, ... The public code is a display
-- reference only and never grants access (recipient access uses hashed per-handoff tokens),
-- so sequential codes are safe. New codes larger than the column width are impossible for
-- realistic id ranges.
UPDATE handoff SET public_code = CONCAT('HO-', id);
