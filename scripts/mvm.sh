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
# When mvm is run, it first checks if the requested version is already present
# in ~/.mesos/mesos_versions. If not, the requested version is compiled.
# By default, MVM will then spawn a shell which was temporarily configured with the paths to
# the requested version of Apache Mesos.
#
# MVM can be switched to "persistent mode", in which the shell configuration is persisted on disk.
# In this mode, MVM will create a symlink at ~/mesos/current, which always points to the currently
# activated version, and store the current version number in ~/mesos/current_version.
# The following operations are carried out during the installation process:
# Path configurations are stored in ~/.mesos/env
# A "source" command for ~/.mesos/env is added to ~/.profile and
# ~/.config/fish/config.fish (if fish is installed)
# ~/.zshrc (if zsh is installed)
# mvm.sh is copied to ~/.mesos/bin
# The file ~/.mesos/install.lock is created to signify that mvm is installed persistently
# By default, mvm will not spawn a new shell while persistent mode is enabled.
#

set -e

MVM_BASE="$HOME/.mesos"
MVM_BIN="$MVM_BASE/bin"
MVM_INSTALL_LOCK_FILE="$MVM_BASE/install.lock"
MVM_ENV_FILE="$MVM_BASE/env"

MESOS_INSTALL_BASE="$MVM_BASE/mesos_versions"
MESOS_SOURCES="$MVM_BASE/mesos_src"

FISH_RC_FILE="$HOME/.config/fish/config.fish"
ZSH_RC_FILE="$HOME/.zshrc"
GENERIC_PROFILE_FILE="$HOME/.profile"
ENV_SOURCE_STRING="source ${MVM_ENV_FILE/$HOME/\$HOME}"

# check if persistent mode is enabled
if [ -f "$MVM_INSTALL_LOCK_FILE" ]; then
  PERSISTENT_MODE=true
else
  PERSISTENT_MODE=false
fi

function print_help {
  echo "MVM, The Mesos Version Manager."
  echo ""
  echo "MVM allows to install and switch between different versions of Apache Mesos."
  echo "All versions are stored in the folder ~/.mesos/mesos_versions"
  echo ""
  echo "By default, mvm will run in non-persistent mode."
  echo "It will spawn a temporarily configured bash shell, and you will have to re-run"
  echo "the script whenever you open up a new shell."
  echo ""
  echo "MVM can be switched to persistent mode by running $0 --self-install"
  echo "This will configure the mesos environment variables for sh, bash, zsh, and fish."
  echo "MVM will be copied into ~/.mesos/bin during the installation."
  echo "If you are using a different shell, run $0 --self-install and add the line"
  echo "$ENV_SOURCE_STRING"
  echo "to your shell configuration or use the --print-config command."
  echo ""
  echo "Usage: $0 [COMMAND|VERSION]"
  echo ""
  echo "VERSION: Any tag or revision hash from the Mesos git repository may be chosen."
  echo ""
  echo "Commands:"
  echo " --current                Show the currently activated Mesos version"
  echo " --delete [VERSION]       Delete an installed version of Mesos"
  echo " --fetch                  See --update"
  echo " --help                   Display this help screen"
  if ! $PERSISTENT_MODE; then
    echo " --self-install                Install mvm to ~/.mesos and configure shells"
  fi
  echo " --installed              List all installed versions"
  echo " --latest                 Switch to the latest avaiable version (HEAD)"
  echo " --list                   See --tags"
  echo " --print-config           Print the shell configuration"
  echo " --tags                   List all available version tags"
  echo " --shell [VERSION]        Force spawning a shell, even in persistent mode"
  echo " --update                 Update the Mesos sources"
  echo ""
  echo "Environment variables:"
  echo "MESOS_MAKE_JOBS:          Maximum number of make jobs to run in parallel"
  echo "                          Default: Number of processors"
  echo "MESOS_CONFIGURE_OPTIONS:  Options passed to ./configure for Mesos."
  echo "                          Default: --enable-ssl --enable-libevent"
}

function print_config {
  local version_path="$1"
  local mesos_base="${MVM_BASE/$HOME/\$HOME}/$version_path"
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

function generate_env_content {
  print_config "current"
  echo "export PATH=\"$MVM_BASE/bin:\$PATH\""
}

function error {
  echo "Error: $1"
  exit 1
}

function ret_confirm {
  read -r -p "$1 [y/n] " response
  if [[ ! "$response" =~ [yY](es)? ]]; then
    return 1
  fi
  return 0
}

function confirm {
  if ! ret_confirm "$1"; then
    echo "Abort."
    exit 0
  fi
}

function warn {
  echo "Warning: $1"
}

function print_tags {
  cd "$MESOS_SOURCES"
  local current_version

  if [ -f "$MVM_BASE/current_version" ]; then
    current_version=$(cat "$MVM_BASE/current_version")
  fi

  for tag in $(git tag); do
    echo -ne "$tag"

    if [ "$current_version" == "$tag" ]; then
      echo -ne "●"
    elif [ -d "$MESOS_INSTALL_BASE/$tag" ]; then
      # version is built & installed
      echo -ne "✓"
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
      echo -ne "●"
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
  if [ "$requested_version" = "" ] || ! git checkout "$requested_version" &> /dev/null; then
    error "Version '$requested_version' does not match a git tag or revision"
  fi

  echo "Version '$requested_version' is not currently installed."
  confirm "Compilation may take 30+ mins. Continue?"

  # always re-generate configure script as it may change with different versions
  ./bootstrap

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

function version_compiled {
  local requested_version="$1"
  [ "$requested_version" != "" ] && test -d "$MESOS_INSTALL_BASE/$requested_version"
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

  if ! version_compiled "$requested_version"; then
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
  bash -c "$(print_config "mesos_versions/$requested_version"); export PS1=\"(mesos $requested_version) $PS1\"; bash"
}

function set_current_link {
  local requested_version="$1"

  rm -f "$MVM_BASE/current"
  ln -s "$MESOS_INSTALL_BASE/$requested_version" "$MVM_BASE/current"
}

function prepare_version {
  local requested_version="$1"
  cd "$MESOS_SOURCES"

  if [ "$requested_version" == "master" ] || [[ $requested_version =~ (head|origin/).* ]]; then
    echo "You have specified a branch name as version."
    echo "Please specify either a tag name or commit hash."
    echo "Use the --latest flag in order to select the latest commit."
    exit 1
  fi

  # verify that the specified Mesos version actually exists
  if ! version_compiled "$requested_version"; then
    compile_mesos_version "$requested_version"
  fi
}

function persist_version {
  local requested_version="$1"
  prepare_version "$requested_version"
  set_current_link "$requested_version"
  echo "$requested_version" > "$MVM_BASE/current_version"
}

function configure_rc_file {
  local rc_file="$1"
  local shell_name="$2"

  if ! grep -qe "^$ENV_SOURCE_STRING\$" "$rc_file"; then
    echo "Configuring $shell_name..."
    echo "$ENV_SOURCE_STRING" >> "$rc_file"
  fi
}

function install_mvm {
  local step_env=false
  local step_bin_dir=false
  local step_zsh=false
  local step_fish=false

  local current_file="${BASH_SOURCE[0]}"
  local mvm_bin_file="$MVM_BIN/mvm"

  # check what needs to be done
  if [ ! -f "$MVM_ENV_FILE" ]; then
    step_env=true
  fi

  if [ ! -d "$MVM_BIN" ]; then
    step_bin_dir=true
  fi

  if which zsh > /dev/null; then
    step_zsh=true
  fi

  if which fish > /dev/null; then
    step_fish=true
  fi

  # let user verify installation
  echo "The following steps will be performed:"
  if $step_env; then echo "Create environment file \"${MVM_ENV_FILE/$HOME/~}\""; fi
  if $step_bin_dir; then echo "Create directory \"${MVM_BIN/$HOME/~}\""; fi
  echo "Copy \"${current_file/$HOME/~}\" to \"${mvm_bin_file/$HOME/~}\""
  echo "Create \"${MVM_INSTALL_LOCK_FILE/$HOME/~}\""
  confirm "Proceed?"

  # perform installation
  if $step_env; then
    echo "Creating \"${MVM_ENV_FILE/$HOME/~}\""
    generate_env_content > "$MVM_ENV_FILE"
  fi

  if $step_bin_dir; then
    echo "Creating directory \"${MVM_BIN/$HOME/~}\""
    mkdir "$MVM_BIN"
  fi

  echo "Copying ${current_file/$HOME/~} to ${mvm_bin_file/$HOME/~}"
  cp "$current_file" "$mvm_bin_file"

  # configure shells
  if ret_confirm "Configure bash & sh?"; then
    echo "Source \"${MVM_ENV_FILE/$HOME/~}\" in \"${GENERIC_PROFILE_FILE/$HOME/~}\""
    configure_rc_file "$GENERIC_PROFILE_FILE" "bash & sh"
  fi

  if $step_zsh && ret_confirm "Configure zsh shell?"; then
    echo "Source \"${MVM_ENV_FILE/$HOME/~}\" in \"${ZSH_RC_FILE/$HOME/~}\""
    configure_rc_file "$ZSH_RC_FILE" "zsh"
  fi

  if $step_fish && ret_confirm "Configure fish shell?"; then
    echo "Source \"${MVM_ENV_FILE/$HOME/~}\" in \"${FISH_RC_FILE/$HOME/~}\""
    configure_rc_file "$FISH_RC_FILE" "fish"
  fi

  echo "Creating ${MVM_INSTALL_LOCK_FILE/$HOME/~}"
  touch "$MVM_INSTALL_LOCK_FILE"

  echo "MVM successfully installed."
  echo "Restart your shell or run \`$ENV_SOURCE_STRING\` to apply the changes."
}

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

# check command line arguments for --help
if [ "$1" == "--help" ]; then
  print_help
  exit 0
fi

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

# process command line arguments
if [ -z "${1+x}" ]; then
  print_installed
  exit 0
elif [ "$1" == "--current" ]; then
  print_current
  exit 0
elif [ "$1" == "--print-config" ]; then
  print_config "current"
  exit 0
elif [ "$1" == "--tags" ] || [ "$1" == "--list" ]; then
  print_tags
  exit 0
elif [ "$1" == "--self-install" ]; then
  if $PERSISTENT_MODE; then
    echo "Error: Already installed"
    exit 1
  else
    install_mvm
  fi
  exit 0
elif [ "$1" == "--installed" ]; then
  print_installed
  exit 0
elif [ "$1" == "--shell" ]; then
  if [ -z "${2+x}" ]; then
    error "VERSION parameter required for --shell option"
  fi
  PERSISTENT_MODE=false
  REQUESTED_VERSION="$2"
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

REQUESTED_VERSION_LOWER="$(echo "$REQUESTED_VERSION" | tr "[:upper:]" "[:lower:]")"

if $PERSISTENT_MODE; then
  persist_version "$REQUESTED_VERSION_LOWER"

  if ! echo "$PATH" | grep -q "$MVM_BASE/current"; then
    warn "Incorrect shell configuration detected."
    echo "MVM run in persistent mode but PATH not correctly set."
    echo "Have you added '$ENV_SOURCE_STRING' to your shell configuration?"
  fi

  echo "Mesos $REQUESTED_VERSION successfully activated."
else
  prepare_version "$REQUESTED_VERSION_LOWER"
  spawn_shell "$REQUESTED_VERSION"
fi
