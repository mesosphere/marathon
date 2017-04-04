marathon-pkg
============

Packaging utilities for Marathon.


Set Up
------

This assumes a Linux system.

* Install Ruby packages.

```bash
apt-get install ruby ruby-dev			## On Debian/Ubuntu
```

```bash
yum install ruby ruby-devel				## On RedHat/CentOS/Fedora
```

* Install FPM.

```bash
gem install fpm
```

* Install packaging tools particular to your platform.

```bash
apt-get install build-essential rpm		## On Debian/Ubuntu
```

```bash
yum install rpm-build                   ## On RedHat/CentOS/Fedora
```

* Install [SBT](http://www.scala-sbt.org/release/tutorial/Installing-sbt-on-Linux.html) and an appropriate JDK to build Marathon.

Building Packages
-----------------


Cd into `./packaging` and call the make target appropriate to your platform.
Find additional targets in the Makefile.

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
