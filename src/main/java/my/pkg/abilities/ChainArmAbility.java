package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChainArmAbility implements Ability, Listener {
    private static final double RANGE = 14.0;
    private static final int CHAIN_TRAVEL_TICKS = 8;   // 사슬 날아가는 연출 시간
    private static final int FAIL_ROOT_TICKS = 20;     // 실패 시 1초 구속
    private static final int HIT_FREEZE_TICKS = 14;    // 성공 시 시전자 고정 시간
    private static final int PULL_TICKS = 10;          // 끌어오는 시간

    private static boolean listenerRegistered = false;

    // 위치+시야 고정
    private static final Map<UUID, FrozenData> frozenPlayers = new ConcurrentHashMap<>();
    // 진행 중 작업 관리
    private static final Map<UUID, BukkitTask> activeTasks = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "chainarm";
    }

    @Override
    public String name() {
        return "사슬팔";
    }

    @Override
    public int cooldownSeconds() {
        return 18;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("§7사슬팔 : 바라보는 방향으로 사슬을 발사합니다.");
        player.sendMessage("§7- 적중 시 대상을 내 앞으로 끌어옵니다.");
        player.sendMessage("§7- 사용 중에는 위치와 시야가 고정됩니다.");
        player.sendMessage("§7- 실패하면 잠깐 구속됩니다.");

        if (!listenerRegistered) {
            system.getPlugin().getServer().getPluginManager().registerEvents(this, system.getPlugin());
            listenerRegistered = true;
        }
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();

        BukkitTask task = activeTasks.remove(id);
        if (task != null) task.cancel();

        FrozenData data = frozenPlayers.remove(id);
        if (data != null && data.releaseTask != null) {
            data.releaseTask.cancel();
        }
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();

        if (activeTasks.containsKey(id)) {
            player.sendMessage("§c[사슬팔] 이미 사용 중입니다.");
            return false;
        }

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().clone().normalize();

        RayTraceResult entityResult = player.getWorld().rayTraceEntities(
                eye,
                dir,
                RANGE,
                entity -> entity instanceof LivingEntity && entity != player
        );

        RayTraceResult blockResult = player.getWorld().rayTraceBlocks(
                eye,
                dir,
                RANGE,
                FluidCollisionMode.NEVER,
                true
        );

        LivingEntity hitTarget = getHitLiving(entityResult);
        Block hitBlock = (blockResult != null) ? blockResult.getHitBlock() : null;

        double entityDist = Double.MAX_VALUE;
        double blockDist = Double.MAX_VALUE;

        if (hitTarget != null) {
            entityDist = eye.distance(hitTarget.getLocation().clone().add(0, hitTarget.getHeight() * 0.5, 0));
        }
        if (blockResult != null && blockResult.getHitPosition() != null) {
            blockDist = eye.toVector().distance(blockResult.getHitPosition());
        }

        boolean hitEntityFirst = hitTarget != null && entityDist <= blockDist;
        boolean hitBlockFirst = hitBlock != null && blockDist < entityDist;

        int freezeTicks = (hitEntityFirst || hitBlockFirst) ? HIT_FREEZE_TICKS : FAIL_ROOT_TICKS;
        freezePlayer(system, player, freezeTicks);

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.8f, 1.4f);

        BukkitTask task = new BukkitRunnable() {
            int tick = 0;

            final Location start = eye.clone();

            final Location end =
                    hitEntityFirst
                            ? hitTarget.getLocation().clone().add(0, hitTarget.getHeight() * 0.5, 0)
                            : hitBlockFirst
                            ? blockResult.getHitPosition().toLocation(player.getWorld())
                            : start.clone().add(dir.clone().multiply(RANGE));

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cleanup(player.getUniqueId());
                    cancel();
                    return;
                }

                tick++;

                double t = Math.min(1.0, (double) tick / CHAIN_TRAVEL_TICKS);
                Vector path = end.toVector().subtract(start.toVector()).multiply(t);
                Location point = start.clone().add(path);

                spawnChainLine(start, point);

                if (tick >= CHAIN_TRAVEL_TICKS) {
                    if (hitEntityFirst && hitTarget != null && hitTarget.isValid() && !hitTarget.isDead()) {
                        onHitEntity(system, player, hitTarget);
                    } else if (hitBlockFirst && hitBlock != null) {
                        onHitBlock(system, player, end);
                    } else {
                        onFail(player);
                    }

                    cleanup(player.getUniqueId());
                    cancel();
                }
            }
        }.runTaskTimer(system.getPlugin(), 0L, 1L);

        activeTasks.put(id, task);
        return true;
    }

    private void onHitEntity(AbilitySystem system, Player player, LivingEntity target) {
        World w = player.getWorld();

        w.playSound(target.getLocation(), Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.8f, 0.8f);
        w.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 0.7f);

        Location front = player.getLocation().clone()
                .add(player.getLocation().getDirection().setY(0).normalize().multiply(1.5));
        front.setY(player.getLocation().getY());

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || !target.isValid() || target.isDead()) {
                    cancel();
                    return;
                }

                ticks++;

                Location targetLoc = target.getLocation();
                Vector pull = front.clone().add(0, 0.2, 0).toVector().subtract(targetLoc.toVector());

                if (pull.lengthSquared() > 0.01) {
                    Vector velocity = pull.normalize().multiply(0.75);
                    velocity.setY(Math.max(0.12, Math.min(0.35, pull.getY() * 0.4)));
                    target.setVelocity(velocity);
                }

                w.spawnParticle(Particle.CRIT, target.getLocation().add(0, 1.0, 0), 6, 0.2, 0.2, 0.2, 0.01);

                if (ticks >= PULL_TICKS) {
                    Location finalLoc = front.clone();
                    finalLoc.setDirection(target.getLocation().getDirection());
                    target.teleport(finalLoc);
                    cancel();
                }
            }
        }.runTaskTimer(system.getPlugin(), 0L, 1L);

        player.sendMessage("§a[사슬팔] 대상을 끌어왔습니다!");
    }

    private void onHitBlock(AbilitySystem system, Player player, Location hookPoint) {
        World w = player.getWorld();

        w.playSound(hookPoint, Sound.BLOCK_CHAIN_HIT, 1.0f, 0.9f);
        w.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 1.1f);

        Location destination = hookPoint.clone().subtract(player.getLocation().getDirection().normalize().multiply(1.2));
        destination.setPitch(player.getLocation().getPitch());
        destination.setYaw(player.getLocation().getYaw());

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }

                ticks++;

                Vector pull = destination.toVector().subtract(player.getLocation().toVector());

                if (pull.lengthSquared() > 0.04) {
                    Vector velocity = pull.normalize().multiply(0.95);
                    velocity.setY(Math.max(0.15, Math.min(0.5, pull.getY() * 0.45)));
                    player.setVelocity(velocity);
                }

                player.getWorld().spawnParticle(
                        Particle.CRIT,
                        player.getLocation().add(0, 1.0, 0),
                        4, 0.15, 0.15, 0.15, 0.01
                );

                if (ticks >= PULL_TICKS) {
                    Location finalLoc = destination.clone();
                    player.teleport(finalLoc);
                    cancel();
                }
            }
        }.runTaskTimer(system.getPlugin(), 0L, 1L);

        player.sendMessage("§b[사슬팔] 사슬이 박힌 곳으로 이동했습니다!");
    }

    private void onFail(Player player) {
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_BREAK, 1.0f, 0.9f);
        player.sendMessage("§c[사슬팔] 그랩에 실패하여 잠시 구속됩니다.");
    }

    private void freezePlayer(AbilitySystem system, Player player, int ticks) {
        UUID id = player.getUniqueId();

        FrozenData old = frozenPlayers.remove(id);
        if (old != null && old.releaseTask != null) {
            old.releaseTask.cancel();
        }

        Location fixed = player.getLocation().clone();

        BukkitTask releaseTask = system.getPlugin().getServer().getScheduler().runTaskLater(system.getPlugin(), () -> {
            FrozenData data = frozenPlayers.remove(id);
            if (data != null && data.releaseTask != null) {
                data.releaseTask.cancel();
            }
        }, ticks);

        frozenPlayers.put(id, new FrozenData(fixed, releaseTask));
    }

    private void spawnChainLine(Location from, Location to) {
        World world = from.getWorld();
        if (world == null) return;

        Vector diff = to.toVector().subtract(from.toVector());
        double length = diff.length();
        if (length <= 0.001) return;

        Vector step = diff.normalize().multiply(0.35);
        Location current = from.clone();

        int count = Math.max(1, (int) (length / 0.35));
        for (int i = 0; i <= count; i++) {
            world.spawnParticle(Particle.CRIT, current, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.SMOKE, current, 1, 0.02, 0.02, 0.02, 0.0);
            current.add(step);
        }
    }

    private LivingEntity getHitLiving(RayTraceResult result) {
        if (result == null) return null;
        Entity hit = result.getHitEntity();
        if (hit instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    private void cleanup(UUID id) {
        BukkitTask task = activeTasks.remove(id);
        if (task != null) task.cancel();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        FrozenData data = frozenPlayers.get(event.getPlayer().getUniqueId());
        if (data == null) return;

        Location to = event.getTo();
        if (to == null) return;

        Location fixed = data.fixedLocation;

        to.setX(fixed.getX());
        to.setZ(fixed.getZ());

        to.setYaw(fixed.getYaw());
        to.setPitch(fixed.getPitch());

        event.setTo(to);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();

        BukkitTask task = activeTasks.remove(id);
        if (task != null) task.cancel();

        FrozenData data = frozenPlayers.remove(id);
        if (data != null && data.releaseTask != null) {
            data.releaseTask.cancel();
        }
    }

    private static class FrozenData {
        private final Location fixedLocation;
        private final BukkitTask releaseTask;

        private FrozenData(Location fixedLocation, BukkitTask releaseTask) {
            this.fixedLocation = fixedLocation;
            this.releaseTask = releaseTask;
        }
    }
}