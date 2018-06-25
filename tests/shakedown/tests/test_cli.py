from click.testing import CliRunner

from shakedown import *
from dcos import config


def test_cli_require_dcos_uri():
    runner = CliRunner()
    result = runner.invoke(cli.main.cli, ['tests/test_cli.py'])

    # with preconfigured dcos cli dcos url isn't required
    assert '--dcos-url' not in result.output
    print(result.output)


def test_cli_version():
    runner = CliRunner()
    result = runner.invoke(cli.main.cli, ['--version'])
    assert result.exit_code == 0
    assert result.output == "shakedown, version " + shakedown.VERSION + "\n"
    print(result.output)
