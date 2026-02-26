package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public class ViperAbility implements Ability {

    @Override
    public String id() {
        return "viper";
    }

    @Override
    public String name() {
        return "바이퍼";
    }

    @Override
    public int cooldownSeconds() {
        return 50; // 원하는대로 조절
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        // 사용법 안내
        player.sendMessage("바이퍼 : 능력사용시 자신의 반경 9칸에 독가스지대를 생성합니다. 독가스 지대는 상대에게 슬로우와 독 피해를 입힙니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        JavaPlugin plugin = system.getPlugin();

        // 생성 위치 고정 (중요)
        Location center = player.getLocation().clone();
        double radius = 9.0;

        // 연출
        player.getWorld().playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.3f);

        int durationTicks = 10 * 20; // 10초 지속
        int periodTicks = 40;

        new BukkitRunnable() {
            int lived = 0;

            @Override
            public void run() {
                // 종료 조건
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                if (lived >= durationTicks) {
                    player.getWorld().playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.4f);
                    cancel();
                    return;
                }

                // 위쪽 퍼지는 독기
                player.getWorld().spawnParticle(
                        Particle.SPORE_BLOSSOM_AIR,
                        center.clone().add(0, 0.8, 0),
                        80,                    // 20 → 80 (4배 증가)
                        radius * 0.6,          // 퍼짐 범위 확대
                        0.8,
                        radius * 0.6,
                        0.02
                );

                // 중간층 가스층 추가
                player.getWorld().spawnParticle(
                        Particle.SPORE_BLOSSOM_AIR,
                        center.clone().add(0, 0.4, 0),
                        60,
                        radius * 0.5,
                        0.4,
                        radius * 0.5,
                        0.02
                );

                // 바닥 연막 강화
                Location ground = center.clone();
                ground.setY(center.getY() + 0.15);

                player.getWorld().spawnParticle(
                        Particle.CAMPFIRE_COSY_SMOKE,
                        ground,
                        25,                    // 6 → 25
                        radius * 0.7,
                        0.2,
                        radius * 0.7,
                        0.01
                );

                // 살짝 일반 연기 섞어서 더 뿌연 느낌
                player.getWorld().spawnParticle(
                        Particle.SMOKE,
                        ground.clone().add(0, 0.3, 0),
                        40,
                        radius * 0.6,
                        0.4,
                        radius * 0.6,
                        0.01
                );
                player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, ground, 6, radius * 0.5, 0.1, radius * 0.5, 0.01);

                // ✅ 반경 내 엔티티에게 슬로우 + 독 부여 (고정된 center 기준)
                for (Entity e : center.getWorld().getNearbyEntities(center, radius, 3, radius)) {
                    if (!(e instanceof LivingEntity target)) continue;
                    if (target.equals(player)) continue;

                    // 거리 원형 판정(정확한 원)
                    if (target.getLocation().distanceSquared(center) > radius * radius) continue;

                    // 효과 부여 (짧게 걸고 계속 갱신하는 방식)
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, true)); // 2초, Slowness II
                    target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 90, 1, false, true));   // 2초, Poison I
                }

                lived += periodTicks;
            }
        }.runTaskTimer(plugin, 0L, periodTicks);

        return true;
    }
}
