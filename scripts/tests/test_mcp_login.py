import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
spec=importlib.util.spec_from_file_location('mcp_login',Path(__file__).resolve().parents[2]/'mcp/login.py')
login=importlib.util.module_from_spec(spec);spec.loader.exec_module(login)
class McpLoginTest(unittest.TestCase):
    def test_origin_validation_and_private_origin_bound_storage(self):
        for value in ('http://example.com','https://user:pass@example.com','https://example.com/manage','https://example.com?token=x','https://example.com\n.evil'):
            with self.assertRaises(ValueError):login.site_origin(value)
        self.assertEqual(login.site_origin('https://EXAMPLE.com:443/'),'https://example.com')
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'private/session.json';login.save_session(path,'https://example.com','a'*43)
            self.assertEqual(path.stat().st_mode&0o777,0o600)
            self.assertEqual(json.loads(path.read_text()),{'version':1,'origin':'https://example.com','session':'a'*43})
            link=Path(directory)/'link';link.symlink_to(path)
            with self.assertRaises(ValueError):login.save_session(link,'https://example.com','b'*43)
            self.assertEqual(json.loads(path.read_text())['session'],'a'*43)
    def test_redirects_never_forward_login_credentials(self):
        class Req:full_url='https://example.com/portal/api/login'
        with self.assertRaises(urllib.error.HTTPError):login.NoRedirect().redirect_request(Req(),None,302,'found',{},'https://evil.invalid')
