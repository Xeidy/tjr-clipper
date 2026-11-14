# TJR Clipper

Aplikasi Android untuk kampanye clipping TJR: pilih video panjang TJR, Claude memilih momen yang paling berpotensi viral, lalu aplikasi memotongnya jadi video 9:16 dengan subtitle dan hook.

## Unduh APK

Buka **Releases → TJR Clipper (terbaru)** di halaman repositori ini dari HP, unduh `TJR-Clipper.apk`, lalu pasang (izinkan "pasang dari sumber tidak dikenal" kalau diminta). APK baru dibuat otomatis setiap ada perubahan kode.

## Cara pakai

1. Isi kunci API Claude di **Pengaturan Claude**.
2. **Pilih video** TJR yang sudah ada di HP. Opsional: pilih file subtitle `.srt`/`.vtt` video itu supaya tidak perlu transkrip.
3. Pilih **gaya editing**, atau ketuk **Tiru gaya dari video contoh** dan pilih clip yang editannya Anda suka.
4. Pilih panjang clip, lalu ketuk **Cari momen viral**.
   - Pertama kali, aplikasi mengunduh model suara (±40 MB).
   - Transkrip berjalan di HP; video 60 menit bisa makan 10–30 menit. Biarkan aplikasi terbuka.
5. Periksa daftar momen, ubah hook/caption bila perlu, centang yang diinginkan, lalu **Render**.
6. Hasil tersimpan di galeri, folder `Movies/TJR Clipper`. Tombol **Bagikan** juga menyalin caption.

## Cara kerja

- Transkrip suara: [Vosk](https://alphacephei.com/vosk/) (offline, bahasa Inggris).
- Pemilihan momen dan pembacaan gaya video contoh: Claude API (kunci milik pengguna).
- Potong, crop 9:16 dengan deteksi wajah (ML Kit), subtitle per kata: Media3 Transformer.

## Catatan

- Biaya API Claude dihitung per pemakaian di akun Anthropic Anda.
- Ikuti aturan kampanye: TJR harus tampil, tidak boleh negatif, tanpa logo, caption pakai #TJR, submit ke Whop maksimal 30 menit setelah posting.
