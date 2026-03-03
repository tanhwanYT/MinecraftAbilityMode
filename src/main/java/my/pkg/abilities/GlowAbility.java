package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GlowAbility implements Ability {

    private static final int FUSE_TICKS = 20 * 3;          // 3초 후 폭발
    private static final int INVIS_TICKS = 20 * 6;         // 6초 투명
    private static final int BLIND_TICKS = 20 * 4;         // 4초 눈뽕
    private static final double RANGE = 10.0;              // 섬광 범위

    // 갑옷 복구용 저장소
    private static final Map<UUID, ItemStack[]> savedArmor = new ConcurrentHashMap<>();

    @Override public String id() { return "glow"; }
    @Override public String name() { return "라이징스타"; }
    @Override public int cooldownSeconds() { return 30; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("라이징스타 : 우클릭 시 3초 후 섬광! 주변 플레이어 눈뽕 + 본인은 6초간 (갑옷까지) 투명.");
        player.sendMessage("§7※ 갑옷 투명은 6초간 갑옷을 잠깐 벗기는 방식(방어력도 감소)으로 구현됨.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        restoreArmor(player);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        JavaPlugin plugin = system.getPlugin();

        // "준비" 느낌 사운드 (이건 상대도 들을 수 있음. 숨기고 싶으면 player.playSound로 바꿔)
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);

        // 1) 3초 퓨즈 동안: 전조(현재는 월드에 뿌려서 상대도 볼 수 있음)
        // 상대가 몰랐으면 좋겠으면 player.spawnParticle / player.playSound 로 바꿔야 함!
        for (int i = 0; i < FUSE_TICKS; i += 5) {
            int delay = i;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || player.isDead()) return;

                Location c = player.getLocation().add(0, 1.0, 0);
                player.spawnParticle(Particle.END_ROD, c, 6, 0.35, 0.35, 0.35, 0.01);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.25f, 1.8f);
            }, delay);
        }

        // 2) 3초 후: 섬광 폭발 + (여기서부터) 투명 6초
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || player.isDead()) return;

            Location center = player.getLocation().add(0, 1.0, 0);
            World w = player.getWorld();

// 섬광 사운드
            w.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.3f);
            w.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.6f, 1.8f);

// "번쩍" 대체: 폭발 1회 + 하얀 연무 + 반짝
            w.spawnParticle(Particle.EXPLOSION, center, 1); // 번쩍 느낌
            w.spawnParticle(Particle.CLOUD, center, 80, 0.9, 0.6, 0.9, 0.0);
            w.spawnParticle(Particle.END_ROD, center, 60, 0.8, 0.8, 0.8, 0.02);
            w.spawnParticle(Particle.CRIT, center, 120, 0.9, 0.9, 0.9, 0.12);

            // ✅ 여기서 투명 + 갑옷 제거 시작 (3초 뒤에 적용)
            applyArmorInvis(player);
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, INVIS_TICKS, 0, true, false, true));

            // ✅ 6초 뒤 갑옷 복구
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                restoreArmor(player);
            }, INVIS_TICKS + 1L);

        }, FUSE_TICKS);

        return true;
    }

    private void safeSpawn(JavaPlugin plugin, World w, Particle particle,
                           Location loc, int count,
                           double ox, double oy, double oz, double extra) {
        try {
            w.spawnParticle(particle, loc, count, ox, oy, oz, extra);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("[GlowAbility] spawnParticle failed: "
                    + particle + " -> " + ex.getMessage());
            throw ex;
        }
    }

    // ===== 갑옷 “투명” 처리(임시 벗김) =====

    private void applyArmorInvis(Player player) {
        UUID id = player.getUniqueId();

        // 이미 저장돼 있으면(연타/중복) 덮어쓰기 방지
        if (!savedArmor.containsKey(id)) {
            savedArmor.put(id, player.getInventory().getArmorContents());
        }

        // 갑옷 제거(시각적으로 완전 투명)
        player.getInventory().setArmorContents(new ItemStack[]{null, null, null, null});
        player.updateInventory();
    }

    private void restoreArmor(Player player) {
        UUID id = player.getUniqueId();
        ItemStack[] armor = savedArmor.remove(id);
        if (armor == null) return;

        player.getInventory().setArmorContents(armor);
        player.updateInventory();
    }
}