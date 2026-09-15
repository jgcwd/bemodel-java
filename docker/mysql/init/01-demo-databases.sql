-- Grant Flyway's application user the privileges required by the project's
-- original migration scripts, including CREATE DATABASE in V2.
GRANT CREATE ON *.* TO 'bemodel'@'%';
