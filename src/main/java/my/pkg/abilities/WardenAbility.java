package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.block.Block;
import org.bukkit.event.player.PlayerMoveEvent;

public class WardenAbility implements Ability, Listener {

    private static final int COOLDOWN = 20;
    private static final double RANGE = 12;
    private static final int DELAY_TICKS = 20 * 3;

    @Override
    public String id() {
        return "warden";
    }

    @Override
    public String name() {
        return "워든";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN;
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {

        World world = player.getWorld();

        // 1. 주변 어둠 부여
        for (Entity e : player.getNearbyEntities(RANGE, RANGE, RANGE)) {
            if (e instanceof Player target && !target.equals(player)) {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.DARKNESS,
                        DELAY_TICKS,
                        0,
                        false,
                        false
                ));
            }
        }

        player.sendMessage("§8[워든] §f주변이 어둠에 잠깁니다...");
        world.playSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 0.8f);

        // 2. 3초 후 파동 발사
        new BukkitRunnable() {
            @Override
            public void run() {
                fireWave(player);
            }
        }.runTaskLater(system.getPlugin(), DELAY_TICKS);

        return true;
    }

    @Override
    public void onMove(AbilitySystem system, PlayerMoveEvent event) {
        Player player = event.getPlayer();

        Block under = player.getLocation().subtract(0, 1, 0).getBlock();

        if (under.getType().isAir()) return;
        if (under.getType() == Material.BEDROCK) return;
        if (under.getType() == Material.BARRIER) return;
        if (under.getType() == Material.SCULK) return;

        under.setType(Material.SCULK, false);
    }

    private void fireWave(Player player) {
        World world = player.getWorld();

        player.sendMessage("§8[워든] §f파동 발사!");
        world.playSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1f);

        Location start = player.getEyeLocation();
        Vector dir = start.getDirection().normalize();

        double length = 15;

        for (double i = 0; i < length; i += 0.5) {
            Location point = start.clone().add(dir.clone().multiply(i));

            world.spawnParticle(
                    Particle.SONIC_BOOM,
                    point,
                    1
            );

            for (Entity e : world.getNearbyEntities(point, 1, 1, 1)) {
                if (e instanceof Player target && !target.equals(player)) {

                    target.damage(10.0, player);

                    Vector knock = dir.clone().multiply(1.2);
                    knock.setY(0.3);
                    target.setVelocity(knock);

                    return; // 첫 대상만 맞추고 끝
                }
            }
        }
    }
}