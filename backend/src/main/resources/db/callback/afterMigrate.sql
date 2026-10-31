-- Runs after every Flyway migrate (PR-1). When the API connects as a role other than the migration owner
-- (DB_USER vs FLYWAY_USER), make sure that role exists and can read and write every table, but owns nothing, so
-- row-level security applies to it. Nothing happens when both are the same role (development default).
DO $body$
DECLARE
    -- The space after the opening quote tag matters: a dollar sign right before the placeholder escapes it.
    app text := btrim($q$ ${approle} $q$);
    pw text := substr($q$ ${apppassword}$q$, 2);
BEGIN
    IF app = '' OR app = current_user THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        IF pw = '' THEN
            RAISE EXCEPTION 'Database role % does not exist; create it or set DB_PASSWORD so it can be created', app;
        END IF;
        EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE', app, pw);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app AND (rolsuper OR rolbypassrls)) THEN
        RAISE WARNING 'Database role % is a superuser or bypasses RLS: tenant row-level security does not apply', app;
    END IF;
    -- CREATE on the schema: document numbering creates one sequence per organization and key at run time.
    EXECUTE format('GRANT USAGE, CREATE ON SCHEMA public TO %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', app);
    EXECUTE format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO %I', app);
    EXECUTE format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO %I', app);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
            app);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', app);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO %I', app);
    -- Read-only reporting views (V111) for the /bi/datasets exports.
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'reporting') THEN
        EXECUTE format('GRANT USAGE ON SCHEMA reporting TO %I', app);
        EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA reporting TO %I', app);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA reporting GRANT SELECT ON TABLES TO %I', app);
    END IF;
END $body$;
