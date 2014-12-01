marathon-pkg
============

Packaging utilities for Marathon.


Set Up
------
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
* If this is a fresh clone of the repo:

```bash
git submodule init
git submodule update
```

* (Optional) Checkout the branch of Marathon you'd like to build in the
  `marathon` directory (maintained as a submodule).

  ```bash
  cd marathon
  git checkout <TAGNAME>
  ```
(To commit this, `cd` back to the root directory and `git add marathon`. This will update the submodule pointer to that commit.)

* Call the make target appropriate to your platform.

```bash
make all                                ## Build all packages
make deb                                ## For Debian/Ubuntu
make rpm                                ## For EL6 packages
make centos7                            ## For EL7 packages
make osx                                ## For Apple Macintosh
```

