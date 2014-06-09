# Note that the prefix affects the init scripts as well.
prefix := usr/local

.PHONY: rpm
rpm: version with-upstart
	cd toor && \
	fpm -t rpm -s dir \
		-n marathon -v `cat ../version` -p ../marathon.rpm .

.PHONY: fedora
fedora: version with-serviced
	cd toor && \
	fpm -t rpm -s dir \
		-n marathon -v `cat ../version` -p ../marathon.rpm .

.PHONY: deb
deb: version with-upstart
	cd toor && \
	fpm -t deb -s dir \
		-n marathon -v `cat ../version` -p ../marathon.deb .

.PHONY: osx
osx: version just-jar
	cd toor && \
	fpm -t osxpkg --osxpkg-identifier-prefix io.mesosphere -s dir \
		-n marathon -v `cat ../version` -p ../marathon.pkg .

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
	mkdir -p toor/$(prefix)/bin
	cp marathon-runnable.jar toor/$(prefix)/bin/marathon
	chmod 755 toor/$(prefix)/bin/marathon

version: marathon-runnable.jar
	( cd marathon && \
		cat version.sbt | sed -rn 's/^version := "(.*)"/\1/p' ) > \
		version

marathon-runnable.jar:
	cd marathon && sbt assembly && bin/build-distribution
	cp marathon/target/$@ $@

