package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;

public class TanhwanAbility implements Ability {

    private static final int COOLDOWN = 26;

    private static final double RADIUS = 5.0;
    private static final double MIN_DAMAGE = 8.0;
    private static final double MAX_DAMAGE = 24.0;

    private static final double SELF_DAMAGE_RATIO = 0.25;
    private static final double LAUNCH_POWER = 2.0;
    private static final double LAUNCH_Y = 0.7;

    @Override
    public String id() {
        return "tanhwan";
    }

    @Override
    public String name() {
        return "탄환";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("§c탄환 §7: 능력 사용 시 전방으로 발사된 뒤 자폭합니다.");
        player.sendMessage("§7- 체력이 낮을수록 폭발 피해가 증가합니다.");
        player.sendMessage("§7- 자폭 시 현재 체력의 25%를 피해로 받습니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        if (player.getGameMode() != GameMode.SURVIVAL) {
            player.sendMessage("§c[탄환] 서바이벌 상태에서만 사용할 수 있습니다.");
            return false;
        }

        double damage = calculateExplosionDamage(player);

        player.sendMessage("§c[탄환] 발사!");
        player.sendActionBar("§c탄환 발사! §7예상 폭발 피해: §e" + String.format("%.1f", damage));

        Vector dir = player.getLocation().getDirection().normalize();
        player.setVelocity(dir.multiply(LAUNCH_POWER).setY(LAUNCH_Y));

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.2f, 1.0f);

        startLaunchEffect(system, player);

        Bukkit.getScheduler().runTaskLater(system.getPlugin(), () -> {
            if (!player.isOnline() || player.isDead()) return;
            explode(player, damage);
        }, 20L);

        return true;
    }

    private double calculateExplosionDamage(Player player) {
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;

        double healthRatio = player.getHealth() / maxHealth;

        // 체력 100%면 MIN_DAMAGE, 체력 0%에 가까우면 MAX_DAMAGE
        return MIN_DAMAGE + (MAX_DAMAGE - MIN_DAMAGE) * (1.0 - healthRatio);
    }

    private void explode(Player player, double damage) {
        Location loc = player.getLocation();
        World world = player.getWorld();

        world.spawnParticle(Particle.EXPLOSION, loc, 3, 0.5, 0.5, 0.5, 0.05);
        world.spawnParticle(Particle.FLAME, loc, 60, 1.0, 1.0, 1.0, 0.08);
        world.spawnParticle(Particle.SMOKE, loc, 80, 1.2, 1.2, 1.2, 0.05);
        world.spawnParticle(Particle.FIREWORK, loc, 50, 1.0, 1.0, 1.0, 0.12);

        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.8f);
        world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.2f);

        for (Player target : world.getPlayers()) {
            if (target.getGameMode() != GameMode.SURVIVAL) continue;

            double distance = target.getLocation().distance(loc);
            if (distance > RADIUS) continue;

            if (target.equals(player)) continue;

            double finalDamage = Math.max(1.0, damage * (1.0 - distance / RADIUS));
            target.damage(finalDamage, player);

            Vector knockback = target.getLocation().toVector()
                    .subtract(loc.toVector())
                    .normalize()
                    .multiply(1.2);

            knockback.setY(0.45);
            target.setVelocity(knockback);
        }

        damageSelf(player);
    }

    private void damageSelf(Player player) {
        if (!player.isOnline() || player.isDead()) return;

        double selfDamage = player.getHealth() * SELF_DAMAGE_RATIO;

        EntityDamageEvent event = new EntityDamageEvent(
                player,
                EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
                selfDamage
        );

        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) return;

        double finalDamage = event.getFinalDamage();
        player.setHealth(Math.max(0.1, player.getHealth() - finalDamage));

        player.sendMessage("§c[탄환] 자폭 반동으로 현재 체력의 25%를 잃었습니다.");
    }

    private void startLaunchEffect(AbilitySystem system, Player player) {
        final int[] tick = {0};

        Bukkit.getScheduler().runTaskTimer(system.getPlugin(), task -> {
            if (!player.isOnline() || player.isDead()) {
                task.cancel();
                return;
            }

            if (tick[0] >= 20) {
                task.cancel();
                return;
            }

            Location loc = player.getLocation().add(0, 0.8, 0);

            player.getWorld().spawnParticle(
                    Particle.FLAME,
                    loc,
                    10,
                    0.25,
                    0.25,
                    0.25,
                    0.03
            );

            player.getWorld().spawnParticle(
                    Particle.CLOUD,
                    loc,
                    8,
                    0.2,
                    0.2,
                    0.2,
                    0.02
            );

            player.getWorld().spawnParticle(
                    Particle.FIREWORK,
                    loc,
                    5,
                    0.2,
                    0.2,
                    0.2,
                    0.04
            );

            tick[0]++;
        }, 0L, 1L);
    }
}