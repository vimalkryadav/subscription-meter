import importlib.util
from contextlib import ExitStack
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import json
import subprocess

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"scripts"))


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DeploymentTests(unittest.TestCase):
    def test_edge_verification_waits_for_two_consecutive_bounded_responses(self):
        deploy=load("blue_green");sha="a"*40;old=b"/assets/releases/"+b"b"*40+b"/app.js"
        expected=b"/assets/releases/"+sha.encode()+b"/app.js"
        class Response:
            status=200
            def __init__(self,body):self.body=body
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self,limit):self.limit=limit;return self.body[:limit]
        now=[0.0]
        def sleep(seconds):now[0]+=seconds
        for bodies in ([old,expected,old,expected,expected],[OSError(),expected,expected],
                       [expected+old,expected,expected],[b"x"*65537,expected,expected]):
            with self.subTest(bodies=len(bodies)):
                now[0]=0
                responses=[v if isinstance(v,Exception) else Response(v) for v in bodies]
                with patch.object(deploy.time,"monotonic",side_effect=lambda:now[0]),patch.object(deploy.time,"sleep",side_effect=sleep),patch.object(deploy.urllib.request,"urlopen",side_effect=responses) as request:
                    deploy.verify_edge(sha)
                    self.assertEqual(request.call_count,len(bodies))
                    self.assertEqual(request.call_args.args[0].get_header("Cache-control"),"no-cache")
        now[0]=0
        with patch.object(deploy.time,"monotonic",side_effect=lambda:now[0]),patch.object(deploy.time,"sleep",side_effect=sleep),patch.object(deploy.urllib.request,"urlopen",return_value=Response(old)) as request:
            with self.assertRaisesRegex(RuntimeError,"deadline"):deploy.verify_edge(sha,timeout=.3)
            self.assertLessEqual(request.call_count,4)
        with self.assertRaises(ValueError):deploy.verify_edge("main")

    def test_failed_switch_requires_verified_restoration(self):
        deploy=load("blue_green");old="a"*40;new="b"*40
        with patch.object(deploy,"reload_edge") as reload,patch.object(deploy,"verify_edge",side_effect=[RuntimeError("candidate failed"),None]) as verify:
            with self.assertRaises(deploy.EdgeRestoredError):deploy.switch_edge("before","candidate",old,new,{})
            self.assertEqual([c.args[0] for c in reload.call_args_list],["candidate","before"])
            self.assertEqual([c.args[0] for c in verify.call_args_list],[new,old])
        with patch.object(deploy,"reload_edge"),patch.object(deploy,"verify_edge",side_effect=[RuntimeError("candidate failed"),RuntimeError("restoration unverified")]):
            with self.assertRaisesRegex(RuntimeError,"restoration unverified") as failure:deploy.switch_edge("before","candidate",old,new,{})
            self.assertNotIsInstance(failure.exception,deploy.EdgeRestoredError)

    def test_failed_deployment_restores_state_only_after_candidate_drain(self):
        for failure_kind in ("restored","unverified","drain_failed"):
            with self.subTest(failure_kind=failure_kind),tempfile.TemporaryDirectory() as folder,ExitStack() as stack:
                deploy=load("blue_green");state=Path(folder);caddy=state/"Caddyfile";caddy.write_text("before")
                (state/"bootstrap.json").write_text('{"verified":true}')
                old={"sha":"a"*40,"image":"sha256:"+"a"*64};new={"sha":"b"*40,"image":"sha256:"+"b"*64}
                (state/"release.json").write_text(json.dumps({"phase":"stable","active":"blue","slots":{"blue":old,"green":new},"previous":{"color":"green","release":new}}))
                stack.enter_context(patch.object(deploy,"STATE",state));stack.enter_context(patch.object(deploy,"CADDY",caddy))
                for name in ("preflight_resources","verify_edge","verify_shared_audit","prepare_image","install_assets","wait_ready"):
                    stack.enter_context(patch.object(deploy,name))
                stack.enter_context(patch.object(deploy,"render_config",return_value="candidate"))
                error=RuntimeError("unverified") if failure_kind=="unverified" else deploy.EdgeRestoredError("restored")
                stack.enter_context(patch.object(deploy,"switch_edge",side_effect=error))
                calls=[]
                stack.enter_context(patch.object(deploy,"api",side_effect=lambda color,action:calls.append(action)))
                stack.enter_context(patch.object(deploy,"compose",side_effect=lambda args,env:calls.append(args[0])))
                def drain(*args):
                    calls.append("wait_drained")
                    if failure_kind=="drain_failed":raise RuntimeError("drain failed")
                stack.enter_context(patch.object(deploy,"wait_drained",side_effect=drain))
                with self.assertRaises(RuntimeError):deploy.deploy(SimpleNamespace(rollback=True,drain_seconds=370))
                manifest=json.loads((state/"release.json").read_text())
                self.assertEqual(manifest["active"],"blue")
                if failure_kind=="restored":
                    self.assertEqual(manifest["phase"],"stable");self.assertNotIn("candidate",manifest)
                    self.assertEqual(calls[-3:],["/drain","wait_drained","stop"])
                else:
                    self.assertEqual(manifest["phase"],"switching");self.assertEqual(manifest["candidate"],"green")
                    self.assertNotIn("stop",calls)

    def test_release_order_uses_actual_git_graph_and_fails_closed(self):
        deploy = load("blue_green")
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def git(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True).stdout.strip()
            git("init", "-q")
            git("config", "user.name", "Deployment test")
            git("config", "user.email", "test@example.invalid")
            def commit(message):
                git("commit", "--allow-empty", "-qm", message)
                return git("rev-parse", "HEAD")
            first = commit("first")
            second = commit("second")
            third = commit("third")
            git("checkout", "--detach", first)
            divergent = commit("divergent")
            with patch.object(deploy, "ROOT", root):
                self.assertEqual(deploy.release_order(second, second), "same")
                self.assertEqual(deploy.release_order(third, first), "stale")
                self.assertEqual(deploy.release_order(first, third), "forward")
                with self.assertRaises(RuntimeError): deploy.release_order(third, divergent)
                with self.assertRaises(RuntimeError): deploy.release_order(third, "f" * 40)
                with self.assertRaises(ValueError): deploy.release_order(third, "main")
                for requested in (third, first):
                    state = root / "state"
                    state.mkdir(exist_ok=True)
                    (state / "bootstrap.json").write_text('{"verified": true}')
                    manifest = json.dumps({"phase": "stable", "active": "blue", "slots": {"blue": {"sha": third}}})
                    (state / "release.json").write_text(manifest)
                    args = SimpleNamespace(rollback=False, sha=requested, image="sha256:" + "a" * 64)
                    with patch.object(deploy, "STATE", state), patch.object(deploy, "preflight_resources") as preflight, patch.object(deploy, "prepare_image") as image, patch.object(deploy, "verify_edge") as edge:
                        deploy.deploy(args)
                        preflight.assert_not_called()
                        image.assert_not_called()
                        edge.assert_not_called()
                    self.assertEqual((state / "release.json").read_text(), manifest)
            with patch.object(deploy, "ROOT", root / "missing"):
                with self.assertRaises(RuntimeError): deploy.release_order(first, third)

    def test_immutable_image_types_reject_tags_and_other_registries(self):
        deploy=load("blue_green")
        self.assertTrue(deploy.is_immutable_image("sha256:"+"a"*64))
        self.assertTrue(deploy.is_immutable_image("ghcr.io/vimalkryadav/control-usage@sha256:"+"b"*64))
        for image in (None,"latest","control-usage:latest","ghcr.io/vimalkryadav/control-usage:main",
                      "sha256:"+"a"*12,"sha256:"+"A"*64,"other.example/control-usage@sha256:"+"a"*64):
            with self.subTest(image=image):
                self.assertFalse(deploy.is_immutable_image(image))
                with patch.object(deploy,"run") as run:
                    with self.assertRaises(ValueError):deploy.prepare_image(image,"b"*40)
                    run.assert_not_called()

    def test_local_image_preparation_inspects_exact_id_without_pulling(self):
        deploy=load("blue_green");image="sha256:"+"a"*64;sha="b"*40
        inspected={"Id":image,"Config":{"Labels":{"org.opencontainers.image.revision":sha,"dev.control-usage.audit-contract":"1"}}}
        with patch.object(deploy,"run",return_value=SimpleNamespace(stdout=json.dumps([inspected]))) as run:
            self.assertEqual(deploy.prepare_image(image,sha),inspected)
            run.assert_called_once_with(["docker","image","inspect",image],capture_output=True)
        with patch.object(deploy,"run",side_effect=subprocess.CalledProcessError(1,["docker","image","inspect",image])) as run:
            with self.assertRaises(subprocess.CalledProcessError):deploy.prepare_image(image,sha)
            self.assertEqual(run.call_count,1)
        for changed in ({"Id":"sha256:"+"c"*64},{"Config":{"Labels":{"org.opencontainers.image.revision":"c"*40,"dev.control-usage.audit-contract":"1"}}},
                        {"Config":{"Labels":{"org.opencontainers.image.revision":sha}}}):
            with self.subTest(changed=changed),patch.object(deploy,"run",return_value=SimpleNamespace(stdout=json.dumps([{**inspected,**changed}]))):
                with self.assertRaises(RuntimeError):deploy.prepare_image(image,sha)

    def test_registry_digest_preparation_still_pulls_and_checks_revision(self):
        deploy=load("blue_green");image="ghcr.io/vimalkryadav/control-usage@sha256:"+"a"*64;sha="b"*40
        inspected={"Config":{"Labels":{"org.opencontainers.image.revision":sha,"dev.control-usage.audit-contract":"1"}}}
        with patch.object(deploy,"run",side_effect=[SimpleNamespace(stdout=""),SimpleNamespace(stdout=json.dumps([inspected]))]) as run:
            deploy.prepare_image(image,sha,True)
            self.assertEqual(run.call_args_list[0].args[0],["docker","pull",image])
            self.assertEqual(run.call_args_list[1].args[0],["docker","image","inspect",image])

    def test_local_worker_cohort_matches_running_image_id_and_contract(self):
        deploy=load("blue_green");image="sha256:"+"a"*64
        manifest={"audit":{"transport":"kafka","workerImage":image,"contractVersion":1}}
        env=deploy.release_env(manifest)
        self.assertEqual(env["AUDIT_WORKER_IMAGE"],image)
        def inspect(args,**kwargs):
            if "{{.State.Running}}" in args:return SimpleNamespace(stdout="true")
            if "{{.Image}}" in args:return SimpleNamespace(stdout=image)
            if args[:3]==["docker","image","inspect"]:return SimpleNamespace(stdout="1")
            raise AssertionError("Unexpected Docker operation")
        with patch.object(deploy,"compose",side_effect=[SimpleNamespace(stdout="broker"),SimpleNamespace(stdout="worker")]),patch.object(deploy,"run",side_effect=inspect):
            deploy.verify_shared_audit(manifest,env)
        with self.assertRaises(ValueError):
            deploy.release_env({"slots":{"blue":{"image":"control-usage:latest","sha":"b"*40}}})

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
