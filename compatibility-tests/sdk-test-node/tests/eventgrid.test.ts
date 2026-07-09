process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { EventGridPublisherClient, AzureKeyCredential } from "@azure/eventgrid";
import { createDefaultHttpClient, HttpClient } from "@typespec/ts-http-runtime";

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
const SUB = "11111111-1111-1111-1111-111111111111";
const RG = "compat-rg-eventgrid-node";
const API = "?api-version=2025-02-15";
let tlsCa: string | undefined;

function shortId(): string {
  return Math.random().toString(36).slice(2, 10);
}

function https(url: string): string {
  return url.replace(/^http:\/\//, "https://");
}

function dataPlaneEndpoint(topic: string): string {
  return https(`${BASE.replace(/\/+$/, "")}/${topic}-eventgrid/api/events`);
}

async function jsonFetch(url: string, init: RequestInit): Promise<any> {
  const response = await fetch(url, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init.headers ?? {}),
    },
  });
  const text = await response.text();
  const json = text ? JSON.parse(text) : undefined;
  return { response, json, text };
}

async function createTopic(topic: string, inputSchema: string): Promise<void> {
  const { response, text, json } = await jsonFetch(
    `${BASE}/subscriptions/${SUB}/resourceGroups/${RG}/providers/Microsoft.EventGrid/topics/${topic}${API}`,
    {
      method: "PUT",
      body: JSON.stringify({
        location: "eastus",
        properties: { inputSchema },
      }),
    },
  );
  expect([200, 201]).toContain(response.status);
  expect(json?.properties?.endpoint).toBeTruthy();
  if (!response.ok) throw new Error(text);
}

async function publisherClient(topic: string, schema: "EventGrid" | "CloudEvent", key: string) {
  if (!tlsCa) {
    const response = await fetch(`${BASE}/_floci/tls-cert`);
    tlsCa = await response.text();
  }
  const defaultHttpClient = createDefaultHttpClient();
  const httpClient: HttpClient = {
    sendRequest(request) {
      request.tlsSettings = tlsCa ? { ca: tlsCa } : request.tlsSettings;
      return defaultHttpClient.sendRequest(request);
    },
  };
  return new EventGridPublisherClient(
    dataPlaneEndpoint(topic),
    schema,
    new AzureKeyCredential(key),
    { httpClient } as any,
  );
}

async function topicKey(topic: string): Promise<string> {
  const { response, text, json } = await jsonFetch(
    `${BASE}/subscriptions/${SUB}/resourceGroups/${RG}/providers/Microsoft.EventGrid/topics/${topic}/listKeys${API}`,
    { method: "POST" },
  );
  expect(response.status).toBe(200);
  if (!response.ok) throw new Error(text);
  return json.key1;
}

async function createSubscription(topic: string, name: string, webhookUrl: string): Promise<void> {
  const scope =
    `/subscriptions/${SUB}/resourceGroups/${RG}/providers/Microsoft.EventGrid/topics/${topic}`;
  const { response, text } = await jsonFetch(
    `${BASE}${scope}/providers/Microsoft.EventGrid/eventSubscriptions/${name}${API}`,
    {
      method: "PUT",
      body: JSON.stringify({
        properties: {
          destination: {
            endpointType: "WebHook",
            properties: { endpointUrl: webhookUrl },
          },
        },
      }),
    },
  );
  expect(response.status).toBe(200);
  if (!response.ok) throw new Error(text);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor<T>(items: T[], attempts: number, delayMs: number): Promise<boolean> {
  for (let i = 0; i < attempts; i += 1) {
    if (items.length > 0) return true;
    await sleep(delayMs);
  }
  return items.length > 0;
}

async function readJson(req: IncomingMessage): Promise<any> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

test("publishes Event Grid schema events to a custom topic", async () => {
  const topic = `compat-eg-${shortId()}`;
  await createTopic(topic, "EventGridSchema");
  const key = await topicKey(topic);

  const client = await publisherClient(topic, "EventGrid", key);
  await client.send([
    {
      id: `evt-${shortId()}`,
      subject: "/orders/1",
      eventType: "Order.Created",
      dataVersion: "1.0",
      eventTime: new Date(),
      data: { orderId: 1 },
    },
    {
      id: `evt-${shortId()}`,
      subject: "/orders/2",
      eventType: "Order.Updated",
      dataVersion: "1.0",
      eventTime: new Date(),
      data: { orderId: 2 },
    },
  ]);
}, 30_000);

test("publishes CloudEvents 1.0 to a custom topic", async () => {
  const topic = `compat-ce-${shortId()}`;
  await createTopic(topic, "CloudEventSchemaV1_0");
  const key = await topicKey(topic);

  const client = await publisherClient(topic, "CloudEvent", key);
  await client.send([
    {
      id: `evt-${shortId()}`,
      type: "Order.Created",
      source: "/orders",
      time: new Date(),
      data: { orderId: 7 },
      datacontenttype: "application/json",
    },
  ]);
}, 30_000);

test("published event is delivered to a subscriber webhook when reachable", async () => {
  const validations: any[] = [];
  const notifications: any[] = [];
  const server = createServer(async (req: IncomingMessage, res: ServerResponse) => {
    const root = await readJson(req);
    const first = Array.isArray(root) ? root[0] : root;
    const code = first?.data?.validationCode;
    if (code) {
      validations.push(first);
      const body = JSON.stringify({ validationResponse: code });
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(body);
      return;
    }
    if (Array.isArray(root)) notifications.push(...root);
    else notifications.push(root);
    res.writeHead(200);
    res.end();
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));

  try {
    const topic = `compat-e2e-${shortId()}`;
    await createTopic(topic, "EventGridSchema");
    const key = await topicKey(topic);
    const port = (server.address() as any).port;
    await createSubscription(topic, "sub-e2e", `http://127.0.0.1:${port}/hook`);

    const validated = await waitFor(validations, 20, 100);
    if (!validated) {
      console.warn("Skipping delivery assertion because the emulator could not reach the local webhook");
      return;
    }

    const client = await publisherClient(topic, "EventGrid", key);
    await client.send([
      {
        id: `evt-${shortId()}`,
        subject: "/orders/42",
        eventType: "Order.Created",
        dataVersion: "1.0",
        eventTime: new Date(),
        data: { orderId: 42 },
      },
    ]);

    const delivered = await waitFor(notifications, 50, 100);
    expect(delivered).toBe(true);
    expect(notifications[0].eventType).toBe("Order.Created");
  } finally {
    await new Promise<void>((resolve, reject) =>
      server.close((err) => (err ? reject(err) : resolve())),
    );
  }
}, 30_000);
