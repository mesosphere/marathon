# Note that the prefix affects the init script (marathon.conf) as well.
prefix := usr/local

rpm: version := $(shell cat version)
rpm: version toor
	cd toor && \
	fpm -s dir -t rpm -n marathon -v $(version) -p ../marathon.rpm .

.PHONY: toor
toor: marathon-runnable.jar marathon.conf
	mkdir -p toor/$(prefix)/bin toor/etc/init
	cp marathon-runnable.jar toor/$(prefix)/bin/marathon
	chmod 755 toor/$(prefix)/bin/marathon
	cp marathon.conf toor/etc/init/

version: plugin := org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate 
version: marathon-runnable.jar
	( cd marathon && \
	  mvn $(plugin) -Dexpression=project.version | sed '/^\[/d' ) | \
	  head -n1 > version

marathon-runnable.jar:
	cd marathon && mvn package && bin/build-distribution
	cp marathon/target/$@ $@

