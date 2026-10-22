# Valorizador de colección Final Fantasy TCG

Calcula cuánto vale tu colección de Final Fantasy TCG (el juego de Square Enix) con el
**precio de mercado de TCGplayer**, en USD y CLP. Hay dos formas de usarlo:

- **App Android (FFTCG Valor)**: escanea el código de la carta con la cámara, busca por código
  o nombre, y muestra el total de la colección, el top de cartas más valiosas y el link de
  "Últimas ventas" de TCGplayer. Descarga el APK desde
  [Releases](https://github.com/Guillecc/fftcg-valorizador/releases/latest).
- **Script de PC** (`valorizar.py`): lee una lista CSV y genera un reporte HTML/CSV.

## App Android

- **Escanear**: apunta al código de abajo a la derecha (`1-001H`). Si el código tiene varias
  versiones (base, Full Art, promo), elige la tuya. Luego marca foil y cantidad.
- **Buscar**: por código (`29-87`, `PR-029`) o por nombre (`cloud`).
- **Colección**: ordenada por valor. Toca una carta para cambiar la cantidad o abrir
  "Últimas ventas" en TCGplayer. Importa y exporta el mismo CSV que usa el script.
- El dólar es el observado del día (mindicador.cl); se puede fijar a mano.
- Los precios salen del release `index`, que GitHub Actions regenera cada día
  (`.github/workflows/index.yml` + `build_index.py`). La app lo descarga si su copia tiene más de 12 h.
- Cada push a `main` compila y publica un APK firmado (`.github/workflows/build.yml`).

## Script de PC

Requiere solo Python 3.10+. No hay que instalar nada más.

1. Edita `coleccion.csv` (Excel o el Bloc de notas):

   ```csv
   codigo,cantidad,foil,version
   1-001H,2,no,
   29-008L,1,si,Full Art
   PR-001,1,si,
   ```

   - `codigo`: el código impreso en la carta (`1-001H`, `29-087L`, `PR-029`). Da igual
     la letra de rareza o los ceros a la izquierda (`1-1` = `1-001H`).
   - `cantidad`: copias.
   - `foil`: `si` / `no`.
   - `version` (opcional): un texto del nombre en TCGplayer (`Full Art`, `Signature`, `Rebel`)
     o el número de producto de TCGplayer. Si se deja vacío se usa la versión base.

2. Ejecuta:

   ```
   python valorizar.py
   ```

   Opciones:
   - `python valorizar.py otra_lista.csv`: usar otra lista.
   - `--fx 950`: fijar el dólar. Si no se indica, usa el dólar observado del día (mindicador.cl).
   - `--top 20`: largo del top.
   - `--refresh`: volver a descargar los precios (por defecto se descargan una vez al día).

3. Revisa `resultados/`:
   - `valorizacion_<fecha>.html`: total, top de cartas más valiosas y link a TCGplayer de cada una.
   - `valorizacion_<fecha>.csv`: lo mismo para Excel.

## Notas

- Datos: [tcgcsv.com](https://tcgcsv.com), una copia diaria del catálogo y los precios de
  TCGplayer (categoría 24). La primera ejecución del día tarda alrededor de 1 minuto porque
  descarga los 39 sets. Las siguientes usan `cache/`.
- El precio es el `marketPrice` de TCGplayer (si falta, `midPrice`).
- Las "Latest Sales" no se pueden descargar por script (TCGplayer las bloquea). Usa el link
  de cada carta para verlas a mano.
- Un mismo código puede tener varias versiones (base, Full Art, promo). La columna `nota` avisa
  cuando hubo que elegir, y `otras_versiones` lista las alternativas.
- Si un código se reimprimió en otro set, se prefiere el set que indica el código (`26-003R` → set 26).
- Cada día `auditoria.py` compara los códigos de TCGplayer con la lista de Materia Hunter. Solo corrige
  sola los errores claros (el nombre del producto trae el código, formato `PR165`, o el código es de otra
  carta y la correcta está al lado). Las correcciones manuales (`CORRECTIONS`) mandan.
- El resultado queda en `auditoria.md`, en el release `index`, con los casos para revisar a mano. Si
  Materia Hunter no responde, el índice se publica igual, solo con las correcciones manuales.
- Antes de publicar, `build_index.py` valida los datos: descarta precios que no sean números entre 0 y
  100.000, recorta nombres a 200 caracteres y aborta (sin publicar, la app sigue con el índice anterior)
  si salen menos de 4.000 o más de 20.000 cartas, o menos de 20 sets.
- Historial de precios: cada día se guarda `AAAA-MM-DD.json.gz` (precio de mercado normal/foil por
  producto) en el release `history`. El índice trae por carta `h7`/`h30` (precio de hace 7 y 30 días,
  o el día más cercano dentro de ±3) y en `hist` las fechas usadas. El índice también trae el elemento
  (`e`, varios separados por `;`) y el tipo (`t`). Si falta historial, el índice sale igual sin esos datos.
- El APK se compila sin firmar y se firma en un job aparte (environment `release`), que verifica el
  certificado. Cada release trae el SHA-256 y una attestation de procedencia
  (`gh attestation verify FFTCG-Valor.apk -R Guillecc/fftcg-valorizador`).
- El APK incluye solo la ABI `arm64-v8a` (teléfonos de 64 bits; no instala en equipos de 32 bits ni en
  emuladores x86) y va minificado con R8.
