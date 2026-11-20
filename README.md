# Puente

Un solo teclado y un solo mouse Bluetooth para dos equipos Android (pensado para una **Redmi Pad 2** y un **Motorola Edge 70 Fusion**).
El teclado y el mouse se conectan a la tablet. Cuando el puntero llega al borde, **pasa al celular**; el teclado escribe en el equipo donde está el puntero.

Es el mismo APK para los dos equipos: al abrirlo se elige si ese equipo es la tablet o el celular.

## Cómo funciona

- **Tablet (emisor):** una franja celeste finita en el borde detecta el puntero. Al tocarla se abre una pantalla transparente que "atrapa" el mouse (pointer capture de Android) y el teclado, y los manda al celular por Wi-Fi.
- **Celular (receptor):** un servicio de accesibilidad dibuja una flecha, convierte los clics en toques y escribe en el campo de texto activo (API de escritura de accesibilidad, Android 13 o superior).
- Se encuentran solos en la red (mDNS). La conexión pide el PIN de 6 números que muestra el celular.

## Controles con el puntero en el celular

| Acción | Resultado |
|---|---|
| Clic | Tocar |
| Clic sostenido | Mantener apretado |
| Clic y arrastrar | Deslizar (se ejecuta al soltar) |
| Rueda | Desplazar |
| Clic derecho / Esc | Atrás |
| Clic de la rueda | Inicio |
| Ctrl + A / C / X / V | Seleccionar todo / copiar / cortar / pegar |
| Borde que da a la tablet, tocar la tablet o Ctrl + Alt + Q | Volver a la tablet |

## Requisitos

- Android 13 o superior en los dos equipos.
- Los dos en la misma red Wi-Fi (o la tablet conectada a la zona Wi-Fi del celular).
- Lo que se tipea viaja sin cifrar por la red local: usarlo en redes de confianza.

## Compilado

GitHub Actions compila solo en cada cambio y publica `puente.apk` en **Releases → Última versión**.
