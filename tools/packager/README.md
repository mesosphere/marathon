packager
============

Packaging utilities for Marathon.


Set Up
------
* Install Docker

* Install [SBT](http://www.scala-sbt.org/release/tutorial/Installing-sbt-on-Linux.html) and an appropriate JDK to build Marathon.

Building Packages
-----------------

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
make fedora                             ## For all Fedora (FC) packages
make debian                             ## For all Debian packages
make ubuntu                             ## For all Ubuntu packages
make osx                                ## For Apple Macintosh
```
