packager
============

Packaging utilities for Marathon.


## Set Up

* Install Docker

* Install [SBT](http://www.scala-sbt.org/release/tutorial/Installing-sbt-on-Linux.html) and an appropriate JDK to build Marathon.

## Building Packages

```bash
make clean
make debian-jessie-8
```

* Call the make target appropriate to your platform. Find additional targets in the Makefile.

```bash
make all                                ## Build all packages
make deb                                ## For all Debian/Ubuntu DEB packages
make rpm                                ## For all EL/Fedora RPM packages
make el                                 ## For all Enterprise Linux (EL) packages
make debian                             ## For all Debian packages
make ubuntu                             ## For all Ubuntu packages
make upload                             ## Upload all debian packages
```

## Uploading Packages

The Makefile is used to upload packages to the pkgmaintainer repository. The following pre-requisites are required:

* You must have an ssh-agent running
* The ssh-agent must have the pkgmaintainer key

For example, to build and upload the packages for v1.9.71, the following command can be invoked

```
ssh-add ~/.ssh/pkgmaintainer.pem
PKG_SSH_USER=... PKG_SSH_HOST=... make REF=v1.9.71 upload
```

## Building docker images

The packager can also build docker images. The following command will build a Marathon artifact, from source, and then use that artifact to make a docker image:

```
make docker
```

You can also tell the Makefile to download a pre-built artifact, which skips any local JDK/sbt requirements.

```
make REF=origin/master docker
```

Note that in order to the latest `origin/master` docker image, you will need to run `git fetch` first to ensure your local repository is up-to-date.
