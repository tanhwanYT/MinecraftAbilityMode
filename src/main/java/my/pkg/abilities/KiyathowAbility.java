package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Particle;
import org.bukkit.potion.PotionEffectType;

public class KiyathowAbility implements Ability {

    @Override
    public String id() {
        return "kiyathow";
    }

    @Override
    public String name() {
        return "끼얏호우";
    }

    @Override
    public int cooldownSeconds() {
        return 30; // 쿨타임은 적당히 길게
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        // 사용법 안내
        player.sendMessage("§a끼얏호우 §7: 능력사용시 폭죽이 터집니다. 플레이어는 8초동안 이동속도가 빨라지고, 추가체력과 재생버프, 성급함, 점프강화를 얻습니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        Location loc = player.getLocation();

        // 🎆 폭죽 소환
        Firework firework = player.getWorld().spawn(loc, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(
                FireworkEffect.builder()
                        .withColor(Color.RED, Color.YELLOW, Color.ORANGE)
                        .withFade(Color.WHITE)
                        .with(FireworkEffect.Type.BALL_LARGE)
                        .trail(true)
                        .flicker(true)
                        .build()
        );
        meta.setPower(0); // 날아가지 않게
        firework.setFireworkMeta(meta);

        // 즉시 폭발
        system.getPlugin().getServer().getScheduler().runTaskLater(
                system.getPlugin(),
                firework::detonate,
                1L
        );

        // ⏱ 8초 버프 (160틱)
        int duration = 8 * 20;

        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, duration, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, duration, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, duration, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, duration, 1, false, false));
        // ↑ 노란 하트 4칸

        int period = 5; // 5틱 = 0.25초
        system.getPlugin().getServer().getScheduler().runTaskTimer(system.getPlugin(), new Runnable() {
            int lived = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    // 플레이어 나가면 중단
                    throw new RuntimeException("cancel"); // 아래에 더 안전한 버전 줄게
                }
            }
        }, 0L, period);

        new org.bukkit.scheduler.BukkitRunnable() {
            int lived = 0;
            final int total = duration; // 160틱

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                if (lived >= total) {
                    cancel();
                    return;
                }

                // 플레이어 주변 하트 (움직이면서 남는 느낌)
                Location pLoc = player.getLocation().add(0, 1.0, 0);
                player.getWorld().spawnParticle(
                        org.bukkit.Particle.HEART,
                        pLoc,
                        2,          // 개수
                        0.35, 0.35, 0.35, // 퍼짐
                        0.0
                );

                lived += 5; // period가 5틱이라
            }
        }.runTaskTimer(system.getPlugin(), 0L, 5L);

        return true;
    }
}
