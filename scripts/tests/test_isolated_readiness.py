import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch


def fixture_module():
    spec=importlib.util.spec_from_file_location("isolated_fixture",Path(__file__).with_name("isolated_deployment.py"))
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Clock:
    now=0
    def monotonic(self):return self.now
    def sleep(self,seconds):self.now+=seconds


class ReadinessTests(unittest.TestCase):
    def test_accepting_server_without_application_database_is_not_ready(self):
        fixture=fixture_module(); clock=Clock()
        # pg_isready can report acceptance while the requested database does not exist.
        results=[SimpleNamespace(returncode=0),SimpleNamespace(returncode=2,stdout=""),
                 SimpleNamespace(returncode=0),SimpleNamespace(returncode=0,stdout="1\n")]
        with patch.object(fixture,"time",clock),patch.object(fixture,"command",side_effect=results) as command:
            fixture.wait_database("fixture",timeout=5)
        self.assertEqual(command.call_count,4)
        for call in command.call_args_list:
            args=call.args[0]
            self.assertEqual(args[args.index("-h")+1],"127.0.0.1")
            self.assertEqual(args[args.index("-d")+1],"meter")
        self.assertEqual(clock.now,1)

    def test_deadline_exhaustion_fails_instead_of_running_migrations(self):
        fixture=fixture_module(); clock=Clock()
        with patch.object(fixture,"time",clock),patch.object(fixture,"command",return_value=SimpleNamespace(returncode=1)) as command:
            with self.assertRaisesRegex(RuntimeError,"did not become ready over TCP"):
                fixture.wait_database("fixture",timeout=2)
        self.assertEqual(command.call_count,2)
        self.assertEqual(clock.now,2)


if __name__=="__main__":unittest.main()
