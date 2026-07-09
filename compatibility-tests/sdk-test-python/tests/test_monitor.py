"""Azure Monitor / Log Analytics SDK compatibility tests."""
import io
import json
import os
import time
import uuid
from datetime import datetime, timedelta, timezone

import requests
from azure.core.credentials import AccessToken
from azure.core.pipeline.transport import RequestsTransport
from azure.monitor.ingestion import LogsIngestionClient
from azure.monitor.query import LogsQueryClient

EMULATOR_BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
SUB = os.environ.get("FLOCI_AZ_SUBSCRIPTION", "00000000-0000-0000-0000-000000000001")
RG = "sdk-test-rg-monitor"
API = "2023-09-01"
STREAM = "Custom-SdkCompat_CL"
HEADERS = {"Authorization": "******", "Content-Type": "application/json"}


class FakeCredential:
    def get_token(self, *scopes, **kwargs):
        return AccessToken("fake-token", int(time.time()) + 3600)


def _resource_url(provider_path):
    return (
        f"{EMULATOR_BASE}/subscriptions/{SUB}/resourceGroups/{RG}"
        f"/providers/{provider_path}?api-version={API}"
    )


def _https(url):
    return url.replace("http://", "https://", 1)


def test_ingest_and_query_logs_round_trip():
    suffix = uuid.uuid4().hex[:8]
    workspace = f"sdk-monitor-ws-{suffix}"
    dce = f"sdk-monitor-dce-{suffix}"
    dcr = f"sdk-monitor-dcr-{suffix}"

    workspace_id = None
    immutable_id = None

    workspace_path = (
        f"/subscriptions/{SUB}/resourceGroups/{RG}"
        f"/providers/Microsoft.OperationalInsights/workspaces/{workspace}"
    )
    workspace_url = _resource_url(f"Microsoft.OperationalInsights/workspaces/{workspace}")
    dce_url = _resource_url(f"Microsoft.Insights/dataCollectionEndpoints/{dce}")
    dcr_url = _resource_url(f"Microsoft.Insights/dataCollectionRules/{dcr}")

    try:
        ws = requests.put(workspace_url, json={"location": "eastus"}, headers=HEADERS, timeout=10)
        assert ws.status_code in (200, 201), ws.text
        workspace_id = ws.json()["properties"]["customerId"]

        dce_resp = requests.put(dce_url, json={"location": "eastus"}, headers=HEADERS, timeout=10)
        assert dce_resp.status_code in (200, 201), dce_resp.text
        ingestion_endpoint = dce_resp.json()["properties"]["logsIngestion"]["endpoint"]

        dcr_body = {
            "location": "eastus",
            "properties": {
                "dataCollectionEndpointId": (
                    f"/subscriptions/{SUB}/resourceGroups/{RG}"
                    f"/providers/Microsoft.Insights/dataCollectionEndpoints/{dce}"
                ),
                "destinations": {
                    "logAnalytics": [
                        {
                            "workspaceResourceId": workspace_path,
                            "name": "workspace-destination",
                        }
                    ]
                },
            },
        }
        dcr_resp = requests.put(dcr_url, json=dcr_body, headers=HEADERS, timeout=10)
        assert dcr_resp.status_code in (200, 201), dcr_resp.text
        immutable_id = dcr_resp.json()["properties"]["immutableId"]

        run_id = uuid.uuid4().hex
        now = datetime.now(timezone.utc)
        logs = [
            {
                "TimeGenerated": now.isoformat(),
                "Level": "Error",
                "Message": "Failed to login",
                "RunId": run_id,
                "Value": 10,
            },
            {
                "TimeGenerated": (now - timedelta(minutes=10)).isoformat(),
                "Level": "Warning",
                "Message": "Slow response time",
                "RunId": run_id,
                "Value": 5,
            },
            {
                "TimeGenerated": (now - timedelta(hours=2)).isoformat(),
                "Level": "Info",
                "Message": "User logged out",
                "RunId": run_id,
                "Value": 2,
            },
        ]

        transport = RequestsTransport(connection_verify=False)
        ingestion_client = LogsIngestionClient(
            endpoint=_https(ingestion_endpoint),
            credential=FakeCredential(),
            transport=transport,
        )
        ingestion_client.upload(
            rule_id=immutable_id,
            stream_name=STREAM,
            logs=io.BytesIO(json.dumps(logs).encode("utf-8")),
        )

        query_client = LogsQueryClient(
            FakeCredential(),
            endpoint=f"{_https(EMULATOR_BASE)}/v1",
            transport=RequestsTransport(connection_verify=False),
        )
        query = (
            f"{STREAM} | where RunId == '{run_id}' "
            "| project TimeGenerated, Level, Message, Value | take 10"
        )
        result = query_client.query_workspace(workspace_id, query, timespan=timedelta(hours=1))

        assert len(result.tables) == 1
        table = result.tables[0]
        columns = [
            column.name if hasattr(column, "name") else column["name"] if isinstance(column, dict) else column
            for column in table.columns
        ]
        assert columns == [
            "TimeGenerated",
            "Level",
            "Message",
            "Value",
        ]
        assert len(table.rows) == 2
        assert table.rows[0][1] == "Error"
        assert table.rows[1][1] == "Warning"
    finally:
        requests.delete(dcr_url, headers=HEADERS, timeout=10)
        requests.delete(dce_url, headers=HEADERS, timeout=10)
        requests.delete(workspace_url, headers=HEADERS, timeout=10)
