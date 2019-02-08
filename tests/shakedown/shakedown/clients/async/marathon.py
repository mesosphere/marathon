import aiohttp
import logging

from . import Resource, Collection

logger = logging.getLogger(__name__)


class AppObject:
    """Represents an app in Marathon."""

    def __init__(self, base, app):
        self._base = base
        self._app = app

    def __getitem__(self, key):
        return self._app[key]

    async def delete(self):
        """Delete this app."""
        async with aiohttp.ClientSession() as session:
            app_id = self._app['id']
            logger.info('Deleting %s...', app_id)
            path = '{}/v2/apps/{}'.format(self._base, app_id)
            async with session.delete(path) as resp:
                logger.info('Done deleting %s: %d', app_id, resp.status)
                await resp.text()


class PodObject:

    def __init__(self, base, pod):
        self._base = base
        self._pod = pod

    def __getitem__(self, key):
        return self._pod[key]

    async def delete(self):
        """Delete this pod."""
        async with aiohttp.ClientSession() as session:
            pod_id = self._pod['id']
            logger.info('Deleting %s...', pod_id)
            path = '{}/v2/pods/{}'.format(self._base, pod_id)
            async with session.delete(path) as resp:
                logger.info('Done deleting %s: %d', pod_id, resp.status)
                await resp.text()


class Apps(Collection):

    def __init__(self, base):
        self._base = base

    async def all(self):
        """
        Fetch all apps from Marathon.

        :return: a generator over all apps.
        """
        async with aiohttp.ClientSession() as session:
            logger.info('Fetching all apps...')
            async with session.get("{}/v2/apps".format(self._base)) as resp:
                apps = await resp.json()
                for app_spec in apps['apps']:
                    yield AppObject(self._base, app_spec)

    async def create(self, app_spec):
        async with aiohttp.ClientSession() as session:
            app_id = app_spec['id']
            logger.info('Posting app %s', app_id)
            async with session.post("{}/v2/apps".format(self._base), json=app_spec) as resp:
                assert resp.status == 201, 'Marathon replied with {}:{}'.format(resp.status, await resp.text())
                logger.info('Done posting %s: %d', app_id, resp.status)
                return AppObject(self._base, app_spec)


class Pods(Collection):
    def __init__(self, base):
        self._base = base

    async def all(self):
        async with aiohttp.ClientSession() as session:
            logger.info('Fetching all pods...')
            async with session.get("{}/v2/pods".format(self._base)) as resp:
                pods = await resp.json()
                for pod_spec in pods:
                    yield PodObject(self._base, pod_spec)

    async def create(self, pod_spec):
        async with aiohttp.ClientSession() as session:
            pod_id = pod_spec['id']
            logger.info('Posting pod %s', pod_id)
            async with session.post("{}/v2/pods".format(self._base), json=pod_spec) as resp:
                assert resp.status == 201, 'Marathon replied with {}:{}'.format(resp.status, await resp.text())
                logger.info('Done posting %s: %d', pod_id, resp.status)
                return PodObject(self._base, pod_spec)


class Marathon(Resource):
    """
    The Marathon resource is an object like access to the Marathon API.

    Example usage::
        import asyncio
        import logging
        from shakedown.clients.async import Marathon

        logging.basicConfig(level=logging.DEBUG)
        loop = asyncio.get_event_loop()

        async def example():
            app_spec = {"id": "/my_app", "cmd": "sleep infinity", "cpus": 0.1, "mem": 10.0, "instances": 1}
            marathon = Marathon('http://localhost:8080')

            app = await marathon.apps.create(app_spec)
            await app.delete()

            # Delete all apps and pods
            await marathon.apps.delete()
            await marathon.pods.delete()

        loop.run_until_complete(example())
        loop.close()
    """

    def __init__(self, base):
        self._base = base

    @property
    def apps(self):
        return Apps(self._base)

    @property
    def pods(self):
        return Pods(self._base)
