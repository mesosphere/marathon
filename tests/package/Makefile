.PHONY: all default test clean
.SECONDARY:

default: all

clean:
	rm -rf target/*
	docker rmi -f marathon-package-test:debian-systemd || echo "Couldn't remove debian-systemd"
	docker rmi -f marathon-package-test:debian-systemv || echo "Couldn't remove debian-systemv"
	docker rmi -f marathon-package-test:mesos || echo "Couldn't remove mesos"

%/mesos-version: target/mesos-version
	cp target/mesos-version $@

# 1.5.0-2.0.1 package has issues for Debian, and 1.5.0-2.0.2 isn't released for all distros. When we move past this,
# remove these build steps
debian-systemv/mesos-version: target/mesos-version
	if [ $$(cat target/mesos-version) = "1.5.0-2.0.1" ]; then echo "1.5.0-2.0.2" > $@; else echo "Package version patch should not be needed; remove this step from the Makefile"; exit 1; fi

debian-systemd/mesos-version: target/mesos-version
	if [ $$(cat target/mesos-version) = "1.5.0-2.0.1" ]; then echo "1.5.0-2.0.2" > $@; else echo "Package version patch should not be needed; remove this step from the Makefile"; exit 1; fi

target/mesos-version: ../../project/Dependencies.scala
	mkdir -p target
	cat ../../project/Dependencies.scala | grep MesosDebian | cut -f 2 -d '"' > $@.tmp
	[ "$$(wc -l $@.tmp | awk '{print $$1}')" = 1 ]
	mv $@.tmp $@

target/debian-systemd.built: debian-systemd/Dockerfile debian-systemd/mesos-version
	cd debian-systemd && docker build . -t marathon-package-test:debian-systemd
	mkdir -p target
	touch $@

target/debian-systemv.built: target/debian-systemd.built debian-systemv/Dockerfile
	cd debian-systemv && docker build . -t marathon-package-test:debian-systemv
	touch $@

target/mesos.built: target/debian-systemd.built mesos/Dockerfile
	cd mesos && docker build . -t marathon-package-test:mesos
	touch $@

target/centos-systemv.built: centos-systemv/Dockerfile centos-systemv/mesos-version
	cd centos-systemv && docker build . -t marathon-package-test:centos-systemv
	touch $@

target/centos-systemd.built: centos-systemd/Dockerfile centos-systemd/mesos-version
	cd centos-systemd && docker build . -t marathon-package-test:centos-systemd
	touch $@

# target/ubuntu-upstart.built: ubuntu-upstart/Dockerfile ubuntu-upstart/mesos-version
# 	cd ubuntu-upstart && docker build . -t marathon-package-test:ubuntu-upstart
# 	touch $@

test: | all
	amm test.sc all

all: target/mesos.built target/debian-systemv.built target/debian-systemd.built target/centos-systemd.built target/centos-systemv.built # target/ubuntu-upstart.built
