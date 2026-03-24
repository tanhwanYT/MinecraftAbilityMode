package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HotSpringAbility implements Ability, Listener {

    // ===== 밸런스 조절용 =====
    private static final int CHECK_PERIOD_TICKS = 20;      // 몇 틱마다 검사할지 (20 = 1초)
    private static final double OWNER_DAMAGE = 4.0;        // 능력자가 받는 피해
    private static final double TARGET_DAMAGE = 7.0;       // 상대가 받는 피해
    private static final int DAMAGE_COOLDOWN_TICKS = 20;   // 같은 상대에게 재적용 간격
    private static final int SEARCH_LIMIT = 500;           // 연결된 물 탐색 최대 블록 수
    private static final int MAX_BLOCK_DISTANCE = 24;      // 너무 먼 물줄기까지 검사 안 하게 제한

    // ===== 상태 저장 =====
    private static final Set<UUID> holders = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> pairCooldown = new ConcurrentHashMap<>();

    private static BukkitTask loopTask;
    private static boolean listenerRegistered = false;

    @Override
    public String id() {
        return "hot_spring";
    }

    @Override
    public String name() {
        return "온탕";
    }

    @Override
    public int cooldownSeconds() {
        return 0;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        holders.add(player.getUniqueId());

        player.sendMessage("§6온탕 §f: 같은 물줄기에 들어온 상대와 함께 뜨거운 물 피해를 입힙니다.");
        player.sendMessage("§7- 이어진 물도 같은 물로 판정됩니다.");
        player.sendMessage("§7- 나는 덜 아프고, 상대는 더 아픕니다.");

        if (!listenerRegistered) {
            system.getPlugin().getServer().getPluginManager().registerEvents(this, system.getPlugin());
            listenerRegistered = true;
        }

        ensureTaskRunning(system.getPlugin());
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        holders.remove(player.getUniqueId());

        UUID removed = player.getUniqueId();
        pairCooldown.entrySet().removeIf(e -> e.getKey().startsWith(removed.toString() + ":"));

        stopTaskIfUnused();
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("§7[온탕] 패시브 능력입니다.");
        return false;
    }

    private void ensureTaskRunning(JavaPlugin plugin) {
        if (loopTask != null) return;

        loopTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (holders.isEmpty()) {
                stopTaskIfUnused();
                return;
            }

            long now = System.currentTimeMillis();

            for (UUID holderId : holders) {
                Player owner = Bukkit.getPlayer(holderId);
                if (owner == null || !owner.isOnline() || owner.isDead()) continue;
                if (!owner.isValid()) continue;

                Block ownerWater = getStandingWaterBlock(owner);
                if (ownerWater == null) continue;

                World world = owner.getWorld();

                for (Player target : world.getPlayers()) {
                    if (target.equals(owner)) continue;
                    if (!target.isOnline() || target.isDead()) continue;
                    if (!target.isValid()) continue;

                    Block targetWater = getStandingWaterBlock(target);
                    if (targetWater == null) continue;

                    // 같은 연결된 물인지 판정
                    if (!isSameConnectedWater(ownerWater, targetWater, SEARCH_LIMIT, MAX_BLOCK_DISTANCE)) {
                        continue;
                    }

                    String key = owner.getUniqueId() + ":" + target.getUniqueId();
                    long last = pairCooldown.getOrDefault(key, 0L);
                    long needed = DAMAGE_COOLDOWN_TICKS * 50L;

                    if (now - last < needed) continue;
                    pairCooldown.put(key, now);

                    applyHotWaterEffect(owner, target);
                }
            }
        }, 0L, CHECK_PERIOD_TICKS);
    }

    private void stopTaskIfUnused() {
        if (!holders.isEmpty()) return;

        if (loopTask != null) {
            loopTask.cancel();
            loopTask = null;
        }
    }

    private void applyHotWaterEffect(Player owner, Player target) {
        World world = owner.getWorld();

        owner.damage(OWNER_DAMAGE);
        target.damage(TARGET_DAMAGE, owner);

        owner.setFireTicks(0);
        target.setFireTicks(0);

        owner.playSound(owner.getLocation(), Sound.BLOCK_LAVA_POP, 0.6f, 1.4f);
        target.playSound(target.getLocation(), Sound.BLOCK_LAVA_POP, 0.8f, 1.0f);

        world.spawnParticle(Particle.SMOKE, owner.getLocation().add(0, 1.0, 0), 8, 0.25, 0.35, 0.25, 0.01);
        world.spawnParticle(Particle.SMOKE, target.getLocation().add(0, 1.0, 0), 12, 0.25, 0.35, 0.25, 0.01);

        world.spawnParticle(Particle.BUBBLE, owner.getLocation().add(0, 0.6, 0), 8, 0.25, 0.2, 0.25, 0.01);
        world.spawnParticle(Particle.BUBBLE, target.getLocation().add(0, 0.6, 0), 10, 0.25, 0.2, 0.25, 0.01);

        owner.sendActionBar("§6[온탕] §f같은 물 안의 상대와 함께 뜨거운 물 피해!");
        target.sendActionBar("§c[온탕] §f물이 뜨겁다!");
    }

    // 플레이어가 서 있는 위치/발 아래 쪽에서 물 판정
    private Block getStandingWaterBlock(Player player) {
        Block feet = player.getLocation().getBlock();
        if (isWaterBlock(feet)) return feet;

        Block body = player.getEyeLocation().subtract(0, 0.5, 0).getBlock();
        if (isWaterBlock(body)) return body;

        Block below = player.getLocation().clone().subtract(0, 1, 0).getBlock();
        if (isWaterBlock(below)) return below;

        return null;
    }

    private boolean isWaterBlock(Block block) {
        Material type = block.getType();
        return type == Material.WATER || type == Material.BUBBLE_COLUMN;
    }

    private boolean isSameConnectedWater(Block start, Block goal, int searchLimit, int maxBlockDistance) {
        if (!start.getWorld().equals(goal.getWorld())) return false;
        if (!isWaterBlock(start) || !isWaterBlock(goal)) return false;

        if (Math.abs(start.getX() - goal.getX()) > maxBlockDistance) return false;
        if (Math.abs(start.getY() - goal.getY()) > maxBlockDistance) return false;
        if (Math.abs(start.getZ() - goal.getZ()) > maxBlockDistance) return false;

        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        queue.add(start);
        visited.add(key(start));

        int searched = 0;

        int[][] dirs = {
                { 1, 0, 0}, {-1, 0, 0},
                { 0, 1, 0}, { 0,-1, 0},
                { 0, 0, 1}, { 0, 0,-1}
        };

        while (!queue.isEmpty() && searched < searchLimit) {
            Block cur = queue.poll();
            searched++;

            if (cur.getX() == goal.getX() && cur.getY() == goal.getY() && cur.getZ() == goal.getZ()) {
                return true;
            }

            for (int[] d : dirs) {
                Block next = cur.getWorld().getBlockAt(
                        cur.getX() + d[0],
                        cur.getY() + d[1],
                        cur.getZ() + d[2]
                );

                if (!isWaterBlock(next)) continue;

                if (Math.abs(next.getX() - start.getX()) > maxBlockDistance) continue;
                if (Math.abs(next.getY() - start.getY()) > maxBlockDistance) continue;
                if (Math.abs(next.getZ() - start.getZ()) > maxBlockDistance) continue;

                String k = key(next);
                if (visited.add(k)) {
                    queue.add(next);
                }
            }
        }

        return false;
    }

    private String key(Block block) {
        return block.getX() + ":" + block.getY() + ":" + block.getZ();
    }
}