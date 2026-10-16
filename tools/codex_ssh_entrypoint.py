#!/usr/bin/env python3
"""Restricted OpenSSH forced command for an Android shared-daemon client.

Install a reviewed copy outside the checkout. Pass --codex with the exact CLI
matching the running app-server daemon. Never evaluates SSH_ORIGINAL_COMMAND.
"""
import argparse
import os
import sys

COMMANDS = {
    'exec "${SHELL:-/bin/sh}" -lc \'codex --version\'': ("--version",),
    'exec "${SHELL:-/bin/sh}" -lc \'codex app-server daemon version\'': ("app-server", "daemon", "version"),
    'exec "${SHELL:-/bin/sh}" -lc \'exec codex app-server proxy\'': ("app-server", "proxy"),
}
POSIX_PROBE = "printf '__CODEX_POSIX__'"


def main(argv=None, environ=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", required=True)
    args = parser.parse_args(argv)
    if not os.path.isabs(args.codex) or not os.access(args.codex, os.X_OK):
        parser.error("--codex must be an absolute executable path")
    command = (os.environ if environ is None else environ).get("SSH_ORIGINAL_COMMAND", "")
    if command == POSIX_PROBE:
        print("__CODEX_POSIX__", end="")
        return 0
    arguments = COMMANDS.get(command)
    if arguments is None:
        print("Android SSH key permits Codex shared-daemon commands only", file=sys.stderr)
        return 126
    os.execv(args.codex, [args.codex, *arguments])


if __name__ == "__main__":
    sys.exit(main())
