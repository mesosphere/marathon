MAKEFILE_DIR := $(dir $(realpath $(firstword $(MAKEFILE_LIST))))

AMMONITE_213 ?= $(MAKEFILE_DIR)/bin/amm
REF ?= HEAD
PKG_COMMIT := $(shell cd ../../ && ./version commit --ref $(REF))
PKG_VER := $(shell cd ../../ && ./version --ref $(PKG_COMMIT))

BUILD_TARGET_ROOT := target/$(PKG_VER)

.SECONDARY:

# General

ifeq ($(REF), HEAD)
../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz:
	cd ../../ && sbt universal:packageZipTarball

target/artifacts/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz: ../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz
	mkdir -p target/artifacts
	cp ../../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz $@
else
target/artifacts/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz:
	mkdir -p $(BUILD_TARGET_ROOT)/target/artifacts
	curl -f -o $@ https://downloads.mesosphere.io/marathon/builds/$(PKG_VER)-$(PKG_COMMIT)/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz
endif


$(BUILD_TARGET_ROOT)/lib:
	mkdir -p $@

$(BUILD_TARGET_ROOT)/docker-built: $(BUILD_TARGET_ROOT)/verified $(BUILD_TARGET_ROOT)/Dockerfile
	cd $(@D); docker build . -t mesosphere/marathon-storage-tool:$(PKG_VER)
	docker run --rm -it mesosphere/marathon-storage-tool:$(PKG_VER) --help | grep "Show help message"
	touch $@

$(BUILD_TARGET_ROOT)/docker-pushed: $(BUILD_TARGET_ROOT)/docker-built
	docker push mesosphere/marathon-storage-tool:$(PKG_VER)
	touch $@

$(BUILD_TARGET_ROOT)/marathon: target/artifacts/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz
	mkdir -p $(BUILD_TARGET_ROOT)
	tar xzf target/artifacts/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz -C $(BUILD_TARGET_ROOT)
	mv $(BUILD_TARGET_ROOT)/marathon-$(PKG_VER)-$(PKG_COMMIT) $@
	touch $@

define copy_19_lib_template
$(BUILD_TARGET_ROOT)/lib/$(1): $(BUILD_TARGET_ROOT)/lib src/lib/$(1)
	cp src/lib/$(1) $$@
endef

$(foreach file,$(foreach f,$(wildcard src/lib/*),$(notdir $f)),$(eval $(call copy_19_lib_template,$(file))))

$(BUILD_TARGET_ROOT)/bin/storage-tool.sh: $(BUILD_TARGET_ROOT)/lib src/bin/storage-tool.sh
	mkdir -p $(@D)
	cp src/bin/storage-tool.sh $@

$(BUILD_TARGET_ROOT)/verified: $(BUILD_TARGET_ROOT)/bin/storage-tool.sh $(BUILD_TARGET_ROOT)/lib/version.sc $(BUILD_TARGET_ROOT)/lib/bindings.sc $(BUILD_TARGET_ROOT)/lib/load-jar.sc $(BUILD_TARGET_ROOT)/lib/predef.sc $(BUILD_TARGET_ROOT)/lib/dsl.sc $(BUILD_TARGET_ROOT)/lib/helpers.sc  $(BUILD_TARGET_ROOT)/marathon
	cd $(BUILD_TARGET_ROOT); $(AMMONITE_213) --predef lib/predef.sc --predef-code 'println("it worked"); sys.exit(0)' | grep "it worked"
	touch $@

$(BUILD_TARGET_ROOT)/Dockerfile: src/Dockerfile
	mkdir -p $(@D)
	cp $< $@

.PHONY: clean
clean:
	rm -rf target

.PHONY: artifact
artifact: target/artifacts/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz

.PHONY: compile
compile: $(BUILD_TARGET_ROOT)/verified

.PHONY: docker
docker: $(BUILD_TARGET_ROOT)/docker-built

.PHONY: push-docker
push-docker: $(BUILD_TARGET_ROOT)/docker-pushed
