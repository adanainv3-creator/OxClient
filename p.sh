#!/data/data/com.termux/files/usr/bin/bash

# Kaynak ve hedef klasör
SOURCE="$HOME/y/OxClient"
TARGET="$HOME/y/l"

# Hedef klasörü oluştur
mkdir -p "$TARGET"

# Sadece dosyaları kopyala (klasörleri direkt oluşturmaz)
find "$SOURCE" -type f | while read -r file; do
    # Kaynağa göre relatif yol
    rel="${file#$SOURCE/}"

    # Hedef dosya yolu
    dest="$TARGET/$rel"

    # Gerekli alt klasörü oluştur
    mkdir -p "$(dirname "$dest")"

    # Dosyayı kopyala
    cp "$file" "$dest"

    echo "Kopyalandı: $rel"
done

echo "Tüm dosyalar kopyalandı."
