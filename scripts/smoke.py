#!/usr/bin/env python3
"""Exercise the local WireMock-backed app. Adds seven mock transaction records per run."""

import argparse
import json
import sys
from decimal import Decimal
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen


def request(base_url, path, expected_status, body=None):
    encoded = None if body is None else json.dumps(body).encode()
    req = Request(base_url + path, data=encoded, headers={"Content-Type": "application/json"})
    try:
        response = urlopen(req, timeout=15)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read().decode()
        if response.status != expected_status:
            raise AssertionError(f"{req.method} {path}: expected {expected_status}, got {response.status}")
        return json.loads(raw, parse_float=Decimal)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    # These requests use the checked-in fixture accounts. Keep the smoke check local.
    if urlsplit(base_url).hostname not in {"localhost", "127.0.0.1", "::1"}:
        parser.error("Use a local application connected to the checked-in WireMock fixtures.")

    for path in ["/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"]:
        assert request(base_url, path, 200)["status"] == "UP", path
    print("Application, liveness, and database readiness: UP")

    cases = [
        ("acc-123456", "CREDIT", 201, "EXECUTED", Decimal("5500.00")),
        ("acc-123456", "DEBIT", 201, "EXECUTED", Decimal("2500.00")),
        ("acc-insufficient-funds", "DEBIT", 201, "REJECTED", None),
        ("acc-provider-rejected", "DEBIT", 201, "REJECTED", None),
        ("acc-timeout", "DEBIT", 504, "UNKNOWN", None),
        ("acc-provider-error", "DEBIT", 502, "UNKNOWN", None),
        ("acc-malformed", "DEBIT", 502, "UNKNOWN", None),
    ]
    for account, kind, status, outcome, balance in cases:
        result = request(base_url, "/transactions", status, {
            "accountId": account, "type": kind, "amount": 1500,
            "currency": "MXN", "description": "Local smoke check",
        })
        transaction_id = result["id"] if status == 201 else result["transactionId"]
        query = urlencode({"accountId": account, "type": kind, "status": outcome, "page": 0, "limit": 100})
        page = request(base_url, "/transactions?" + query, 200)
        stored = next(item for item in page["items"] if item["id"] == transaction_id)
        assert stored["status"] == outcome
        assert stored["balanceAfter"] == balance
        print(f"{kind} {account}: HTTP {status}, persisted {outcome}")

    invalid = request(base_url, "/transactions", 422, {
        "accountId": "acc-123456", "type": "CREDIT", "amount": 1, "currency": "MXN",
    })
    assert invalid["code"] == "BUSINESS_RULE_VIOLATION"
    assert "transactionId" not in invalid
    invalid_query = request(base_url, "/transactions?limit=101", 400)
    assert invalid_query["code"] == "INVALID_REQUEST"
    print("Business-rule and query validation: passed")

    print("Smoke check passed; seven mock transaction records were added.")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, KeyError, StopIteration, URLError, ValueError) as error:
        print(f"Smoke check failed: {error}", file=sys.stderr)
        sys.exit(1)
