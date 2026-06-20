package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpaceMageAbility implements Ability, Listener {

    private static final int COOLDOWN = 30;

    private static final double SKY_HEIGHT = 18.0;
    private static final double MAX_TELEPORT_DISTANCE = 28.0;
    private static final long LIMIT_TICKS = 20L * 5;

    private static final double ABSORPTION_HEARTS = 8.0; // 노란 체력 4칸
    private static final int SPEED_DURATION = 20 * 3; // 3초
    private static final int SPEED_LEVEL = 1; // 신속 II

    private static boolean listenerRegistered = false;

    private final Map<UUID, SpaceState> states = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "spacemage";
    }

    @Override
    public String name() {
        return "공간술사";
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

        player.sendMessage("§d[공간술사] §f능력 사용 시 상공의 공간으로 이동해 아래를 내려다봅니다.");
        player.sendMessage("§75초 안에 재사용하면 바라보는 위치로 재등장합니다.");
        player.sendMessage("§7재등장 시 폭죽, 노란 체력, 신속 II를 얻습니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        endSpaceMode(player, false);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        if (player.getGameMode() != GameMode.SURVIVAL) {
            player.sendMessage("§c[공간술사] 서바이벌 상태에서만 사용할 수 있습니다.");
            return false;
        }

        UUID uuid = player.getUniqueId();

        if (states.containsKey(uuid)) {
            return reappear(system, player); // 여기서만 true 반환해서 쿨타임 적용
        }

        enterSpaceMode(system, player);
        return false; // 첫 사용은 쿨타임 소모 X
    }

    private void enterSpaceMode(AbilitySystem system, Player player) {
        Location original = player.getLocation().clone();
        Location sky = original.clone().add(0, SKY_HEIGHT, 0);

        sky.setPitch(80f);

        SpaceState state = new SpaceState(original, sky);
        states.put(player.getUniqueId(), state);

        player.teleport(sky);
        player.setVelocity(new Vector(0, 0, 0));
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);

        player.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                20 * 6,
                0,
                false,
                false,
                false
        ));

        player.getWorld().playSound(original, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.4f);
        player.getWorld().spawnParticle(
                Particle.REVERSE_PORTAL,
                original.add(0, 1, 0),
                50,
                0.4, 0.8, 0.4,
                0.08
        );

        startPreviewTask(system, player);
        startCountdownTask(system, player);

        Bukkit.getScheduler().runTaskLater(system.getPlugin(), () -> {
            SpaceState current = states.get(player.getUniqueId());
            if (current == null) return;

            player.sendMessage("§c[공간술사] 제한 시간이 끝나 원래 위치로 돌아왔습니다.");
            player.teleport(current.originalLocation());
            endSpaceMode(player, false);
        }, LIMIT_TICKS);
    }

    private void startCountdownTask(AbilitySystem system, Player player) {
        final int[] secondsLeft = {5};

        Bukkit.getScheduler().runTaskTimer(system.getPlugin(), task -> {
            SpaceState state = states.get(player.getUniqueId());

            if (state == null || !player.isOnline() || player.isDead()) {
                task.cancel();
                return;
            }

            if (secondsLeft[0] <= 0) {
                task.cancel();
                return;
            }

            player.sendMessage("§d[공간술사] §f남은 시간: §e" + secondsLeft[0] + "초");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.0f + (5 - secondsLeft[0]) * 0.15f);

            secondsLeft[0]--;

        }, 0L, 20L);
    }

    private boolean reappear(AbilitySystem system, Player player) {
        SpaceState state = states.get(player.getUniqueId());
        if (state == null) return false;

        Location target = getTargetLocation(player);

        if (target == null) {
            player.sendMessage("§c[공간술사] 바라보는 위치가 너무 멀거나 유효하지 않습니다.");
            return false;
        }

        if (!hasLineOfSight(player.getEyeLocation(), target)) {
            player.sendMessage("§c[공간술사] 벽 뒤로는 이동할 수 없습니다.");
            return false;
        }

        target = safeLocation(target);

        player.teleport(target);
        endSpaceMode(player, true);

        player.setAbsorptionAmount(Math.max(player.getAbsorptionAmount(), ABSORPTION_HEARTS));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SPEED,
                SPEED_DURATION,
                SPEED_LEVEL,
                false,
                true,
                true
        ));

        spawnFirework(target);
        target.getWorld().spawnParticle(
                Particle.PORTAL,
                target.clone().add(0, 1, 0),
                80,
                0.5, 0.9, 0.5,
                0.12
        );

        player.playSound(target, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1.2f);
        player.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.3f);
        player.sendMessage("§d[공간술사] §f공간을 찢고 재등장했습니다!");

        return true;
    }

    private void startPreviewTask(AbilitySystem system, Player player) {
        Bukkit.getScheduler().runTaskTimer(system.getPlugin(), task -> {
            SpaceState state = states.get(player.getUniqueId());

            if (state == null || !player.isOnline() || player.isDead()) {
                task.cancel();
                return;
            }

            Location target = getTargetLocation(player);

            if (target != null && hasLineOfSight(player.getEyeLocation(), target)) {
                showTeleportPreview(target);
                player.sendActionBar("§d[공간술사] §f재사용 시 해당 위치로 재등장");
            } else {
                player.sendActionBar("§c[공간술사] 이동 불가 위치");
            }
        }, 0L, 4L);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        SpaceState state = states.get(player.getUniqueId());

        if (state == null) return;
        if (event.getTo() == null) return;

        Location to = event.getTo().clone();

        // 위치 고정, 시점만 허용
        to.setX(state.skyLocation().getX());
        to.setY(state.skyLocation().getY());
        to.setZ(state.skyLocation().getZ());

        event.setTo(to);
    }

    private Location getTargetLocation(Player player) {
        RayTraceResult result = player.rayTraceBlocks(MAX_TELEPORT_DISTANCE);

        if (result == null || result.getHitBlock() == null || result.getHitPosition() == null) {
            return null;
        }

        Location loc = result.getHitPosition().toLocation(player.getWorld());

        Vector normal = result.getHitBlockFace() != null
                ? result.getHitBlockFace().getDirection()
                : new Vector(0, 1, 0);

        loc.add(normal.multiply(0.6));

        return loc;
    }

    private boolean hasLineOfSight(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();

        if (distance <= 0) return false;

        direction.normalize();

        RayTraceResult result = from.getWorld().rayTraceBlocks(
                from,
                direction,
                distance,
                FluidCollisionMode.NEVER,
                true
        );

        return result == null;
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

    private void showTeleportPreview(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;

        Location center = loc.clone().add(0, 0.08, 0);

        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2 * i / 24.0;
            double x = Math.cos(angle) * 0.75;
            double z = Math.sin(angle) * 0.75;

            world.spawnParticle(
                    Particle.DUST,
                    center.clone().add(x, 0, z),
                    1,
                    0, 0, 0,
                    new Particle.DustOptions(Color.PURPLE, 1.4f)
            );
        }

        world.spawnParticle(
                Particle.END_ROD,
                center.clone().add(0, 0.8, 0),
                6,
                0.25, 0.4, 0.25,
                0.02
        );
    }

    private void spawnFirework(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;

        Firework firework = world.spawn(loc.clone().add(0, 0.2, 0), Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();

        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.PURPLE, Color.AQUA)
                .withFade(Color.WHITE)
                .with(FireworkEffect.Type.BURST)
                .trail(true)
                .flicker(true)
                .build());

        meta.setPower(0);
        firework.setFireworkMeta(meta);

        Bukkit.getScheduler().runTaskLater(
                Bukkit.getPluginManager().getPlugin("MyPlugin"),
                firework::detonate,
                2L
        );
    }

    private void endSpaceMode(Player player, boolean success) {
        states.remove(player.getUniqueId());

        player.setInvulnerable(false);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);

        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }

        if (!success) {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.8f);
        }
    }

    private record SpaceState(Location originalLocation, Location skyLocation) {
    }
}