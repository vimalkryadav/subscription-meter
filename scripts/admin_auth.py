"""Use an explicitly provisioned admin session for operator acceptance scripts."""
import hashlib
from urllib.parse import urlsplit


def authorize(request, root, bootstrap_key):
    path = root / 'secrets/admin-session'
    if (urlsplit(request.full_url).path.startswith('/admin/')
            and request.get_header('Authorization') == 'Bearer ' + bootstrap_key and path.is_file()):
        token = path.read_text().strip()
        if not token or any(c.isspace() for c in token):
            raise ValueError('Invalid admin session file; run scripts/admin_login.py')
        request.remove_header('Authorization')
        request.add_header('Cookie', 'meter_session=' + token)
        request.add_header('X-Portal-Request', '1')
        request.add_header('X-CSRF-Token', hashlib.sha256(('portal-csrf:' + token).encode()).hexdigest())
    return request
