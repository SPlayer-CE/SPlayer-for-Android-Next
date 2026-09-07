# SPlayer Standalone API

This folder contains a standalone local API service extracted from the desktop-side Netease API flow.

## Start

Run:

```bat
API\start-api.bat
```

Default listen address:

- Host: `0.0.0.0`
- Port: `18098`

Android should use this base URL format:

```text
http://<your-computer-ip>:18098/api/netease
```

## Environment Variables

- `SP_API_HOST`: bind host, default `0.0.0.0`
- `SP_API_PORT`: bind port, default `18098`
- `SP_AMLL_DB_SERVER`: TTML lyric source, optional

## Notes

- This standalone service currently exposes the Netease API route set under `/api/netease/*`.
- The script depends on the root project's `node_modules`.
