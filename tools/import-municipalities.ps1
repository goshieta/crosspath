param([string]$SourceDirectory = '.gradle')
$ErrorActionPreference = 'Stop'
# Input: the 12 e-Stat HTML result pages documented in the resource README.
$rows = @()
foreach ($page in 1..12) {
    $html = Get-Content (Join-Path $SourceDirectory "municipalities-$page.html") -Raw -Encoding utf8
    $codes = [regex]::Matches($html, '<td class="td_c1 htCode">(.*?)</td>')
    $prefectures = [regex]::Matches($html, '<td class="td_l1 todoNm">(.*?)</td>')
    $parents = [regex]::Matches($html, '<td class="td_l1 parentCityNm">(.*?)</td>')
    $cities = [regex]::Matches($html, '<td class="td_l1 selfCityNm">(.*?)</td>')
    $expected = if ($page -eq 12) { 13 } else { 20 }
    foreach ($column in @($codes, $prefectures, $parents, $cities)) {
        if ($column.Count -ne $expected) { throw "Unexpected page size on page $page" }
    }
    foreach ($i in 0..($codes.Count - 1)) {
        $city = [Net.WebUtility]::HtmlDecode($cities[$i].Groups[1].Value)
        if (-not $city) { $city = [Net.WebUtility]::HtmlDecode($parents[$i].Groups[1].Value) }
        if ($city -notmatch '[市町村]$') { throw "Missing municipality name on page $page" }
        $rows += [pscustomobject]@{
            Official = $codes[$i].Groups[1].Value
            Prefecture = [Net.WebUtility]::HtmlDecode($prefectures[$i].Groups[1].Value)
            Name = $city
        }
    }
}
if ($rows.Count -ne 233 -or ($rows.Official | Select-Object -Unique).Count -ne 233) {
    throw 'Expected 233 unique municipalities'
}
$rows = @($rows | Sort-Object Official)
$expectedCounts = @{ '福岡県'=60; '佐賀県'=20; '長崎県'=21; '熊本県'=45; '大分県'=18; '宮崎県'=26; '鹿児島県'=43 }
foreach ($group in ($rows | Group-Object Prefecture)) {
    if ($expectedCounts[$group.Name] -ne $group.Count) { throw "Unexpected count: $($group.Name)" }
}
$lines = @('# wire_code' + "`t" + 'official_code' + "`t" + 'prefecture' + "`t" + 'municipality')
foreach ($i in 0..232) {
    $row = $rows[$i]
    $lines += "$($i + 1)`t$($row.Official)`t$($row.Prefecture)`t$($row.Name)"
}
$destination = Join-Path $PSScriptRoot '..\app\src\main\resources\municipalities'
New-Item -ItemType Directory -Force $destination | Out-Null
[IO.File]::WriteAllLines((Join-Path $destination 'kyushu-2026-09-26-v1.tsv'), $lines, [Text.UTF8Encoding]::new($false))
$rows | Group-Object Prefecture | Select-Object Name, Count
