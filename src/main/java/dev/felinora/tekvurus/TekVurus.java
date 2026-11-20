package dev.felinora.tekvurus;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/** Eğilerek baltayla vurunca bütün ağaç, kazmayla vurunca bütün maden damarı kırılır. */
public final class TekVurus extends JavaPlugin implements Listener, TabCompleter {

    // 1.20.5'te adı değişen parçacıklar: hangi sürümde hangisi varsa o kullanılır.
    private static final Particle BLOK = parcacik("BLOCK", "BLOCK_CRACK");
    private static final Particle MUTLU = parcacik("HAPPY_VILLAGER", "VILLAGER_HAPPY");
    private static final Enchantment KIRILMAZ = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
    private static final Enchantment IPEK = Enchantment.getByKey(NamespacedKey.minecraft("silk_touch"));
    private static final Set<Material> NETHER_YAPRAK = EnumSet.of(
            Material.NETHER_WART_BLOCK, Material.WARPED_WART_BLOCK, Material.SHROOMLIGHT);

    private final Set<UUID> kapali = new HashSet<>();
    private final Set<UUID> calisiyor = new HashSet<>();
    private final Set<Material> madenler = EnumSet.noneOf(Material.class);
    private final Set<Display> gorseller = new HashSet<>();
    private boolean kendiOlayimiz = false;
    private File kapaliDosyasi;
    private String dil;

    private static Particle parcacik(String... adlar) {
        for (String ad : adlar) {
            try {
                return Particle.valueOf(ad);
            } catch (IllegalArgumentException ignored) {
            }
        }
        throw new IllegalStateException("Parçacık bulunamadı: " + String.join("/", adlar));
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ayarlariYukle();
        kapaliDosyasi = new File(getDataFolder(), "kapali.yml");
        for (String s : YamlConfiguration.loadConfiguration(kapaliDosyasi).getStringList("oyuncular")) {
            try { kapali.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        getServer().getPluginManager().registerEvents(this, this);
        var komut = getCommand("tekvurus");
        if (komut != null) {
            komut.setExecutor(this);
            komut.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        gorseller.forEach(Display::remove);
        gorseller.clear();
    }

    private void ayarlariYukle() {
        reloadConfig();
        dil = getConfig().getString("dil", "tr").toLowerCase();
        madenler.clear();
        for (String ad : getConfig().getStringList("maden.madenler")) {
            ad = ad.toUpperCase();
            for (String aday : new String[]{ad, "DEEPSLATE_" + ad}) {
                Material m = Material.getMaterial(aday);
                if (m != null && m.isBlock()) madenler.add(m);
            }
        }
    }

    // ------------------------------------------------------------------ mesajlar

    static String renk(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String metin(String anahtar, String... degisenler) {
        String s = getConfig().getString("mesajlar." + dil + "." + anahtar);
        if (s == null) s = getConfig().getString("mesajlar.tr." + anahtar, anahtar);
        for (int i = 0; i + 1 < degisenler.length; i += 2) s = s.replace(degisenler[i], degisenler[i + 1]);
        return s;
    }

    private void mesaj(CommandSender kime, String anahtar, String... degisenler) {
        kime.sendMessage(renk(metin("onek") + metin(anahtar, degisenler)));
    }

    private void aksiyonCubugu(Player p, String anahtar, String... degisenler) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(renk(metin(anahtar, degisenler))));
    }

    // ------------------------------------------------------------------ olay

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void kir(BlockBreakEvent e) {
        if (kendiOlayimiz) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (calisiyor.contains(id) || kapali.contains(id)) return;
        if (getConfig().getStringList("haric-dunyalar").contains(p.getWorld().getName())) return;

        ItemStack alet = p.getInventory().getItemInMainHand();
        Block b = e.getBlock();
        Material tur = b.getType();
        if (balta(alet) && Tag.LOGS.isTagged(tur) && tetik(p, "agac", false)
                && getConfig().getBoolean("agac.aktif", true) && p.hasPermission("tekvurus.agac")) {
            agacKes(p, b, e);
        } else if (kazma(alet) && madenler.contains(tur) && tetik(p, "maden", true)
                && getConfig().getBoolean("maden.aktif", true) && p.hasPermission("tekvurus.maden")) {
            madenKaz(p, b, tur);
        }
    }

    /**
     * egilerek-calis: true  -> sadece eğilirken çalışır
     * egilerek-calis: false -> eğilmeden çalışır, eğilirken normal kırılır
     */
    private boolean tetik(Player p, String tur, boolean varsayilan) {
        boolean egilerek = getConfig().getBoolean(tur + ".egilerek-calis", varsayilan);
        return egilerek == p.isSneaking();
    }

    private static boolean balta(ItemStack i) {
        return i != null && i.getType().name().endsWith("_AXE");
    }

    private static boolean kazma(ItemStack i) {
        return i != null && i.getType().name().endsWith("_PICKAXE");
    }

    private static boolean dogalYaprak(Block b) {
        if (NETHER_YAPRAK.contains(b.getType())) return true;
        return b.getBlockData() instanceof Leaves l && !l.isPersistent();
    }

    /** Başlangıç bloğuna (köşeler dahil) bağlı uygun blokları bulur. Başlangıç bloğu listede yoktur. */
    private static List<Block> bagliBloklar(Block bas, Predicate<Block> uygun, int limit) {
        List<Block> sonuc = new ArrayList<>();
        Set<Block> gorulen = new HashSet<>();
        ArrayDeque<Block> kuyruk = new ArrayDeque<>();
        gorulen.add(bas);
        kuyruk.add(bas);
        while (!kuyruk.isEmpty() && sonuc.size() < limit) {
            Block c = kuyruk.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                Block n = c.getRelative(dx, dy, dz);
                if (gorulen.add(n) && uygun.test(n)) {
                    sonuc.add(n);
                    kuyruk.add(n);
                    if (sonuc.size() >= limit) return sonuc;
                }
            }
        }
        return sonuc;
    }

    private static Comparator<Block> uzakliga(Block merkez) {
        return Comparator.comparingDouble(x -> x.getLocation().distanceSquared(merkez.getLocation()));
    }

    // ------------------------------------------------------------------ ağaç

    private void agacKes(Player p, Block bas, BlockBreakEvent olay) {
        int limit = getConfig().getInt("agac.limit", 200);
        List<Block> kutukler = bagliBloklar(bas, x -> Tag.LOGS.isTagged(x.getType()), limit);

        // Ağaç mı? Kütüklerin etrafında yeterince doğal yaprak olmalı (kütükten yapılmış evleri korur).
        List<Block> govde = new ArrayList<>(kutukler);
        govde.add(bas);
        Set<Block> yakinYapraklar = new LinkedHashSet<>();
        for (Block k : govde) {
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block n = k.getRelative(f);
                if (dogalYaprak(n)) yakinYapraklar.add(n);
            }
        }
        if (yakinYapraklar.size() < getConfig().getInt("agac.en-az-yaprak", 5)) return;

        // Yapraklar: kütüklerin kapladığı alanın 4 blok etrafındaki doğal yapraklar.
        List<Block> yapraklar = new ArrayList<>();
        if (getConfig().getBoolean("agac.yapraklar", true)) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (Block k : govde) {
                minX = Math.min(minX, k.getX()); maxX = Math.max(maxX, k.getX());
                minY = Math.min(minY, k.getY()); maxY = Math.max(maxY, k.getY());
                minZ = Math.min(minZ, k.getZ()); maxZ = Math.max(maxZ, k.getZ());
            }
            final int x0 = minX - 4, x1 = maxX + 4, y0 = minY, y1 = maxY + 4, z0 = minZ - 4, z1 = maxZ + 4;
            Set<Block> gorulen = new HashSet<>(yakinYapraklar);
            ArrayDeque<Block> kuyruk = new ArrayDeque<>(yakinYapraklar);
            yapraklar.addAll(yakinYapraklar);
            while (!kuyruk.isEmpty() && yapraklar.size() < 600) {
                Block c = kuyruk.poll();
                for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                    Block n = c.getRelative(f);
                    if (n.getX() < x0 || n.getX() > x1 || n.getY() < y0 || n.getY() > y1 || n.getZ() < z0 || n.getZ() > z1) continue;
                    if (gorulen.add(n) && dogalYaprak(n)) {
                        yapraklar.add(n);
                        kuyruk.add(n);
                    }
                }
            }
            yapraklar.sort(uzakliga(bas));
        }
        kutukler.sort(uzakliga(bas));

        // Fidan dikilecek yerler: gövdenin en alt kütükleri (2x2 ağaçlarda 4 fidan)
        int enAlt = govde.stream().mapToInt(Block::getY).min().orElse(bas.getY());
        List<Location> fidanYerleri = new ArrayList<>();
        Material fidan = fidanTuru(bas.getType());
        for (Block k : govde) if (k.getY() == enAlt && fidanYerleri.size() < 4) fidanYerleri.add(k.getLocation());

        Runnable fidanIsi = () -> {
            if (fidan != null && getConfig().getBoolean("agac.fidan-dik", true)) fidanDik(fidanYerleri, fidan);
        };
        if (getConfig().getBoolean("agac.devrilme", true)) devir(p, bas, olay, kutukler, yapraklar, fidanIsi);
        else calistir(p, bas, kutukler, yapraklar, false, fidanIsi);
    }

    private static Material fidanTuru(Material kutuk) {
        String ad = kutuk.name().replace("STRIPPED_", "");
        for (String son : new String[]{"_LOG", "_WOOD", "_STEM", "_HYPHAE"}) {
            if (ad.endsWith(son)) { ad = ad.substring(0, ad.length() - son.length()); break; }
        }
        return switch (ad) {
            case "MANGROVE" -> Material.getMaterial("MANGROVE_PROPAGULE");
            case "CRIMSON" -> Material.CRIMSON_FUNGUS;
            case "WARPED" -> Material.WARPED_FUNGUS;
            default -> Material.getMaterial(ad + "_SAPLING");
        };
    }

    private void fidanDik(List<Location> yerler, Material fidan) {
        boolean mantar = fidan == Material.CRIMSON_FUNGUS || fidan == Material.WARPED_FUNGUS;
        for (Location l : yerler) {
            Block b = l.getBlock();
            Block alt = b.getRelative(BlockFace.DOWN);
            boolean zeminUygun = mantar ? Tag.NYLIUM.isTagged(alt.getType()) : Tag.DIRT.isTagged(alt.getType());
            if (!b.getType().isAir() || !zeminUygun) continue;
            b.setType(fidan);
            Location orta = l.clone().add(0.5, 0.3, 0.5);
            b.getWorld().spawnParticle(MUTLU, orta, 10, 0.3, 0.3, 0.3, 0);
            b.getWorld().playSound(orta, Sound.BLOCK_GRASS_PLACE, 1f, 1.2f);
        }
    }


    // ------------------------------------------------------------------ devrilen ağaç

    /** Gerçek blokların yerine konan, döndürülerek "devrilen" görüntü. */
    private static final class Parca {
        BlockDisplay gorsel;
        Vector3f ofset;
        Collection<ItemStack> dusenler;
        BlockData veri;
        boolean kutuk;
    }

    private void devir(Player p, Block bas, BlockBreakEvent olay, List<Block> kutukler, List<Block> yapraklar, Runnable sonra) {
        ItemStack el = p.getInventory().getItemInMainHand();
        boolean yaratici = p.getGameMode() == GameMode.CREATIVE;

        // Koruma eklentileri bir kütüğe bile izin vermiyorsa ağaç devrilmez.
        for (Block x : kutukler) if (!izinVar(p, x)) return;
        yapraklar.removeIf(x -> !izinVar(p, x));

        // Alet bütün ağacı kesmeye dayanamayacaksa hiç başlama.
        if (!yaratici && getConfig().getBoolean("dayaniklilik", true) && getConfig().getBoolean("aleti-koru", true)) {
            int max = el.getType().getMaxDurability();
            ItemMeta meta = el.getItemMeta();
            if (max > 0 && meta instanceof Damageable d && !meta.isUnbreakable()) {
                int seviye = KIRILMAZ == null ? 0 : el.getEnchantmentLevel(KIRILMAZ);
                if (max - d.getDamage() - 2 < kutukler.size() / (double) (seviye + 1)) {
                    mesaj(p, "alet-korundu");
                    return;
                }
            }
        }

        UUID id = p.getUniqueId();
        calisiyor.add(id);
        World w = bas.getWorld();

        // Ağaç oyuncunun baktığı yöne doğru, gövdenin ön alt kenarı etrafında döner.
        Vector yon = p.getLocation().getDirection().setY(0);
        if (yon.lengthSquared() < 1e-4) yon = new Vector(1, 0, 0);
        yon.normalize();
        // Dönme noktası: gövdenin en alttaki kütüğü (vurulan yere en yakın olanı).
        Block kok = bas;
        for (Block k : kutukler) {
            if (k.getY() < kok.getY() || (k.getY() == kok.getY()
                    && yatayUzaklik(k, bas) < yatayUzaklik(kok, bas))) kok = k;
        }
        Location pivot = kok.getLocation().add(0.5, 0, 0.5).add(yon.clone().multiply(0.5));
        Vector3f eksen = new Vector3f((float) yon.getZ(), 0f, (float) -yon.getX());

        List<Parca> parcalar = new ArrayList<>();
        // Vurulan kütük de ağaçla birlikte devrilsin: oyunun kendi kırmasında eşya düşmesin, biz düşürelim.
        olay.setDropItems(false);
        Parca vurulan = new Parca();
        vurulan.veri = bas.getBlockData();
        vurulan.kutuk = true;
        vurulan.dusenler = yaratici ? List.of() : bas.getDrops(el, p);
        vurulan.ofset = ofset(bas, pivot);
        vurulan.gorsel = gorselYap(bas, pivot, vurulan);
        parcalar.add(vurulan);
        for (Block x : kutukler) parcalar.add(parcaYap(p, x, pivot, yaratici ? null : el, true, yaratici));
        for (Block x : yapraklar) parcalar.add(parcaYap(p, x, pivot, null, false, yaratici));
        for (int i = 0; i < kutukler.size(); i++) hasarVer(p, el);

        w.playSound(pivot, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.8f, 0.5f);
        aksiyonCubugu(p, "devriliyor");

        final int adimlar = 12;
        new BukkitRunnable() {
            int adim = 0;

            @Override
            public void run() {
                adim++;
                double t = adim / (double) adimlar;
                Quaternionf q = new Quaternionf().rotateAxis((float) (Math.toRadians(88) * t * t), eksen);
                for (Parca pr : parcalar) {
                    if (!pr.gorsel.isValid()) continue;
                    pr.gorsel.setInterpolationDelay(0);
                    pr.gorsel.setInterpolationDuration(2);
                    pr.gorsel.setTransformation(new Transformation(q.transform(new Vector3f(pr.ofset)),
                            new Quaternionf(q), new Vector3f(1, 1, 1), new Quaternionf()));
                }
                if (adim == adimlar / 2) w.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 1f, 0.5f);
                if (adim >= adimlar) {
                    cancel();
                    Bukkit.getScheduler().runTaskLater(TekVurus.this, () -> {
                        yereCarp(pivot, q, parcalar);
                        aksiyonCubugu(p, "agac-bitti", "%sayi%", String.valueOf(kutukler.size() + 1));
                        calisiyor.remove(id);
                        Bukkit.getScheduler().runTaskLater(TekVurus.this, sonra, 10L);
                    }, 2L);
                }
            }
        }.runTaskTimer(this, 2L, 2L);
    }

    private static double yatayUzaklik(Block a, Block b) {
        int dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static Vector3f ofset(Block x, Location pivot) {
        return new Vector3f((float) (x.getX() - pivot.getX()), (float) (x.getY() - pivot.getY()),
                (float) (x.getZ() - pivot.getZ()));
    }

    private BlockDisplay gorselYap(Block x, Location pivot, Parca pr) {
        BlockDisplay d = x.getWorld().spawn(pivot, BlockDisplay.class);
        d.setBlock(pr.veri);
        d.setPersistent(false);
        d.setTransformation(new Transformation(new Vector3f(pr.ofset), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
        gorseller.add(d);
        return d;
    }

    private Parca parcaYap(Player p, Block x, Location pivot, ItemStack alet, boolean kutuk, boolean yaratici) {
        Parca pr = new Parca();
        pr.veri = x.getBlockData();
        pr.kutuk = kutuk;
        pr.dusenler = yaratici ? List.of() : (alet == null ? x.getDrops() : x.getDrops(alet, p));
        pr.ofset = ofset(x, pivot);
        x.setType(Material.AIR, false);
        pr.gorsel = gorselYap(x, pivot, pr);
        p.setExhaustion(Math.min(40f, p.getExhaustion() + 0.005f));
        return pr;
    }

    private void yereCarp(Location pivot, Quaternionf q, List<Parca> parcalar) {
        World w = pivot.getWorld();
        int i = 0;
        double sx = 0, sy = 0, sz = 0;
        int kutukSayisi = 0;
        for (Parca pr : parcalar) {
            Vector3f m = q.transform(new Vector3f(pr.ofset).add(0.5f, 0.5f, 0.5f));
            Location yer = pivot.clone().add(m.x, m.y, m.z);
            for (ItemStack it : pr.dusenler) {
                if (it != null && !it.getType().isAir()) w.dropItemNaturally(yer, it);
            }
            if (i++ % (pr.kutuk ? 2 : 5) == 0) {
                w.spawnParticle(BLOK, yer, pr.kutuk ? 10 : 4, 0.4, 0.4, 0.4, 0, pr.veri);
            }
            if (pr.kutuk) { sx += yer.getX(); sy += yer.getY(); sz += yer.getZ(); kutukSayisi++; }
            pr.gorsel.remove();
            gorseller.remove(pr.gorsel);
        }
        Location orta = kutukSayisi > 0 ? new Location(w, sx / kutukSayisi, sy / kutukSayisi, sz / kutukSayisi) : pivot;
        w.spawnParticle(Particle.CLOUD, orta, 30, 1.5, 0.3, 1.5, 0.03);
        w.playSound(orta, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.2f, 0.6f);
        w.playSound(orta, Sound.BLOCK_GRASS_BREAK, 1f, 0.6f);
    }

    // ------------------------------------------------------------------ uçan madenler

    private void madeniUcur(Player p, Block x, ItemStack el, int sira, int xp) {
        boolean yaratici = p.getGameMode() == GameMode.CREATIVE;
        Collection<ItemStack> dusenler = yaratici ? List.of() : x.getDrops(el, p);
        BlockData veri = x.getBlockData();
        Location orta = x.getLocation().add(0.5, 0.5, 0.5);
        World w = x.getWorld();
        w.spawnParticle(BLOK, orta, 14, 0.3, 0.3, 0.3, 0, veri);
        w.playSound(orta, veri.getSoundGroup().getBreakSound(), 0.7f, 0.9f + ThreadLocalRandom.current().nextFloat() * 0.2f);
        x.setType(Material.AIR);
        p.setExhaustion(Math.min(40f, p.getExhaustion() + 0.005f));
        for (ItemStack it : dusenler) {
            if (it != null && !it.getType().isAir()) ucur(p, orta, it, sira);
        }
        if (xp > 0) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline()) return;
                p.giveExp(xp);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
            }, 12L);
        }
    }

    /** Eşya kavis çizip dönerek oyuncuya uçar ve envantere girer. */
    private void ucur(Player p, Location bas, ItemStack esya, int sira) {
        ItemDisplay d = bas.getWorld().spawn(bas, ItemDisplay.class);
        d.setItemStack(esya);
        d.setPersistent(false);
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
        gorseller.add(d);
        final int adimlar = 5;
        new BukkitRunnable() {
            int adim = 0;

            @Override
            public void run() {
                adim++;
                if (!d.isValid() || !p.isOnline() || !p.getWorld().equals(bas.getWorld())) { teslim(); return; }
                if (adim > adimlar) { teslim(); return; }
                double t = adim / (double) adimlar;
                Vector fark = p.getLocation().add(0, 1.0, 0).toVector().subtract(bas.toVector());
                Vector3f konum = new Vector3f((float) (fark.getX() * t),
                        (float) (fark.getY() * t + Math.sin(Math.PI * t) * 1.3), (float) (fark.getZ() * t));
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(2);
                d.setTransformation(new Transformation(konum, new Quaternionf().rotateY((float) (adim * 1.1)),
                        new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
            }

            void teslim() {
                cancel();
                d.remove();
                gorseller.remove(d);
                if (!p.isOnline()) { bas.getWorld().dropItemNaturally(bas, esya); return; }
                p.getInventory().addItem(esya).values().forEach(k -> p.getWorld().dropItemNaturally(p.getLocation(), k));
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, Math.min(2f, 0.9f + sira * 0.06f));
            }
        }.runTaskTimer(this, 1L, 2L);
    }

    // ------------------------------------------------------------------ maden

    private static String madenGrubu(Material m) {
        return m.name().replace("DEEPSLATE_", "");
    }

    private void madenKaz(Player p, Block bas, Material tur) {
        String grup = madenGrubu(tur);
        List<Block> damar = bagliBloklar(bas,
                x -> madenler.contains(x.getType()) && madenGrubu(x.getType()).equals(grup),
                getConfig().getInt("maden.limit", 64));
        if (damar.isEmpty()) return;
        damar.sort(uzakliga(bas));
        calistir(p, bas, damar, List.of(), true, null);
    }

    /** Vanilla'nın kırarken verdiği XP (İpek Dokunuş yoksa). */
    private static int madenXp(Material m) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return switch (madenGrubu(m)) {
            case "COAL_ORE" -> r.nextInt(0, 3);
            case "DIAMOND_ORE", "EMERALD_ORE" -> r.nextInt(3, 8);
            case "LAPIS_ORE", "NETHER_QUARTZ_ORE" -> r.nextInt(2, 6);
            case "REDSTONE_ORE" -> r.nextInt(1, 6);
            case "NETHER_GOLD_ORE" -> r.nextInt(0, 2);
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ kırma animasyonu

    private void calistir(Player p, Block bas, List<Block> ana, List<Block> yapraklar, boolean maden, Runnable sonra) {
        UUID id = p.getUniqueId();
        calisiyor.add(id);
        int hiz = Math.max(1, getConfig().getInt("hiz", 3));
        Material ilkTur = bas.getType();

        new BukkitRunnable() {
            int i = 0, j = 0, kirilan = 1;
            boolean durdu = false;

            @Override
            public void run() {
                if (!p.isOnline()) { bitir(); return; }

                // Kütükler / madenler
                int n = 0;
                while (!durdu && n < hiz && i < ana.size()) {
                    Block x = ana.get(i++);
                    boolean hala = maden ? madenGrubu(x.getType()).equals(madenGrubu(ilkTur)) : Tag.LOGS.isTagged(x.getType());
                    if (!hala) continue;
                    ItemStack el = p.getInventory().getItemInMainHand();
                    if (maden ? !kazma(el) : !balta(el)) { durdu = true; break; }
                    if (aletBitmekUzere(p, el)) { mesaj(p, "alet-korundu"); durdu = true; break; }
                    if (!izinVar(p, x)) continue;
                    int xp = maden && (IPEK == null || !el.containsEnchantment(IPEK)) ? madenXp(x.getType()) : 0;
                    if (maden && getConfig().getBoolean("maden.miknatis", true)) {
                        madeniUcur(p, x, el, kirilan, xp);
                        hasarVer(p, el);
                        kirilan++;
                        n++;
                        continue;
                    }
                    blokKir(p, x, el, n % 2 == 0);
                    if (xp > 0) {
                        ExperienceOrb orb = x.getWorld().spawn(x.getLocation().add(0.5, 0.5, 0.5), ExperienceOrb.class);
                        orb.setExperience(xp);
                    }
                    hasarVer(p, el);
                    kirilan++;
                    n++;
                }
                // Kütükler bitince yapraklar (daha hızlı dökülür)
                if (durdu || i >= ana.size()) {
                    int y = 0;
                    while (!durdu && y < hiz * 4 && j < yapraklar.size()) {
                        Block x = yapraklar.get(j++);
                        if (!dogalYaprak(x) || !izinVar(p, x)) continue;
                        blokKir(p, x, null, y % 3 == 0);
                        y++;
                    }
                }
                if (durdu || (i >= ana.size() && j >= yapraklar.size())) {
                    aksiyonCubugu(p, maden ? "maden-bitti" : "agac-bitti", "%sayi%", String.valueOf(kirilan));
                    if (!maden) p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                    if (sonra != null && !durdu) Bukkit.getScheduler().runTaskLater(TekVurus.this, sonra, 10L);
                    bitir();
                }
            }

            void bitir() {
                calisiyor.remove(id);
                cancel();
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /** Koruma eklentileri (WorldGuard, GriefPrevention...) izin veriyor mu? */
    private boolean izinVar(Player p, Block x) {
        kendiOlayimiz = true;
        try {
            BlockBreakEvent olay = new BlockBreakEvent(x, p);
            Bukkit.getPluginManager().callEvent(olay);
            return !olay.isCancelled();
        } finally {
            kendiOlayimiz = false;
        }
    }

    private void blokKir(Player p, Block x, ItemStack alet, boolean sesli) {
        BlockData veri = x.getBlockData();
        Location orta = x.getLocation().add(0.5, 0.5, 0.5);
        World w = x.getWorld();
        w.spawnParticle(BLOK, orta, 14, 0.3, 0.3, 0.3, 0, veri);
        if (sesli) {
            w.playSound(orta, veri.getSoundGroup().getBreakSound(), 0.7f,
                    0.85f + ThreadLocalRandom.current().nextFloat() * 0.3f);
        }
        if (p.getGameMode() == GameMode.CREATIVE) x.setType(Material.AIR);
        else if (alet == null) x.breakNaturally();
        else x.breakNaturally(alet);
        p.setExhaustion(Math.min(40f, p.getExhaustion() + 0.005f));
    }

    private boolean aletBitmekUzere(Player p, ItemStack el) {
        if (!getConfig().getBoolean("aleti-koru", true) || p.getGameMode() == GameMode.CREATIVE) return false;
        int max = el.getType().getMaxDurability();
        return max > 0 && el.getItemMeta() instanceof Damageable d && d.getDamage() >= max - 2;
    }

    private void hasarVer(Player p, ItemStack el) {
        if (!getConfig().getBoolean("dayaniklilik", true) || p.getGameMode() == GameMode.CREATIVE) return;
        if (el.getType().getMaxDurability() <= 0) return;
        ItemMeta meta = el.getItemMeta();
        if (!(meta instanceof Damageable d) || meta.isUnbreakable()) return;
        int seviye = KIRILMAZ == null ? 0 : el.getEnchantmentLevel(KIRILMAZ);
        if (ThreadLocalRandom.current().nextInt(seviye + 1) != 0) return; // Kırılmaz büyüsü
        d.setDamage(d.getDamage() + 1);
        el.setItemMeta(meta);
        if (d.getDamage() >= el.getType().getMaxDurability()) {
            p.getInventory().setItemInMainHand(null);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
        }
    }

    // ------------------------------------------------------------------ komut

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length > 0 && (args[0].equalsIgnoreCase("yenile") || args[0].equalsIgnoreCase("reload"))) {
            if (!s.hasPermission("tekvurus.yonetici")) { mesaj(s, "yetki-yok"); return true; }
            ayarlariYukle();
            mesaj(s, "yenilendi");
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("/tekvurus yenile"); return true; }
        if (kapali.remove(p.getUniqueId())) mesaj(p, "acildi");
        else { kapali.add(p.getUniqueId()); mesaj(p, "kapandi"); }
        YamlConfiguration y = new YamlConfiguration();
        y.set("oyuncular", kapali.stream().map(UUID::toString).toList());
        try { y.save(kapaliDosyasi); } catch (IOException ex) { getLogger().warning("kapali.yml kaydedilemedi: " + ex.getMessage()); }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length == 1 && s.hasPermission("tekvurus.yonetici") && "yenile".startsWith(args[0].toLowerCase())) {
            return List.of("yenile");
        }
        return List.of();
    }
}
