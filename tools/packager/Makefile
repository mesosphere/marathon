# Note that the prefix affects the init scripts as well.
PREFIX := usr/local

# Command to extract from X.X.X-rcX the version (X.X.X) and tag (rcX)
EXTRACT_VER := perl -n -e\
	'/"([0-9]+\.[0-9]+\.[0-9]+).*"/ && print $$1'
EXTRACT_TAG := perl -n -e\
	'/"[0-9]+\.[0-9]+\.[0-9]+-([A-Za-z0-9]+).*"/ && print $$1'
PKG_VER ?= $(shell cd marathon && cat version.sbt | $(EXTRACT_VER))
PKG_TAG ?= $(shell cd marathon && cat version.sbt | $(EXTRACT_TAG))

ifeq ($(strip $(PKG_TAG)),)
PKG_REL ?= 0.1.$(shell date -u +'%Y%m%d%H%M%S')
else
PKG_REL ?= 0.1.$(shell date -u +'%Y%m%d%H%M%S').$(PKG_TAG)
endif

FPM_OPTS := -s dir -n marathon -v $(PKG_VER) --iteration $(PKG_REL) \
	--architecture native \
	--url "https://github.com/mesosphere/marathon" \
	--license Apache-2.0 \
	--description "Cluster-wide init and control system for services running on\
	Apache Mesos" \
	--maintainer "Mesosphere Package Builder <support@mesosphere.io>" \
	--vendor "Mesosphere, Inc."
FPM_OPTS_DEB := -t deb \
	-d 'java7-runtime-headless | java6-runtime-headless' \
	--deb-init marathon.init \
	--after-install marathon.postinst \
	--after-remove marathon.postrm
FPM_OPTS_RPM := -t rpm \
	-d coreutils -d 'java >= 1.6'
FPM_OPTS_OSX := -t osxpkg --osxpkg-identifier-prefix io.mesosphere

.PHONY: all
all: deb rpm

.PHONY: help
help:
	@echo "Please choose one of the following targets: deb, rpm, fedora, osx"
	@echo "For release builds:"
	@echo "  make PKG_REL=1.0 deb"
	@echo "To override package release version:"
	@echo "  make PKG_REL=0.2.20141228050159 rpm"
	@exit 0

.PHONY: rpm
rpm: toor/rpm/etc/init/marathon.conf
rpm: toor/rpm/$(PREFIX)/bin/marathon
	fpm -C toor/rpm --config-files etc/ $(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: fedora
fedora: toor/fedora/usr/lib/systemd/system/marathon.service
fedora: toor/fedora/$(PREFIX)/bin/marathon
	fpm -C toor/fedora --config-files usr/lib/systemd/system/marathon.service \
		$(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: deb
deb: toor/deb/etc/init/marathon.conf
deb: toor/deb/etc/init.d/marathon
deb: toor/deb/$(PREFIX)/bin/marathon
deb: marathon.postinst
deb: marathon.postrm
	fpm -C toor/deb --config-files etc/ $(FPM_OPTS_DEB) $(FPM_OPTS) .

.PHONY: osx
osx: toor/osx/$(PREFIX)/bin/marathon
	fpm -C toor/osx $(FPM_OPTS_OSX) $(FPM_OPTS) .

toor/%/etc/init/marathon.conf: marathon.conf
	mkdir -p "$(dir $@)"
	cp marathon.conf "$@"

toor/%/etc/init.d/marathon: marathon.init
	mkdir -p "$(dir $@)"
	cp marathon.init "$@"

toor/%/usr/lib/systemd/system/marathon.service: marathon.service
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

