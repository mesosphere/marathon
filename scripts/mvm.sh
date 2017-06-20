#!/bin/bash -i
#
# ***Mesos Version Manager***
#
# * Introduction *
# This script allows to easily and quickly install and switch between different
# versions of Apache Mesos.
# It was built to simplify testing of Marathon against multiple versions of Mesos.
#
# * Prerequisites *
# MVM assumes that all dependencies required to compile Mesos are readily installed.
# Follow the instructions on this page in order to install the dependencies:
# http://mesos.apache.org/gettingstarted/
# MVM compiles Mesos with SSL support by default, which also requires openssl and libevent.
# For macOS: brew install openssl libevent
# For CentOS: yum install -y libevent-devel openssl-devel
#
# * Compatibility *
# MVM has currently been tested on the following systems:
#     * macOS 10.12.5 "Sierra".
#     * CentOS 7 Minimal 1611
#     * Fedora 25
#
# * Functional Overview *
# The script clones the Apache Mesos sources files into ~/.mesos/mesos_src
# Versions activated by the user are compiled and installed to ~/.mesos/mesos_versions/<VERSION>
# (E.g. ~/.mesos/mesos_version/1.3.0-rc3)
# ~/mesos/current is a symlink which always points to the currently activated version.
# When mvm is run, it first checks if the requested version is already present
# in ~/.mesos/mesos_versions. If not, the requested version is compiled.
# It then updates the symlink ~/.mesos/current
# If a shell was given as second paramter mvm will spawn an instance of the shell which was configured
# to be able to run the chosen version of Mesos.
#

set -e

function print_help {
  echo "MVM, The Mesos Version Manager."
  echo ""
  echo "This script allows to install and switch between different versions of Apache Mesos."
  echo "All versions are stored in the folder ~/.mesos/mesos_versions"
  echo "The folder ~/.mesos/current always points to the currently active version."
  echo "You should configure your shell to search for Mesos at this location."
  echo "Use the --print-config option to display the required shell configuration."
  echo "Alternatively, you may pass the SHELL argument to launch a configured shell."
  echo ""
  echo "Usage: $0 [OPTION|VERSION] [SHELL]"
  echo ""
  echo "VERSION: Any tag or revision hash from the Mesos git repository may be chosen."
  echo "SHELL: If set, the given shell will be configured to run Mesos and launched."
  echo ""
  echo "OPTIONS:"
  echo " --current                Show the currently activated Mesos version"
  echo " --delete [VERSION]       Delete an installed version of Mesos"
  echo " --fetch                  See --update"
  echo " --help                   Display this help screen"
  echo " --installed              List all installed versions"
  echo " --latest                 Switch to the latest avaiable version (HEAD)"
  echo " --print-config           Print the bash configuration"
  echo " --tags                   List all available version tags"
  echo " --update                 Update the Mesos sources"
  echo ""
  echo "Environment variables:"
  echo "MESOS_MAKE_JOBS:          Maximum number of make jobs to run in parallel"
  echo "                          Default: Number of processors"
  echo "MESOS_CONFIGURE_OPTIONS:  Options passed to ./configure for Mesos."
  echo "                          Default: --enable-ssl --enable-libevent"
}

function print_config {
  local mesos_base="\$HOME/.mesos/current"
  local mesos_bin="$mesos_base/bin"
  local mesos_sbin="$mesos_base/sbin"
  local mesos_lib="$mesos_base/lib"
  local mesos_include="$mesos_base/include"

  echo "export PATH=\"$mesos_bin:$mesos_sbin:\$PATH\";"
  echo "export MESOS_NATIVE_JAVA_LIBRARY=\"$mesos_lib/libmesos.$SHARED_LIBRARY_EXT\";"
  echo "export CPATH=\"$mesos_include:\$CPATH\";"
  echo "export LIBRARY_PATH=\"$mesos_lib:\$LIBRARY_PATH\";"
  echo "export PYTHONPATH=\"$mesos_lib/$PYTHON_LIB_FOLDER/site-packages:\$PYTHONPATH\""
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
  local current_version
  local version_name

  if [ -f "$MVM_BASE/current_version" ]; then
    current_version=$(cat "$MVM_BASE/current_version")
  fi

  for version in $MESOS_INSTALL_BASE/*; do
    version_name=$(basename "$version")
    echo -ne "$version_name"
    if [ "$version_name" == "$current_version" ]; then
      echo -ne "*"
    fi
    echo ""
  done
}

function print_current {
  if [ ! -f "$MVM_BASE/current_version" ]; then
    echo "No Mesos version currently activated"
  else
    cat "$MVM_BASE/current_version"
  fi
}

function compile_mesos_version {
  cd "$MESOS_SOURCES"
  local requested_version="$1"
  local target_dir="$MESOS_INSTALL_BASE/$requested_version"

  # checkout requested revision
  if ! git checkout "$requested_version" &> /dev/null; then
    error "Version '$requested_version' does not match a git tag or revision"
  fi

  echo "Version '$requested_version' is not currently installed."
  confirm "Compilation may take 30+ mins. Continue?"

  # generate configure script if it does not exist
  if [ ! -f "$MESOS_SOURCES/configure" ] || [ ! -f "$MESOS_SOURCES/Makefile.in" ]; then
    ./bootstrap
  fi

  mkdir -p build
  cd build

  ../configure \
    CXXFLAGS=-Wno-deprecated-declarations \
    ${MESOS_CONFIGURE_OPTIONS---enable-ssl --enable-libevent} \
    --prefix="$target_dir"

  local num_parallel=${MESOS_MAKE_JOBS:-$(getconf _NPROCESSORS_ONLN)}
  make clean
  make "-j$num_parallel"

  # prepare installation directory and install Mesos
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

  if [ -f "$MVM_BASE/current_version" ] && [ "$requested_version" == "$(cat "$MVM_BASE/current_version")" ]; then
    rm "$MVM_BASE/current_version"
    rm -f "$MVM_BASE/current"
  fi
}

function spawn_shell {
  local requested_version="$1"
  local mesos_base="$MVM_BASE/current"
  local mesos_bin="$mesos_base/bin"
  local mesos_sbin="$mesos_base/sbin"
  local mesos_lib="$mesos_base/lib"
  local mesos_include="$mesos_base/include"

  # spawn new shell
  bash -c "$(print_config); export PS1=\"(mesos $requested_version) $PS1\"; ${SHELL:-bash}"
}

function set_current_link {
  local requested_version="$1"

  rm -f "$MVM_BASE/current"
  ln -s "$MESOS_INSTALL_BASE/$requested_version" "$MVM_BASE/current"
}

function activate_version {
  local requested_version="$1"
  cd "$MESOS_SOURCES"

  if [ "$requested_version" == "master" ] || [[ $requested_version =~ (head|origin/).* ]]; then
    echo "You have specified a branch name as version."
    echo "Please specify either a tag name or commit hash."
    echo "Use the --latest flag in order to select the latest commit."
    exit 1
  fi

  # verify that the specified Mesos version actually exists
  if ! version_exists "$requested_version"; then
    compile_mesos_version "$requested_version"
  fi

  set_current_link "$requested_version"
  echo "$requested_version" > "$MVM_BASE/current_version"
}

MVM_BASE="$HOME/.mesos"
MESOS_INSTALL_BASE="$MVM_BASE/mesos_versions"
MESOS_SOURCES="$HOME/.mesos/mesos_src"

# check OS compatibility
if [ "$(uname)" == "Darwin" ]; then
  SHARED_LIBRARY_EXT="dylib"
  PYTHON_LIB_FOLDER="python"
elif [ "$(uname)" == "Linux" ]; then
  SHARED_LIBRARY_EXT="so"
  PYTHON_LIB_FOLDER="python2.7"
else
  error "MVM currently only supports macOS and Linux."
fi

# ensure that the MVM_BASE directory exists
if [ ! -d "$MVM_BASE" ]; then
  mkdir "$MVM_BASE"
fi

# ensure that the MESOS_INSTALL_BASE directory exists
if [ ! -d "$MESOS_INSTALL_BASE" ]; then
  mkdir "$MESOS_INSTALL_BASE"
fi

# check command line arguments for --help
if [ "$1" == "--help" ]; then
  print_help
  exit 0
fi

# check if mesos source directory exists
if [ ! -d "$MESOS_SOURCES" ]; then
  echo "Mesos sources not found. Cloning sources."
  git clone https://git-wip-us.apache.org/repos/asf/mesos.git "$MESOS_SOURCES"
fi

# process command line arguments
if [ -z "${1+x}" ]; then
  print_installed
  exit 0
elif [ "$1" == "--current" ]; then
  print_current
  exit 0
elif [ "$1" == "--print-config" ]; then
  print_config
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
elif [[ $1 == --* ]]; then
  error "Unknown flag: '$1'."
else
  REQUESTED_VERSION="$1"
fi

activate_version "$(echo "$REQUESTED_VERSION" | tr "[:upper:]" "[:lower:]")"

if [ -n "$2" ]; then
  SHELL="$2"
  spawn_shell "$REQUESTED_VERSION"
else
  if ! echo "$PATH" | grep -q "$MVM_BASE/current"; then
    warn "Incorrect shell configuration detected."
    echo "Run $0 --print-config and add the printed configuration to your shells' RC file."
    echo "For bash, simply run the following command:"
    echo "$0 --print-config >> ~/.bash_profile && source ~/.bash_profile"
    echo ""
  fi
  echo "Mesos $REQUESTED_VERSION successfully activated."
fi
