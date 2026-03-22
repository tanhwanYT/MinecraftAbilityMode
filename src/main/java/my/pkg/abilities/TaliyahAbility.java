package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TaliyahAbility implements Ability, Listener {

    private final JavaPlugin plugin;

    // ===== 밸런스/연출 설정 =====
    private static final int COOLDOWN = 120;

    private static final int DURATION_TICKS = 20 * 4;      // 4초 동안 전진/벽 생성
    private static final int REVERT_DELAY_TICKS = 20 * 6;  // 6초 후 벽 원복

    private static final double SPEED = 0.85;              // 전진 속도(틱당)
    private static final int WALL_HALF_WIDTH = 1;          // 벽 반폭
    private static final int WALL_HEIGHT = 3;              // 벽 높이

    private static final Material WALL_MATERIAL = Material.DEEPSLATE_TILES;

    // 플레이어별 상태
    private static final Map<UUID, TempBlocks> activeBlocks = new ConcurrentHashMap<>();
    private static final Map<UUID, BukkitTask> dashTasks = new ConcurrentHashMap<>();
    private static final Map<UUID, BukkitTask> revertTasks = new ConcurrentHashMap<>();

    public TaliyahAbility(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "taliyah";
    }

    @Override
    public String name() {
        return "탈리아";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("탈리아 : 능력 사용시 벽을 세우며 돌진합니다.");
        player.sendMessage("§7- 쉬프트를 누르면 즉시 내립니다.");
        player.sendMessage("§7- 벽은 일정 시간 뒤 사라집니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        forceStop(player.getUniqueId(), true);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID uid = player.getUniqueId();

        // 혹시 이전 잔여 상태가 있으면 먼저 정리
        forceStop(uid, true);

        World w = player.getWorld();
        Location start = player.getLocation().clone();

        Vector tempDir = start.getDirection().setY(0);
        if (tempDir.lengthSquared() < 1e-6) {
            tempDir = new Vector(0, 0, 1);
        }
        final Vector dir = tempDir.normalize().clone();

        w.playSound(start, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
        w.playSound(start, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.8f);

        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0)).normalize();

        TempBlocks temp = new TempBlocks(plugin);
        activeBlocks.put(uid, temp);

        BukkitTask dashTask = new BukkitRunnable() {
            int ticks = 0;
            Location cur = start.clone();
            boolean stopping = false;

            @Override
            public void run() {
                if (stopping) return;

                if (!player.isOnline() || player.isDead()) {
                    stopping = true;
                    forceStop(uid, true);
                    cancel();
                    return;
                }

                // 쉬프트 누르면 즉시 종료
                if (player.isSneaking()) {
                    player.sendMessage("§7[탈리아] 내려왔습니다.");
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.3f);
                    stopping = true;
                    endDashAndScheduleRevert(uid, w, cur.clone());
                    cancel();
                    return;
                }

                Vector step = dir.clone().multiply(SPEED);
                cur.add(step);

                int groundY = w.getHighestBlockYAt(cur);
                Location ground = new Location(w, cur.getX(), groundY, cur.getZ());

                placeWallSlice(temp, ground, right);

                Location ride = ground.clone().add(0, WALL_HEIGHT + 1.0, 0);
                ride.setYaw(player.getLocation().getYaw());
                ride.setPitch(player.getLocation().getPitch());
                player.teleport(ride);

                ticks++;
                if (ticks >= DURATION_TICKS) {
                    stopping = true;
                    endDashAndScheduleRevert(uid, w, cur.clone());
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        dashTasks.put(uid, dashTask);
        return true;
    }

    private void endDashAndScheduleRevert(UUID uid, World world, Location cur) {
        // 돌진 task 제거
        BukkitTask dash = dashTasks.remove(uid);
        if (dash != null) dash.cancel();

        // 기존 예약 있으면 취소 후 새로 예약
        BukkitTask oldRevert = revertTasks.remove(uid);
        if (oldRevert != null) oldRevert.cancel();

        BukkitTask revertTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            TempBlocks tb = activeBlocks.remove(uid);
            if (tb != null) tb.revertAll();
            revertTasks.remove(uid);
        }, REVERT_DELAY_TICKS);

        revertTasks.put(uid, revertTask);

        world.playSound(cur, Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.7f);
        world.playSound(cur, Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.2f);
    }

    private void forceStop(UUID uid, boolean revertNow) {
        BukkitTask dash = dashTasks.remove(uid);
        if (dash != null) dash.cancel();

        BukkitTask revert = revertTasks.remove(uid);
        if (revert != null) revert.cancel();

        if (revertNow) {
            TempBlocks tb = activeBlocks.remove(uid);
            if (tb != null) tb.revertAll();
        }
    }

    private void placeWallSlice(TempBlocks temp, Location ground, Vector right) {
        World w = ground.getWorld();
        if (w == null) return;

        int baseX = ground.getBlockX();
        int baseY = ground.getBlockY();
        int baseZ = ground.getBlockZ();

        for (int i = -WALL_HALF_WIDTH; i <= WALL_HALF_WIDTH; i++) {
            int x = baseX + (int) Math.round(right.getX() * i);
            int z = baseZ + (int) Math.round(right.getZ() * i);

            for (int h = 1; h <= WALL_HEIGHT; h++) {
                Block b = w.getBlockAt(x, baseY + h, z);

                if (b.getType().isSolid() && b.getType() != Material.AIR) continue;

                temp.setTempBlock(b, WALL_MATERIAL.createBlockData());
            }
        }
    }

    private static class TempBlocks {
        private final JavaPlugin plugin;
        private final Map<Location, BlockData> original = new HashMap<>();
        private final Set<Location> placed = new HashSet<>();

        TempBlocks(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        void setTempBlock(Block b, BlockData newData) {
            Location key = b.getLocation();

            if (!original.containsKey(key)) {
                original.put(key, b.getBlockData().clone());
            }

            b.setBlockData(newData, false);
            placed.add(key);
        }

        void revertAll() {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Map.Entry<Location, BlockData> e : original.entrySet()) {
                    Location loc = e.getKey();
                    BlockData data = e.getValue();

                    World w = loc.getWorld();
                    if (w == null) continue;

                    Block b = w.getBlockAt(loc);
                    b.setBlockData(data, false);
                }
                original.clear();
                placed.clear();
            });
        }
    }
}