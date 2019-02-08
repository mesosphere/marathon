from abc import ABC, abstractmethod


class Resource:
    """
    A resource represents a service in DC/OS.
    """
    pass


class Collection(ABC):
    """Resources have collections of objects."""

    @abstractmethod
    async def all(self):
        pass

    @abstractmethod
    async def create(self, spec):
        pass

    async def delete(self):
        """
        Deletes all objects in this collection.

        :return: Nothing
        """
        async for app in self.all():
            await app.delete()
