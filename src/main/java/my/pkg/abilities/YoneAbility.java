package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class YoneAbility implements Ability, Listener {

    private static final int COOLDOWN = 40;

    private static final double RANGE = 12.0;
    private static final double HIT_WIDTH = 1.8;
    private static final double DAMAGE = 9.0;

    private static final long CAST_DELAY_TICKS = 13L;
    private static final double BEHIND_DISTANCE = 1.4;

    private static boolean listenerRegistered = false;

    private final Map<UUID, CastLock> casting = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "yone";
    }

    @Override
    public String name() {
        return "요네";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, system.getPlugin());
            listenerRegistered = true;
        }

        player.sendMessage("§b요네 §7: 0.65초 후 전방의 모든 적을 베고 마지막 적 뒤로 이동합니다.");
        player.sendMessage("§7시전 중 X/Z와 시점이 고정되지만 Y축 이동은 허용됩니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        casting.remove(player.getUniqueId());
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        if (player.getGameMode() != GameMode.SURVIVAL) {
            player.sendMessage("§c[요네] 서바이벌 상태에서만 사용할 수 있습니다.");
            return false;
        }

        UUID uuid = player.getUniqueId();

        Location castLoc = player.getLocation().clone();
        Vector castDir = castLoc.getDirection().clone().setY(0).normalize();

        casting.put(uuid, new CastLock(castLoc, castLoc.getYaw(), castLoc.getPitch()));
        player.setVelocity(new Vector(0, 0, 0));

        player.sendActionBar("§b[요네] 운명봉인 시전 중...");
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 1f, 1.3f);

        startCastEffect(system, player, castLoc, castDir);

        Bukkit.getScheduler().runTaskLater(system.getPlugin(), () -> {
            casting.remove(uuid);

            if (!player.isOnline() || player.isDead()) return;
            if (player.getGameMode() != GameMode.SURVIVAL) return;

            executeSlash(player, castLoc, castDir);
        }, CAST_DELAY_TICKS);

        return true;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        CastLock lockData = casting.get(player.getUniqueId());
        if (lockData == null) return;
        if (event.getTo() == null) return;

        Location to = event.getTo().clone();

        // Y는 허용, X/Z와 시점만 고정
        to.setX(lockData.location().getX());
        to.setZ(lockData.location().getZ());
        to.setYaw(lockData.yaw());
        to.setPitch(lockData.pitch());

        event.setTo(to);
    }

    private void executeSlash(Player player, Location origin, Vector dir) {
        World world = player.getWorld();

        List<HitTarget> hits = findTargets(player, origin, dir);

        showSlashEffect(world, origin, dir);

        Player lastTarget = null;
        double farthest = -1;

        for (HitTarget hit : hits) {
            Player target = hit.player();

            if (hit.distance() > farthest) {
                farthest = hit.distance();
                lastTarget = target;
            }

            target.damage(DAMAGE, player);

            Vector pull = player.getLocation().toVector()
                    .subtract(target.getLocation().toVector())
                    .normalize()
                    .multiply(1.25);

            pull.setY(0.95);
            target.setVelocity(pull);

            target.getWorld().spawnParticle(
                    Particle.SWEEP_ATTACK,
                    target.getLocation().add(0, 1.0, 0),
                    4,
                    0.35, 0.45, 0.35,
                    0.01
            );

            target.getWorld().spawnParticle(
                    Particle.SOUL_FIRE_FLAME,
                    target.getLocation().add(0, 1.0, 0),
                    18,
                    0.35, 0.55, 0.35,
                    0.03
            );
        }

        Location destination;

        if (lastTarget != null) {
            destination = lastTarget.getLocation().clone().add(dir.clone().multiply(BEHIND_DISTANCE));
            player.sendActionBar("§b[요네] 운명봉인 적중!");
        } else {
            destination = origin.clone().add(dir.clone().multiply(RANGE));
            player.sendActionBar("§b[요네] 적중한 적이 없어 전방으로 이동했습니다.");
        }

        destination.setYaw(origin.getYaw());
        destination.setPitch(origin.getPitch());
        destination = safeLocation(destination);

        player.teleport(destination);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.35f);

        world.spawnParticle(
                Particle.REVERSE_PORTAL,
                player.getLocation().add(0, 1.0, 0),
                50,
                0.45, 0.8, 0.45,
                0.08
        );
    }

    private List<HitTarget> findTargets(Player caster, Location origin, Vector dir) {
        List<HitTarget> result = new ArrayList<>();

        for (Player target : caster.getWorld().getPlayers()) {
            if (target.equals(caster)) continue;
            if (target.isDead()) continue;
            if (target.getGameMode() != GameMode.SURVIVAL) continue;

            Vector toTarget = target.getLocation().toVector().subtract(origin.toVector());
            double forward = toTarget.dot(dir);

            if (forward < 0 || forward > RANGE) continue;

            Vector closestPoint = origin.toVector().add(dir.clone().multiply(forward));
            double sideDistance = target.getLocation().toVector().distance(closestPoint);

            if (sideDistance > HIT_WIDTH) continue;

            result.add(new HitTarget(target, forward));
        }

        result.sort(Comparator.comparingDouble(HitTarget::distance));
        return result;
    }

    private void startCastEffect(AbilitySystem system, Player player, Location origin, Vector dir) {
        final int[] tick = {0};

        Bukkit.getScheduler().runTaskTimer(system.getPlugin(), task -> {
            if (!player.isOnline() || player.isDead()) {
                task.cancel();
                return;
            }

            if (tick[0] >= CAST_DELAY_TICKS) {
                task.cancel();
                return;
            }

            World world = player.getWorld();

            for (double d = 0; d <= RANGE; d += 0.75) {
                Location point = origin.clone().add(dir.clone().multiply(d)).add(0, 0.15, 0);

                world.spawnParticle(
                        Particle.DUST,
                        point,
                        2,
                        0.04, 0.04, 0.04,
                        new Particle.DustOptions(Color.AQUA, 1.5f)
                );

                if (tick[0] % 3 == 0) {
                    world.spawnParticle(
                            Particle.SOUL_FIRE_FLAME,
                            point.clone().add(0, 0.25, 0),
                            1,
                            0.03, 0.03, 0.03,
                            0.01
                    );
                }
            }

            world.spawnParticle(
                    Particle.SOUL,
                    player.getLocation().add(0, 1.0, 0),
                    8,
                    0.35, 0.6, 0.35,
                    0.03
            );

            tick[0]++;
        }, 0L, 1L);
    }

    private void showSlashEffect(World world, Location origin, Vector dir) {
        for (double d = 0; d <= RANGE; d += 0.35) {
            Location center = origin.clone().add(dir.clone().multiply(d)).add(0, 1.0, 0);

            world.spawnParticle(
                    Particle.SWEEP_ATTACK,
                    center,
                    1,
                    0.04, 0.04, 0.04,
                    0.01
            );

            world.spawnParticle(
                    Particle.CRIT,
                    center,
                    5,
                    0.18, 0.18, 0.18,
                    0.06
            );

            world.spawnParticle(
                    Particle.SOUL_FIRE_FLAME,
                    center,
                    3,
                    0.12, 0.12, 0.12,
                    0.03
            );

            if (((int) (d * 10)) % 7 == 0) {
                world.spawnParticle(
                        Particle.END_ROD,
                        center,
                        4,
                        0.12, 0.12, 0.12,
                        0.03
                );
            }
        }

        world.playSound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.65f);
        world.playSound(origin, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.7f);
        world.playSound(origin, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.9f, 1.5f);
    }

    private Location safeLocation(Location loc) {
        Location safe = loc.clone();

        if (safe.getBlock().getType().isSolid()) {
            safe.add(0, 1, 0);
        }

        if (safe.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
            safe.add(0, 1, 0);
        }

        return safe;
    }

    private record HitTarget(Player player, double distance) {
    }

    private record CastLock(Location location, float yaw, float pitch) {
    }
}