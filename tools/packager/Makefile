# Note that the prefix affects the init scripts as well.
PREFIX := usr/local

# Command to extract from X.X.X-rcX the version (X.X.X) and tag (rcX)
EXTRACT_VER := perl -n -e\
	'/^version := "([0-9]+\.[0-9]+\.[0-9]+).*"/ && print $$1'
EXTRACT_TAG := perl -n -e\
	'/^version := "[0-9]+\.[0-9]+\.[0-9]+-([A-Za-z0-9]+).*"/ && print $$1'
PKG_VER := $(shell cd marathon && cat version.sbt | $(EXTRACT_VER))
PKG_TAG := $(shell cd marathon && cat version.sbt | $(EXTRACT_TAG))

ifeq ($(strip $(PKG_TAG)),)
PKG_REL := 0.1.$(shell date -u +'%Y%m%d%H%M')
else
PKG_REL := 0.1.$(shell date -u +'%Y%m%d%H%M').$(PKG_TAG)
endif

.PHONY: all
all: snapshot

.PHONY: release
release: PKG_REL := 1
release: deb rpm

.PHONY: snapshot
snapshot: deb rpm

.PHONY: rpm
rpm: with-upstart
	fpm -t rpm -s dir \
		-n marathon -v $(PKG_VER) --iteration $(PKG_REL) -C toor .

.PHONY: fedora
fedora: with-serviced
	fpm -t rpm -s dir \
		-n marathon -v $(PKG_VER) --iteration $(PKG_REL) -C toor .

.PHONY: deb
deb: with-upstart
	fpm -t deb -s dir \
		-n marathon -v $(PKG_VER) --iteration $(PKG_REL) -C toor .

.PHONY: osx
osx: just-jar
	fpm -t osxpkg --osxpkg-identifier-prefix io.mesosphere -s dir \
		-n marathon -v $(PKG_VER) --iteration $(PKG_REL) -C toor .

.PHONY: with-upstart
with-upstart: just-jar marathon.conf
	mkdir -p toor/etc/init
	cp marathon.conf toor/etc/init/

.PHONY: with-serviced
with-serviced: just-jar marathon.service
	mkdir -p toor/usr/lib/systemd/system/
	cp marathon.service toor/usr/lib/systemd/system/

.PHONY: just-jar
just-jar: marathon-runnable.jar
	mkdir -p toor/$(PREFIX)/bin
	cp marathon-runnable.jar toor/$(PREFIX)/bin/marathon
	chmod 755 toor/$(PREFIX)/bin/marathon

marathon-runnable.jar:
	cd marathon && sbt assembly && bin/build-distribution
	cp marathon/target/$@ $@

clean:
	rm -rf marathon-runnable.jar marathon*.deb marathon*.rpm marathon*.pkg toor

.PHONY: prep-ubuntu
prep-ubuntu: SBT_URL := http://dl.bintray.com/sbt/debian/sbt-0.13.5.deb
prep-ubuntu: SBT_TMP := $(shell mktemp -t XXXXXX)
prep-ubuntu:
	wget $(SBT_URL) -qO $(SBT_TMP)
	sudo dpkg -i $(SBT_TMP)
	rm $(SBT_TMP)
	sudo apt-get -y install default-jdk ruby-dev rpm
	sudo gem install fpm

