import importlib.util
from pathlib import Path
import tempfile
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"scripts"))


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DeploymentTests(unittest.TestCase):
    def test_audit_cohort_defaults_jdbc_and_pins_kafka_worker(self):
        deploy=load("blue_green")
        self.assertEqual(deploy.release_env({})["METER_AUDIT_TRANSPORT"],"jdbc")
        image="ghcr.io/vimalkryadav/control-usage@sha256:"+"a"*64
        env=deploy.release_env({"audit":{"transport":"kafka","workerImage":image,"contractVersion":1}})
        self.assertEqual(env["AUDIT_WORKER_IMAGE"],image)
        self.assertEqual(env["METER_AUDIT_KAFKA_BOOTSTRAP_SERVERS"],"audit-kafka:9092")
        with self.assertRaises(ValueError):
            deploy.release_env({"audit":{"transport":"kafka","workerImage":"latest"}})

    def test_route_switch_preserves_allowlist_and_stream_policy(self):
        deploy = load("blue_green")
        initial = (ROOT / "deploy/public-gateway.Caddyfile").read_text()
        first = deploy.render_config(initial, "green", "a" * 40)
        second = deploy.render_config(first, "blue", "b" * 40)
        self.assertNotIn("reverse_proxy app:", first)
        self.assertNotIn("reverse_proxy app-green:", second)
        self.assertEqual(second.count("rewrite * /releases/"), 1)
        self.assertIn("/releases/" + "b" * 40 + "/index.html", second)
        self.assertIn('respond "Not found" 404', second)
        self.assertIn("flush_interval -1", second)
        self.assertNotIn("/internal", second)

    def test_bootstrap_only_pauses_new_gateway_and_auth_routes(self):
        bootstrap=load("bootstrap_blue_green")
        initial=(ROOT/"deploy/public-gateway.Caddyfile").read_text()
        paused=bootstrap.maintenance_config(initial)
        self.assertIn('@bootstrapPause path /gateway/* /portal/api/* /admin/*',paused)
        self.assertIn('header Retry-After "60"',paused)
        self.assertEqual(initial.count('reverse_proxy app:8180'),paused.count('reverse_proxy app:8180'))
        self.assertIn('admin off',paused)

    def test_migration_checksums_and_transaction_lock(self):
        migrate = load("migrate")
        sql = migrate.migration_sql()
        self.assertLess(sql.index("pg_advisory_xact_lock"), sql.index("CREATE TABLE IF NOT EXISTS members"))
        self.assertIn("Migration checksum mismatch", sql)
        self.assertTrue(sql.rstrip().endswith("COMMIT;"))
        self.assertNotIn("CREATE EXTENSION", sql)
        self.assertIn("CREATE EXTENSION", migrate.migration_sql(timescale=True))

    def test_duplicate_migration_versions_rejected(self):
        migrate = load("migrate")
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            target = root / "deploy/migrations"
            target.mkdir(parents=True)
            (target / "V001__first.sql").write_text("SELECT 1;")
            (target / "V001__second.sql").write_text("SELECT 2;")
            with self.assertRaises(ValueError):
                migrate.migration_sql(root)

    def test_release_values_cannot_inject_caddy_directives(self):
        deploy = load("blue_green")
        with self.assertRaises(ValueError):
            deploy.render_config("", "green\nadmin :2019", "a"*40)
        with self.assertRaises(ValueError):
            deploy.render_config("", "green", "../")


if __name__ == "__main__":
    unittest.main()
