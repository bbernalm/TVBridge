# TvBridge

Android app that turns your phone into a remote control for an **LG webOS TV** and a bridge to **Google Home** (via [Sinric Pro](https://sinric.pro)), so voice commands like "turn the volume up" reach a TV that Google Home cannot see by itself.

```
Google Home → Sinric Pro (cloud) → TvBridge (your Android phone) → local Wi-Fi → LG TV
```

- On-screen remote: power on (Wake-on-LAN) and off, volume, mute, channels, inputs, apps, arrows/OK/back/home, playback, text entry and on-screen notices.
- Voice (Google Home): power, volume, mute, channel number/name, input, media controls.
- Not included: LG's AI features, screen mirroring, Magic Remote pointer, control outside your home network.

> **Status:** untested on a real TV. See the Spanish guide, [README.es.md](README.es.md), for the full step-by-step tutorial, build instructions and troubleshooting.

Built the same way as [LedBridge](https://github.com/bbernalm/LedBridge): a foreground service keeps the Sinric Pro WebSocket open and answers each request with an HMAC-signed message. MIT licensed.
