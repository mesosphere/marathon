# Note that the prefix affects the init scripts as well.
PREFIX := usr/local

# There appears to be no way to escape () within a shell function, so define
# the sed command as a variable. Extract only the numeric portion of the
# version string to ensure snapshots / release version ordering is sane.
SED_CMD := sed -rn 's/^version := "([0-9]+\.[0-9]+\.[0-9]+).*"/\1/p'
PKG_VER := $(shell cd marathon && \
	cat version.sbt | $(SED_CMD))
PKG_REL := 0.1.$(shell date -u +'%Y%m%d%H%M')

.PHONY: rpm
rpm: version with-upstart
	cd toor && \
	fpm -t rpm -s dir \
		-n marathon -v $(PKG_VER) --iteration $(PKG_REL) -p ../marathon.rpm .

.PHONY: fedora
fedora: version with-serviced
	cd toor && \
	fpm -t rpm -s dir \
		-n marathon -v $(PKG_VER) --iteration $(PKG_REL) -p ../marathon.rpm .

.PHONY: deb
deb: version with-upstart
	cd toor && \
	fpm -t deb -s dir \
		-n marathon -v $(PKG_VER) --iteration $(PKG_REL) -p ../marathon.deb .

.PHONY: osx
osx: version just-jar
	cd toor && \
	fpm -t osxpkg --osxpkg-identifier-prefix io.mesosphere -s dir \
		-n marathon -v $(PKG_VER) --iteration $(PKG_REL) -p ../marathon.pkg .

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

