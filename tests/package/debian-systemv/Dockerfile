FROM marathon-package-test:debian-systemd

RUN apt-get update && \
  (apt-get install -y sysvinit-core sysvinit-utils || echo ignoring failure) && \
  apt-get remove --purge --auto-remove systemd -y && \
  cp /usr/share/sysvinit/inittab /etc/inittab && \
  echo 'Package: systemd\nPin: release *\nPin-Priority: -1' > /etc/apt/preferences.d/systemd && \
  rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
