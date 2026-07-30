GRANT USAGE ON SCHEMA sample TO fault_lab_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON sample.orders TO fault_lab_role;
GRANT SELECT, UPDATE ON sample.inventory TO fault_lab_role;
