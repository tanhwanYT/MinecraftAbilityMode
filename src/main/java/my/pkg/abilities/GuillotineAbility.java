package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GuillotineAbility implements Ability, Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey anvilKey;
    private final NamespacedKey ownerKey;

    private static final int COOLDOWN = 20;
    private static final double RANGE = 18.0;
    private static final int DROP_HEIGHT = 10;
    private static final long ANVIL_LIFETIME_TICKS = 60L; // 3초 후 사라짐

    private static boolean listenerRegistered = false;

    private static final Set<UUID> holders = ConcurrentHashMap.newKeySet();

    // 설치된 모루 블록 위치 추적
    private static final Map<UUID, Set<BlockPos>> placedAnvilsByOwner = new ConcurrentHashMap<>();
    // 제거 예약 task 추적
    private static final Map<BlockPos, BukkitTask> removeTasks = new ConcurrentHashMap<>();

    public GuillotineAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        this.anvilKey = new NamespacedKey(plugin, "guillotine_anvil");
        this.ownerKey = new NamespacedKey(plugin, "guillotine_owner");
    }

    @Override
    public String id() {
        return "guillotine";
    }

    @Override
    public String name() {
        return "단두대";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        holders.add(player.getUniqueId());

        player.sendMessage("§8[단두대] §f바라보는 3x3 지역에 모루를 떨어뜨립니다.");
        player.sendMessage("§7- 내 모루 낙하 피해는 받지 않습니다.");
        player.sendMessage("§7- 떨어진 모루는 잠시 후 사라집니다.");

        if (!listenerRegistered) {
            system.getPlugin().getServer().getPluginManager().registerEvents(this, system.getPlugin());
            listenerRegistered = true;
        }
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        Location center = getTargetCenter(player);
        if (center == null) {
            player.sendMessage("§c[단두대] 바라보는 위치를 찾을 수 없습니다.");
            return false;
        }

        World world = player.getWorld();
        UUID ownerId = player.getUniqueId();

        player.sendMessage("§8[단두대] §f모루를 떨어뜨립니다!");
        world.playSound(player.getLocation(), Sound.BLOCK_ANVIL_PLACE, 1.0f, 0.7f);
        world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.8f);

        int cx = center.getBlockX();
        int cz = center.getBlockZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx;
                int z = cz + dz;

                int groundY = world.getHighestBlockYAt(x, z);
                int spawnY = Math.max(center.getBlockY(), groundY) + DROP_HEIGHT;

                Location spawnLoc = new Location(world, x + 0.5, spawnY, z + 0.5);
                spawnTaggedAnvil(ownerId, spawnLoc);
            }
        }

        return true;
    }

    private Location getTargetCenter(Player player) {
        RayTraceResult result = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                RANGE
        );

        if (result != null && result.getHitPosition() != null) {
            Location loc = result.getHitPosition().toLocation(player.getWorld());
            return loc.getBlock().getLocation();
        }

        Vector dir = player.getEyeLocation().getDirection().clone().normalize();
        Location fallback = player.getLocation().clone().add(dir.multiply(8.0));
        fallback.setY(player.getWorld().getHighestBlockYAt(fallback));
        return fallback.getBlock().getLocation();
    }

    private void spawnTaggedAnvil(UUID ownerId, Location spawnLoc) {
        World world = spawnLoc.getWorld();
        if (world == null) return;

        FallingBlock falling = world.spawnFallingBlock(spawnLoc, Material.ANVIL.createBlockData());
        falling.setDropItem(false);
        falling.setHurtEntities(true);

        // ✅ 낙하 피해 설정
        falling.setDamagePerBlock(3.0f); // 블록당 피해
        falling.setMaxDamage(40);        // 최대 피해

        falling.getPersistentDataContainer().set(anvilKey, PersistentDataType.BYTE, (byte) 1);
        falling.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, ownerId.toString());
    }

    @EventHandler
    public void onOwnerAnvilImmune(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALLING_BLOCK) return;

        if (holders.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void scheduleRemovePlacedAnvil(UUID ownerId, Block block) {
        BlockPos pos = BlockPos.of(block);

        placedAnvilsByOwner.computeIfAbsent(ownerId, k -> ConcurrentHashMap.newKeySet()).add(pos);

        BukkitTask old = removeTasks.remove(pos);
        if (old != null) old.cancel();

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (block.getType() == Material.ANVIL
                    || block.getType() == Material.CHIPPED_ANVIL
                    || block.getType() == Material.DAMAGED_ANVIL) {
                block.setType(Material.AIR, false);
            }

            removeTasks.remove(pos);

            Set<BlockPos> set = placedAnvilsByOwner.get(ownerId);
            if (set != null) {
                set.remove(pos);
                if (set.isEmpty()) {
                    placedAnvilsByOwner.remove(ownerId);
                }
            }
        }, ANVIL_LIFETIME_TICKS);

        removeTasks.put(pos, task);
    }

    private void clearOwnedAnvils(UUID ownerId) {
        Set<BlockPos> set = placedAnvilsByOwner.remove(ownerId);
        if (set == null) return;

        for (BlockPos pos : set) {
            BukkitTask task = removeTasks.remove(pos);
            if (task != null) task.cancel();

            World world = plugin.getServer().getWorld(pos.worldId);
            if (world == null) continue;

            Block block = world.getBlockAt(pos.x, pos.y, pos.z);
            if (block.getType() == Material.ANVIL
                    || block.getType() == Material.CHIPPED_ANVIL
                    || block.getType() == Material.DAMAGED_ANVIL) {
                block.setType(Material.AIR, false);
            }
        }
    }

    private boolean isTaggedGuillotineAnvil(Entity entity) {
        Byte value = entity.getPersistentDataContainer().get(anvilKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private UUID getOwnerId(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @EventHandler
    public void onAnvilDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALLING_BLOCK) return;
        AbilitySystem.PlayerState state = null; // 이 방식은 여기서 직접 못 씀
    }

    @EventHandler
    public void onAnvilLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)) return;
        if (!isTaggedGuillotineAnvil(falling)) return;

        UUID ownerId = getOwnerId(falling);
        if (ownerId == null) return;

        // 모루가 블록으로 정착했을 때 제거 예약
        if (event.getTo() == Material.ANVIL
                || event.getTo() == Material.CHIPPED_ANVIL
                || event.getTo() == Material.DAMAGED_ANVIL) {
            Block block = event.getBlock();
            plugin.getServer().getScheduler().runTask(plugin, () -> scheduleRemovePlacedAnvil(ownerId, block));
        }
    }

    @EventHandler
    public void onTaggedAnvilDrop(EntityDropItemEvent event) {
        if (isTaggedGuillotineAnvil(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // 필요하면 유지해도 되지만, 여기선 나가면 정리
        clearOwnedAnvils(event.getPlayer().getUniqueId());
    }



    @Override
    public void onRemove(AbilitySystem system, Player player) {
        holders.remove(player.getUniqueId());
        clearOwnedAnvils(player.getUniqueId());
    }

    private static class BlockPos {
        private final UUID worldId;
        private final int x;
        private final int y;
        private final int z;

        private BlockPos(UUID worldId, int x, int y, int z) {
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private static BlockPos of(Block block) {
            return new BlockPos(
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ()
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BlockPos other)) return false;
            return x == other.x && y == other.y && z == other.z && worldId.equals(other.worldId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldId, x, y, z);
        }
    }
}