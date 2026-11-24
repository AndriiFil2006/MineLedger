"""A throwaway local identity provider for trying authenticated endpoints by hand. Never for deployment.

Run from backend/:  python -m tests.dev_idp alex sam

It serves a discovery document and JWKS on 127.0.0.1 (the test suite's `FakeIdentityProvider`), prints
the two settings the API needs, and prints one access token per name given. The signing key exists only
in this process's memory: stop it and every token it issued becomes unverifiable.

A backend is only as trustworthy as its issuer. Pointing a real deployment at this would let anyone who
can run this script sign in as anyone.
"""

from __future__ import annotations

import argparse
import time

from tests.test_auth import AUDIENCE, FakeIdentityProvider


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("subjects", nargs="*", default=["alex"], help="Names to issue tokens for (default: alex)")
    parser.add_argument("--port", type=int, default=9011)
    parser.add_argument("--hours", type=float, default=8, help="Token lifetime (default: 8)")
    args = parser.parse_args()

    provider = FakeIdentityProvider(port=args.port)
    print("Local development identity provider. Set these where uvicorn runs (PowerShell):\n")
    print(f"  $env:MINELEDGER_AUTH_ISSUER   = '{provider.issuer}'")
    print(f"  $env:MINELEDGER_AUTH_AUDIENCE = '{AUDIENCE}'\n")
    for subject in args.subjects:
        token = provider.token(subject, exp=int(time.time() + args.hours * 3600))
        print(f"Access token for {subject!r}:\n{token}\n")
    print("Serving until Ctrl+C. Tokens stop working when this stops.")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        provider.server.shutdown()


if __name__ == "__main__":
    main()
