import collections

from dcos.errors import DCOSException

Command = collections.namedtuple(
    'Command',
    ['hierarchy', 'arg_keys', 'function'])
"""Describe a CLI command.

:param hierarchy: the noun and verbs that need to be set for the command to
                  execute
:type hierarchy: list of str
:param arg_keys: the arguments that must get passed to the function; the order
                 of the keys determines the order in which they get passed to
                 the function
:type arg_keys: list of str
:param function: the function to execute
:type function: func(args) -> int
"""


def execute(cmds, args):
    """Executes one of the commands based on the arguments passed.

    :param cmds: commands to try to execute; the order determines the order of
                 evaluation
    :type cmds: list of Command
    :param args: command line arguments
    :type args: dict
    :returns: the process status
    :rtype: int
    """

    for hierarchy, arg_keys, function in cmds:
        # Let's find if the function matches the command
        match = True
        for positional in hierarchy:
            if not args[positional]:
                match = False

        if match:
            params = [args[name] for name in arg_keys]
            return function(*params)

    raise DCOSException('Could not find a command with the passed arguments')
