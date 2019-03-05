from abc import ABC, abstractmethod
import logging
import os
import requests
import urllib

from . import dcos_url_path
from .authentication import dcos_acs_token, DCOSAcsAuth
from .rpcclient import verify_ssl

logger = logging.getLogger(__name__)


class Collection(ABC):
    """Resources have collections of objects."""

    @abstractmethod
    def create(self):
        pass


class Session(ABC):

    def create_url(self, path):
        """Create the URL based off this partial path."""
        return urllib.parse.urljoin(self.base_url, path)

    def get(self, path, *args, **kwargs):
        url = self.create_url(path)
        kwargs['auth'] = self.auth
        kwargs['verify'] = verify_ssl()
        return requests.get(url, *args, **kwargs)

    def post(self, path, *args, **kwargs):
        url = self.create_url(path)
        kwargs['auth'] = self.auth
        kwargs['verify'] = verify_ssl()
        return requests.post(url, *args, **kwargs)


class DiagnosticBundle():

    def __init__(self, session, bundle_name):
        logger.info('Created diagnostic bundle %s', bundle_name)

        self.session = session
        self.bundle_name = bundle_name

    def download_path(self):
        """Check the status of the bundle creation and return the download url.

        :returns None if the bundle is not done yet, the bundle's download path realtive to the base URL otherwise.
        """
        logger.info('Retrieve status for %s', self.bundle_name)
        resp = self.session.get('list/all')
        resp.raise_for_status()

        nodes = resp.json()

        # Flatten bundle lists
        bundle_list = [bundle for bundle_list in nodes.values() if bundle_list is not None for bundle in bundle_list]

        # Search for our bundle.
        for bundle in bundle_list:
            file_name = bundle['file_name']
            if os.path.basename(file_name) == self.bundle_name:
                bundle_name = os.path.basename(file_name)
                download_path = os.path.join('serve', bundle_name)
                return download_path

        return None

    def download(self, bundle_path, download_path):
        assert download_path is not None, 'The bundle is not ready yet.'

        logger.info('Downloading diagnostic bundle %s', self.bundle_name)

        with self.session.get(download_path, stream=True) as r:
            with open(bundle_path, 'wb') as f:
                for chunk in r.iter_content(1024):
                    f.write(chunk)

        logger.info('Saved diagnostic bundle %s in %s', self.bundle_name, bundle_path)


class Diagnostics(Collection):

    def __init__(self, session):
        self.session = session

    def create(self, nodes=None):
        if nodes is None:
            nodes = {"nodes": ["all"]}
        resp = self.session.post('create', json=nodes)
        resp.raise_for_status()
        bundle_name = resp.json()['extra']['bundle_name']
        return DiagnosticBundle(self.session, bundle_name)

    def all(self):
        resp = self.session.get('list/all')
        resp.raise_for_status()

        nodes = resp.json()

        # Flatten bundle lists
        bundle_list = [bundle for bundle_list in nodes.values() if bundle_list is not None for bundle in bundle_list]

        # Yield all bundle names.
        for bundle in bundle_list:
            file_name = bundle['file_name']
            bundle_name = os.path.basename(file_name)
            yield DiagnosticBundle(self.session, bundle_name)


class Node(Session):
    """Resource API session for `/system` endpoint of DC/OS

    Example::
        client = system.Node()

        bundle = client.diagnostics.create()

        while bundle.status() is None:
            sleep(500)

        bundle.download()
    """

    def __init__(self, auth_token=None):
        self.auth = DCOSAcsAuth(auth_token or dcos_acs_token())
        self.base_url = dcos_url_path('/system/health/v1/report/diagnostics/')

    @property
    def diagnostics(self):
        return Diagnostics(self)
