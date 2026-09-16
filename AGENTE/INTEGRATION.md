# Integración del corrector + re-ranker neural (Kotlin / Android)

Este folder contiene TODO lo necesario para integrar el corrector fuzzy de
diccionario offline y su re-ranker neural en la app Kotlin (Lola Keyboard).
El agente de Kotlin se encarga de las migraciones/port; este doc es el
**contrato de inferencia**: qué hace cada pieza y cómo deben encajar.

---

## 1. Qué entrega y qué tenés que portar

| Archivo | Qué es | Acción en Kotlin |
|---|---|---|
| `neural_ranker.tflite` | Modelo de re-ranker (inferencia) | Cargar con TFLite + Flex delegate. Invocar con 3 inputs. |
| `dictionary.bin` | Diccionario 60.960 palabras + frecuencias | Parsear el binario (formato §5) para construir el BK-tree y el mapa de frecuencias. |
| `palabras_comunes.txt` | Vocabulario top-20k ordenado por frecuencia | Define el mapeo word→id del modelo (§4). |
| `vocab.json` + `vocab_meta.json` | word→id ya resuelto (top 20k) | Usarlo directamente para los ids del modelo. |
| `model_signature.json` | Nombres/shapes/dtypes de los tensors del .tflite | Cablear inputs/outputs. |
| `integrator.py` | **Contrato de re-rank** (`_neural_rerank`, `_typo_features`, pesos) | Portar la lógica de re-rank (§4, §6). |
| `infer_ranker.py` | Cómo se invoca el TFLite (3 inputs) | Referencia para el wiring. |
| `corrector.py` / `bk_tree.py` / `normalize.py` | Corrector fuzzy (candidatos + distancia) | Portar el corrector a Kotlin (§3). |
| `HANDOFF.md` | Contexto de los fixes | Lectura recomendada. |
| `benchmark_ranker.py` / `check_cases.py` / `verify_cases.py` | Verificación (Python) | Referencia para re-validar tras portar. |

No se entrega código Kotlin: el agente lo escribe. La lógica a replicar está
toda en los `.py` de arriba y en este doc.

---

## 2. Pipeline de inferencia (2 etapas)

```
entrada del usuario (typo)
   │
   ├─ normalize_for_search(typo)                 [§3]
   ├─ BK-tree fuzzy lookup  ->  candidatos (formas originales del diccionario)
   │                          cada candidato trae: palabra, edit_distance, total_cost, frecuencia
   │
   ├─ SI hay contexto previo real (>=1 palabra):
   │     re-ranker neural reordena SOLO dentro de la banda de distancia  [§6]
   └─ SIN contexto:  devolver el fuzzy tal cual (no re-rankear)

top-1 = palabra corregida
```

---

## 3. Corrector fuzzy (portar `corrector.py` + `bk_tree.py` + `normalize.py`)

### 3.1 Normalización  — **CRÍTICO replicar idéntico en Kotlin**
`normalize_for_search(w)`:
1. minúsculas
2. descomponer NFD y **quitar marcas combinantes** (á→a, é→e, í→i, ó→o, ú→u, ü→u)
3. **NO tocar la ñ** (se queda)
Resultado: minúscula, sin diacríticos, con ñ.

El BK-tree y el cálculo de distancia operan sobre formas **normalizadas**.

### 3.2 Distancia de edición — `bk_tree.edit_distance`
Levenshtein estándar: inserción=1, eliminación=1 y sustitución=1.
**No aplica una operación especial de transposición**: invertir dos letras
adyacentes cuesta 2. Esto replica las features con las que se entrenó el modelo;
cambiar a Damerau-Levenshtein exige reentrenar y volver a medir el re-ranker.

### 3.3 Decisión de compatibilidad español/Kotlin (2026-08-29)
- La `ñ` se conserva siempre y **no** colisiona con `n`. Python y Kotlin deben
  verificar expresamente casos como `año != ano`.
- Kotlin replica Levenshtein común porque el `.tflite` fue entrenado con esa
  implementación.
- Si un agente futuro cambia normalización o distancia, debe modificar ambos
  runtimes, reentrenar el modelo y ejecutar los benchmarks de §8.

### 3.3 `correct_word` (candidatos)
- Lookup exacto primero: si `normalize_for_search(word)` existe en el diccionario
  como forma normalizada, devolver esa palabra (costo 0).
- Si no, `max_dist` según largo:  L<=4 → 1;  L<=8 → 2;  L>8 → 2.
- BK-tree search devuelve formas normalizadas; se mapean a **formas originales**
  (con tildes) vía `original_map`.
- Para cada candidato original `orig`:
  - `dist = edit_distance(normalize(typo), normalize(orig))`
  - `edit_cost = 1.0 * dist`
  - `diacritic_cost = diacritic_cost(typo, orig)`  (0 si normalizan igual;
    0.1 si difieren en tildes; 0.1*0.3=0.03 si son par ambiguo de significado,
    ver `AMBIGUOUS_PAIRS` en `normalize.py`)
  - `total_cost = edit_cost + diacritic_cost`   ← **esto usa el re-rank para la banda**
  - `frequency = freq_map[orig]` (del dictionary.bin)

Candidatos ordenados por `total_cost` ascendente, desempate por `frequency` desc.

---

## 4. Vocabulario y mapeo de ids (para el TFLite)

- Modelo entrenado con el **top 20.000** de `palabras_comunes.txt`, en ese orden.
- `id(word) = posición (1-based) en palabras_comunes.txt`, para las primeras 20.000.
- `PAD_ID = 0`   (padding de contexto a izquierda)
- `OOV_ID = 20001`  (cualquier palabra fuera del top-20k, o desconocida)
- `CTX_LEN = 5`  (se toman las últimas 5 palabras de contexto)

`vocab.json` ya trae `{ "palabra": id }` para las 20.000. `vocab_meta.json`
tiene esas constantes. Para una palabra no encontrada en `vocab.json` → usar
`OOV_ID`.

---

## 5. Formato de `dictionary.bin` (para parsear en Kotlin)

Header de 64 bytes:
- bytes `[0:4]`  = magic `"LOLA"`
- bytes `[8:12]` = `word_count`  (uint32 little-endian)
- bytes `[12:16]` = `string_offset` (uint32 little-endian) → inicio de la string table

Entries: empiezan en offset 64, **10 bytes c/u**:
- `[0:4]`  word_offset (uint32) — no se usa en el parser de referencia
- `[4:8]`  frecuencia (uint32)
- `[8:10]` flags (uint16)

String table: en `string_offset`, palabras UTF-8 **separadas por byte 0x00**.
La palabra del i-ésimo entry es la i-ésima cadena al hacer `split(0x00)`.
(parseo de referencia en `integrator.py :: _parse_dictionary_bin`.)

De ahí construís:
- `original_map`: normalizada → [formas originales]
- `freq_map`: forma original → frecuencia

---

## 6. Re-ranker neural — contrato de inferencia (`integrator.py :: _neural_rerank`)

### 6.1 Cuándo aplicar
Solo si hay contexto previo real (>=1 palabra). Sin contexto → devolver fuzzy.

### 6.2 Features del typo (4 floats) por cada par (typo, candidato)
```
ed_norm     = edit_distance(typo, cand) / max(len(typo), len(cand), 1)
shared_frac = (chars en común por multiset) / max(len(typo), len(cand), 1)
first_char  = 1.0 si typo[0].lower()==cand[0].lower() else 0.0
len_diff    = abs(len(typo)-len(cand)) / max(len(typo), len(cand), 1)
feats = [ed_norm, shared_frac, first_char, len_diff]   # float32
```

### 6.3 Invocación del TFLite (`model_signature.json`)
Inputs (batch=1):
- `serving_default_ctx:0`    shape (1,5)   int32   → 5 ids de contexto (pad 0 a izquierda)
- `serving_default_cand:0`   shape (1,1)   int32   → id del candidato
- `serving_default_feats:0`  shape (1,4)   float32 → las 4 features de §6.2
Output:
- `StatefulPartitionedCall_1:0`  shape (1,1) float32 → **probabilidad sigmoid 0..1** (`s`)

Repetir por cada candidato del BK-tree.

### 6.4 Re-ordenamiento (la parte que importa)
1. `best = min(total_cost de los candidatos)`
2. Banda: `tier = candidatos con total_cost <= best + 0.15`
   (`rest = los demás`, se dejan ordenados por total_cost ascendente, al final)
3. Dentro de la banda, score combinado:
   ```
   boost = 0.6
   score_neuronal = 0.0                  si el candidato es OOV (id == OOV_ID)
                    boost * s             si no
   typo_score  = 1.0 - ed_norm
   freq_score   = log1p(frequency) / max_log_freq_en_la_banda   # max_log_freq = max(log1p(f) del tier), default 1.0 si 0
   score = 0.45 * score_neuronal + 0.30 * typo_score + 0.25 * freq_score
   ```
4. Ordenar el `tier` por `score` descendente; desempate por `total_cost` ascendente.
5. Resultado = `tier_ordenado` + `rest_ordenado`. El top-1 es la corrección.

### 6.5 Pesos tunables (NO requiere reentrenar)
`0.45 / 0.30 / 0.25` (neuronal / typo / frecuencia) se eligieron empíricamente
sobre los 3 casos objetivo, no sobre el benchmark completo. Si en producción la
frecuencia pisa la señal contextual (palabra rara pero correcta en contexto
específico), bajar `0.25` y subir `0.45` — **primer knob, sin reentrenar**.

---

## 7. Android / TFLite

El modelo usa una capa **GRU** → necesita ops `Select_TF_OPS` (Flex).
En Android agregar la dependencia:
```
implementation 'org.tensorflow:tensorflow-lite-select-tf-ops:X.Y.Z'
```
(En Python esto corre vía `TfLiteFlexDelegate`; el `.tflite` se validó
directamente en este entorno, no solo el `.h5`.)

El artefacto Flex contiene bibliotecas nativas para varias ABI. Por eso un APK
debug universal puede ser muy grande (~300 MB); para distribución usar Android
App Bundle, que entrega a cada dispositivo solo su arquitectura. No eliminar
ABI a ciegas durante desarrollo porque impediría probar en otros dispositivos.

---

## 8. Métricas de referencia (para re-validar tras portar)

- Benchmark context-aware 300 casos (pipeline integrado, `.tflite`):
  - Baseline fuzzy: **57.7%**
  - Re-ranker: **61.3%** (+3.7pp)
- Baseline fuzzy sobre `typos_dataset.tsv` (1000 casos, sin contexto, sin red):
  **65.7%** (efecto del fix de distancia en `corrector.py`).
- 3 casos objetivo (OK con y sin contexto):
  `ouedo→puedo`, `xausqr→causar`, `juefos→juegos`.

Verificación en Python: `python check_cases.py` (usa el `.tflite` vía un
entorno con TensorFlow). `benchmark_ranker.py --n 300` además necesita los
artefactos de entrenamiento `train_ranker.py`, `gen_typos.py` y el corpus
`generated/corpus/es.sentences.txt`; no forman parte de este paquete de
inferencia. La verificación del port Kotlin vive en los tests de `language/`.
