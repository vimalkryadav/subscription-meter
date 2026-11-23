import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import deploy_broker as broker

IMAGE = "ghcr.io/vimalkryadav/control-usage@sha256:" + "a" * 64
OLD = "ghcr.io/vimalkryadav/control-usage@sha256:" + "b" * 64
SHA = "1" * 40


class DeployBrokerTest(unittest.TestCase):
    def setUp(self):
        self.state = Path(tempfile.mkdtemp())
        self.manifest = {"phase": "stable", "active": "blue", "slots": {"blue": {"image": IMAGE, "sha": SHA}}}
        (self.state / "release.json").write_text(json.dumps(self.manifest))
        self.patches = [mock.patch.object(broker.bg, "STATE", self.state), mock.patch.object(broker.bg, "prepare_image")]
        for p in self.patches: p.start()
        self.started = []

    def tearDown(self):
        for p in self.patches: p.stop()

    def run_deploy(self, healths, previous=OLD, image=IMAGE, sha=SHA):
        states = iter(healths)
        with mock.patch.object(broker, "running_image", return_value=previous), \
             mock.patch.object(broker, "health", side_effect=lambda env: next(states)), \
             mock.patch.object(broker, "start", side_effect=lambda img, env: self.started.append(img)):
            return broker.deploy(image, sha, timeout=30, sleep=lambda s: None, clock=iter(range(0, 10_000, 1)).__next__)

    def stored(self): return json.loads((self.state / "release.json").read_text())

    def test_deploys_active_release_image_and_records_it(self):
        self.assertEqual(self.run_deploy(["starting", "healthy"]), "deployed")
        self.assertEqual(self.started, [IMAGE])
        self.assertEqual(self.stored()["broker"], {"image": IMAGE, "sha": SHA})

    def test_unhealthy_broker_restores_previous_image(self):
        with self.assertRaisesRegex(RuntimeError, "previous image restored"):
            self.run_deploy(["unhealthy", "healthy"])
        self.assertEqual(self.started, [IMAGE, OLD])
        self.assertNotIn("broker", self.stored())

    def test_refuses_images_other_than_the_active_release(self):
        with self.assertRaisesRegex(RuntimeError, "active release"):
            self.run_deploy([], image=OLD)
        with self.assertRaisesRegex(RuntimeError, "active release"):
            self.run_deploy([], sha="2" * 40)
        self.assertEqual(self.started, [])

    def test_refuses_during_an_unfinished_release(self):
        self.manifest["phase"] = "switching"
        (self.state / "release.json").write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(RuntimeError, "unfinished"):
            self.run_deploy([])

    def test_same_healthy_image_is_a_no_op(self):
        self.assertEqual(self.run_deploy(["healthy"], previous=IMAGE), "unchanged")
        self.assertEqual(self.started, [])


if __name__ == "__main__":
    unittest.main()
