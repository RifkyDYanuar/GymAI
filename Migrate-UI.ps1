$baseDir = "e:\Skripsi\GymAI\app\src\main"

$replacements = @(
    # Packages
    @{ old="com.modul.gymai.ui.home"; new="com.modul.gymai.antarmuka.beranda" }
    @{ old="com.modul.gymai.ui.exercise"; new="com.modul.gymai.antarmuka.latihan" }
    @{ old="com.modul.gymai.ui.guide"; new="com.modul.gymai.antarmuka.panduan" }
    @{ old="com.modul.gymai.ui.history"; new="com.modul.gymai.antarmuka.riwayat" }
    @{ old="com.modul.gymai.ui.profile"; new="com.modul.gymai.antarmuka.profil" }
    @{ old="com.modul.gymai.ui.detection"; new="com.modul.gymai.antarmuka.deteksi" }
    @{ old="com.modul.gymai.ui"; new="com.modul.gymai.antarmuka" }

    # Home
    @{ old="HomeFragment"; new="BerandaFragment" }
    @{ old="HomeViewModel"; new="BerandaViewModel" }
    @{ old="FragmentHomeBinding"; new="FragmentBerandaBinding" }
    @{ old="fragment_home"; new="fragment_beranda" }
    @{ old="homeFragment"; new="berandaFragment" }

    # Exercise
    @{ old="ExerciseFragment"; new="LatihanFragment" }
    @{ old="FragmentExerciseBinding"; new="FragmentLatihanBinding" }
    @{ old="fragment_exercise"; new="fragment_latihan" }
    @{ old="exerciseFragment"; new="latihanFragment" }

    # Guide
    @{ old="GuideFragment"; new="PanduanFragment" }
    @{ old="FragmentGuideBinding"; new="FragmentPanduanBinding" }
    @{ old="fragment_guide"; new="fragment_panduan" }
    @{ old="guideFragment"; new="panduanFragment" }

    # History
    @{ old="HistoryFragment"; new="RiwayatFragment" }
    @{ old="HistoryViewModel"; new="RiwayatViewModel" }
    @{ old="HistoryAdapter"; new="RiwayatAdapter" }
    @{ old="FragmentHistoryBinding"; new="FragmentRiwayatBinding" }
    @{ old="ItemHistoryBinding"; new="ItemRiwayatBinding" }
    @{ old="fragment_history"; new="fragment_riwayat" }
    @{ old="item_history"; new="item_riwayat" }
    @{ old="historyFragment"; new="riwayatFragment" }

    # Profile
    @{ old="ProfileFragment"; new="ProfilFragment" }
    @{ old="FragmentProfileBinding"; new="FragmentProfilBinding" }
    @{ old="fragment_profile"; new="fragment_profil" }
    @{ old="profileFragment"; new="profilFragment" }

    # Detection
    @{ old="DetectionFragment"; new="DeteksiFragment" }
    @{ old="FragmentDetectionBinding"; new="FragmentDeteksiBinding" }
    @{ old="fragment_detection"; new="fragment_deteksi" }
    @{ old="detectionFragment"; new="deteksiFragment" }
)

Write-Host "Memulai penggantian teks file. Ini mungkin memakan waktu beberapa detik..."
$files = Get-ChildItem -Path $baseDir -Recurse -Include *.kt, *.xml
$utf8NoBom = New-Object System.Text.UTF8Encoding $False

foreach ($file in $files) {
    if ($file.FullName -match "\\build\\") { continue }
    
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $original = $content

    foreach ($item in $replacements) {
        $content = $content.Replace($item.old, $item.new)
    }

    if ($content -cne $original) {
        [System.IO.File]::WriteAllText($file.FullName, $content, $utf8NoBom)
        Write-Host "Diperbarui: $($file.Name)"
    }
}

Write-Host "`nMemulai penggantian nama file layout XML..."
$resLayoutDir = Join-Path $baseDir "res\layout"
$layoutRenames = @{
    "fragment_home.xml" = "fragment_beranda.xml"
    "fragment_exercise.xml" = "fragment_latihan.xml"
    "fragment_guide.xml" = "fragment_panduan.xml"
    "fragment_history.xml" = "fragment_riwayat.xml"
    "item_history.xml" = "item_riwayat.xml"
    "fragment_profile.xml" = "fragment_profil.xml"
    "fragment_detection.xml" = "fragment_deteksi.xml"
}

foreach ($old in $layoutRenames.Keys) {
    $path = Join-Path $resLayoutDir $old
    if (Test-Path $path) {
        Rename-Item -Path $path -NewName $layoutRenames[$old]
        Write-Host "Ganti Nama: $old -> $($layoutRenames[$old])"
    }
}

Write-Host "`nMemulai pembuatan struktur folder baru dan memindahkan file Kotlin..."
$javaRoot = Join-Path $baseDir "java\com\modul\gymai"
$newDirs = @("antarmuka\beranda", "antarmuka\latihan", "antarmuka\panduan", "antarmuka\riwayat", "antarmuka\profil", "antarmuka\deteksi")

foreach ($dir in $newDirs) {
    $fullPath = Join-Path $javaRoot $dir
    if (-not (Test-Path $fullPath)) {
        New-Item -ItemType Directory -Force -Path $fullPath | Out-Null
    }
}

$moves = @(
    @{ old="ui\home\HomeFragment.kt"; new="antarmuka\beranda\BerandaFragment.kt" }
    @{ old="ui\home\HomeViewModel.kt"; new="antarmuka\beranda\BerandaViewModel.kt" }
    
    @{ old="ui\exercise\ExerciseFragment.kt"; new="antarmuka\latihan\LatihanFragment.kt" }
    
    @{ old="ui\guide\GuideFragment.kt"; new="antarmuka\panduan\PanduanFragment.kt" }
    
    @{ old="ui\history\HistoryFragment.kt"; new="antarmuka\riwayat\RiwayatFragment.kt" }
    @{ old="ui\history\HistoryViewModel.kt"; new="antarmuka\riwayat\RiwayatViewModel.kt" }
    @{ old="ui\history\HistoryAdapter.kt"; new="antarmuka\riwayat\RiwayatAdapter.kt" }
    
    @{ old="ui\profile\ProfileFragment.kt"; new="antarmuka\profil\ProfilFragment.kt" }
    
    @{ old="ui\detection\DetectionFragment.kt"; new="antarmuka\deteksi\DeteksiFragment.kt" }
)

foreach ($move in $moves) {
    $oldPath = Join-Path $javaRoot $move.old
    $newPath = Join-Path $javaRoot $move.new
    if (Test-Path $oldPath) {
        Move-Item -Path $oldPath -Destination $newPath -Force
        Write-Host "Pindah: $($move.old) -> $($move.new)"
    }
}

$uiPath = Join-Path $javaRoot "ui"
if (Test-Path $uiPath) {
    # Hapus folder lama berserta isinya yang sudah kosong
    Remove-Item -Path $uiPath -Recurse -Force
    Write-Host "Folder UI lama berhasil dihapus."
}

Write-Host "`n=================================="
Write-Host "Selesai! Struktur UI GymAI Anda telah diubah ke Bahasa Indonesia."
Write-Host "Silahkan klik 'Sync Project with Gradle Files' dan jalankan proyeknya."
Write-Host "=================================="
