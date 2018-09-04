from shakedown import *


def test_run_command():
    exit_status, output = run_command(master_ip(), 'cat /etc/motd')
    assert exit_status

def test_run_command_on_master():
    exit_status, output = run_command_on_master('uname -a')
    assert exit_status
    assert output.startswith('Linux')

def test_run_command_on_leader():
    exit_status, output = run_command_on_leader('uname -a')
    assert exit_status
    assert output.startswith('Linux')

def test_run_command_on_marathon_leader():
    exit_status, output = run_command_on_marathon_leader('uname -a')
    assert exit_status
    assert output.startswith('Linux')

def test_run_command_on_agent():
    """Run 'ps' on all agents looking for jenkins."""
    service_ips = get_private_agents() + get_public_agents()
    for host in service_ips:
        exit_status, output = run_command_on_agent(host, 'ps -eaf | grep -i docker | grep -i jenkins')
        assert exit_status
        assert len(output) > 0

def test_run_dcos_command():
    stdout, stderr, return_code = run_dcos_command('package search jenkins --json')
    result_json = json.loads(stdout)
    assert result_json['packages'][0]['name'] == 'jenkins'
