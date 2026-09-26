"""Run inside the gateway container: python check_realtime.py (no audio upload)."""
import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def main() -> int:
    token = os.getenv("CLIENT_API_KEY") or os.getenv("WHISPER_API_KEY", "")
    if not token:
        print("CLIENT_API_KEY is missing from the container environment.")
        return 1
    request = Request(
        "http://127.0.0.1:8000/v1/realtime/check",
        data=b"",
        headers={"Authorization": f"Bearer {token}"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=40) as response:
            payload = json.load(response)
        print(json.dumps(payload))
        return 0
    except HTTPError as error:
        # Gateway check responses contain only sanitized error codes.
        try:
            code = json.load(error).get("detail", "gateway_check_failed")
        except (ValueError, AttributeError):
            code = "gateway_check_failed"
        print(f"Realtime check failed: HTTP {error.code} {code}")
    except (URLError, TimeoutError, ValueError):
        print("Realtime check failed: gateway_unavailable")
    return 1


if __name__ == "__main__":
    sys.exit(main())
