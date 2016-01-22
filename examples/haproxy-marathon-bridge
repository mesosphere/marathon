#!/bin/bash
set -o errexit -o nounset -o pipefail

##########################################################
# DEPRECATION WARNING: This script has been deprecated.  #
#                                                        #
# Please use marathon-lb instead:                        #
# https://github.com/mesosphere/marathon-lb              #
##########################################################

function -h {
cat <<USAGE
 USAGE: $name <marathon host:port>+
        $name install_haproxy_system <marathon host:port>+

  Generates a new configuration file for HAProxy from the specified Marathon
  servers, replaces the file in /etc/haproxy and restarts the service.

  In the second form, installs the script itself, HAProxy and a cronjob that
  once a minute pings one of the Marathon servers specified and refreshes
  HAProxy if anything has changed. The list of Marathons to ping is stored,
  one per line, in:

    $cronjob_conf_file

  The script is installed as:

    $script_path

  The cronjob is installed as:

    $cronjob

  and run as root.

USAGE
}; function --help { -h ;}
export LC_ALL=en_US.UTF-8

name=haproxy-marathon-bridge
cronjob_conf_file=/etc/"$name"/marathons
cronjob=/etc/cron.d/"$name"
script_path=/usr/local/bin/"$name"
conf_file=haproxy.cfg

function main {
  config "$@"
}

function refresh_system_haproxy {
  config "$@" > /tmp/"$conf_file"
  if ! diff -q /tmp/"$conf_file" /etc/haproxy/"$conf_file" >&2
  then
    msg "Found changes. Sending reload request to HAProxy..."
    cat /tmp/"$conf_file" > /etc/haproxy/"$conf_file"
    if [[ -f /etc/init/haproxy.conf ]]
    then reload haproxy ## Upstart
    elif [[ -f /usr/lib/systemd/system/haproxy.service ]]
    then systemctl reload haproxy ## systemd
    else /etc/init.d/haproxy reload
    fi
  fi
}

function install_haproxy_system {

  sudo=""
  if [[ $EUID -ne 0 ]]
    then sudo="sudo"
  fi

  current_path=$(dirname $0)
  current_path=$(cd $current_path && pwd) ## absolute path
  if [ $current_path/$name = $script_path ]
  then
    # executing the script from its install location leads to an empty file
    echo "running $name in install mode from the install path $script_path is not supported."
    exit 1 ## fail
  fi

  if hash lsb_release 2>/dev/null
  then
    os=$(lsb_release -si)
  elif [ -e "/etc/system-release" ] && (grep -q "Amazon Linux AMI" "/etc/system-release")
  then
    os="AmazonAMI"
  elif [ -e "/etc/SuSE-release" ]
  then
    os="SuSE"
  fi
     
  if [[ $os == "CentOS" ]] || [[ $os == "RHEL" ]] || [[ $os == "AmazonAMI" ]] || [[ $os == "OracleServer" ]]
  then
    $sudo yum install -y haproxy
    $sudo chkconfig haproxy on
  elif [[ $os == "Ubuntu" ]] || [[ $os == "Debian" ]]
  then 
    $sudo env DEBIAN_FRONTEND=noninteractive aptitude install -y haproxy
    $sudo sed -i 's/^ENABLED=0/ENABLED=1/' /etc/default/haproxy
  elif [[ $os == "SuSE" ]]
  then
    $sudo zypper -n --no-gpg-checks --non-interactive install --auto-agree-with-licenses haproxy
    $sudo chkconfig haproxy on
  else 
    echo "$os is not a supported OS for this feature."
    exit 1
  fi
  install_cronjob "$@"
}

function install_cronjob {
  $sudo mkdir -p "$(dirname "$cronjob_conf_file")"
  [[ -f $cronjob_conf_file ]] || $sudo touch "$cronjob_conf_file"
  if [[ $# -gt 0 ]]
  then printf '%s\n' "$@" | $sudo dd of="$cronjob_conf_file"
  fi
  cat "$0" | $sudo dd of="$script_path"
  $sudo chmod ug+rx "$script_path"
  cronjob  | $sudo dd of="$cronjob"
  header   | $sudo dd of=/etc/haproxy/"$conf_file"
}

function cronjob {
cat <<EOF
* * * * * root $script_path logged refresh_system_haproxy \$(cat $cronjob_conf_file)
EOF
}

function config {
  header
  apps "$@"
}

function header {
cat <<\EOF
global
  daemon
  log 127.0.0.1 local0
  log 127.0.0.1 local1 notice
  maxconn 4096

defaults
  log            global
  retries             3
  maxconn          2000
  timeout connect  5000
  timeout client  50000
  timeout server  50000

listen stats
  bind 127.0.0.1:9090
  balance
  mode http
  stats enable
  stats auth admin:admin
EOF
}

function apps {
  (until curl -sSfLk -m 10 -H 'Accept: text/plain' "${1%/}"/v2/tasks; do [ $# -lt 2 ] && return 1 || shift; done) | while read -r txt
  do
    set -- $txt
    if [ $# -lt 2 ]; then
      shift $#
      continue
    fi

    local app_name="$1"
    local app_port="$2"
    shift 2

    if [ ! -z "${app_port##*[!0-9]*}" ]
    then
      cat <<EOF

listen $app_name-$app_port
  bind 0.0.0.0:$app_port
  mode tcp
  option tcplog
  balance leastconn
EOF
      while [[ $# -ne 0 ]]
      do
        out "  server ${app_name}-$# $1 check"
        shift
      done
    fi
  done
}

function logged {
  exec 1> >(logger -p user.info -t "$name[$$]")
  exec 2> >(logger -p user.notice -t "$name[$$]")
  "$@"
}

function msg { out "$*" >&2 ;}
function err { local x=$? ; msg "$*" ; return $(( $x == 0 ? 1 : $x )) ;}
function out { printf '%s\n' "$*" ;}

# If less than 1 argument is provided, print usage and exit. At least one
# argument is required as described in the `USAGE` message.
[ $# -lt 1 ] && { -h; exit 1; }

if [[ ${1:-} ]] && declare -F | cut -d' ' -f3 | fgrep -qx -- "${1:-}"
then "$@"
else main "$@"
fi
