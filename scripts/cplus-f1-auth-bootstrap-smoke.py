"""Isolated C-F1 diagnostic secret bootstrap smoke test (no secret printed).

Called by the Windows PowerShell runner with its own TEMP secret-file override.
Never modifies a Minecraft world, main backend DB or production credential.
"""

from npcortex_backend.cplus_local_auth import initialize_secret, read_secret


def main() -> None:
    first = initialize_secret()
    if first != "new private file":
        raise AssertionError(f"expected isolated first-time bootstrap, got {first!r}")
    secret = read_secret()
    if len(secret) != 64 or any(char not in "0123456789abcdef" for char in secret):
        raise AssertionError("invalid generated local secret")
    again = initialize_secret()
    if again != "existing private file" or read_secret() != secret:
        raise AssertionError("local secret changed across bootstrap")
    print("PASS isolated Backend local credential bootstrap, persistent across startup")


if __name__ == "__main__":
    main()
