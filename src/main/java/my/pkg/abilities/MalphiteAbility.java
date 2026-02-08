package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public class MalphiteAbility implements Ability {
    @Override
    public String id() {
        return "malphite";
    }

    @Override
    public String name() {
        return "말파이트";
    }

    @Override
    public int cooldownSeconds() {
        return 12;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        // 사용법 안내
        player.sendMessage("말파이트 : 네더의 별을 우클릭시 1초동안 매우 빠른 스피드를 얻습니다. 1초후 주변 엔티티를 높이 띄우며 대미지를 줍니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        // 1초간 이동 속도 상승 (돌진)
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SPEED,
                20, // 1초
                3,
                false,
                false
        ));
        player.getWorld().playSound(
                player.getLocation(),
                Sound.ENTITY_ENDER_DRAGON_FLAP,
                0.8f,
                1.2f
        );

        // 1초 후 충돌 판정
        system.getPlugin().getServer().getScheduler().runTaskLater(system.getPlugin(), () -> {
            if (!player.isOnline()) return;

            Location center = player.getLocation();

            // 폭발 이펙트
            player.getWorld().spawnParticle(
                    Particle.EXPLOSION,
                    center,
                    1, 0, 0, 0, 0
            );
            player.getWorld().playSound(
                    center,
                    Sound.ENTITY_GENERIC_EXPLODE,
                    0.8f,
                    1.0f
            );

            // 반경 2칸 내 모든 LivingEntity 공격
            for (Entity entity : player.getWorld().getNearbyEntities(center, 2, 2, 2)) {
                if (!(entity instanceof LivingEntity target)) continue;
                if (target.equals(player)) continue;

                // 데미지
                target.damage(4.0, player);

                // 에어본 (위로 띄우기)
                Vector v = target.getVelocity();
                v.setY(1.1); // 띄우는 힘
                target.setVelocity(v);
            }
        }, 20L);

        return true;
    }
}
