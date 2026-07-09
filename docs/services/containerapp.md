# Azure Container Apps

Compatible with ARM-speaking clients that manage `Microsoft.App/managedEnvironments` and `Microsoft.App/containerApps`.

> **Mocked mode (default): no Docker required.** Managed environments and container apps are kept as ARM state only and immediately report `Succeeded` / `Running`.
>
> **Container-backed mode:** set `FLOCI_AZ_SERVICES_CONTAINER_APP_MOCKED=false` to back each Container App with a real Docker container. The image comes from `properties.template.containers[0].image`, falling back to `mcr.microsoft.com/azuredocs/containerapps-helloworld:latest`. Ingress `targetPort` is published to a host port from the configured range, and provisioning completes asynchronously once the container becomes reachable.

---

## Features

- **Managed environments** — CreateOrUpdate, Get, Delete, List (by subscription and by resource group)
- **Container apps** — CreateOrUpdate, Get, Delete, List (by subscription and by resource group)
- **Ingress** — `configuration.ingress.targetPort` is preserved and exposed as a reachable `fqdn`
- **Scale metadata** — `template.scale.minReplicas` / `maxReplicas` are stored and round-tripped unchanged
- **Async provisioning** — non-mocked apps transition `Creating` → `Succeeded` once the backing container is reachable

---

## Endpoints

All operations use ARM paths:

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments
GET    /subscriptions/{sub}/providers/Microsoft.App/managedEnvironments

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps
GET    /subscriptions/{sub}/providers/Microsoft.App/containerApps
```

---

## Quickstart

### 1 — Create a managed environment

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.App/managedEnvironments/my-env?api-version=2023-05-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "appLogsConfiguration": {"destination": "none"}
    }
  }'
```

### 2 — Create a container app

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.App/containerApps/my-app?api-version=2023-05-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "managedEnvironmentId": "/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.App/managedEnvironments/my-env",
      "configuration": {
        "ingress": {
          "external": true,
          "targetPort": 80,
          "traffic": [{"latestRevision": true, "weight": 100}]
        }
      },
      "template": {
        "containers": [{
          "name": "hello",
          "image": "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest"
        }],
        "scale": {"minReplicas": 1, "maxReplicas": 2}
      }
    }
  }'
```

### 3 — Read the mapped ingress

```bash
curl -s \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.App/containerApps/my-app?api-version=2023-05-01"
# ... properties.configuration.ingress.fqdn = "localhost:7080" (example)
```

---

## Configuration

```yaml
floci-az:
  services:
    container-app:
      enabled: true
      mocked: true
      default-image: "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest"
      base-port: 7080
      max-port: 7179
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_CONTAINER_APP_ENABLED` | `true` | Enable/disable Container Apps |
| `FLOCI_AZ_SERVICES_CONTAINER_APP_MOCKED` | `true` | Mocked mode (no Docker) |
| `FLOCI_AZ_SERVICES_CONTAINER_APP_DEFAULT_IMAGE` | `mcr.microsoft.com/azuredocs/containerapps-helloworld:latest` | Fallback Docker image |
| `FLOCI_AZ_SERVICES_CONTAINER_APP_BASE_PORT` | `7080` | Start of the ingress host-port range |
| `FLOCI_AZ_SERVICES_CONTAINER_APP_MAX_PORT` | `7179` | End of the ingress host-port range |

---

## Notes & limitations

- `managedEnvironmentId` is stored and echoed but not strictly validated against an existing managed environment.
- Scale settings are metadata only for now — a single container replica is started even when `minReplicas` / `maxReplicas` are greater than 1.
- Revisions history, scale-to-zero, KEDA rules, Dapr, secrets, registries, and real autoscaling are not implemented in this MVP.
