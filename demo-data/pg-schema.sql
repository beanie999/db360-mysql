-- PostgreSQL demo schema for the DB360 demo. Run as the postgres superuser against db360,
-- after the demo and newrelic roles exist.
-- login_audit is deliberately left without secondary indexes so history/report queries scan it.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Lets the NRDOT receiver collect query plans for locking and write statements
-- without giving the monitoring user write access.
CREATE SCHEMA IF NOT EXISTS otel;
GRANT USAGE ON SCHEMA otel TO newrelic;

CREATE OR REPLACE FUNCTION otel.explain_statement(l_query text)
RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_plan json;
    v_param_count int;
    v_nulls text := '';
    i int;
BEGIN
    SET TRANSACTION READ ONLY;
    SET plan_cache_mode = force_generic_plan;
    EXECUTE 'PREPARE otel_explain_stmt AS ' || l_query;

    SELECT COALESCE(array_length(parameter_types, 1), 0) INTO v_param_count
    FROM pg_prepared_statements WHERE name = 'otel_explain_stmt';

    IF v_param_count > 0 THEN
        FOR i IN 1..v_param_count LOOP
            v_nulls := v_nulls || CASE WHEN i > 1 THEN ', ' ELSE '' END || 'null';
        END LOOP;
        v_nulls := '(' || v_nulls || ')';
    END IF;

    EXECUTE 'EXPLAIN (FORMAT JSON) EXECUTE otel_explain_stmt' || v_nulls INTO v_plan;
    DEALLOCATE otel_explain_stmt;
    RETURN v_plan;
EXCEPTION WHEN OTHERS THEN
    IF EXISTS (SELECT 1 FROM pg_prepared_statements WHERE name = 'otel_explain_stmt') THEN
        DEALLOCATE otel_explain_stmt;
    END IF;
    RAISE;
END;
$$;

GRANT EXECUTE ON FUNCTION otel.explain_statement(text) TO newrelic;

SET ROLE demo;

DROP TABLE IF EXISTS login_audit;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
  id              SERIAL PRIMARY KEY,
  username        TEXT NOT NULL UNIQUE,
  email           TEXT NOT NULL,
  password_hash   TEXT NOT NULL,
  failed_attempts INT  NOT NULL DEFAULT 0,
  locked_until    TIMESTAMPTZ,
  last_login      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE login_audit (
  id         BIGSERIAL PRIMARY KEY,
  username   TEXT NOT NULL,
  success    BOOLEAN NOT NULL,
  reason     TEXT,
  source_ip  TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

RESET ROLE;
