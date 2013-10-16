marathon-pkg
============

Packaging utilities for Marathon.

* Install FPM.

```bash
gem install fpm
```

* Install packaging tools particular to your platform.

```bash
yum install rpm-build                   ## On RedHat/CentOS/Fedora
```

* Install Maven and an appropriate JDK to build Marathon.

* (Optional) Checkout the branch of Marathon you'd like to build in the
  `marathon` directory (maintained as a submodule).

* Call the make target appropriate to your platform.

```bash
make rpm                                ## On RedHat-alikes
make osx                                ## For Apple Macintosh
```

