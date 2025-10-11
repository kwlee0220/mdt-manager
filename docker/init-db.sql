-- Create mdt user if not exists
DO
$$
BEGIN
  IF NOT EXISTS (
    SELECT FROM pg_catalog.pg_roles
    WHERE rolname = 'mdt'
  ) THEN
    CREATE USER mdt WITH PASSWORD 'mdt2025';
  END IF;
END
$$;

-- Create mdt database if not exists
SELECT 'CREATE DATABASE mdt OWNER mdt'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'mdt')\gexec

-- Create mdt_app database if not exists
SELECT 'CREATE DATABASE mdt_app OWNER mdt'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'mdt_app')\gexec

-- Grant privileges to mdt user on databases
GRANT ALL PRIVILEGES ON DATABASE mdt TO mdt;
GRANT ALL PRIVILEGES ON DATABASE mdt_app TO mdt;

-- Connect to mdt database and grant schema privileges
\c mdt
GRANT ALL ON SCHEMA public TO mdt;

-- Connect to mdt_app database and grant schema privileges
\c mdt_app
GRANT ALL ON SCHEMA public TO mdt;

