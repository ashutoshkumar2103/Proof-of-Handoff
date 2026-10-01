ALTER TABLE handoff ADD COLUMN return_wait_requested_at TIMESTAMP NULL;
ALTER TABLE handoff ADD COLUMN return_wait_reason VARCHAR(1000) NULL;
ALTER TABLE handoff ADD COLUMN return_wait_requested_by_name VARCHAR(200) NULL;
