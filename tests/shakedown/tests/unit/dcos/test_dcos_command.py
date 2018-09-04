from shakedown import connection_cache
from shakedown.dcos import command


class MockConnection:

    def __init__(self, h, u, k, failure=False):
        self.h = h
        self.u = u
        self.k = k
        self.failure = failure
        self.access_count = 0

    def open_session(self):
        if not self.failure:
            return self.h, self.u, self.k
        return None

    def is_active(self):
        return not self.failure

    def is_authenticated(self):
        return not self.failure


def test_hostsession_enter(monkeypatch):
    """Test that `get_session` calls `_get_connection` for a 
    connection and then opens a new session.
    """
    def mockreturn(h, u, k):
        return MockConnection(h, u, k)
    # replace _get_connection with mockreturn
    monkeypatch.setattr(command, '_get_connection', mockreturn)

    hs = command.HostSession('local', 'me', 'key', True)
    v = hs.__enter__()
    assert v is not None
    assert hs.host == 'local'
    assert hs.session[0] == 'local'


def test_hostsession_enter_fail(monkeypatch):
    """Test that `get_session` calls `_get_connection` for a 
    connection and then opens a new session.
    """
    def mockreturn(h, u, k):
        return MockConnection(h, u, k, True)
    # replace _get_connection with mockreturn
    monkeypatch.setattr(command, '_get_connection', mockreturn)

    hs = command.HostSession('local', 'me', 'key', True)
    v = hs.__enter__()
    assert v is not None
    assert hs.host == 'local'
    assert hs.session is None


def test_connection_cache():
    @connection_cache
    def f(host, user, key_path):
        """generic func to emulate _get_connection"""
        return MockConnection(host, user, key_path)

    # basic tests
    assert f.get_cache() == {}
    f('local', 'me', 'key')
    assert 'local-me' in f.get_cache()
    cache_obj = f.get_cache()['local-me']
    f('local', 'me', 'key')
    assert f.get_cache()['local-me'] == cache_obj
    assert len(f.get_cache()) == 1
    f('local2', 'me', 'key')
    assert len(f.get_cache()) == 2
    f('local2', 'me2', 'key')
    assert len(f.get_cache()) == 3
    f('local2', 'me2', 'key2')
    assert len(f.get_cache()) == 3

    # set an existing connection to fail
    # make sure it is recreated and not the same
    # connection obj as before
    assert f.get_cache()['local-me'] == cache_obj
    f.get_cache()['local-me'].failure = True
    f('local', 'me', 'key2')
    assert len(f.get_cache()) == 3
    assert f.get_cache()['local-me'] != cache_obj

    # purge should do things.
    f.purge('local2-me2')
    assert len(f.get_cache()) == 2
    f.purge()
    assert len(f.get_cache()) == 0


def test_connection_cache_none():
    """Test that a None return from the function is not cached."""
    return_none = True

    @connection_cache
    def f(host, user, key_path):
        """generic func to emulate _get_connection"""
        nonlocal return_none
        if return_none:
            return None
        return MockConnection(host, user, key_path)

    # basic tests
    assert f.get_cache() == {}
    r = f('local', 'me', 'key')
    assert r is None
    assert f.get_cache() == dict()
    return_none = False
    r = f('local', 'me', 'key')
    assert r
    assert 'local-me' in f.get_cache()
    f('local2', 'me', 'key')
    assert len(f.get_cache()) == 2
