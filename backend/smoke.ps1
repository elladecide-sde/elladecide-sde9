$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8787/api/v1'
$out = @()

function Show($label, $res, $body) {
  $status = $res.StatusCode
  $text = if ($body.Length -gt 420) { $body.Substring(0, 420) + ' ...' } else { $body }
  $script:out += "[$status] $label"
  $script:out += "      $text"
  $script:out += ''
}

function Get-Json($url) {
  $r = Invoke-WebRequest -Uri $url -UseBasicParsing
  return @{ res = $r; body = $r.Content }
}
function Post-Json($url, $obj, $headers) {
  $json = $obj | ConvertTo-Json -Depth 10 -Compress
  $r = Invoke-WebRequest -Uri $url -Method Post -UseBasicParsing -Headers $headers -ContentType 'application/json' -Body $json
  return @{ res = $r; body = $r.Content }
}
function Keys($obj) { (($obj | Get-Member -MemberType NoteProperty).Name -join ', ') }

# --- 1. version / sync / sync-config -----------------------------------------
$v = Get-Json "$base/version"; Show 'GET /version' $v.res $v.body
$s = Get-Json "$base/sync";    Show 'GET /sync (v1)' $s.res $s.body
$c = Get-Json "$base/sync-config"; Show 'GET /sync-config' $c.res $c.body

# --- 2. login ----------------------------------------------------------------
$login = Post-Json "$base/auth/login" @{ username = 'admin'; password = 'admin-local-123' } @{}
$tok = ($login.body | ConvertFrom-Json).token
Show 'POST /auth/login' $login.res ("token=" + $tok.Substring(0, 24) + '...')
$H = @{ Authorization = "Bearer $tok" }

# --- 3. reemplazo con 150 productos (excede el limite de 100 params) ---------
$prods = @(1..150 | ForEach-Object {
  $n = $_
  @{
    codigo_unico = 'P-{0:D3}' -f $n
    descripcion  = "PRODUCTO DE PRUEBA $n MARCA X LISO ALGODON HOMBRE"
    precios      = @((1000 + $n), (1100 + $n), (1200 + $n), (1300 + $n))
    categoria    = $(if ($n % 2 -eq 0) { 'BOMBACHA' } else { 'PANTALON' })
    fabrica      = 'PRUEBA'
  }
})
$up = Post-Json "$base/admin/products" @{ products = $prods; replace = $true } $H
Show 'POST /admin/products (replace, 150 filas)' $up.res $up.body

# --- 4. upsert: shape viejo (nombre) + shape nuevo (descripcion) -------------
$up2 = Post-Json "$base/admin/products" @{ products = @(
  @{ codigo_unico = 'VIEJO-1'; nombre = 'PRODUCTO VIEJO 777'; precios = @(90, 95, 99, 120) },
  @{ codigo_unico = 'NUEVO-1'; descripcion = 'PRODUCTO NUEVO 777'; precios = @(80, 85, 90, 110); categoria = 'REMERAS'; fabrica = 'ACME' }
) } $H
Show 'POST /admin/products (upsert viejo + nuevo)' $up2.res $up2.body

# --- 5. sync v1 (contrato congelado) -----------------------------------------
$s1 = Get-Json "$base/sync"; $j1 = $s1.body | ConvertFrom-Json
Show 'GET /sync (v1) claves' $s1.res (Keys $j1.products[0])
Show 'GET /sync (v1) muestra' $s1.res ($j1.products[0] | ConvertTo-Json -Compress)
Show 'GET /sync (v1) resumen' $s1.res ("count=" + $j1.products.Count + " hash=" + $j1.hash + " rev=" + $j1.revision)

# --- 6. sync v2 modo Predeterminado -----------------------------------------
$v2 = Get-Json "$base/sync/v2"; $j2 = $v2.body | ConvertFrom-Json
Show 'GET /sync/v2 claves' $v2.res (Keys $j2.products[0])
Show 'GET /sync/v2 muestra' $v2.res ($j2.products[0] | ConvertTo-Json -Compress)
Show 'GET /sync/v2 resumen' $v2.res ("format=" + $j2.format + " count=" + $j2.products.Count)

# --- 7. paginacion ------------------------------------------------------------
$pg = Get-Json "$base/sync/v2?limit=3&offset=5"; $jp = $pg.body | ConvertFrom-Json
Show 'GET /sync/v2?limit=3&offset=5' $pg.res ("count=" + $jp.products.Count + " primero=" + $jp.products[0].codigo_unico)

# --- 8. modo Descripcion-Costos ----------------------------------------------
$sc = Post-Json "$base/admin/sync-config" @{ modo = 'descripcion-costos' } $H
Show 'POST /admin/sync-config {modo:descripcion-costos}' $sc.res $sc.body
$sc2 = Get-Json "$base/sync-config"; Show 'GET /sync-config' $sc2.res $sc2.body

$v2b = Get-Json "$base/sync/v2"; $j2b = $v2b.body | ConvertFrom-Json
Show 'GET /sync/v2 (desc-costos) claves' $v2b.res (Keys $j2b.products[0])
Show 'GET /sync/v2 (desc-costos) muestra' $v2b.res ($j2b.products[0] | ConvertTo-Json -Compress)
Show 'GET /sync/v2 (desc-costos) resumen' $v2b.res ("format=" + $j2b.format + " count=" + $j2b.products.Count)

$s1b = Get-Json "$base/sync"; $j1b = $s1b.body | ConvertFrom-Json
Show 'GET /sync (v1) sigue intacto' $s1b.res (Keys $j1b.products[0])
Show 'GET /sync (v1) muestra' $s1b.res ($j1b.products[0] | ConvertTo-Json -Compress)

# --- 9. errores ---------------------------------------------------------------
try {
  Post-Json "$base/admin/sync-config" @{ modo = 'modo-raro' } $H
  Show 'POST sync-config invalido' @{ StatusCode = 'NO FALLO' } ''
} catch {
  $r = $_.Exception.Response
  $body = (New-Object IO.StreamReader($r.GetResponseStream())).ReadToEnd()
  Show 'POST sync-config invalido (esperado 400)' @{ StatusCode = [int]$r.StatusCode } $body
}
try {
  Get-Json "$base/sync/v2?limit=0"
  Show 'limit=0' @{ StatusCode = 'NO FALLO' } ''
} catch {
  $r = $_.Exception.Response
  $body = (New-Object IO.StreamReader($r.GetResponseStream())).ReadToEnd()
  Show 'limit=0 (esperado 400)' @{ StatusCode = [int]$r.StatusCode } $body
}

# --- 10. resto ---------------------------------------------------------------
$m = Get-Json "$base/mapa-catalogo"; Show 'GET /mapa-catalogo' $m.res ($m.body.Substring(0, [Math]::Min(220, $m.body.Length)))
$rh = Post-Json "$base/admin/rehash" @{} $H; Show 'POST /admin/rehash' $rh.res $rh.body

$dir = Join-Path (Get-Location) 'smoke-out.txt'
Set-Content -Path $dir -Value ($out -join "`n") -Encoding UTF8
Get-Content $dir
