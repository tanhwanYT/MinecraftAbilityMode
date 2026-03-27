package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BackAttackerAbility implements Ability {

    // ===== 밸런스 설정 =====
    private static final double BONUS_DAMAGE = 2.0;      // 뒤 공격 추가 대미지
    private static final double BACK_THRESHOLD = -0.5;   // 뒤 판정 기준(dot)
    private static final int DASH_TICKS = 5;             // 돌진 지속 틱
    private static final double DASH_SPEED = 1.0;       // 돌진 속도
    private static final double DASH_Y = 0.04;           // 돌진 시 약간 뜨는 값

    // ===== 상태 =====
    private final JavaPlugin plugin;
    private final Set<UUID> dashing = ConcurrentHashMap.newKeySet();

    public BackAttackerAbility(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "backattacker";
    }

    @Override
    public String name() {
        return "백어택커";
    }

    @Override
    public int cooldownSeconds() {
        return 15;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("백어택커 : 상대의 뒤를 공격하면 추가 피해를 입힙니다. 능력 사용시 바라보는 방향으로 돌진합니다.");
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target.isDead()) return;

        if (isBehindTarget(attacker, target)) {
            event.setDamage(event.getDamage() + BONUS_DAMAGE);

            World w = attacker.getWorld();
            Location hitLoc = target.getLocation().clone().add(0, 1.0, 0);

            w.spawnParticle(Particle.CRIT, hitLoc, 12, 0.25, 0.35, 0.25, 0.05);
            w.playSound(hitLoc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.1f);

            attacker.sendActionBar("§6[백어택커] §e뒤를 찔러 추가 피해!");
        }
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();

        if (dashing.contains(id)) {
            player.sendMessage("§c[백어택커] 이미 돌진 중입니다!");
            return false;
        }

        dashing.add(id);

        World w = player.getWorld();
        w.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.5f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    dashing.remove(id);
                    cancel();
                    return;
                }

                Vector dir = player.getLocation().getDirection().setY(0).normalize();
                if (dir.lengthSquared() < 0.0001) {
                    dashing.remove(id);
                    cancel();
                    return;
                }

                Vector velocity = dir.multiply(DASH_SPEED);
                velocity.setY(DASH_Y);
                player.setVelocity(velocity);

                Location loc = player.getLocation().clone().add(0, 1.0, 0);
                w.spawnParticle(Particle.CLOUD, loc, 6, 0.2, 0.1, 0.2, 0.02);
                w.spawnParticle(Particle.SWEEP_ATTACK, loc, 1, 0, 0, 0, 0);

                t++;
                if (t >= DASH_TICKS) {
                    dashing.remove(id);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        return true;
    }

    private boolean isBehindTarget(Player attacker, LivingEntity target) {
        Vector targetForward = target.getLocation().getDirection().setY(0).normalize();

        Vector targetToAttacker = attacker.getLocation().toVector()
                .subtract(target.getLocation().toVector())
                .setY(0);

        if (targetToAttacker.lengthSquared() < 0.0001) return false;
        targetToAttacker.normalize();

        double dot = targetForward.dot(targetToAttacker);

        // dot이 -1에 가까울수록 완전 뒤쪽
        return dot <= BACK_THRESHOLD;
    }
}