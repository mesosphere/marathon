import click
import contextlib
import os
import re
import toml

import shakedown


def read_config(args):
    """ Read configuration options from ~/.shakedown (if exists)

        :param args: a dict of arguments
        :type args: dict

        :return: a dict of arguments
        :rtype: dict
    """

    configfile = os.path.expanduser('~/.shakedown')

    if os.path.isfile(configfile):
        with open(configfile, 'r') as f:
            config = toml.loads(f.read())

        for key in config:
            param = key.replace('-', '_')

            if not param in args or args[param] in [False, None]:
                args[param] = config[key]

    return args


def set_config_defaults(args):
    """ Set configuration defaults

        :param args: a dict of arguments
        :type args: dict

        :return: a dict of arguments
        :rtype: dict
    """

    defaults = {
        'fail': 'fast',
        'stdout': 'fail'
    }

    for key in defaults:
        if not args[key]:
            args[key] = defaults[key]

    return args


def fchr(char):
    """ Print a fancy character

        :param char: the shorthand character key
        :type char: str

        :return: a fancy character
        :rtype: str
    """

    return {
        'PP': chr(10003),
        'FF': chr(10005),
        'SK': chr(10073),
        '>>': chr(12299)
    }.get(char, '')


def banner():
    """ Display a product banner

        :return: a delightful Mesosphere logo rendered in unicode
        :rtype: str
    """

    banner_dict = {
        'a0': click.style(chr(9601), fg='magenta'),
        'a1': click.style(chr(9601), fg='magenta', bold=True),
        'b0': click.style(chr(9616), fg='magenta'),
        'c0': click.style(chr(9626), fg='magenta'),
        'c1': click.style(chr(9626), fg='magenta', bold=True),
        'd0': click.style(chr(9622), fg='magenta'),
        'd1': click.style(chr(9622), fg='magenta', bold=True),
        'e0': click.style(chr(9623), fg='magenta'),
        'e1': click.style(chr(9623), fg='magenta', bold=True),
        'f0': click.style(chr(9630), fg='magenta'),
        'f1': click.style(chr(9630), fg='magenta', bold=True),
        'g1': click.style(chr(9612), fg='magenta', bold=True),
        'h0': click.style(chr(9624), fg='magenta'),
        'h1': click.style(chr(9624), fg='magenta', bold=True),
        'i0': click.style(chr(9629), fg='magenta'),
        'i1': click.style(chr(9629), fg='magenta', bold=True),
        'j0': click.style(fchr('>>'), fg='magenta'),
        'k0': click.style(chr(9473), fg='magenta'),
        'l0': click.style('_', fg='magenta'),
        'l1': click.style('_', fg='magenta', bold=True),
        'v0': click.style('mesosphere', fg='magenta'),
        'x1': click.style('shakedown', fg='magenta', bold=True),
        'y0': click.style('v' + shakedown.VERSION, fg='magenta'),
        'z0': chr(32)
    }

    banner_map = [
        " %(z0)s%(z0)s%(l0)s%(l0)s%(l1)s%(l0)s%(l1)s%(l1)s%(l1)s%(l1)s%(l1)s%(l1)s%(l1)s%(l1)s",
        " %(z0)s%(b0)s%(z0)s%(c0)s%(z0)s%(d0)s%(z0)s%(z0)s%(z0)s%(z0)s%(e1)s%(z0)s%(f1)s%(z0)s%(g1)s",
        " %(z0)s%(b0)s%(z0)s%(z0)s%(c0)s%(z0)s%(h0)s%(e0)s%(d1)s%(i1)s%(z0)s%(f1)s%(z0)s%(z0)s%(g1)s%(z0)s%(j0)s%(v0)s %(x1)s %(y0)s",
        " %(z0)s%(b0)s%(z0)s%(z0)s%(f0)s%(c0)s%(i0)s%(z0)s%(z0)s%(h1)s%(f1)s%(c1)s%(z0)s%(z0)s%(g1)s%(z0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(k0)s%(z0)s%(k0)s%(k0)s%(z0)s%(z0)s%(k0)s",
        " %(z0)s%(i0)s%(f0)s%(h0)s%(z0)s%(z0)s%(c0)s%(z0)s%(z0)s%(f0)s%(z0)s%(z0)s%(i1)s%(c1)s%(h1)s",
        " %(z0)s%(z0)s%(z0)s%(z0)s%(z0)s%(z0)s%(z0)s%(c0)s%(f0)s",
    ]

    if 'TERM' in os.environ and os.environ['TERM'] in ('velocity', 'xterm', 'xterm-256color', 'xterm-color'):
        return echo("\n".join(banner_map) % banner_dict)
    else:
        return echo(fchr('>>') + 'mesosphere shakedown v' + shakedown.VERSION, b=True)


def decorate(text, style):
    """ Console decoration style definitions

        :param text: the text string to decorate
        :type text: str
        :param style: the style used to decorate the string
        :type style: str

        :return: a decorated string
        :rtype: str
    """

    return {
        'step-maj': click.style("\n" + '> ' + text, fg='yellow', bold=True),
        'step-min': click.style('  - ' + text + ' ', bold=True),
        'item-maj': click.style('    - ' + text + ' '),
        'item-min': click.style('      - ' + text + ' '),
        'quote-head-fail': click.style("\n" + chr(9485) + (chr(9480)*2) + ' ' + text, fg='red'),
        'quote-head-pass': click.style("\n" + chr(9485) + (chr(9480)*2) + ' ' + text, fg='green'),
        'quote-head-skip': click.style("\n" + chr(9485) + (chr(9480)*2) + ' ' + text, fg='yellow'),
        'quote-fail': re.sub('^', click.style(chr(9482) + ' ', fg='red'), text, flags=re.M),
        'quote-pass': re.sub('^', click.style(chr(9482) + ' ', fg='green'), text, flags=re.M),
        'quote-skip': re.sub('^', click.style(chr(9482) + ' ', fg='yellow'), text, flags=re.M),
        'fail': click.style(text + ' ', fg='red'),
        'pass': click.style(text + ' ', fg='green'),
        'skip': click.style(text + ' ', fg='yellow')
    }.get(style, '')


def echo(text, **kwargs):
    """ Print results to the console

        :param text: the text string to print
        :type text: str

        :return: a string
        :rtype: str
    """

    if shakedown.cli.quiet:
        return

    if not 'n' in kwargs:
        kwargs['n'] = True

    if 'd' in kwargs:
        text = decorate(text, kwargs['d'])

    if 'TERM' in os.environ and os.environ['TERM'] == 'velocity':
        if text:
            print(text, end="", flush=True)
        if kwargs.get('n'):
            print()
    else:
        click.echo(text, nl=kwargs.get('n'))


@contextlib.contextmanager
def stdchannel_redirected(stdchannel, dest_filename):
    """ A context manager to temporarily redirect stdout or stderr

        :param stdchannel: the channel to redirect
        :type stdchannel: sys.std
        :param dest_filename: the filename to redirect output to
        :type dest_filename: os.devnull

        :return: nothing
        :rtype: None
    """

    try:
        oldstdchannel = os.dup(stdchannel.fileno())
        dest_file = open(dest_filename, 'w')
        os.dup2(dest_file.fileno(), stdchannel.fileno())

        yield
    finally:
        if oldstdchannel is not None:
            os.dup2(oldstdchannel, stdchannel.fileno())
        if dest_file is not None:
            dest_file.close()
