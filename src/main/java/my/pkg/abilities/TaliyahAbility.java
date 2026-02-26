package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.Bukkit;
import org.bukkit.Sound;

import java.util.*;

public class TaliyahAbility implements Ability {

    private final JavaPlugin plugin;

    // ===== 밸런스/연출 설정 =====
    private static final int COOLDOWN = 120;

    private static final int DURATION_TICKS = 20 * 4; // 4초 동안 전진/벽 생성
    private static final int REVERT_DELAY_TICKS = 20 * 6; // 6초 후 벽 원복

    private static final double SPEED = 0.85; // 전진 속도(틱당)
    private static final int WALL_HALF_WIDTH = 1; // 벽 반폭(3이면 총 7칸)
    private static final int WALL_HEIGHT = 3; // 벽 높이

    private static final Material WALL_MATERIAL = Material.DEEPSLATE_TILES; // 벽 재질(원하는 걸로)

    // 임시 블록 원복용 저장소 (플레이어별로 한 번에 여러 벽을 만들 수 있으니 ability 내부에서 관리)
    private static final Map<UUID, TempBlocks> active = new HashMap<>();

    public TaliyahAbility(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() { return "taliyah"; }

    @Override
    public String name() { return "탈리아"; }

    @Override
    public int cooldownSeconds() { return COOLDOWN; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("탈리아 : 능력 사용시 벽을 세우며 돌진합니다. 중간에 내리는건 없고, 앞에 뭐가 있으면 넘어갑니다. 벽은 일정시간뒤에 사라집니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        // 능력 회수 시 남아있는 임시 블록이 있으면 즉시 원복
        TempBlocks tb = active.remove(player.getUniqueId());
        if (tb != null) tb.revertAll();
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID uid = player.getUniqueId();

        // 이미 실행 중이면 중복 방지
        if (active.containsKey(uid)) {
            player.sendMessage("§7[벽타기] 이미 사용 중입니다.");
            return false;
        }

        World w = player.getWorld();
        Location start = player.getLocation().clone();

        Vector tempDir = start.getDirection().setY(0).normalize();
        if (tempDir.lengthSquared() < 1e-6) {
            tempDir = new Vector(0, 0, 1);
        }
        final Vector dir = tempDir.clone();  // ✅ final로 확정

        w.playSound(start, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
        w.playSound(start, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.8f);

        // 벽의 가로 방향(좌우) = 진행방향에 수직
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0)).normalize();

        TempBlocks temp = new TempBlocks(plugin);
        active.put(uid, temp);

        new BukkitRunnable() {
            int ticks = 0;
            Location cur = start.clone();

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cleanup();
                    return;
                }

                // 진행 중 방향 고정(원하면 여기서 player.getLocation().getDirection()으로 매틱 갱신해서 조준 변경 가능)
                Vector step = dir.clone().multiply(SPEED);

                // 다음 위치로 이동
                cur.add(step);

                // 바닥 기준 Y 계산: 해당 XZ의 최고 블록 위
                int groundY = w.getHighestBlockYAt(cur);
                Location ground = new Location(w, cur.getX(), groundY, cur.getZ());

                // 벽 한 "단면(slice)" 생성
                placeWallSlice(temp, ground, right);

                // 플레이어를 벽 위로 올려서 "타는" 느낌
                Location ride = ground.clone().add(0, WALL_HEIGHT + 1.0, 0);
                ride.setYaw(player.getLocation().getYaw());
                ride.setPitch(player.getLocation().getPitch());
                player.teleport(ride);

                ticks++;
                if (ticks >= DURATION_TICKS) {
                    // 종료: 원복 예약
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        TempBlocks tb = active.remove(uid);
                        if (tb != null) tb.revertAll();
                    }, REVERT_DELAY_TICKS);

                    w.playSound(cur, Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.7f);
                    w.playSound(cur, Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.2f);

                    // 사용은 끝났지만 벽은 남아있다가 나중에 사라짐
                    this.cancel();
                }
            }

            private void cleanup() {
                // 즉시 원복하고 종료
                TempBlocks tb = active.remove(uid);
                if (tb != null) tb.revertAll();
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);

        return true;
    }

    private void placeWallSlice(TempBlocks temp, Location ground, Vector right) {
        World w = ground.getWorld();
        if (w == null) return;

        int baseX = ground.getBlockX();
        int baseY = ground.getBlockY();
        int baseZ = ground.getBlockZ();

        // 좌우로 퍼지는 벽
        for (int i = -WALL_HALF_WIDTH; i <= WALL_HALF_WIDTH; i++) {
            int x = baseX + (int) Math.round(right.getX() * i);
            int z = baseZ + (int) Math.round(right.getZ() * i);

            // 바닥에서부터 위로 WALL_HEIGHT만큼
            for (int h = 1; h <= WALL_HEIGHT; h++) {
                Block b = w.getBlockAt(x, baseY + h, z);

                // 공기/식물류 등은 덮고, 고체 블록은 웬만하면 건드리지 않게 안전장치
                if (b.getType().isSolid() && b.getType() != Material.AIR) continue;

                temp.setTempBlock(b, WALL_MATERIAL.createBlockData());
            }
        }
    }

    // ===== 임시 블록 원복 매니저 =====
    private static class TempBlocks {
        private final JavaPlugin plugin;
        private final Map<Location, BlockData> original = new HashMap<>();
        private final Set<Location> placed = new HashSet<>();

        TempBlocks(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        void setTempBlock(Block b, BlockData newData) {
            Location key = b.getLocation();

            // 최초 1회만 원본 저장
            if (!original.containsKey(key)) {
                original.put(key, b.getBlockData().clone());
            }

            b.setBlockData(newData, false);
            placed.add(key);
        }

        void revertAll() {
            // 원복은 메인스레드에서
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