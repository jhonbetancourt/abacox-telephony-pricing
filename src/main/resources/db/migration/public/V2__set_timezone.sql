-- Set the database timezone to America/Bogota dynamically for the current database
DO $$ 
BEGIN 
  EXECUTE 'ALTER DATABASE ' || current_database() || ' SET timezone TO ''America/Bogota'''; 
END $$;
ALTER ROLE CURRENT_USER SET timezone TO 'America/Bogota';
