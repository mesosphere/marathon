# Note that the prefix affects the init scripts as well.
PREFIX := usr

FPM_VERSION := 1.10.2
FPM := FPM_VERSION=$(FPM_VERSION) bin/fpm-docker

REF ?= HEAD

PKG_REL ?= 0.1.$(shell date -u +'%Y%m%d%H%M%S')
PKG_COMMIT = $(shell cd ../../ && ./version commit --ref $(REF))
PKG_VER = $(shell cd ../../ && ./version --ref $(PKG_COMMIT))

FPM_OPTS := -s dir -n marathon -v $(PKG_VER) \
	-p target/ \
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
debian: debian-stretch-9

.PHONY: el6
el6: target/marathon-$(PKG_VER)-$(PKG_REL).el6.noarch.rpm
target/marathon-$(PKG_VER)-$(PKG_REL).el6.noarch.rpm: target/toor/el6/etc/init.d/marathon target/toor/el6/$(PREFIX)/share/marathon target/toor/el6/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C target/toor/el6 \
	        --config-files etc/default/marathon \
		--iteration $(PKG_REL).el6 \
	        --before-install scripts/adduser \
	        --after-install scripts/el6.enable \
	        --rpm-init scripts/el6.init \
		$(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: el7
el7: target/marathon-$(PKG_VER)-$(PKG_REL).el7.noarch.rpm
target/marathon-$(PKG_VER)-$(PKG_REL).el7.noarch.rpm: target/toor/el7/usr/lib/systemd/system/marathon.service target/toor/el7/$(PREFIX)/share/marathon target/toor/el7/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C target/toor/el7 \
		--iteration $(PKG_REL).el7 \
		--config-files usr/lib/systemd/system/marathon.service \
                --config-files etc/default/marathon \
	        --before-install scripts/adduser \
	        --after-install scripts/systemd.enable\
		--after-remove scripts/rmuser \
		--before-remove scripts/systemd.disable \
		$(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: ubuntu-trusty-1404
ubuntu-trusty-1404: target/marathon_$(PKG_VER)-$(PKG_REL).ubuntu1404_all.deb
target/marathon_$(PKG_VER)-$(PKG_REL).ubuntu1404_all.deb: target/toor/ubuntu-trusty/etc/init.d/marathon target/toor/ubuntu-trusty/$(PREFIX)/share/marathon target/toor/ubuntu-trusty/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C target/toor/ubuntu-trusty \
	        --config-files etc/default/marathon \
		--iteration $(PKG_REL).ubuntu1404 \
	        --before-install scripts/adduser \
	        --after-install scripts/ubuntu1404.enable \
	        --deb-init scripts/ubuntu1404.init \
		$(FPM_OPTS_DEB) $(FPM_OPTS) .

.PHONY: ubuntu-xenial-1604
ubuntu-xenial-1604: target/marathon_$(PKG_VER)-$(PKG_REL).ubuntu1604_all.deb
target/marathon_$(PKG_VER)-$(PKG_REL).ubuntu1604_all.deb: target/toor/ubuntu-xenial/lib/systemd/system/marathon.service target/toor/ubuntu-xenial/$(PREFIX)/share/marathon target/toor/ubuntu-xenial/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C target/toor/ubuntu-xenial \
		--iteration $(PKG_REL).ubuntu1604 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

.PHONY: ubuntu-bionic-1804
ubuntu-bionic-1804: target/marathon_$(PKG_VER)-$(PKG_REL).ubuntu1804_all.deb
target/marathon_$(PKG_VER)-$(PKG_REL).ubuntu1804_all.deb: target/toor/ubuntu-bionic/lib/systemd/system/marathon.service target/toor/ubuntu-bionic/$(PREFIX)/share/marathon target/toor/ubuntu-bionic/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C target/toor/ubuntu-xenial \
		--iteration $(PKG_REL).ubuntu1804 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

.PHONY: debian-stretch-9
debian-stretch-9: marathon_$(PKG_VER)-$(PKG_REL).debian9_all.deb
target/marathon_$(PKG_VER)-$(PKG_REL).debian9_all.deb: target/toor/debian-stretch-9/lib/systemd/system/marathon.service target/toor/debian-stretch-9/$(PREFIX)/share/marathon target/toor/debian-stretch-9/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C target/toor/debian-stretch-9 \
		--iteration $(PKG_REL).debian9 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

target/toor/%/etc/default/marathon:
	mkdir -p "$(dir $@)"
	cp marathon.defaults $@

target/toor/el6/etc/init.d/marathon: scripts/el6.init
	mkdir -p "$(dir $@)"
	cp scripts/el6.init "$@"
	chmod 0755 "$@"

target/toor/ubuntu-trusty/etc/init.d/marathon: scripts/ubuntu1404.init
	mkdir -p "$(dir $@)"
	cp scripts/ubuntu1404.init "$@"
	chmod 0755 "$@"

target/toor/%/usr/lib/systemd/system/marathon.service: scripts/marathon.systemd.service
	mkdir -p "$(dir $@)"
	cp scripts/marathon.systemd.service "$@"

target/toor/%/lib/systemd/system/marathon.service: scripts/marathon.systemd.service
	mkdir -p "$(dir $@)"
	cp scripts/marathon.systemd.service "$@"

target/toor/%/share/marathon: target/toor/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz
	rm -rf "$(dir $@)"
	mkdir -p "$(dir $@)"
	tar xzf target/toor/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz -C "$(dir $@)"
	mv $@-$(PKG_VER)-$(PKG_COMMIT) $@
	touch $@

ifeq ($(REF), HEAD)
target/toor/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz:
	mkdir -p target/toor
	cd ../../ && sbt universal:packageZipTarball
	cp ../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz $@
else
target/toor/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz:
	mkdir -p target/toor
	curl -f -o $@ https://downloads.mesosphere.io/marathon/builds/$(PKG_VER)-$(PKG_COMMIT)/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz
endif

download: | target/toor/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz

target/fpm-docker/.provisioned:
	cd fpm-docker && docker build . --build-arg FPM_VERSION=$(FPM_VERSION) -t fpm:$(FPM_VERSION)
	mkdir -p target/fpm-docker
	touch $@

clean:
	rm -rf marathon*.deb marathon*.rpm marathon*.pkg target

purge: | clean
	# We could also use 'sbt clean' but it takes forever and is not as thorough.
	## || true is so that we still get an exit 0 to allow builds to proceed
	cd marathon && find . -name target -type d -exec rm -rf {} \; || true
