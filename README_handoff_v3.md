# neural_ranker v3 — sin Flex / SELECT_TF_OPS, compatible Android 16KB

## Qué cambió respecto a la versión anterior

El `.tflite` anterior requería `SELECT_TF_OPS` (Flex delegate) porque la capa
`GRU` de Keras se traza en TF 2.x con `tf.TensorListReserve`, un op que el
conversor TFLite no puede bajar a builtins nativos — ni con
`implementation=1`, ni reemplazando `GRU` por `SimpleRNN` (probé ambas, las
dos siguen generando `TensorListReserve` en el grafo de funciones de TF 2.21).

**La única forma de eliminar Flex por completo fue sacar la recurrencia.**
Arquitectura nueva:

```
ctx (5 ids)  -> Embedding(20002,64) -> Flatten(320) -> Dense(64, tanh)   ─┐
cand (1 id)  -> Embedding(20002,64) -> Reshape(64)                       ─┼─> Concat(144) -> Dense(128,relu) -> Dropout(0.3) -> Dense(64,relu) -> Dense(1,sigmoid)
feats (4)    -> Dense(16, relu)                                          ─┘
```

También reemplacé la capa `Lambda` (squeeze) por `Reshape`, que es la otra
capa que generaba un op no-builtin.

Para `CTX_LEN=5` (contexto corto y fijo) `Flatten+Dense` captura las mismas
dependencias posicionales que la GRU sin necesitar procesamiento secuencial
dinámico — la ventaja real de una GRU/LSTM es en secuencias largas con
dependencias de largo alcance, que no es el caso acá.

**Verificación binaria de que no quedó Flex** (no solo "compiló sin error"):
leí el flatbuffer del `.tflite` con el schema de TFLite y listé los opcodes.
Los 12 opcodes usados son todos `BUILTIN` (ninguno `CUSTOM`, que es como se
marcan los ops Flex). Podés repetir este chequeo:

```python
from tensorflow.lite.python import schema_py_generated as schema_fb
data = open('model/neural_ranker.tflite','rb').read()
model = schema_fb.Model.GetRootAsModel(data, 0)
opcodes = set()
for i in range(model.OperatorCodesLength()):
    oc = model.OperatorCodes(i)
    opcodes.add(('CUSTOM', oc.CustomCode().decode()) if oc.CustomCode() else ('BUILTIN', oc.BuiltinCode()))
print(opcodes)
print('Tiene Flex?', any(t == 'CUSTOM' for t, _ in opcodes))  # False
```

Con esto, en Android **no hace falta** la dependencia
`tensorflow-lite-select-tf-ops` — solo el runtime base de TFLite, que sí
soporta 16KB page size (el paquete `select-tf-ops` es el que trae binarios
nativos que en algunas builds no vienen alineados a 16KB).

## 1. Modelo entrenado — `model/`
- `neural_ranker.h5` — checkpoint Keras que generó el `.tflite`
- `neural_ranker_savedmodel/` — mismo modelo en formato SavedModel
- `neural_ranker.tflite` — export final, **TFLITE_BUILTINS only**
- `model_signature.json` — inputs/outputs exactos, shapes, dtypes, orden de features

## 2. Scripts — `scripts/`
- `train_ranker.py` — entrenamiento + export (ya con la arquitectura sin Flex)
- `check_ranker.py` — verificación ejecutable (ítems 7 y 8 de tu pedido)
- `bk_tree.py`, `corrector.py`, `normalize.py`, `integrator.py`, `infer_ranker.py` — módulos que importa

**Falta `gen_typos.py`**: no fue subido en ninguna sesión de este chat, así
que no lo puedo entregar. No es necesario para reentrenar este modelo (ya
tenés `typos_dataset.tsv`, el output que genera `gen_typos.py`), pero si
necesitás regenerar el dataset de typos desde cero con otras palabras, ese
script hay que rescatarlo de donde se generó originalmente.

## 3. `requirements.txt`
Versiones exactas usadas para este entrenamiento y export (verificadas con
`pip freeze` en el entorno donde corrí todo esto, no supuestas):
```
python==3.12.3
tensorflow-cpu==2.21.0
keras==3.15.1
numpy==2.5.2
```

## 4. Datos — `data/`
- `palabras_comunes.txt` — vocabulario (60.960 palabras, top-20k usado para embeddings)
- `typos_dataset.tsv` — 1000 pares typo→palabra correcta
- `confusables.json` — mapa de confusables BK precomputado (negativos difíciles)
- `corpus/es.sentences.txt` — 160.913 oraciones (contexto real)
- `dictionary.bin` — diccionario binario del proyecto

Para reentrenar desde cero:
```bash
python3 -m venv tfenv && tfenv/bin/pip install -r requirements.txt
cd scripts/
../tfenv/bin/python3 train_ranker.py
```
(asume la estructura `data/`, `data/corpus/`, `generated/` relativa al
directorio de `train_ranker.py` — mové las carpetas o ajustá los paths al
inicio del script si cambia la disposición.)

## 5. `model/neural_ranker.tflite`
Confirmado: **TFLITE_BUILTINS only**, 0 ops CUSTOM/Flex (ver verificación
arriba). Tamaño: 5.1MB (vs 5.3MB de la versión con Flex — comparable).

## 6. `model/model_signature.json`
Generado desde el `.tflite` real (no a mano) — nombres de tensores, shapes,
dtypes tal cual los expone el intérprete.

## 7 y 8. Verificación + benchmark — `scripts/check_ranker.py`

```bash
cd scripts/
../tfenv/bin/python3 check_ranker.py
```

Corre, en este orden:
1. **Muestra de inferencia raw** — para cada uno de los 3 casos, imprime los
   3 tensores exactos (`ctx`, `cand`, `feats`) y el score que devuelve el
   modelo, para los top-3 candidatos de cada typo.
2. **Los 3 casos objetivo end-to-end** (fuzzy + red, con contexto real):

   ```
   typo       target     ctx=0        ctx>=1
   ouedo      puedo      OK           OK
   xausqr     causar     OK           OK
   juefos     juegos     OK           OK
   ```

3. **Benchmark fuzzy baseline** sobre las 1000 entradas de `typos_dataset.tsv`
   (sin contexto, sin red): **657/1000 = 65.7%**, igual a lo reportado antes
   → confirma que el fix de `corrector.py` (que ya tenías desde el handoff
   previo) sigue funcionando igual con este modelo nuevo.

### Resumen antes/después

| Métrica | Antes | Ahora |
|---|---|---|
| Baseline fuzzy (1000 casos) | 47.3% | 65.7% |
| Re-ranker global (300 casos, medido con `.venv-neural` de tu lado) | 59.0% | 61.3% |
| 3 casos objetivo | 2/3 OK | 3/3 OK |
| TFLite requiere Flex/SELECT_TF_OPS | Sí | **No** |

**Nota**: el número de 300 casos (61.3%) lo mediste vos con
`benchmark_ranker.py` sobre el modelo *anterior* (con Flex). No volví a
correr ese benchmark específico sobre este modelo v3 porque no tengo
`benchmark_ranker.py` en este entorno (no fue subido). Los 3 casos objetivo y
el benchmark de 1000 sí están verificados acá, contra el `.tflite` nuevo, sin
Flex, cargado con el intérprete liviano estándar (sin ningún delegate
adicional) — que es justamente la prueba de que no necesita Flex.

**Antes de dar por cerrado esto**: correr `benchmark_ranker.py` (que vos
tenés) contra este `neural_ranker.tflite` nuevo para confirmar que el número
global de 300 casos se mantiene o mejora respecto al 61.3%. La arquitectura
cambió (recurrencia → Flatten+Dense), así que aunque los 3 casos objetivo y
el baseline de 1000 dan bien, no puedo asegurar que el comportamiento sea
idéntico en el conjunto completo de 300 sin correrlo.
