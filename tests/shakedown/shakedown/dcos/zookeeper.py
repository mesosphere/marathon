import requests

from ..clients import dcos_url
from ..clients.authentication import dcos_acs_token, DCOSAcsAuth


# API found via https://groups.google.com/forum/#!topic/exhibitor-users/HoTXQWmQ1bs
def get_zk_node_data(node_name):
    znode_url = "{}/exhibitor/exhibitor/v1/explorer/node-data?key={}".format(dcos_url(), node_name)
    auth = DCOSAcsAuth(dcos_acs_token())
    response = requests.get(znode_url, auth=auth)
    return response.json()


def get_zk_node_children(node_name):
    znode_url = "{}/exhibitor/exhibitor/v1/explorer/node?key={}".format(dcos_url(), node_name)
    auth = DCOSAcsAuth(dcos_acs_token())
    response = requests.get(znode_url, auth=auth)
    return response.json()


def delete_zk_node(node_name):
    znode_url = "{}/exhibitor/exhibitor/v1/explorer/znode/{}".format(dcos_url(), node_name)
    auth = DCOSAcsAuth(dcos_acs_token())
    response = requests.delete(znode_url, auth=auth)

    if 200 <= response.status_code < 300:
        return True
    else:
        return False
