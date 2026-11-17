# CHECKLIST — de HomeTV a Beagle

Lista de todo lo que hace la app de la tele de HomeTV y que Beagle tiene que replicar antes de sustituirla.
Se marca aquí al terminar cada cosa (`[x]`), para saber entre sesiones por dónde vamos.

- Referencia: HomeTV **0.70.2** (repo privado `locometopa/hometv`). Si HomeTV gana funciones, se añaden aquí.
- Entre paréntesis, dónde está cada cosa en HomeTV, para portarla leyendo el original.
- **Reglas**: HomeTV y su servidor de casa no se tocan desde este repo. Si hiciera falta cambiar el servidor
  (`servidor-casa/hometv-servidor.js`), el cambio va en el repo `hometv`. Beagle es un cliente más del mismo servidor.
- Hecho = funciona en la Google TV con el mando, no solo compila.
- `[x]` hecho y visto en la tele · `[~]` escrito y compilado, falta probarlo en la tele · `[ ]` sin hacer.

## 0. Esqueleto

- [x] Proyecto Android nativo: Kotlin 2.0.21, Compose for TV, paquete `es.locometopa.beagle`
- [x] `MainActivity` con «Hola Beagle»
- [x] Icono y banner para el inicio de Google TV / Fire TV
- [x] Workflow «Compilar APK»: compila en cada subida a `main` y publica en la rama `apk`
- [x] Firma propia de Beagle fuera del repo (secretos `KEYSTORE_B64` y `KEYSTORE_PASSWORD`)
- [ ] Primera APK instalada en la tele y «Hola Beagle» visto en pantalla

## 1. Base: servidor de casa y datos

- [~] Ajuste «Servidor de casa» (IP o nombre) y aviso si no responde (`store.js: servidorCasa`)
- [~] Buscar el servidor solo en la red local (puerto 8096, `/salud`)
- [~] Leer canales, listas y ajustes compartidos: `GET /sincro` (con `desde` y `ligero=1`)
- [~] Guardar cambios en el servidor: `PUT /sincro` (con `base`, y recargar si responde 409)
- [ ] Modelo de datos: canales, fuentes (listas), ajustes, vistos, recordatorios (`store.js`)
- [~] Copia local para abrir al instante y funcionar si el servidor tarda
- [~] Sincronización ligera: no guardar en la tele los canales de listas apagadas (`sincroLigero`)
- [~] Darse de alta como tele en el servidor (sale en el panel, en «Teles») y decir qué se está viendo
- [~] Bloqueo de la tele desde el servidor (`GET /bloqueo`): cierra el reproductor si se bloquea
- [~] Copias de seguridad en el servidor: hacer, ver y recuperar (`POST /api/restaurar`) (0.18.0)
- [ ] Uso fuera de casa por Tailscale (dirección `100.x` o `.ts.net`)

## 2. Motor de Ace Stream

- [~] Motor del servidor de casa (`http://SERVIDOR:6878`) como principal (`engine.js`)
- [~] Motor en la propia tele (`http://127.0.0.1:6878`) como alternativa
- [~] Abrir canal: `getstream` / `manifest.m3u8` con `format=json`, por `id` o `infohash`
- [~] Reescribir las direcciones que devuelve el motor (vienen con 127.0.0.1)
- [~] Estado de la emisión (peers, velocidad) y orden de parar al salir
- [ ] Indicador del motor en la barra (conectado / esperando) y versión

## 3. Reproductor nativo (`ReproductorActivity.java`)

- [~] Media3 / ExoPlayer con la configuración que va bien en HomeTV (búfer 15/60/2,5/5 s)
- [~] Formato TS o HLS, elegible; recordar los canales que solo van en HLS
- [ ] Canales IPTV/HLS directos (sin motor)
- [~] Zapping ▲▼ y botones de canal, con espera de 0,7 s y rótulo del canal
- [~] ◀▶ cambia de enlace del mismo canal
- [~] Recuperación en tres pasos: reconectar, volver a pedir el canal, siguiente enlace; dos vueltas
- [~] Panel con número, título, estado (peers, velocidad, búfer) y datos técnicos (resolución, códec, fps)
- [~] OK: información · OK dos veces: recolocar la imagen · mantener OK o Menú: opciones
- [~] Opciones del canal: favorito, elegir enlace, recargar, recolocar, ir a otro canal, salir
- [~] Sonido: elegir pista de audio y subtítulos (menú en dos pasos, en las opciones del reproductor) (0.18.2)
- [~] Sonido no compatible (AC3…): pedirlo convertido al servidor (`/aac/abrir`) y recordarlo
- [~] Vídeo no compatible (MPEG-2, HEVC 10 bits): pasar a otro enlace o avisar
- [~] Calidad ligera fuera de casa (`/aac/abrir?calidad=baja`)
- [ ] Canales IPTV por la pasarela VPN del servidor (`tvPorVpn`)
- [ ] Apuntar el resultado de cada enlace (arrancó o no, peers) para ordenar mejor después
- [~] Grabar el canal en el servidor (`/api/grabar`) desde las opciones del reproductor, con «⏺ REC» en el rótulo (0.18.3)
- [ ] Vídeos de carpetas: pausa, saltos de 10 s / 1 min, pasar al siguiente al terminar
- [ ] Gestos en el móvil: deslizar para canal y enlace, tocar, doble toque, mantener pulsado
- [~] Cerrar el canal y liberar el motor al salir

## 4. Reproductores externos

- [~] Abrir en Ace Player (`AceListNativoPlugin: abrirApp`)
- [~] Abrir en AceServe (app Android `org.free.aceserve`)
- [~] Abrir en VLC
- [~] Ajuste de reproductor preferido: interno, Ace Player, AceServe, VLC o elegir cada vez
- [~] Saber qué apps están instaladas y llevar a la tienda si falta alguna

## 5. Navegación y diseño

- [~] Diseño: estructura de HomeTV (menú lateral fijo, filas planas, Ajustes en lista) con los colores del logo nuevo: azul noche y dorado (0.19.0)
- [~] Menú: Inicio, Canales, Agenda, Buscar, Importar, Listas, Móvil, Ajustes; recuerda dónde estabas (recuerda la sección; faltan Importar y Móvil)
- [~] Todo manejable con el mando: foco visible, ▲▼◀▶, OK, Atrás, pulsación larga de OK
- [~] Tipografía Outfit incluida en la app
- [ ] Vídeo de entrada (intro) si el servidor tiene uno (`/intro.mp4`, ajuste `intro`)
- [ ] Diseño para móvil (pantalla táctil) con la misma APK (`diseno: auto | tele | movil`)

## 6. Inicio (portada)

- [~] Evento destacado: el partido en directo en tu mejor canal (cartel grande)
- [ ] Cuadrados de eventos destacados del día (`destacados`: 0–3)
- [~] Filas: en directo, a continuación, favoritos y una por deporte; lo que se puede ver, primero
- [ ] Filas por categoría de canales (`filasCategorias`)
- [~] Inicio como HomeTV: destacado arriba y filas de tarjetas que se recorren de lado (0.18.1)
- [~] Canales vistos hace poco (`vistos`)

## 7. Canales

- [ ] Vista en lista (por defecto) y en cuadrícula
- [~] Estado de cada canal (vivo, sin peers, no encontrado, sin comprobar), peers y fecha de comprobación
- [ ] Logotipos de los canales (`logos`)
- [~] Nombres limpios: los `_` pasan a espacios
- [~] Enlaces repetidos agrupados en un canal («3 enlaces»); OK reproduce el mejor (`agrupar`, `agenda.js: claveCanal`)
- [~] Filtros: vivos, sin caídos, caídos; por categoría (falta por lista)
- [~] Orden recomendado y A-Z
- [~] Ocultar los caídos (`hideDead`) en «Todos» (0.18.0)
- [ ] Ficha del canal y ajuste «OK abre la ficha o reproduce» (`okAction`)
- [ ] Opciones del canal (pulsación larga o ⋮): favorito, renombrar, categoría, ver enlaces, borrar, juntar o separar
- [~] Favoritos
- [ ] Qué se emite ahora en el canal (guía, `guia.js`)

## 8. Buscar

- [~] Buscar canales con teclado cómodo para el mando
- [~] Buscar también eventos de la agenda
- [~] Buscar en las listas apagadas (`GET /api/buscar-canal`)

## 9. Agenda deportiva (`agenda.js`)

- [ ] Fuentes: todos los deportes (3 días), solo fútbol (15 días), Marca / Ciriaco (`agendaFuente`)
- [ ] Cruce de la agenda con mis canales por nombre (quita calidades, diales y relleno; respeta números)
- [ ] Nombres genéricos («DAZN») solo como *posible*
- [~] «Ver en el mejor canal» y estado de mis canales para cada evento
- [ ] «Asignar canal» a mano cuando el nombre no cuadra (`agendaMapa`)
- [~] Filtro «En abierto» (TDT, RTVE Play, autonómicas, YouTube)
- [~] Eventos en directo y aplazados
- [ ] Recordatorios / eventos favoritos con aviso (`recordatorios`, `POST /api/recordatorio`)
- [~] Usar la agenda ya cruzada del servidor (`GET /api/simple`) cuando haya servidor de casa

## 10. Listas e importar (`importer.js`)

- [ ] Importar: exportación de AceList (JSON), M3U, enlaces `acestream://` sueltos, texto pegado
- [~] Importar desde una dirección web y guardarla como fuente para actualizarla
- [~] Listas con nombre; pantalla Listas: activar, desactivar, renombrar y quitar (✕, dos pulsaciones) (falta ver canales)
- [~] Varias fuentes con interruptor; actualizar las fuentes al abrir (`refreshSources`)
- [ ] Lista TDTChannels incluida de serie, desactivada
- [~] Listas Xtream, W3U y MylinkPaste (las resuelve el servidor: `POST /api/lista`)
- [ ] Reglas de las listas que llegan del servidor (`reglas.js`)
- [ ] Exportar en formato AceList (JSON) o M3U

## 11. Comprobar canales

- [~] Comprobar todos los canales: lo hace el servidor (`POST /api/comprobar`), con progreso en Canales y Ajustes (0.18.0)
- [ ] Varios a la vez y segunda pasada para los que fallan (`concurrency`, `retryFailed`)
- [ ] Comprobar al abrir la app o cada N horas (`autoVerify`, `autoVerifyHours`, `preComprobar`)
- [ ] Canales IPTV: comprobación propia sin motor (`GET /probe`)
- [ ] Historial de las últimas comprobaciones de cada canal
- [ ] No borrar nunca canales por estar caídos (pueden volver otro día)

## 12. Panel del móvil (`ServidorMovil.java`, `www/movil/`)

- [ ] Servidor en la tele (puerto 8095) con clave de 6 caracteres
- [ ] Botón «Móvil»: código QR y dirección corta
- [ ] Página del móvil servida por la tele
- [ ] Desde el móvil: importar listas o archivos, renombrar, cambiar categoría, favoritos, borrar, comprobar
- [ ] Desde el móvil: mandar un canal a reproducir en la tele
- [ ] Desde el móvil: listas (activar, actualizar, renombrar, borrar), ajustes, agenda, exportar

## 13. Carpetas y grabaciones del servidor

- [~] Ver las carpetas de vídeo del servidor y carpetas de red (`/api/carpetas`, `/carpetas/…`)
- [~] Reproducir sus vídeos en el reproductor nativo
- [~] Ver y reproducir las grabaciones (`/api/grabaciones`, `/grabaciones/…`)

## 14. Red y VPN

- [~] Indicador de la VPN del servidor (WARP / Proton) en el menú y en Ajustes → Red (`/salud`)
- [~] Cambiar la VPN del servidor desde la tele (`POST /api/vpn`)
- [ ] Indicador de Cloudflare WARP en la tele y acceso a la app 1.1.1.1

## 15. Ajustes

- [ ] Pantalla de ajustes que cambia con OK, sin desplegables ni teclado
- [ ] Todos los ajustes de `store.js: defaultSettings` (los que no estén ya marcados arriba)
- [~] Limpiar la caché de la app
- [~] «Empezar de cero» (falta restaurar desde una copia)

## 16. Actualizaciones

- [~] Mirar si hay versión nueva (`version.txt` en la rama `apk`, o en el servidor de casa)
- [~] Descargar e instalar la APK desde la app (permiso para instalar apps)
- [ ] El servidor de casa guarda la APK de Beagle para actualizar dentro de casa (cambio en el repo `hometv`)

## 17. Relevo

- [ ] Todo lo anterior marcado y probado unos días en la Google TV
- [ ] Probado en los Fire TV y en el móvil
- [ ] Decidir el nombre definitivo
- [ ] Pasar los datos de HomeTV a Beagle (llegan solos por el servidor de casa)
- [ ] Retirar HomeTV de las teles
