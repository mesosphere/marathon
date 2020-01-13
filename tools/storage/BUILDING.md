# Pre-requisites

Install docker.

Install JDK8

Install Ammonite:

    curl -L https://github.com/lihaoyi/Ammonite/releases/download/2.0.1/2.12-2.0.1 > /usr/local/bin/amm-2.12-2.0.1
    chmod +x /usr/local/bin/amm-2.12-2.0.1
    ln -sf /usr/local/bin/amm-2.12-2.0.1 /usr/local/bin/amm-2.12

# Building

Use make to build the current version:

```
make compile
```

Make will use the checked-out version of Marathon and build a local artifact, and then assemble a working version of the tool in `target/${MARATHON_VERSION}`, where `MARATHON_VERSION=$(../../version)`.


You can build a docker image with :

```
make docker

```
If successful, you'll have a docker image tagged `mesosphere/marathon-storage-tool:${MARATHON_VERSION}`. The code is compiled inside and outside of the docker container as a sanity check.

If you have the credentials, you can push it to DockerHub:

```
make push-docker
```

# Building previous versions of Marathon

The storage tool can be built against previous versions of Marathon by using the `REF` environment variable; for example, to build Marathon-storage-tool for Marathon tagged as `v1.9.109`, run:

```
REF=v1.9.109 make compile
```

When `REF` is specified to a value other than `HEAD`, then the artifact will be downloaded from s3 and used instead of the artifact built locally.


# Developing

You can run the Marathon Storage Tool from `src/` by symlinking the desired marathon jar.

```
MARATHON_VERSION=$(../../version)
# Assemble the Marathon artifact from source
make artifact

# Extract the artifact to `src`
tar xzf target/artifacts/marathon-${MARATHON_VERSION}* -C src
mv src/marathon-${MARATHON_VERSION}* src/marathon

# Run storage tool
cd src
bin/storage-tool.sh --help
```

From here, you can edit the files in `src/lib` and re-run the storage tool. You can also run `make compile` to quickly validate that your changes compile.
