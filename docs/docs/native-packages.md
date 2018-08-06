# Native Packages

(since Marathon 1.5.0)

Native packages are built alongside with each Marathon release and are available for the following distributions:

- Debian Jessie
- Debian Stretch
- Ubuntu Xenial
- Ubuntu Trusty
- Centos 6
- Centos 7

# Installing Marathon

## Ubuntu and Debian

```
# Setup
sudo apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF
DISTRO=$(lsb_release -is | tr '[:upper:]' '[:lower:]')
CODENAME=$(lsb_release -cs)

# Add the repository
echo "deb http://repos.mesosphere.com/${DISTRO} ${CODENAME} main" | \
  sudo tee /etc/apt/sources.list.d/mesosphere.list
sudo apt-get -y update

# Install packages
sudo apt-get -y install mesos marathon
```

# RedHat and CentOS 6

```
# Add the repository
sudo rpm -Uvh http://repos.mesosphere.com/el/6/noarch/RPMS/mesosphere-el-repo-6-2.noarch.rpm

# Install packages
sudo yum -y install mesos marathon
```

# RedHat and CentOS 7

```
# Add the repository
sudo rpm -Uvh http://repos.mesosphere.com/el/7/noarch/RPMS/mesosphere-el-repo-7-2.noarch.rpm

# Install packages
sudo yum -y install mesos marathon
```

# Configuring Marathon

After installation, you can configure Marathon command-line arguments by specifying environment variables in `/etc/default/marathon`. For information about how environment variables map to command-line arguments, see "Specifying Command-Line Flags with Environment Variables" in the [command line flags](command-line-flags.html) documentation.

**IMPORTANT** Marathon is configured to launch as the system user `marathon`, and this causes the default value for `--mesos_user` to be `marathon`. It is unlikely that your agents will have this user. You will want to either add the `marathon` user to all agents, or specify a system user that is present on all agents by setting `MARATHON_MESOS_USER`.

# Logging Location

For systemd based distros, logs go to the system journal and can be viewed by running `journalctl -xefu marathon`.

For SystemV distros (Centos / RedHat 6), logs are written to /var/log/marathon.

For Upstart distros (Ubuntu Trusty), logs are sent to the upstart logging mechanism.
