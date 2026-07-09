process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

import { ServiceBusClient, ServiceBusReceivedMessage } from "@azure/service-bus";
import { ACCOUNT, randomSuffix } from "./config";

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
const HOST = process.env.SERVICEBUS_HOST ?? "localhost";
const NAMESPACE = process.env.SERVICEBUS_NAMESPACE ?? "default";

let mocked = false;
let amqpsPort = parseInt(process.env.SERVICEBUS_AMQPS_PORT ?? "0", 10);

beforeAll(async () => {
  const response = await fetch(`${BASE}/${ACCOUNT}-servicebus/namespaces/${NAMESPACE}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: "{}",
    signal: AbortSignal.timeout(120_000),
  });
  if (!response.ok) {
    throw new Error(`Failed to start Service Bus namespace: HTTP ${response.status}`);
  }
  const json = await response.json() as { mocked?: boolean; amqpsPort?: number };
  mocked = json.mocked === true;
  if (!amqpsPort && json.amqpsPort) amqpsPort = json.amqpsPort;
}, 130_000);

function queueBody(requiresSession = false): string {
  if (!requiresSession) return "";
  return [
    '<entry xmlns="http://www.w3.org/2005/Atom"><content type="application/xml">',
    '<QueueDescription xmlns="http://schemas.microsoft.com/netservices/2010/10/servicebus/connect">',
    "<RequiresSession>true</RequiresSession>",
    "</QueueDescription></content></entry>",
  ].join("");
}

async function ensureQueue(name: string, requiresSession = false): Promise<void> {
  const response = await fetch(`${BASE}/${ACCOUNT}-servicebus/${NAMESPACE}/queues/${name}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/atom+xml;charset=utf-8",
    },
    body: queueBody(requiresSession),
    signal: AbortSignal.timeout(30_000),
  });
  if (![200, 201].includes(response.status)) {
    throw new Error(`Failed to create queue ${name}: HTTP ${response.status} ${await response.text()}`);
  }
}

async function ensureTopic(name: string): Promise<void> {
  const response = await fetch(`${BASE}/${ACCOUNT}-servicebus/${NAMESPACE}/topics/${name}`, {
    method: "PUT",
    headers: { "Content-Type": "application/atom+xml;charset=utf-8" },
    signal: AbortSignal.timeout(30_000),
  });
  if (![200, 201].includes(response.status)) {
    throw new Error(`Failed to create topic ${name}: HTTP ${response.status} ${await response.text()}`);
  }
}

async function ensureSubscription(topic: string, subscription: string): Promise<void> {
  const response = await fetch(
    `${BASE}/${ACCOUNT}-servicebus/${NAMESPACE}/topics/${topic}/subscriptions/${subscription}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/atom+xml;charset=utf-8" },
      signal: AbortSignal.timeout(30_000),
    },
  );
  if (![200, 201].includes(response.status)) {
    throw new Error(
      `Failed to create subscription ${topic}/${subscription}: HTTP ${response.status} ${await response.text()}`,
    );
  }
}

function serviceBusClient(): ServiceBusClient {
  const connectionString =
    `Endpoint=sb://${HOST};SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;`;
  return new ServiceBusClient(connectionString, {
    customEndpointAddress: `https://${HOST}:${amqpsPort}`,
  });
}

async function receiveOne(receiver: any, timeoutMs = 10_000): Promise<ServiceBusReceivedMessage | undefined> {
  const messages = await receiver.receiveMessages(1, { maxWaitTimeInMs: timeoutMs });
  return messages[0];
}

test("send and complete removes a message from a queue", async () => {
  if (mocked) {
    console.warn("Service Bus is mocked; skipping AMQP queue assertions");
    return;
  }

  const queue = `q-${randomSuffix()}`;
  const payload = `hello-${Date.now()}`;
  await ensureQueue(queue);

  const client = serviceBusClient();
  const sender = client.createSender(queue);
  const receiver = client.createReceiver(queue, { receiveMode: "peekLock" });

  try {
    await sender.sendMessages({
      body: payload,
      applicationProperties: { key1: "value1", num: 42 },
      contentType: "text/plain",
    });

    const message = await receiveOne(receiver);
    expect(message).toBeDefined();
    expect(message?.body).toBe(payload);
    expect(message?.applicationProperties?.key1).toBe("value1");
    expect(message?.applicationProperties?.num).toBe(42);
    expect(message?.contentType).toBe("text/plain");

    await receiver.completeMessage(message!);

    const after = await receiver.receiveMessages(1, { maxWaitTimeInMs: 2_000 });
    expect(after).toHaveLength(0);
  } finally {
    await sender.close();
    await receiver.close();
    await client.close();
  }
}, 30_000);

test("topic fan-out delivers the same message to each subscription", async () => {
  if (mocked) {
    console.warn("Service Bus is mocked; skipping AMQP topic assertions");
    return;
  }

  const topic = `topic-${randomSuffix()}`;
  const sub1 = "sub-a";
  const sub2 = "sub-b";
  const payload = `fanout-${Date.now()}`;

  await ensureTopic(topic);
  await ensureSubscription(topic, sub1);
  await ensureSubscription(topic, sub2);

  const client = serviceBusClient();
  const sender = client.createSender(topic);
  const receiver1 = client.createReceiver(topic, sub1, { receiveMode: "peekLock" });
  const receiver2 = client.createReceiver(topic, sub2, { receiveMode: "peekLock" });

  try {
    await sender.sendMessages({ body: payload });

    const msg1 = await receiveOne(receiver1);
    const msg2 = await receiveOne(receiver2);

    expect(msg1?.body).toBe(payload);
    expect(msg2?.body).toBe(payload);

    await receiver1.completeMessage(msg1!);
    await receiver2.completeMessage(msg2!);
  } finally {
    await sender.close();
    await receiver1.close();
    await receiver2.close();
    await client.close();
  }
}, 30_000);
