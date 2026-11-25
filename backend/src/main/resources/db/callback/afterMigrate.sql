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

-- Embedded Superset (V570, ADR-S078): superset_reader reads every view in `bi`, including views a later migration adds.
-- The API role gets nothing there: those views are not filtered by row-level security.
DO $bi$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'bi')
            AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'superset_reader') THEN
        GRANT USAGE ON SCHEMA bi TO superset_reader;
        GRANT SELECT ON ALL TABLES IN SCHEMA bi TO superset_reader;
    END IF;
END $bi$;

-- Finance readiness audit B4 (V655): trigger functions in public name tables unqualified; pin their search_path with
-- pg_temp last so a temporary table cannot shadow "JournalEntry" and blind a guard. Repeated here so trigger functions
-- added by later migrations are pinned too. A function the migration role does not own is skipped with a warning.
DO $pin$
DECLARE f record;
BEGIN
    FOR f IN SELECT p.oid::regprocedure AS sig FROM pg_proc p
              WHERE p.pronamespace = 'public'::regnamespace
                AND (p.prorettype = 'trigger'::regtype
                     OR p.proname IN ('evidence_purge_enabled', 'evidence_row_changed'))
                AND NOT coalesce(array_to_string(p.proconfig, ',') LIKE '%search_path=public, pg_temp%', false)
    LOOP
        BEGIN
            EXECUTE format('ALTER FUNCTION %s SET search_path = public, pg_temp', f.sig);
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE WARNING 'B4: cannot pin the search_path of % (not its owner)', f.sig;
        END;
    END LOOP;
END $pin$;

-- Finance readiness audit B4: the backup role (DB_BACKUP_USER / DB_BACKUP_PASSWORD). pg_dump needs to read every row
-- (BYPASSRLS, pg_read_all_data) and a restore test creates a new database (CREATEDB); it owns nothing and cannot write
-- the live tables. Creating a BYPASSRLS role needs a superuser: otherwise a warning asks for it to be created by hand.
DO $backup$
DECLARE
    role text := btrim($q$ ${backuprole} $q$);
    pw text := substr($q$ ${backuppassword}$q$, 2);
BEGIN
    IF role = '' OR pw = '' OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role) THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolsuper) THEN
        RAISE WARNING 'B4: backup role % does not exist and % may not create a BYPASSRLS role; create it as a superuser'
            ' (docs/OPERATIONS.md, "Separate migration step and backup role")', role, current_user;
        RETURN;
    END IF;
    EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER BYPASSRLS CREATEDB NOCREATEROLE', role, pw);
    EXECUTE format('GRANT pg_read_all_data TO %I', role);
END $backup$;
