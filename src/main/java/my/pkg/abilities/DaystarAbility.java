package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class DaystarAbility implements Ability, Listener {

    private static final int COOLDOWN = 25;
    private static final int DURATION = 20 * 5;

    private final Set<UUID> marked = new HashSet<>();

    @Override
    public String id() {
        return "daystar";
    }

    @Override
    public String name() {
        return "데이스타";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN;
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        Player target = getTarget(player);

        if (target == null) {
            player.sendMessage("§c대상이 없습니다.");
            return false;
        }

        UUID tid = target.getUniqueId();
        marked.add(tid);

        player.sendMessage("§6[데이스타] §f태양의 표식을 부여했습니다.");

        // 신속 버프
        target.addPotionEffect(new PotionEffect(
                PotionEffectType.SPEED,
                DURATION,
                2,
                false,
                false
        ));

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!target.isOnline() || target.isDead()) {
                    marked.remove(tid);
                    cancel();
                    return;
                }

                Location fireLoc = target.getLocation().getBlock().getLocation();
                Material type = fireLoc.getBlock().getType();

                if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
                    fireLoc.getBlock().setType(Material.FIRE);
                }

                target.getWorld().spawnParticle(
                        Particle.FLAME,
                        target.getLocation(),
                        10, 0.3, 0.1, 0.3, 0.01
                );

                tick++;

                if (tick >= DURATION) {
                    explode(player,target);
                    marked.remove(tid);
                    cancel();
                }
            }
        }.runTaskTimer(system.getPlugin(), 0L, 1L);

        return true;
    }

    private void explode(Player caster, Player target) {
        Location loc = target.getLocation();

        World world = loc.getWorld();
        if (world == null) return;

        // 태양 폭발 연출
        world.spawnParticle(Particle.EXPLOSION, loc, 2);
        world.spawnParticle(Particle.FLAME, loc, 50, 0.5, 0.5, 0.5, 0.02);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);

        // 범위 피해
        for (Player p : world.getPlayers()) {
            if (p.equals(target)) continue;
            if (p.equals(caster)) continue; // 시전자는 안 맞음

            if (p.getLocation().distance(loc) <= 3) {
                p.damage(5.0, caster);
            }
        }

        // 대상 직접 피해
        if (!target.equals(caster)) {
            target.damage(4.0, caster);
        }
    }

    @EventHandler
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!marked.contains(e.getPlayer().getUniqueId())) return;

        Material bucket = e.getBucket();
        Material handType = e.getPlayer().getInventory().getItemInMainHand().getType();
        Material offType = e.getPlayer().getInventory().getItemInOffHand().getType();

        if (bucket == Material.WATER_BUCKET ||
                handType == Material.WATER_BUCKET ||
                offType == Material.WATER_BUCKET) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§c물이 태양열에 증발해버렸다...");
        }
    }

    // 대상 탐지 (간단 레이캐스트)
    private Player getTarget(Player player) {
        for (Entity e : player.getNearbyEntities(10, 10, 10)) {
            if (e instanceof Player p) {
                if (p.equals(player)) continue;

                if (player.hasLineOfSight(p)) {
                    return p;
                }
            }
        }
        return null;
    }
}