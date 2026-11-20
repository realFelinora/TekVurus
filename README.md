# TekVuruş

Paper/Spigot sunucuları için **Türkçe ağaç kesme ve maden kazma eklentisi**.
Tek vuruşta bütün ağaç **gerçekçi şekilde devrilir**, maden damarındaki bütün cevherler **havada uçarak** envanterine gelir.

## Özellikler
- **Devrilen ağaç:** Ağaç bir anda yok olmaz. Gövdesi ve yapraklarıyla birlikte baktığın yönün tersine yavaşça yatar, hızlanarak yere çarpar, toz kalkar ve kütükler düştüğü yere saçılır
- **Uçan madenler:** Eğilip kazmayla vurunca damardaki bütün cevherler dönerek ve kavis çizerek sana uçar, envanterine girer
- **Fidan otomatik dikilir:** Kesilen ağacın yerine doğru türde fidan dikilir (2x2 ağaçlarda 4 fidan)
- **Evleri korur:** Etrafında doğal yaprak olmayan kütükler (oyuncu yapıları) devrilmez
- **Gerçekçi dayanıklılık:** Her blok aletten dayanıklılık götürür, Kırılmaz büyüsü hesaba katılır, alet kırılmak üzereyken durur
- **Servet / İpek Dokunuş** ve maden XP'si normal kırmadaki gibi çalışır
- **Koruma eklentileriyle uyumlu:** WorldGuard, GriefPrevention vb. izin vermeyen bloklara dokunmaz
- Her oyuncu `/tekvurus` ile kendisi için açıp kapatabilir
- **Türkçe ve İngilizce** dil desteği (`dil: tr` / `dil: en`)

## Nasıl kullanılır
| | Normal vuruş | Eğilerek (Shift) vuruş |
|---|---|---|
| 🪓 Balta + ağaç | Bütün ağaç devrilir | Sadece o kütük kırılır |
| ⛏ Kazma + maden | Sadece o blok kırılır | Bütün damar sana uçar |

İkisi de `config.yml` dosyasından ters çevrilebilir (`egilerek-calis`).

## Komutlar
| Komut | Açıklama | Yetki |
|---|---|---|
| `/tekvurus` | TekVuruş'u kendin için açar/kapatır | herkes |
| `/tekvurus yenile` | `config.yml` dosyasını yeniden yükler | `tekvurus.yonetici` (OP) |

| Yetki | Açıklama | Varsayılan |
|---|---|---|
| `tekvurus.agac` | Tek vuruşta ağaç kesme | herkes |
| `tekvurus.maden` | Tek vuruşta maden kazma | herkes |

## Kurulum
1. [Releases](../../releases) kısmından `TekVurus-x.x.x.jar` dosyasını indirin.
2. Sunucunun `plugins` klasörüne atın ve sunucuyu yeniden başlatın.
3. İsterseniz `plugins/TekVurus/config.yml` dosyasını düzenleyip `/tekvurus yenile` yazın.

## Ayarlar (özet)
```yaml
dil: tr
hiz: 3                # maden animasyonu: tick başına blok
dayaniklilik: true
aleti-koru: true
agac:
  egilerek-calis: false
  limit: 200
  devrilme: true      # false: bloklar tek tek kırılır
  yapraklar: true
  fidan-dik: true
maden:
  egilerek-calis: true
  limit: 64
  miknatis: true      # false: madenler yere düşer
```

**Uyumluluk:** Paper / Spigot 1.20.1 – 1.21.x, Java 17+

## Derleme
```sh
./gradlew build
# çıktı: build/libs/TekVurus-1.0.2.jar
```
