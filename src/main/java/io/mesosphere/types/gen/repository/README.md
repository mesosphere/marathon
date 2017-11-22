# Repository Management

This package contains the interfaces and implementations to the classes that implement
the tool's access to the repository.

The repository accessor is automatically selected based on the URI
provided, using the `Repository.fromURI` factory.

The URIs currently supported are:

* `file:` : Accesses a locally-deployed repository (for example the `example` folder in this repo)