# Note that the prefix affects the init scripts as well.
PREFIX := usr

# Command to extract from X.X.X-rcX the version (X.X.X) and tag (rcX)
EXTRACT_VER := cut -d \- -f 1
EXTRACT_TAG := cut -d \- -f 2- | tr - _
PKG_VER ?= $(shell cd marathon && git describe --tags --always | $(EXTRACT_VER))
PKG_TAG ?= $(shell cd marathon && git describe --tags --always | $(EXTRACT_TAG))

ifeq ($(strip $(PKG_TAG)),)
PKG_REL ?= 0.1.$(shell date -u +'%Y%m%d%H%M%S')
else
PKG_REL ?= 0.1.$(shell date -u +'%Y%m%d%H%M%S').$(PKG_TAG)
endif

FPM_OPTS := -s dir -n marathon -v $(PKG_VER) \
	--architecture native \
	--url "https://github.com/mesosphere/marathon" \
	--license Apache-2.0 \
	--description "Cluster-wide init and control system for services running on\
	Apache Mesos" \
	--maintainer "Mesosphere Package Builder <support@mesosphere.io>" \
	--vendor "Mesosphere, Inc."
FPM_OPTS_DEB := -t deb \
	-d 'java8-runtime-headless' \
	-d 'lsb-release' \
	-d 'unzip' \
	--after-install marathon.postinst \
	--after-remove marathon.postrm
FPM_OPTS_DEB_INIT := --deb-init marathon.init
FPM_OPTS_RPM_RHEL6 := -t rpm \
	-d coreutils -d unzip -d 'java >= 1.8.0'
FPM_OPTS_RPM := -t rpm \
	-d coreutils -d unzip -d 'java >= 1:1.8.0'
FPM_OPTS_OSX := -t osxpkg --osxpkg-identifier-prefix io.mesosphere

.PHONY: help
help:
	@echo "Please choose one of the following targets:"
	@echo "  all, deb, rpm, fedora, osx, or el"
	@echo "For release builds:"
	@echo "  make PKG_REL=1.0 deb"
	@echo "To override package release version:"
	@echo "  make PKG_REL=0.2.20141228050159 rpm"
	@exit 0

.PHONY: all
all: deb rpm

.PHONY: deb
deb: ubuntu debian

.PHONY: rpm
rpm: el

.PHONY: el
el: el6 el7

.PHONY: fedora
fedora: fedora20 fedora21 fedora22

.PHONY: ubuntu
ubuntu: ubuntu-precise ubuntu-trusty ubuntu-xenial

.PHONY: debian
debian: debian-jessie

.PHONY: debian-wheezy
debian-wheezy: debian-wheezy-77

.PHONY: debian-jessie
debian-jessie: debian-jessie-81

.PHONY: fedora20
fedora20: toor/fedora20/usr/lib/systemd/system/marathon.service
fedora20: toor/fedora20/$(PREFIX)/bin/marathon
	fpm -C toor/fedora20 --config-files usr/lib/systemd/system/marathon.service \
		--iteration $(PKG_REL).fc20 \
		$(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: fedora21
fedora21: toor/fedora21/usr/lib/systemd/system/marathon.service
fedora21: toor/fedora21/$(PREFIX)/bin/marathon
	fpm -C toor/fedora21 --config-files usr/lib/systemd/system/marathon.service \
		--iteration $(PKG_REL).fc21 \
		$(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: fedora22
fedora22: toor/fedora22/usr/lib/systemd/system/marathon.service
fedora22: toor/fedora22/$(PREFIX)/bin/marathon
	fpm -C toor/fedora22 --config-files usr/lib/systemd/system/marathon.service \
		--iteration $(PKG_REL).fc22 \
		$(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: el6
el6: toor/el6/etc/init/marathon.conf
el6: toor/el6/$(PREFIX)/bin/marathon
	fpm -C toor/el6 --config-files etc/ --iteration $(PKG_REL).el6 \
		$(FPM_OPTS_RPM_RHEL6) $(FPM_OPTS) .

.PHONY: el7
el7: toor/el7/usr/lib/systemd/system/marathon.service
el7: toor/el7/$(PREFIX)/bin/marathon
el7: marathon.systemd.postinst
	fpm -C toor/el7 --config-files usr/lib/systemd/system/marathon.service \
		--iteration $(PKG_REL).el7 \
		--after-install marathon.systemd.postinst \
		$(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: ubuntu-precise
ubuntu-precise: toor/ubuntu-precise/etc/init/marathon.conf
ubuntu-precise: toor/ubuntu-precise/etc/init.d/marathon
ubuntu-precise: toor/ubuntu-precise/$(PREFIX)/bin/marathon
ubuntu-precise: marathon.postinst
ubuntu-precise: marathon.postrm
	fpm -C toor/ubuntu-precise --config-files etc/ --iteration $(PKG_REL).ubuntu1204 \
		$(FPM_OPTS_DEB) $(FPM_OPTS_DEB_INIT) $(FPM_OPTS) .

.PHONY: ubuntu-quantal
ubuntu-quantal: toor/ubuntu-quantal/etc/init/marathon.conf
ubuntu-quantal: toor/ubuntu-quantal/etc/init.d/marathon
ubuntu-quantal: toor/ubuntu-quantal/$(PREFIX)/bin/marathon
ubuntu-quantal: marathon.postinst
ubuntu-quantal: marathon.postrm
	fpm -C toor/ubuntu-quantal --config-files etc/ --iteration $(PKG_REL).ubuntu1210 \
		$(FPM_OPTS_DEB) $(FPM_OPTS_DEB_INIT) $(FPM_OPTS) .

.PHONY: ubuntu-raring
ubuntu-raring: toor/ubuntu-raring/etc/init/marathon.conf
ubuntu-raring: toor/ubuntu-raring/etc/init.d/marathon
ubuntu-raring: toor/ubuntu-raring/$(PREFIX)/bin/marathon
ubuntu-raring: marathon.postinst
ubuntu-raring: marathon.postrm
	fpm -C toor/ubuntu-raring --config-files etc/ --iteration $(PKG_REL).ubuntu1304 \
		$(FPM_OPTS_DEB) $(FPM_OPTS_DEB_INIT) $(FPM_OPTS) .

.PHONY: ubuntu-saucy
ubuntu-saucy: toor/ubuntu-saucy/etc/init/marathon.conf
ubuntu-saucy: toor/ubuntu-saucy/etc/init.d/marathon
ubuntu-saucy: toor/ubuntu-saucy/$(PREFIX)/bin/marathon
ubuntu-saucy: marathon.postinst
ubuntu-saucy: marathon.postrm
	fpm -C toor/ubuntu-saucy --config-files etc/ --iteration $(PKG_REL).ubuntu1310 \
		$(FPM_OPTS_DEB) $(FPM_OPTS_DEB_INIT) $(FPM_OPTS) .

.PHONY: ubuntu-trusty
ubuntu-trusty: toor/ubuntu-trusty/etc/init/marathon.conf
ubuntu-trusty: toor/ubuntu-trusty/etc/init.d/marathon
ubuntu-trusty: toor/ubuntu-trusty/$(PREFIX)/bin/marathon
ubuntu-trusty: marathon.postinst
ubuntu-trusty: marathon.postrm
	fpm -C toor/ubuntu-trusty --config-files etc/ --iteration $(PKG_REL).ubuntu1404 \
		$(FPM_OPTS_DEB) $(FPM_OPTS_DEB_INIT) $(FPM_OPTS) .

.PHONY: ubuntu-utopic
ubuntu-utopic: toor/ubuntu-utopic/etc/init/marathon.conf
ubuntu-utopic: toor/ubuntu-utopic/etc/init.d/marathon
ubuntu-utopic: toor/ubuntu-utopic/$(PREFIX)/bin/marathon
ubuntu-utopic: marathon.postinst
ubuntu-utopic: marathon.postrm
	fpm -C toor/ubuntu-utopic --config-files etc/ --iteration $(PKG_REL).ubuntu1410 \
		$(FPM_OPTS_DEB) $(FPM_OPTS_DEB_INIT) $(FPM_OPTS) .

.PHONY: ubuntu-vivid
ubuntu-vivid: toor/ubuntu-vivid/lib/systemd/system/marathon.service
ubuntu-vivid: toor/ubuntu-vivid/$(PREFIX)/bin/marathon
ubuntu-vivid: marathon.systemd.postinst
	fpm -C toor/ubuntu-vivid --config-files lib/systemd/system/marathon.service \
		--iteration $(PKG_REL).ubuntu1504 \
		--after-install marathon.systemd.postinst \
		$(FPM_OPTS_DEB) $(FPM_OPTS) .

.PHONY: ubuntu-xenial
ubuntu-xenial: toor/ubuntu-xenial/lib/systemd/system/marathon.service
ubuntu-xenial: toor/ubuntu-xenial/$(PREFIX)/bin/marathon
ubuntu-xenial: marathon.systemd.postinst
	fpm -C toor/ubuntu-xenial --config-files lib/systemd/system/marathon.service \
		--iteration $(PKG_REL).ubuntu1604 \
		--after-install marathon.systemd.postinst \
		$(FPM_OPTS_DEB) $(FPM_OPTS) .

.PHONY: debian-wheezy-77
debian-wheezy-77: toor/debian-wheezy-77/etc/init/marathon.conf
debian-wheezy-77: toor/debian-wheezy-77/etc/init.d/marathon
debian-wheezy-77: toor/debian-wheezy-77/$(PREFIX)/bin/marathon
debian-wheezy-77: marathon.postinst
debian-wheezy-77: marathon.postrm
	fpm -C toor/debian-wheezy-77 --config-files etc/ --iteration $(PKG_REL).debian77 \
		$(FPM_OPTS_DEB) $(FPM_OPTS_DEB_INIT) $(FPM_OPTS) .

.PHONY: debian-jessie-81
debian-jessie-81: toor/debian-jessie-81/lib/systemd/system/marathon.service
debian-jessie-81: toor/debian-jessie-81/$(PREFIX)/bin/marathon
debian-jessie-81: marathon.systemd.postinst
	fpm -C toor/debian-jessie-81 --config-files lib/systemd/system/marathon.service \
		--iteration $(PKG_REL).debian81 \
		--after-install marathon.systemd.postinst \
		$(FPM_OPTS_DEB) $(FPM_OPTS) .


.PHONY: osx
osx: toor/osx/$(PREFIX)/bin/marathon
	fpm -C toor/osx --iteration $(PKG_REL) $(FPM_OPTS_OSX) $(FPM_OPTS) .

toor/%/etc/init/marathon.conf: marathon.conf
	mkdir -p "$(dir $@)"
	cp marathon.conf "$@"

toor/%/etc/init.d/marathon: marathon.init
	mkdir -p "$(dir $@)"
	cp marathon.init "$@"

toor/%/usr/lib/systemd/system/marathon.service: marathon.service
	mkdir -p "$(dir $@)"
	cp marathon.service "$@"

toor/%/lib/systemd/system/marathon.service: marathon.service
	mkdir -p "$(dir $@)"
	cp marathon.service "$@"

toor/%/bin/marathon: marathon-runnable.jar
	mkdir -p "$(dir $@)"
	cp marathon-runnable.jar "$@"
	chmod 755 "$@"

marathon-runnable.jar:
	cd marathon && sbt assembly && bin/build-distribution
	cp marathon/target/$@ $@

clean:
	rm -rf marathon-runnable.jar marathon*.deb marathon*.rpm marathon*.pkg toor
	# We could also use 'sbt clean' but it takes forever and is not as thorough.
	## || true is so that we still get an exit 0 to allow builds to proceed
	cd marathon && find . -name target -type d -exec rm -rf {} \; || true

.PHONY: prep-ubuntu
prep-ubuntu: SBT_URL := http://dl.bintray.com/sbt/debian/sbt-0.13.5.deb
prep-ubuntu: SBT_TMP := $(shell mktemp -t XXXXXX)
prep-ubuntu:
	sudo apt-get update
	sudo apt-get -y install default-jdk ruby-dev rpm
	wget $(SBT_URL) -qO $(SBT_TMP)
	sudo dpkg -i $(SBT_TMP)
	rm $(SBT_TMP)
	sudo gem install fpm
