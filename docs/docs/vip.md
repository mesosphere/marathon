---------------------------
title: Virtual IP Addresses
---------------------------

VIPs allow you to assign a single (virtual) IP to an application, rather than assign each application instance a real IP. This means you no longer have to keep track of port mappings. In addition, while real IPs and ports can only be used once, VIPs will always have the requested port available. DCOS routes traffic from a VIP to the proper port on the host machine.

This feature is only available in Marathon deployed with DCOS. See the (DCOS docs)[STILL NEED THE URL] for this feature.
