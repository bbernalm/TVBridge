# TvBridge

**TvBridge** convierte tu celular Android en un control remoto para tu **TV LG webOS** (pensada para el LG UH603T) y en el puente para controlarlo con **Google Home** y la voz: *"Ok Google, sube el volumen del TV"*.

Nace del mismo problema y de la misma idea que [LedBridge](https://github.com/bbernalm/LedBridge): Google Home no puede hablar con el aparato, así que el celular hace de puente.

```
Google Home  →  Sinric Pro (nube)  →  TvBridge (tu celular)  →  Wi-Fi local  →  TV LG
```

> **Estado:** el código está escrito pero **no se ha probado con un TV real**. Si algo falla, el registro de la app dice qué orden llegó y qué respondió el TV; con eso se ajusta.

## Qué hace

Casi todo lo que hace la app LG ThinQ como control remoto:

| Función | Botones en la app | Voz (Google Home) |
|---|---|---|
| Encender (Wake-on-LAN) | Sí | Sí |
| Apagar | Sí | Sí |
| Volumen, subir / bajar / fijar | Sí | Sí |
| Silenciar | Sí | Sí |
| Canal +/− y número de canal | Sí | Sí |
| Cambiar de entrada (HDMI, TV) | Sí | Sí |
| Abrir aplicaciones (YouTube, Netflix…) | Sí | Solo si Google acepta el nombre como "entrada" |
| Reproducir, pausa, stop, atrasar, adelantar | Sí | Sí (Play, Pause, Stop, Rewind, FastForward) |
| Flechas, OK, Atrás, Inicio, Salir | Sí | No |
| Escribir texto en el TV | Sí | No |
| Mostrar un aviso en la pantalla | Sí | No |

**Lo que no hace:** funciones de IA de ThinQ, duplicar la pantalla del celular, puntero tipo Magic Remote, ni controlar el TV fuera de tu casa.

## Qué necesitas

- Un celular **Android 8.0 o superior**, en la **misma red Wi-Fi** que el TV.
- Una cuenta gratis de [Sinric Pro](https://sinric.pro) (solo para la voz; los botones funcionan sin ella).
- La app Google Home (solo para la voz).
- Para la voz: el celular con TvBridge abierto y la voz iniciada. Si lo cierras, la voz deja de funcionar (los botones de la app siguen funcionando mientras la abras).

## Tutorial paso a paso

### 1. Prepara el TV

1. **Conéctalo al Wi-Fi** de tu casa (la misma del celular).
2. En los ajustes del TV busca y activa la opción de **LG Connect Apps** (o similar) para permitir que el celular lo controle. Los nombres cambian entre versiones: suele estar en **Ajustes → Red**.
3. Activa **encender con Wi-Fi / Turn on via Wi-Fi** (o LAN). Sin esto no se puede encender por voz ni con el botón *Encender*. Suele estar en **Ajustes → General** o **Red**.
4. Anota la **IP** y la **MAC** del TV: **Ajustes → Red → Estado de la red** (o **Acerca de este TV**). La MAC tiene el formato `AA:BB:CC:DD:EE:FF`. Usa la del Wi-Fi si lo tienes conectado por Wi-Fi, o la del cable si es por cable.
5. Recomendado: en el router, **reserva esa IP** para el TV, así no cambia. Si cambia, hay que corregirla en la app.

### 2. Crea el TV en Sinric Pro (solo para la voz)

1. Entra a [sinric.pro](https://sinric.pro) → **Devices → Add Device**.
2. Ponle un nombre (por ejemplo *TV sala*) y elige el tipo de dispositivo **TV**. Guarda.
3. Anota tres datos:
   - **App Key** y **App Secret**: en **Credentials**.
   - **Device ID**: junto a tu dispositivo, en la lista de **Devices**.

### 3. Compila e instala la app

El proyecto se compila solo con GitHub Actions, como LedBridge:

1. Crea un repositorio nuevo en GitHub (por ejemplo `TvBridge`) y sube **todo el contenido** de esta carpeta (incluida la carpeta oculta `.github`).
2. Entra a la pestaña **Actions**. Cuando termine el job **Build APK**, abre la ejecución y descarga el artefacto **TvBridge-apk**.
3. Descomprímelo, pasa `app-debug.apk` al celular y ábrelo (Android pedirá permitir instalar desde esa fuente).

Otra opción: abrir la carpeta en **Android Studio** y pulsar **Run**.

Para publicar una versión con el APK adjunto: sube un tag que empiece por `v`:

```bash
git tag v1.0.0
git push origin v1.0.0
```

### 4. Configura y empareja

1. Abre TvBridge. En **Configuración** escribe la **IP** y la **MAC** del TV y pulsa **Guardar (solo control con botones)**.
2. **Mira la pantalla del TV:** aparece un aviso para permitir la conexión. Pulsa **Aceptar**. Solo se pide la primera vez.
3. El estado debe decir `TV: conectado ✔`. Prueba **Vol +**, **Vol −**, **Canal +** y los botones de navegación.
4. Pulsa **Recargar entradas y aplicaciones** si las listas de entradas y apps salen vacías.
5. Con el TV apagado, prueba **Encender**: espera unos 10 segundos.

### 5. Activa la voz con Google Home

1. En TvBridge escribe **App Key**, **App Secret** y **Device ID** de Sinric Pro y pulsa **Guardar e iniciar voz con Google Home**. Acepta el permiso de notificaciones.
2. El estado debe decir `Sinric Pro: conectado ✔`.
3. En la app **Google Home**: **+ → Dispositivo → Funciona con Google**, busca **Sinric Pro**, inicia sesión y autoriza. Tu TV aparece en Google Home.
4. Prueba: *"Ok Google, apaga el TV"*, *"sube el volumen"*, *"silencia el TV"*, *"cambia al canal 5"*, *"cambia la entrada a HDMI 1"*.

Cada orden que llega de Google aparece en el **Registro** de la app (`Google → setVolume {...}`), útil para ver qué pidió Google y qué pasó.

### 6. Evita que el celular cierre la app

Para que la voz siga funcionando con la pantalla apagada:

1. En TvBridge, **Configuración → Quitar ahorro de batería** y permite.
2. **Ajustes → Aplicaciones → TvBridge → Batería → Sin restricciones** (el texto varía según la marca).
3. Bloquea TvBridge en las apps recientes, y activa el *inicio automático* si tu celular lo tiene. Más pasos por marca en [dontkillmyapp.com](https://dontkillmyapp.com).

TvBridge no arranca sola al reiniciar el celular: ábrela y pulsa **Guardar e iniciar** otra vez.

## Solución de problemas

<details>
<summary><b>"sin conexión (¿TV apagado o IP incorrecta?)"</b></summary>

Comprueba que el TV esté encendido, que celular y TV estén en la misma red Wi-Fi y que la IP sea la actual. Algunos routers aíslan los dispositivos del Wi-Fi ("aislamiento de clientes" o "AP isolation"): desactívalo.
</details>

<details>
<summary><b>El TV nunca muestra el aviso de aceptar</b></summary>

Revisa que **LG Connect Apps** (o el nombre equivalente) esté activado. Si ya aceptaste antes y ahora no conecta, pulsa **Olvidar emparejamiento con el TV** en Configuración y vuelve a intentar.
</details>

<details>
<summary><b>"Encender" no hace nada</b></summary>

Falta la MAC o está mal escrita, o el TV no tiene activado el encendido por Wi-Fi/LAN. Prueba con la MAC de cable si el TV está conectado por cable. Algunos TV solo se encienden por Wake-on-LAN desde unos minutos después de apagarse, o no lo permiten por Wi-Fi: en ese caso conéctalo por cable.
</details>

<details>
<summary><b>Los botones de navegación no responden pero el volumen sí</b></summary>

Los botones usan un canal aparte. Mira el Registro: si dice `No pude abrir el canal de botones del TV`, vuelve a intentarlo pasados unos segundos o reinicia la conexión (**Olvidar emparejamiento**).
</details>

<details>
<summary><b>"Sinric Pro desconectado: rechazado (401/403)"</b></summary>

La App Key o el Device ID están mal. Cópialos de nuevo sin espacios.
</details>

<details>
<summary><b>Google dice que el TV no responde</b></summary>

El TV está apagado o desconectado (solo la orden *encender* funciona con el TV apagado), o el App Secret está mal. El Registro muestra `Respondí con error` cuando TvBridge tuvo que rechazar una orden.
</details>

<details>
<summary><b>Funciona un rato y luego deja de funcionar</b></summary>

El ahorro de batería del celular está cerrando la app. Repite el paso 6.
</details>

## Cómo funciona

- **Lado TV.** LG webOS acepta órdenes por un WebSocket en el puerto 3000 (`ws://`) en los modelos antiguos, y en el 3001 (`wss://`) en los nuevos; TvBridge prueba ambos. Los comandos usan direcciones `ssap://…` (por ejemplo `audio/volumeUp`, `tv/channelUp`, `system.launcher/launch`). Los botones de flechas y OK usan un segundo canal de "puntero". Para encender, que el TV apagado no puede responder, se envía un paquete **Wake-on-LAN**.
- **Lado nube.** Igual que LedBridge: TvBridge abre un WebSocket a `wss://ws.sinric.pro`, recibe las órdenes de Google (`setPowerState`, `setVolume`, `adjustVolume`, `setMute`, `mediaControl`, `selectInput`, `changeChannel`, `skipChannels`) y responde a cada una con un mensaje firmado con HMAC-SHA256 usando tu App Secret.
- **Mapa del código:** `WebOsClient.kt` (conexión con el TV), `TvController.kt` (acciones del TV), `WakeOnLan.kt` (encendido), `SinricClient.kt` (nube), `TvHub.kt` (conexión compartida), `BridgeService.kt` (servicio en primer plano), `MainActivity.kt` (pantalla).

## Limitaciones conocidas

- El celular debe estar encendido, en la misma red que el TV y con la voz iniciada.
- Sin Wake-on-LAN en el TV, la orden *encender* no funciona.
- La app usa conexión sin cifrar (`ws://`) **solo** con el TV de tu red local. Con el modelo wss, no verifica el certificado del TV porque es autofirmado.
- La interfaz está solo en español.
- Si el TV tiene firmware más nuevo, algunos comandos pueden cambiar.

## Créditos y licencia

Publicado bajo la licencia MIT (ver `LICENSE`). La estructura de la app (servicio en primer plano y cliente de Sinric Pro) sale de LedBridge. El mensaje de registro con el TV (`app/src/main/res/raw/webos_manifest.json`) usa el manifiesto público de prueba de LG que comparten proyectos de código abierto como [bscpylgtv](https://github.com/chros73/bscpylgtv). TvBridge es un proyecto independiente y no está afiliado ni respaldado por LG, Google ni Sinric Pro.
