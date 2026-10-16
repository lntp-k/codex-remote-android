import contextlib
import io
import unittest
from unittest.mock import patch
import codex_ssh_entrypoint as entry


class EntrypointTest(unittest.TestCase):
    def run_entry(self, command):
        return entry.main(["--codex", "/bin/true"], {"SSH_ORIGINAL_COMMAND": command})

    def test_probe(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), patch.object(entry.os, "execv") as execute:
            self.assertEqual(0, self.run_entry(entry.POSIX_PROBE))
            execute.assert_not_called()
        self.assertEqual("__CODEX_POSIX__", out.getvalue())

    def test_allowed_commands_pass_only_fixed_argv(self):
        for command, expected in entry.COMMANDS.items():
            with self.subTest(command=command), patch.object(entry.os, "execv", side_effect=SystemExit(0)) as execute:
                with self.assertRaises(SystemExit):
                    self.run_entry(command)
                execute.assert_called_once_with("/bin/true", ["/bin/true", *expected])

    def test_denies_shell_substitutions_stdio_and_empty_command(self):
        for command in ["", "sh", "id", "$(id)", "codex --version; id", "exec codex app-server --listen stdio://", entry.POSIX_PROBE + "; id"]:
            with self.subTest(command=command), contextlib.redirect_stderr(io.StringIO()), patch.object(entry.os, "execv") as execute:
                self.assertEqual(126, self.run_entry(command))
                execute.assert_not_called()

    def test_requires_existing_absolute_binary(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            entry.main(["--codex", "codex"], {})


if __name__ == "__main__":
    unittest.main()
