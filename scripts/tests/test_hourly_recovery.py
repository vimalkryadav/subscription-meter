import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import hourly_recovery as recovery

class RecoveryTest(unittest.TestCase):
    def checks(self):return {key:('ok',0,'') for key in recovery.monitor.CHECKS}
    def test_only_verified_pressure_and_zero_lag_allow_repair(self):
        checks=self.checks();self.assertEqual(recovery.decision(checks,False),'healthy')
        checks['audit_guard']=('failed',0,'');checks['gateway']=('failed',0,'')
        self.assertEqual(recovery.decision(checks,True),'prune_committed')
        self.assertEqual(recovery.decision(checks,False),'needs_attention')
        checks['worker_lag']=('ok',1,'');self.assertEqual(recovery.decision(checks,True),'needs_attention')
    def test_unknown_partial_auth_disk_and_unsettled_are_not_auto_fixed(self):
        self.assertEqual(recovery.decision({},True),'needs_attention')
        for key in recovery.monitor.CHECKS:
            for state in ('unknown','failed'):
                checks=self.checks();checks[key]=(state,0,'')
                self.assertEqual(recovery.decision(checks,False),'needs_attention')
