.PHONY: all default test clean purge
.SECONDARY:

default: all

clean:
	rm -rf target/*

purge: | clean
	docker rmi -f marathon-package-test:debian9 || echo "Couldn't remove debian9"
	docker rmi -f marathon-package-test:centos6 || echo "Couldn't remove centos6"
	docker rmi -f marathon-package-test:centos7 || echo "Couldn't remove centos7"
	docker rmi -f marathon-package-test:ubuntu1604 || echo "Couldn't remove ubuntu1604"
	docker rmi -f marathon-package-test:mesos || echo "Couldn't remove mesos"

%/mesos-version: target/mesos-version
	cp target/mesos-version $@

target/mesos-version: ../../project/Dependencies.scala
	mkdir -p target
	cat ../../project/Dependencies.scala | grep MesosDebian | cut -f 2 -d '"' > $@.tmp
	[ "$$(wc -l $@.tmp | awk '{print $$1}')" = 1 ]
	mv $@.tmp $@

target/debian9.built: debian9/Dockerfile debian9/mesos-version
	cd debian9 && docker build . -t marathon-package-test:debian9
	mkdir -p target
	touch $@

target/ubuntu1604.built: ubuntu1604/Dockerfile ubuntu1604/mesos-version
	cd ubuntu1604 && docker build . -t marathon-package-test:ubuntu1604
	mkdir -p target
	touch $@

target/centos6.built: centos6/Dockerfile centos6/mesos-version
	cd centos6 && docker build . -t marathon-package-test:centos6
	touch $@

target/centos7.built: centos7/Dockerfile centos7/mesos-version
	cd centos7 && docker build . -t marathon-package-test:centos7
	touch $@

target/mesos.built: target/debian9.built mesos/Dockerfile
	cd mesos && docker build . -t marathon-package-test:mesos
	touch $@

test: | all
	amm test.sc all

all: target/mesos.built target/debian9.built target/centos7.built target/centos6.built target/ubuntu1604.built
