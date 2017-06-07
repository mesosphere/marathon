#!/bin/bash -i
#
# ***Mesos Version Manager***
#
# * Introduction *
# This script allows to easily and quickly install and switch between different
# versions of Apache Mesos.
# It was built to simplify testing of Marathon against multiple versions of Mesos.
#
# * Prerequisites *
# MVM assumes that all dependencies required to compile Mesos are readily installed.
# Follow the instructions on this page in order to install the dependencies:
# http://mesos.apache.org/gettingstarted/
#
# * Compatibility *
# MVM has currently only been tested on macOS 10.12.5 "Sierra".
# It should run on older versions of macOS, but support for Linux will require minor alterations.
#
# * Functional Overview *
# The script clones the Apache Mesos sources files into ~/.mesos/mesos_src.
# Versions activated by the user are compiled and installed to ~/.mesos/mesos_versions/<VERSION>.
# E.g. ~/.mesos/mesos_version/1.3.0-rc3
# The script then spawns a bash shell with paths set to point to the chosen version of Mesos.
#

set -e

function print_help {
	echo "MVM, The Mesos Version Manager."
	echo "Usage: $0 [OPTION|VERSION]"
	echo ""
	echo "VERSION: Any tag or revision hash from the Mesos git repository may be chosen."
	echo ""
	echo "OPTIONS:"
	echo " --delete [VERSION]	Delete an installed version of Mesos"
	echo " --fetch		See --update"
	echo " --help			Display this help screen"
	echo " --latest		Switch to the latest avaiable version (HEAD)"
	echo " --tags			List all available version tags"
	echo " --installed		List all installed versions"
	echo " --update		Update the Mesos sources"
}

function error {
	echo "Error: $1"
	exit 1
}

function confirm {
	read -r -p "$1 [y/n] " response
	if [[ ! "$response" =~ [yY](es)* ]]; then
		echo "Abort."
		exit 0
	fi
}

function warn {
	echo "Warning: $1"
}

function print_tags {
	cd "$MESOS_SOURCES"

	for tag in $(git tag); do
		echo -ne "$tag"

		# check if version is built yet
		if [ -d "$MESOS_INSTALL_BASE/$tag" ]; then
			echo -ne "*"
		fi

		echo ""
	done
}

function print_installed {
	for version in $MESOS_INSTALL_BASE/*; do
		basename "$version"
	done
}

function compile_mesos_version {
	cd "$MESOS_SOURCES"
	local requested_version="$1"
	local target_dir="$MESOS_INSTALL_BASE/$requested_version"

	# checkout requested revision
	if ! git checkout "$requested_version" > /dev/null 2>&1; then
		error "Version '$requested_version' does not match a git tag or revision"
	fi

	# generate configure script if it does not exist
	if [ ! -f "$MESOS_SOURCES/configure" ]; then
		./bootstrap
	fi

	mkdir -p build
	cd build

	../configure CXXFLAGS=-Wno-deprecated-declarations --prefix="$target_dir"

	local num_parallel=${MVM_MAKE_NUM_PARALLEL:-$(getconf _NPROCESSORS_ONLN)}
	make clean
	make "-j$num_parallel"

	# prepare installation directory and install Mesos
	mkdir "$target_dir"
	make install
}

function version_exists {
	local requested_version="$1"
	test -d "$MESOS_INSTALL_BASE/$requested_version"
}

function update_sources {
	cd "$MESOS_SOURCES"
	git fetch
}

function get_head_revision {
	cd "$MESOS_SOURCES"
	git checkout master > /dev/null 2>&1
	git rev-parse --short=9 HEAD
}

function delete_version {
	local requested_version="$1"

	if ! version_exists "$requested_version"; then
		error "Mesos version '$requested_version' is not currently installed"
	fi

	confirm "rm -rf \"$MESOS_INSTALL_BASE/$1\". Proceed?"
	rm -rf "${MESOS_INSTALL_BASE:?}/$requested_version"
}

function spawn_shell {
	local requested_version="$1"
	local mesos_base="$MESOS_INSTALL_BASE/$requested_version"
	local mesos_bin="$mesos_base/bin"
	local mesos_sbin="$mesos_base/sbin"
	local mesos_lib="$mesos_base/lib"
	local mesos_include="$mesos_base/include"

	# spawn new bash shell
	PATH="$mesos_bin:$mesos_sbin:$PATH" \
	MESOS_NATIVE_JAVA_LIBRARY="$mesos_lib/libmesos.dylib" \
	CPATH="$mesos_include:$CPATH" \
	LIBRARY_PATH="$mesos_lib:$LIBRARY_PATH" \
	PS1="(mesos $requested_version) $PS1" \
	bash
}

function activate_version {
	local requested_version="$1"

	if [ "$requested_version" == "master" ]; then
		echo "Please specify either a tag name or commit hash."
		echo "Use the --latest flag in order to select the latest commit."
		exit 1
	fi

	# verify that the specified Mesos version actually exists
	if ! version_exists "$requested_version"; then
		echo "Version '$requested_version' is not currently installed."
		confirm "Compilation may take 30+ mins. Continue?"
		compile_mesos_version "$requested_version"
	fi

	spawn_shell "$requested_version"
}	

MVM_BASE="$HOME/.mesos"
MESOS_INSTALL_BASE="$MVM_BASE/mesos_versions"
MESOS_SOURCES="$HOME/.mesos/mesos_src"

# ensure that the MVM_BASE directory exists
if [ ! -d "$MVM_BASE" ]; then
	mkdir "$MVM_BASE"
fi

# ensure that the MESOS_INSTALL_BASE directory exists
if [ ! -d "$MESOS_INSTALL_BASE" ]; then
	mkdir "$MESOS_INSTALL_BASE"
fi

# check if mesos source directory exists
if [ ! -d "$MESOS_SOURCES" ]; then
	echo "Mesos sources not found. Cloning sources."
	git clone https://git-wip-us.apache.org/repos/asf/mesos.git "$MESOS_SOURCES"
fi

# check OS compatibility
if [ "$(uname)" != "Darwin" ]; then
	error "MVM currently only supports macOS."
elif [ "$(sw_vers -productVersion)" != "10.12.5" ]; then
	warn "MVM has only been tested on macOS 10.12.5."
	echo "You are running macOS $(sw_vers -productVersion). Proceed at your own risk."
fi

# check command line arguments
if [ -z "${1+x}" ] || [ "$1" == "--help" ]; then
	print_help
	exit 0
elif [ "$1" == "--tags" ]; then
	print_tags
	exit 0
elif [ "$1" == "--installed" ]; then
	print_installed
	exit 0
elif [ "$1" == "--update" ] || [ "$1" == "--fetch" ]; then
	echo "Fetching updates..."
	update_sources
	echo "Done."
	exit 0
elif [ "$1" == "--delete" ]; then
	if [ -z "${2+x}" ]; then
		error "VERSION parameter required for --delete option"
	fi

	delete_version "$2"
	exit 0
elif [ "$1" == "--latest" ]; then
	# set requested_version to the latest commit
	REQUESTED_VERSION=$(get_head_revision)
	echo "Latest commit is '$REQUESTED_VERSION'."
	activate_version "$REQUESTED_VERSION"
elif [[ $1 == --* ]]; then
	error "Unknown flag: '$1'."
else
	activate_version "$1"
fi
