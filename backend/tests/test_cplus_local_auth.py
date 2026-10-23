"""C-F1 local authentication: private file, restart, HTTP, and fail-closed tests."""
import http.client
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import threading
import unittest
from unittest.mock import patch

from npcortex_backend.cplus_local_auth import initialize_secret, read_secret, secret_path
from npcortex_backend.server import make_http_server
from test_cplus_f1_bridge import fixture


class LocalAuthTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.secret = str(Path(self.temp.name) / "private" / "cplus-f1.secret")
        self.config = patch.dict(os.environ, {"NPCORTEX_CPLUS_F1_SECRET_FILE": self.secret}, clear=False)
        self.config.start()
        self.addCleanup(self.config.stop)
        self.manual = patch.dict(os.environ, {"NPCORTEX_CPLUS_F1_TOKEN": ""})
        self.manual.start()
        self.addCleanup(self.manual.stop)

    def test_initialize_restart_private_permissions(self):
        self.assertEqual(secret_path(), Path(self.secret))
        self.assertEqual(initialize_secret(), 'new private file')
        first = read_secret()
        self.assertEqual(len(first), 64)
        self.assertEqual(initialize_secret(), 'existing private file')
        self.assertEqual(read_secret(), first)
        if os.name != 'nt':
            self.assertEqual(Path(self.secret).stat().st_mode & 0o777, 0o600)
        self.assertNotIn(first, repr(secret_path()))

    def test_explicit_override_preserves_previous_behavior(self):
        manual='user-set-diagnostic-key-0000000000'
        with patch.dict(os.environ, {'NPCORTEX_CPLUS_F1_TOKEN': manual}):
            self.assertEqual(initialize_secret(), 'manual override')
            self.assertEqual(read_secret(), manual)
            self.assertFalse(Path(self.secret).exists())
        self.assertEqual(read_secret(), '')

    def test_invalid_existing_secret_fails_closed_no_rotation(self):
        path=Path(self.secret)
        path.parent.mkdir(parents=True)
        path.write_text('partial-secret', encoding='ascii')
        if os.name != 'nt': path.chmod(0o600)
        with self.assertRaises(ValueError): initialize_secret()
        self.assertEqual(read_secret(), '')
        self.assertEqual(path.read_text(), 'partial-secret')

    def test_no_secret_no_acceptance(self):
        self.assertEqual(read_secret(), '')
        with patch.dict(os.environ, {'NPCORTEX_CPLUS_F1_TOKEN': 'bad'}):
            self.assertEqual(read_secret(), 'bad')
            with self.assertRaises(ValueError): initialize_secret()

    @unittest.skipIf(os.name == 'nt', 'POSIX permissions only')
    def test_world_readable_file_denied(self):
        initialize_secret()
        Path(self.secret).chmod(0o644)
        self.assertEqual(read_secret(), '')
        with self.assertRaises(PermissionError): initialize_secret()

    def test_relative_override_denied(self):
        with patch.dict(os.environ, {'NPCORTEX_CPLUS_F1_SECRET_FILE': 'relative/key'}):
            with self.assertRaises(ValueError): initialize_secret()
            self.assertEqual(read_secret(), '')

    def test_http_rejects_missing_bad_and_accepts_auto_secret(self):
        initialize_secret()
        secret=read_secret()
        db=Path(self.temp.name)/'npcortex.sqlite3'
        service=SimpleNamespace(model='test',_require_world_store=lambda:SimpleNamespace(db_path=db))
        httpd=make_http_server(service,'127.0.0.1',0)
        thread=threading.Thread(target=httpd.serve_forever,daemon=True)
        thread.start()
        event=fixture()
        def request(key):
            con=http.client.HTTPConnection('127.0.0.1',httpd.server_port,timeout=3)
            try:
                headers={'Content-Type':'application/json'}
                if key is not None: headers['X-NPCortex-CPlus-Token']=key
                con.request('POST','/v1/cplus/f1/events',json.dumps(event).encode(),headers)
                resp=con.getresponse()
                return resp.status,json.loads(resp.read())
            finally: con.close()
        try:
            self.assertEqual(request(None)[0],403)
            self.assertEqual(request('wrong-token')[0],403)
            self.assertEqual(request(secret)[0],200)
            self.assertTrue(request(secret)[1]['duplicate'])
            self.assertEqual(request('bad')[0],403)
        finally:
            httpd.shutdown()
            httpd.server_close()
            thread.join(timeout=3)


if __name__ == '__main__':
    unittest.main()
