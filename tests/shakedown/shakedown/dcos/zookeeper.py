from shakedown import *


# API found via https://groups.google.com/forum/#!topic/exhibitor-users/HoTXQWmQ1bs
def get_zk_node_data(node_name):
    znode_url = "{}/exhibitor/exhibitor/v1/explorer/node-data?key={}".format(dcos_url(), node_name)
    response = http.get(znode_url)
    return response.json()


def get_zk_node_children(node_name):
    znode_url = "{}/exhibitor/exhibitor/v1/explorer/node?key={}".format(dcos_url(), node_name)
    response = http.get(znode_url)
    return response.json()


def delete_zk_node(node_name):
    znode_url = "{}/exhibitor/exhibitor/v1/explorer/znode/{}".format(dcos_url(), node_name)
    response = http.delete(znode_url)

    if 200 <= response.status_code < 300:
        return True
    else:
        return False
