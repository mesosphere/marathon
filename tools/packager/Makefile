# Note that the prefix affects the init scripts as well.
PREFIX := usr

FPM_VERSION := 1.10.2
FPM := FPM_VERSION=$(FPM_VERSION) bin/fpm-docker

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
	--before-install scripts/adduser \
	--after-install scripts/systemd.enable \
	--after-remove scripts/rmuser \
	--before-remove scripts/systemd.disable

FPM_OPTS_RPM := -t rpm \
	-d coreutils \
	-d unzip \
	-d 'java >= 1:1.8.0' \
	-d redhat-lsb-core

.PHONY: help
help:
	@echo "Please choose one of the following targets:"
	@echo "  all, deb, rpm, ubuntu, debian or el"
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
ubuntu: ubuntu-trusty-1404 ubuntu-xenial-1604 ubuntu-bionic-1804

.PHONY: debian
debian: debian-jessie-8 debian-stretch-9

.PHONY: el6
el6: toor/el6/etc/init.d/marathon
el6: toor/el6/$(PREFIX)/share/marathon
el6: toor/el6/etc/default/marathon
el6: fpm-docker/.provisioned
	$(FPM) -C toor/el6 \
	        --config-files etc/default/marathon \
		--iteration $(PKG_REL).el6 \
	        --before-install scripts/adduser \
	        --after-install scripts/el6.enable \
	        --rpm-init scripts/el6.init \
		$(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: el7
el7: toor/el7/usr/lib/systemd/system/marathon.service
el7: toor/el7/$(PREFIX)/share/marathon
el7: toor/el7/etc/default/marathon
el7: fpm-docker/.provisioned
	$(FPM) -C toor/el7 \
		--iteration $(PKG_REL).el7 \
		--config-files usr/lib/systemd/system/marathon.service \
                --config-files etc/default/marathon \
	        --before-install scripts/adduser \
	        --after-install scripts/systemd.enable\
		--after-remove scripts/rmuser \
		--before-remove scripts/systemd.disable \
		$(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: ubuntu-trusty-1404
ubuntu-trusty-1404: toor/ubuntu-trusty/etc/init.d/marathon
ubuntu-trusty-1404: toor/ubuntu-trusty/$(PREFIX)/share/marathon
ubuntu-trusty-1404: toor/ubuntu-trusty/etc/default/marathon
ubuntu-trusty-1404: fpm-docker/.provisioned
	$(FPM) -C toor/ubuntu-trusty \
	        --config-files etc/default/marathon \
		--iteration $(PKG_REL).ubuntu1404 \
	        --before-install scripts/adduser \
	        --after-install scripts/ubuntu1404.enable \
	        --deb-init scripts/ubuntu1404.init \
		$(FPM_OPTS_DEB) $(FPM_OPTS) .

.PHONY: ubuntu-xenial-1604
ubuntu-xenial-1604: toor/ubuntu-xenial/lib/systemd/system/marathon.service
ubuntu-xenial-1604: toor/ubuntu-xenial/$(PREFIX)/share/marathon
ubuntu-xenial-1604: toor/ubuntu-xenial/etc/default/marathon
ubuntu-xenial-1604: fpm-docker/.provisioned
	$(FPM) -C toor/ubuntu-xenial \
		--iteration $(PKG_REL).ubuntu1604 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

.PHONY: ubuntu-bionic-1804
ubuntu-bionic-1804: toor/ubuntu-bionic/lib/systemd/system/marathon.service
ubuntu-bionic-1804: toor/ubuntu-bionic/$(PREFIX)/share/marathon
ubuntu-bionic-1804: toor/ubuntu-bionic/etc/default/marathon
ubuntu-bionic-1804: fpm-docker/.provisioned
	$(FPM) -C toor/ubuntu-xenial \
		--iteration $(PKG_REL).ubuntu1804 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

.PHONY: debian-jessie-8
debian-jessie-8: toor/debian-jessie-8/lib/systemd/system/marathon.service
debian-jessie-8: toor/debian-jessie-8/$(PREFIX)/share/marathon
debian-jessie-8: toor/debian-jessie-8/etc/default/marathon
debian-jessie-8: fpm-docker/.provisioned
	$(FPM) -C toor/debian-jessie-8 \
		--iteration $(PKG_REL).debian8 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

.PHONY: debian-stretch-9
debian-stretch-9: toor/debian-stretch-9/lib/systemd/system/marathon.service
debian-stretch-9: toor/debian-stretch-9/$(PREFIX)/share/marathon
debian-stretch-9: toor/debian-stretch-9/etc/default/marathon
debian-stretch-9: fpm-docker/.provisioned
	$(FPM) -C toor/debian-stretch-9 \
		--iteration $(PKG_REL).debian9 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

toor/%/etc/default/marathon:
	mkdir -p "$(dir $@)"
	cp marathon.defaults $@

toor/el6/etc/init.d/marathon: scripts/el6.init
	mkdir -p "$(dir $@)"
	cp scripts/el6.init "$@"
	chmod 0755 "$@"

toor/ubuntu-trusty/etc/init.d/marathon: scripts/ubuntu1404.init
	mkdir -p "$(dir $@)"
	cp scripts/ubuntu1404.init "$@"
	chmod 0755 "$@"

toor/%/usr/lib/systemd/system/marathon.service: scripts/marathon.systemd.service
	mkdir -p "$(dir $@)"
	cp scripts/marathon.systemd.service "$@"

toor/%/lib/systemd/system/marathon.service: scripts/marathon.systemd.service
	mkdir -p "$(dir $@)"
	cp scripts/marathon.systemd.service "$@"

toor/%/share/marathon: ../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz
	rm -rf "$(dir $@)"
	mkdir -p "$(dir $@)"
	tar xzf ../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz -C "$(dir $@)"
	mv $@-$(PKG_VER)-$(PKG_COMMIT) $@
	touch $@

../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz:
	cd ../../ && sbt universal:packageZipTarball

fpm-docker/.provisioned:
	cd fpm-docker && docker build . --build-arg FPM_VERSION=$(FPM_VERSION) -t fpm:$(FPM_VERSION)
	touch $@

clean:
	rm -rf marathon*.deb marathon*.rpm marathon*.pkg toor fpm-docker/.provisioned

purge: | clean
	# We could also use 'sbt clean' but it takes forever and is not as thorough.
	## || true is so that we still get an exit 0 to allow builds to proceed
	cd marathon && find . -name target -type d -exec rm -rf {} \; || true
