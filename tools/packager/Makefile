# Note that the prefix affects the init scripts as well.
PREFIX := usr

FPM := bin/fpm-docker

PKG_VER ?= $(shell cd ../../ && ./version)
PKG_REL ?= 0.1.$(shell date -u +'%Y%m%d%H%M%S')
PKG_COMMIT ?= $(shell cd ../../ && ./version commit)

FPM_OPTS := -s dir -n marathon -v $(PKG_VER) \
	--architecture all \
	--url "https://github.com/mesosphere/marathon" \
	--license Apache-2.0 \
	--description "Cluster-wide init and control system for services running on\
	Apache Mesos" \
	--maintainer "Mesosphere Package Builder <support@mesosphere.io>" \
	--vendor "Mesosphere, Inc."
FPM_OPTS_DEB := -t deb \
	-d 'java8-runtime-headless' \
	-d 'lsb-release' \
	-d 'unzip'

FPM_OPTS_DEB_SYSTEMD := $(FPM_OPTS_DEB) \
	--config-files lib/systemd/system/marathon.service \
  --config-files etc/default/marathon \
	--after-install marathon.systemd.postinst \
	--after-remove marathon.systemd.postrm \
	--before-remove marathon.systemd.prerm

FPM_OPTS_DEB_INIT := $(FPM_OPTS_DEB) --deb-init marathon.init
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

.PHONY: ubuntu
ubuntu: ubuntu-trusty ubuntu-xenial

.PHONY: debian
debian: debian-jessie-8 debian-stretch-9

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

.PHONY: ubuntu-trusty
ubuntu-trusty: toor/ubuntu-trusty/etc/init/marathon.conf
ubuntu-trusty: toor/ubuntu-trusty/etc/init.d/marathon
ubuntu-trusty: toor/ubuntu-trusty/$(PREFIX)/bin/marathon
ubuntu-trusty: marathon.postinst
ubuntu-trusty: marathon.postrm
	fpm -C toor/ubuntu-trusty --config-files etc/ --iteration $(PKG_REL).ubuntu1404 \
		$(FPM_OPTS_DEB_INIT) $(FPM_OPTS) .

.PHONY: ubuntu-xenial
ubuntu-xenial: toor/ubuntu-xenial/lib/systemd/system/marathon.service
ubuntu-xenial: toor/ubuntu-xenial/$(PREFIX)/bin/marathon
ubuntu-xenial: marathon.systemd.postinst
	fpm -C toor/ubuntu-xenial --config-files lib/systemd/system/marathon.service \
		--iteration $(PKG_REL).ubuntu1604 \
		--after-install marathon.systemd.postinst \
		$(FPM_OPTS_DEB) $(FPM_OPTS) .

.PHONY: debian-jessie-8
debian-jessie-8: toor/debian-jessie-8/lib/systemd/system/marathon.service
debian-jessie-8: toor/debian-jessie-8/$(PREFIX)/share/marathon
debian-jessie-8: toor/debian-jessie-8/etc/default/marathon
debian-jessie-8: marathon.systemd.postinst
	$(FPM) -C toor/debian-jessie-8 \
		--iteration $(PKG_REL).debian8 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

.PHONY: debian-stretch-9
debian-stretch-9: toor/debian-stretch-9/lib/systemd/system/marathon.service
debian-stretch-9: toor/debian-stretch-9/$(PREFIX)/share/marathon
debian-stretch-9: toor/debian-stretch-9/etc/default/marathon
debian-stretch-9: marathon.systemd.postinst
	$(FPM) -C toor/debian-stretch-9 \
		--iteration $(PKG_REL).debian9 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

.PHONY: osx
osx: toor/osx/$(PREFIX)/bin/marathon
	fpm -C toor/osx --iteration $(PKG_REL) $(FPM_OPTS_OSX) $(FPM_OPTS) .

toor/%/etc/default/marathon:
	mkdir -p "$(dir $@)"
	cp marathon.defaults $@

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

toor/%/share/marathon: ../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz
	rm -rf "$(dir $@)"
	mkdir -p "$(dir $@)"
	tar xzf ../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz -C "$(dir $@)"
	mv $@-$(PKG_VER)-$(PKG_COMMIT) $@
	touch $@

../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz:
	cd ../../ && sbt universal:packageZipTarball

clean:
	rm -rf marathon*.deb marathon*.rpm marathon*.pkg toor

purge: | clean
	# We could also use 'sbt clean' but it takes forever and is not as thorough.
	## || true is so that we still get an exit 0 to allow builds to proceed
	cd marathon && find . -name target -type d -exec rm -rf {} \; || true

.PHONY: prep-ubuntu
prep-ubuntu: SBT_URL := http://dl.bintray.com/sbt/debian/sbt-1.1.0.deb
prep-ubuntu: SBT_TMP := $(shell mktemp -t XXXXXX)
prep-ubuntu:
	sudo apt-get update
	sudo apt-get -y install default-jdk ruby-dev rpm
	wget $(SBT_URL) -qO $(SBT_TMP)
	sudo dpkg -i $(SBT_TMP)
	rm $(SBT_TMP)
	sudo gem install fpm

