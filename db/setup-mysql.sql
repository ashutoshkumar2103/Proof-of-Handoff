-- HandOffly — MySQL setup. Run this ONCE in MySQL Workbench (connected as root).
--
-- IMPORTANT: run the WHOLE script, not just one line.
--   In Workbench click the FIRST lightning-bolt button ("Execute all") — NOT the one
--   with the cursor (that runs only the highlighted statement).
--
-- It creates the database and an application user matching the app's default
-- credentials (handoffly / handoffly). The app (Flyway) creates all TABLES on startup,
-- so do not create tables here.

CREATE DATABASE IF NOT EXISTS handoffly
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Create the user if missing, then force the password (so re-running always fixes it).
CREATE USER IF NOT EXISTS 'handoffly'@'localhost' IDENTIFIED BY 'handoffly';
ALTER USER 'handoffly'@'localhost' IDENTIFIED BY 'handoffly';

GRANT ALL PRIVILEGES ON handoffly.* TO 'handoffly'@'localhost';
FLUSH PRIVILEGES;

-- Verify (should return one row: handoffly | localhost):
SELECT user, host FROM mysql.user WHERE user = 'handoffly';
