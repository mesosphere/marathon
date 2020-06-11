SHELL := /bin/bash

# Note that the prefix affects the init scripts as well.
PREFIX := usr

FPM_VERSION := 1.10.2
FPM := FPM_VERSION=$(FPM_VERSION) bin/fpm-docker

REF ?= HEAD

PKG_REL ?= 1
PKG_COMMIT := $(shell cd ../../ && ./version commit --ref $(REF))
PKG_VER := $(shell cd ../../ && ./version --ref $(PKG_COMMIT))

MESOS_PKG_VERSION := $(shell cat ../../project/Dependencies.scala | grep MesosDebian | cut -f 2 -d '"')
# Fun fact: PWD environment is not necessarily pwd when make is launched from ammonite.
PWD := $(shell pwd)
BUILD_TARGET_ROOT := target/$(PKG_VER)

FPM_OPTS := -s dir -n marathon -v $(PKG_VER) \
	-p $(BUILD_TARGET_ROOT)/ \
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


EL6_PKG_PATH=$(BUILD_TARGET_ROOT)/marathon-$(PKG_VER)-$(PKG_REL).el6.noarch.rpm
EL7_PKG_PATH=$(BUILD_TARGET_ROOT)/marathon-$(PKG_VER)-$(PKG_REL).el7.noarch.rpm
UBUNTU_1604_PKG_PATH=$(BUILD_TARGET_ROOT)/marathon_$(PKG_VER)-$(PKG_REL).ubuntu1604_all.deb
UBUNTU_1804_PKG_PATH=$(BUILD_TARGET_ROOT)/marathon_$(PKG_VER)-$(PKG_REL).ubuntu1804_all.deb
DEBIAN_8_PKG_PATH=$(BUILD_TARGET_ROOT)/marathon_$(PKG_VER)-$(PKG_REL).debian8_all.deb
DEBIAN_9_PKG_PATH=$(BUILD_TARGET_ROOT)/marathon_$(PKG_VER)-$(PKG_REL).debian9_all.deb

ALL_PKGS=$(EL6_PKG_PATH) $(EL7_PKG_PATH) $(DEBIAN_8_PKG_PATH) $(DEBIAN_9_PKG_PATH) $(UBUNTU_1804_PKG_PATH) $(UBUNTU_1604_PKG_PATH)

.PHONY: help
help:
	@echo "Please choose one of the following targets:"
	@echo "  all, deb, rpm, ubuntu, debian, docker or el"
	@echo "To build all packages for the current checked-out version of Marathon:"
	@echo "  make all"
	@echo "To build and upload all packages, including docker:"
	@echo "  make upload-packages push-docker"
	@echo "To build packages for a different artifact version of Marathon:"
	@echo "  make REF=v1.9.100 all"
	@exit 0

.PHONY: all
all: $(foreach pkg,$(ALL_PKGS),$(pkg)) $(BUILD_TARGET_ROOT)/docker.tagged

.PHONY: deb
deb: ubuntu debian

.PHONY: rpm
rpm: el

.PHONY: el
el: el6 el7

.PHONY: ubuntu
ubuntu: ubuntu-xenial-1604 ubuntu-bionic-1804

.PHONY: debian
debian: debian-stretch-9

.PHONY: el6
el6: $(EL6_PKG_PATH)
$(EL6_PKG_PATH): $(BUILD_TARGET_ROOT)/toor/el6/etc/init.d/marathon $(BUILD_TARGET_ROOT)/toor/el6/$(PREFIX)/share/marathon $(BUILD_TARGET_ROOT)/toor/el6/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C $(BUILD_TARGET_ROOT)/toor/el6 \
	        --config-files etc/default/marathon \
		--iteration $(PKG_REL).el6 \
	        --before-install scripts/adduser \
	        --after-install scripts/el6.enable \
	        --rpm-init scripts/el6.init \
		$(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: el7
el7: $(EL7_PKG_PATH)
$(EL7_PKG_PATH): $(BUILD_TARGET_ROOT)/toor/el7/usr/lib/systemd/system/marathon.service $(BUILD_TARGET_ROOT)/toor/el7/$(PREFIX)/share/marathon $(BUILD_TARGET_ROOT)/toor/el7/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C $(BUILD_TARGET_ROOT)/toor/el7 \
		--iteration $(PKG_REL).el7 \
		--config-files usr/lib/systemd/system/marathon.service \
                --config-files etc/default/marathon \
	        --before-install scripts/adduser \
	        --after-install scripts/systemd.enable\
		--after-remove scripts/rmuser \
		--before-remove scripts/systemd.disable \
		$(FPM_OPTS_RPM) $(FPM_OPTS) .

.PHONY: ubuntu-xenial-1604
ubuntu-xenial-1604: $(UBUNTU_1604_PKG_PATH)
$(UBUNTU_1604_PKG_PATH): $(BUILD_TARGET_ROOT)/toor/ubuntu-xenial/lib/systemd/system/marathon.service $(BUILD_TARGET_ROOT)/toor/ubuntu-xenial/$(PREFIX)/share/marathon $(BUILD_TARGET_ROOT)/toor/ubuntu-xenial/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C $(BUILD_TARGET_ROOT)/toor/ubuntu-xenial \
		--iteration $(PKG_REL).ubuntu1604 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

.PHONY: ubuntu-bionic-1804
ubuntu-bionic-1804: $(UBUNTU_1804_PKG_PATH)
$(UBUNTU_1804_PKG_PATH): $(BUILD_TARGET_ROOT)/toor/ubuntu-bionic/lib/systemd/system/marathon.service $(BUILD_TARGET_ROOT)/toor/ubuntu-bionic/$(PREFIX)/share/marathon $(BUILD_TARGET_ROOT)/toor/ubuntu-bionic/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C $(BUILD_TARGET_ROOT)/toor/ubuntu-bionic \
		--iteration $(PKG_REL).ubuntu1804 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

.PHONY: debian-jessie-8
debian-jessie-8: $(DEBIAN_8_PKG_PATH)
$(DEBIAN_8_PKG_PATH): $(BUILD_TARGET_ROOT)/toor/debian-jessie-8/lib/systemd/system/marathon.service $(BUILD_TARGET_ROOT)/toor/debian-jessie-8/$(PREFIX)/share/marathon $(BUILD_TARGET_ROOT)/toor/debian-jessie-8/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C $(BUILD_TARGET_ROOT)/toor/debian-jessie-8 \
		--iteration $(PKG_REL).debian8 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

.PHONY: debian-stretch-9
debian-stretch-9: $(DEBIAN_9_PKG_PATH)
$(DEBIAN_9_PKG_PATH): $(BUILD_TARGET_ROOT)/toor/debian-stretch-9/lib/systemd/system/marathon.service $(BUILD_TARGET_ROOT)/toor/debian-stretch-9/$(PREFIX)/share/marathon $(BUILD_TARGET_ROOT)/toor/debian-stretch-9/etc/default/marathon target/fpm-docker/.provisioned
	$(FPM) -C $(BUILD_TARGET_ROOT)/toor/debian-stretch-9 \
		--iteration $(PKG_REL).debian9 \
		$(FPM_OPTS_DEB_SYSTEMD) $(FPM_OPTS) .

$(BUILD_TARGET_ROOT)/toor/%/etc/default/marathon:
	mkdir -p "$(dir $@)"
	cp marathon.defaults $@

$(BUILD_TARGET_ROOT)/toor/el6/etc/init.d/marathon: scripts/el6.init
	mkdir -p "$(dir $@)"
	cp scripts/el6.init "$@"
	chmod 0755 "$@"

$(BUILD_TARGET_ROOT)/toor/%/usr/lib/systemd/system/marathon.service: scripts/marathon.systemd.service
	mkdir -p "$(dir $@)"
	cp scripts/marathon.systemd.service "$@"

$(BUILD_TARGET_ROOT)/toor/%/lib/systemd/system/marathon.service: scripts/marathon.systemd.service
	mkdir -p "$(dir $@)"
	cp scripts/marathon.systemd.service "$@"

$(BUILD_TARGET_ROOT)/%/share/marathon: $(BUILD_TARGET_ROOT)/toor/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz
	rm -rf "$(dir $@)"
	mkdir -p "$(dir $@)"
	tar xzf $(BUILD_TARGET_ROOT)/toor/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz -C "$(dir $@)"
	mv $@-$(PKG_VER)-$(PKG_COMMIT) $@
	touch $@

ifeq ($(REF), HEAD)
../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz:
	cd ../../ && sbt universal:packageZipTarball

$(BUILD_TARGET_ROOT)/toor/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz: ../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz
	mkdir -p $(BUILD_TARGET_ROOT)/toor
	cp ../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz $@
else
$(BUILD_TARGET_ROOT)/toor/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz:
	mkdir -p $(BUILD_TARGET_ROOT)/toor
	curl -f -o $@ https://downloads.mesosphere.io/marathon/builds/$(PKG_VER)-$(PKG_COMMIT)/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz
endif


define upload_target
$(1).uploaded: $(1)
	set -o pipefail && ./bin/upload-package $(1) 2>&1 | tee $(1).uploaded.tmp
	mv $(1).uploaded.tmp $(1).uploaded
endef

$(foreach target,$(ALL_PKGS),$(eval $(call upload_target,$(target))))

.PHONY: upload-packages
upload-packages: $(foreach target,$(ALL_PKGS),$(target).uploaded)

.PHONY: download
download: | $(BUILD_TARGET_ROOT)/toor/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz

target/fpm-docker/.provisioned:
	cd fpm-docker && docker build . --build-arg FPM_VERSION=$(FPM_VERSION) -t fpm:$(FPM_VERSION)
	mkdir -p target/fpm-docker
	touch $@

### DOCKER BUILD section

$(BUILD_TARGET_ROOT)/docker/Dockerfile: docker/Dockerfile
	mkdir -p "$(dir $@)"
	cp $< $@

$(BUILD_TARGET_ROOT)/docker.tagged: $(BUILD_TARGET_ROOT)/docker/Dockerfile $(BUILD_TARGET_ROOT)/docker/share/marathon
	mkdir -p "$(dir $@)"
	cd $(BUILD_TARGET_ROOT)/docker && docker build --build-arg MESOS_PKG_VERSION=$(MESOS_PKG_VERSION) --tag mesosphere/marathon:v$(PKG_VER) . 2>&1 | tee "$(PWD)/$@.work"
	mv $@.work $@

$(BUILD_TARGET_ROOT)/docker.pushed: $(BUILD_TARGET_ROOT)/docker.tagged
	docker push mesosphere/marathon:v$(PKG_VER) 2>&1 | tee $@.work

.PHONY: docker
docker: $(BUILD_TARGET_ROOT)/docker.tagged

.PHONY: push-docker
push-docker: $(BUILD_TARGET_ROOT)/docker.pushed

### END DOCKER


.PHONY: clean
clean:
	rm -rf marathon*.deb marathon*.rpm marathon*.pkg target

.PHONY: purge
purge: | clean
	# We could also use 'sbt clean' but it takes forever and is not as thorough.
	## || true is so that we still get an exit 0 to allow builds to proceed
	cd marathon && find . -name target -type d -exec rm -rf {} \; || true
