import http, { type IncomingMessage } from "node:http";
import https from "node:https";
import type { HostRequestOptions, HostRequestResult } from "../../shared/types/plugin";
import {
  INSTALL_URL_MAX_SIZE,
  INSTALL_URL_TIMEOUT,
  PluginErrorCodes,
  REQUEST_DEFAULT_TIMEOUT,
  REQUEST_MAX_TIMEOUT,
} from "../../shared/defaults/plugin-api";

const isLoopbackHost = (hostname: string): boolean =>
  hostname === "localhost" || hostname === "127.0.0.1";

const HOST_REQUEST_MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

const assertAllowedInstallUrl = (urlText: string): void => {
  const parsed = new URL(urlText);
  if (parsed.protocol === "https:") return;
  if (parsed.protocol === "http:" && isLoopbackHost(parsed.hostname)) return;
  throw new Error(`protocol not allowed: ${parsed.protocol}`);
};

const readResponseBuffer = async (
  response: IncomingMessage,
  sizeLimit = Number.POSITIVE_INFINITY,
): Promise<Buffer> => {
  const chunks: Buffer[] = [];
  let received = 0;
  for await (const chunk of response) {
    const piece = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    received += piece.length;
    if (received > sizeLimit) {
      response.destroy();
      throw new Error("PLUGIN_INSTALL_URL_TOO_LARGE");
    }
    chunks.push(piece);
  }
  return Buffer.concat(chunks);
};

const requestOnce = (
  url: URL,
  options: {
    method: string;
    headers?: Record<string, string>;
    body?: string | ArrayBuffer | Uint8Array;
    timeout: number;
    family?: 4;
  },
): Promise<IncomingMessage> =>
  new Promise((resolve, reject) => {
    const client = url.protocol === "https:" ? https : http;
    let bodyBuffer: Buffer | undefined;
    if (typeof options.body === "string") {
      bodyBuffer = Buffer.from(options.body, "utf8");
    } else if (options.body instanceof ArrayBuffer) {
      bodyBuffer = Buffer.from(options.body);
    } else if (options.body instanceof Uint8Array) {
      bodyBuffer = Buffer.from(
        options.body.buffer,
        options.body.byteOffset,
        options.body.byteLength,
      );
    }
    const headers = { ...(options.headers ?? {}) };
    if (bodyBuffer && !headers["Content-Length"] && !headers["content-length"]) {
      headers["Content-Length"] = String(bodyBuffer.byteLength);
    }
    const request = client.request(
      url,
      {
        method: options.method,
        headers,
        family: options.family,
        agent: false,
        ...(url.protocol === "https:" && !/^\d+\.\d+\.\d+\.\d+$/.test(url.hostname)
          ? { servername: url.hostname }
          : {}),
      },
      (response) => resolve(response),
    );
    request.setTimeout(options.timeout, () => {
      request.destroy(Object.assign(new Error("request timeout"), { name: "AbortError" }));
    });
    request.on("error", reject);
    if (bodyBuffer) request.write(bodyBuffer);
    request.end();
  });

const requestWithRedirect = async (
  urlText: string,
  options: {
    method: string;
    headers?: Record<string, string>;
    body?: string | ArrayBuffer | Uint8Array;
    timeout: number;
    family?: 4;
  },
  redirectsLeft: number,
  onRedirect?: (nextUrl: string) => void,
): Promise<{ response: IncomingMessage; url: string; redirected: boolean }> => {
  const url = new URL(urlText);
  const response = await requestOnce(url, options);
  const status = response.statusCode ?? 0;
  if (status >= 300 && status < 400 && response.headers.location) {
    response.resume();
    if (redirectsLeft <= 0) throw new Error("too many redirects");
    const nextUrl = new URL(response.headers.location, url).toString();
    onRedirect?.(nextUrl);
    return requestWithRedirect(nextUrl, options, redirectsLeft - 1, onRedirect).then((result) => ({
      ...result,
      redirected: true,
    }));
  }
  return { response, url: url.toString(), redirected: false };
};

const shouldRetryWithIpv4 = (url: URL, error: unknown, forcedFamily?: 4): boolean => {
  if (
    forcedFamily === 4 ||
    url.protocol !== "https:" ||
    /^\d+\.\d+\.\d+\.\d+$/.test(url.hostname)
  ) {
    return false;
  }
  const message = error instanceof Error ? error.message : String(error);
  const code =
    typeof error === "object" && error ? String((error as { code?: string }).code ?? "") : "";
  return (
    code === "ECONNRESET" ||
    code === "EHOSTUNREACH" ||
    code === "ENETUNREACH" ||
    message.includes(
      "Client network socket disconnected before secure TLS connection was established",
    )
  );
};

export const fetchScript = async (url: string): Promise<string> => {
  assertAllowedInstallUrl(url);
  const response = await requestWithRedirect(
    url,
    {
      method: "GET",
      headers: { "User-Agent": "SPlayer-Next-Android" },
      timeout: INSTALL_URL_TIMEOUT,
    },
    5,
    assertAllowedInstallUrl,
  );
  if ((response.response.statusCode ?? 0) < 200 || (response.response.statusCode ?? 0) >= 300) {
    throw new Error(`HTTP ${response.response.statusCode ?? 0}`);
  }
  const declaredLength = Number(response.response.headers["content-length"] ?? 0);
  if (declaredLength > INSTALL_URL_MAX_SIZE) {
    response.response.destroy();
    throw new Error("PLUGIN_INSTALL_URL_TOO_LARGE");
  }
  const buffer = await readResponseBuffer(response.response, INSTALL_URL_MAX_SIZE);
  return buffer.toString("utf8");
};

export const hostRequest = async (
  url: string,
  options: HostRequestOptions = {},
): Promise<HostRequestResult> => {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw Object.assign(new Error(`invalid url: ${url}`), {
      code: PluginErrorCodes.URL_NOT_ALLOWED,
    });
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw Object.assign(new Error(`protocol not allowed: ${parsed.protocol}`), {
      code: PluginErrorCodes.URL_NOT_ALLOWED,
    });
  }

  const timeoutMs = Math.min(
    Math.max(options.timeout ?? REQUEST_DEFAULT_TIMEOUT, 1_000),
    REQUEST_MAX_TIMEOUT,
  );

  const performRequest = async (family?: 4): Promise<HostRequestResult> => {
    const {
      response,
      url: finalUrl,
      redirected,
    } = await requestWithRedirect(
      url,
      {
        method: options.method ?? "GET",
        headers: {
          "User-Agent": "SPlayer-Next-Android",
          ...(options.headers ?? {}),
        },
        body: options.body,
        timeout: timeoutMs,
        family,
      },
      5,
    );
    const headers: Record<string, string> = {};
    for (const [key, value] of Object.entries(response.headers)) {
      if (typeof value === "string") headers[key] = value;
      else if (Array.isArray(value) && value[0]) headers[key] = value[0];
    }
    const declaredLength = Number(response.headers["content-length"] ?? 0);
    if (declaredLength > HOST_REQUEST_MAX_RESPONSE_BYTES) {
      response.destroy();
      throw Object.assign(new Error("response too large"), {
        code: PluginErrorCodes.NETWORK_ERROR,
      });
    }
    const buffer = await readResponseBuffer(response, HOST_REQUEST_MAX_RESPONSE_BYTES);
    let body: unknown;
    const responseType = options.responseType ?? "text";
    if (responseType === "arraybuffer") {
      body = new Uint8Array(buffer);
    } else if (responseType === "json") {
      const text = buffer.toString("utf8");
      try {
        body = JSON.parse(text);
      } catch {
        body = text;
      }
    } else {
      body = buffer.toString("utf8");
    }
    return {
      status: response.statusCode ?? 200,
      statusText: response.statusMessage ?? "",
      headers,
      url: finalUrl,
      redirected,
      body,
    };
  };

  try {
    return await performRequest();
  } catch (error) {
    if (shouldRetryWithIpv4(parsed, error)) {
      try {
        return await performRequest(4);
      } catch (retryError) {
        error = retryError;
      }
    }
    if ((error as Error).name === "AbortError" || (error as Error).message === "request timeout") {
      throw Object.assign(new Error("request timeout"), {
        code: PluginErrorCodes.REQUEST_TIMEOUT,
      });
    }
    throw Object.assign(
      new Error(`network error: ${error instanceof Error ? error.message : String(error)}`),
      { code: PluginErrorCodes.NETWORK_ERROR },
    );
  }
};
