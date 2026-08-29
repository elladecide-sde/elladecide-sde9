# Handoff — fix de corrector + re-ranker neural

## Qué cambió y por qué

### 1. `corrector.py` — bug real en `correct_word` (el más importante)

Línea original:
```python
dist = edit_distance(norm_word, normalize_for_search(orig))
```
`orig` normaliza exactamente a `norm_word` por construcción de `original_map`,
así que esto comparaba una palabra contra sí misma → `dist` daba **siempre 0**
para cualquier candidato devuelto por el árbol BK, sin importar si estaba a
distancia 1 o 2 del typo real del usuario.

Fix:
```python
dist = edit_distance(norm, norm_word)   # norm = normalize_for_search(word), la query
```

Impacto medido (1000 casos de `typos_dataset.tsv`, sin contexto, sin red
neuronal — solo el corrector fuzzy):

| | Antes (bug) | Después (fix) |
|---|---|---|
| Top-1 | 47.3% | **65.7%** |

El bug hacía que el ranking dependiera casi solo de frecuencia dentro de
`max_dist`, ignorando la distancia real al typo. Esto también afectaba el
entrenamiento del re-ranker (`train_ranker.py` arma los negativos llamando a
este mismo `correct_word`), así que el modelo neuronal entrenado antes
aprendía sobre una distribución de negativos peor de lo pensado.

### 2. `integrator.py` — `_neural_rerank`

Dos cambios, ambos dentro de la banda de empate (candidatos a distancia de
edición similar):

- **Candidatos OOV** (fuera del vocabulario top-20k, `cid == oov_id`): su
  embedding colisiona con el de *cualquier otra* palabra desconocida, así que
  el score neuronal no dice nada específico de esa palabra. Se pone en 0 su
  aporte neuronal (no se descarta del todo el candidato, solo se ignora la
  señal neuronal ruidosa).
- **Frecuencia real como señal explícita**: antes, dentro del empate, solo se
  usaban red neuronal + similitud ortográfica del typo. Eso fallaba cuando
  ambas quedaban empatadas entre una palabra común y una rarísima (ej.
  `puedo` freq=2413 vs `ruedo` freq=1, mismo edit_distance=1 al typo
  `ouedo`) — la red, con pocas muestras de la palabra rara, podía dar un
  score ruidoso y ganarle a la respuesta correcta. Se agregó frecuencia
  (log-normalizada dentro de la banda) como tercera señal:
  `score = 0.45*neuronal + 0.30*typo + 0.25*frecuencia`.

Interfaz pública (`IntegratedCorrector.correct_ctx`, `correct_with_context`)
intacta.

### 3. `neural_ranker.tflite` — reentrenado

Reentrenado con el `corrector.py` corregido (afecta los negativos "difíciles"
usados en `build_typo_samples`). Mismos hiperparámetros que la versión
anterior (12 épocas, arquitectura GRU sin cambios). `val_accuracy` final
~0.896, en línea con corridas previas.

## Verificación hecha acá

Los 3 casos objetivo que venían fallando, ahora con contexto real:

```
typo       target     ctx=0      ctx>=1
ouedo      puedo      OK         OK
xausqr     causar     OK         OK
juefos     juegos     OK         OK
```

Usé el modelo `.h5` directo para verificar (no el `.tflite`) porque el
intérprete TFLite liviano de Python no soporta los ops `SELECT_TF_OPS`
(`FlexTensorListReserve`, viene de la capa GRU) sin el delegate Flex. **Esto
es esperable y no es un problema del modelo** — en Android se resuelve
agregando la dependencia `org.tensorflow:tensorflow-lite-select-tf-ops`,
tal cual señala el error de TF. `verify_cases.py` queda incluido si quieren
correr más casos ad-hoc contra el `.h5`.

## Lo que NO pude validar acá (limitación real, no la escondo)

No tengo `benchmark_ranker.py` (no se subió en esta sesión), que era el
script que corría los "300 casos con contexto real" para medir el número
global reportado antes (59.0%). Lo que sí puedo asegurar con lo que tuve:

- El baseline fuzzy (sin red) subió de 47.3% a 65.7% sobre las 1000 entradas
  completas de `typos_dataset.tsv` — no es un efecto de overfitting a 3 casos.
- Los 3 casos objetivo funcionan con el pipeline completo (fuzzy + neurona +
  contexto).

**Antes de integrar a producción, correr `benchmark_ranker.py` con estos
archivos para confirmar el número global sobre los 300 casos** y chequear
que no bajó respecto al 59.0% anterior. Dado que el baseline mejoró tanto,
es muy probable que suba, pero no lo pude confirmar sin ese script.

## Archivos entregados
- `corrector.py` — fix del bug de distancia
- `integrator.py` — fix OOV + frecuencia en el re-rank
- `neural_ranker.tflite` — reentrenado
- `verify_cases.py` — script de verificación (usa el `.h5`, no el `.tflite`)
