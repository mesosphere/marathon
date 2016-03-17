---------------------------
title: Virtual IP Addresses
---------------------------

# Virtual IP Addresses

Virtual IPs are addresses in a special reserved space on your network. DCOS routes traffic from a VIP to the proper port on the host machine.

## Benefits of Using VIPs

VIPs allow you to assign a single (virtual) IP to an application, rather than assign each application instance a real IP. This means you no longer have to keep track of port mappings. In addition, while real IPs and ports can only be used once, VIPs will always have the requested port available.

# Assign a VIP to Your App

You can assign a VIP to your application from the Marathon web interface. The values you enter in these fields are translated into the appropriate `portMapping` entry in your application definition. Toggle to `JSON mode` as you create your app to see and edit your application definition..

## Prerequisite:
- A pool of VIP addresses

1. Access the Marathon web interface at `http://$DCOS_URI/marathon`
1. Either create a new application or click a previously created application in the list of `Applications`.
1. If you are creating a new application, choose `Ports and Service Discovery` menu option. If you are editing an application you have already created, click the `Configuration` tab, then click the `Edit` button.
1. For each VIP, enter your desired port, protocol, and name, as well as a VIP address.
1. If you require more complex port configuration, toggle to `JSON mode` to edit your application definition directly. For more information on port configuration, see the [ports documentation](ports.html).
