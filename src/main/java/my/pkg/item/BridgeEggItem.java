package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class BridgeEggItem implements SupplyItem {

    private final NamespacedKey itemIdKey;
    private final NamespacedKey bridgeProjKey; // 프로젝타일 식별용

    // 각 계란 UUID -> 설치된 블록 원복 정보
    private final Map<UUID, List<BlockRollback>> rollbacks = new HashMap<>();

    // 설정
    private static final int MAX_TICKS = 120; // 6초 정도 추적
    private static final int ROLLBACK_TICKS = 20 * 20; // 20초 후 복구
    private static final Material BRIDGE_MAT = Material.WHITE_WOOL;

    public BridgeEggItem(NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
        this.bridgeProjKey = new NamespacedKey(itemIdKey.getNamespace().equals("minecraft") ? JavaPlugin.getProvidingPlugin(getClass()) : JavaPlugin.getProvidingPlugin(getClass()),
                "bridge_egg_proj");
        // 위 한 줄이 불편하면 SupplyManager에서 projKey도 넘겨주는 방식으로 바꿔도 됨.
    }

    @Override
    public String id() {
        return "bridge_egg";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack it = new ItemStack(Material.EGG, 1);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§b브릿지 에그");
        meta.setLore(List.of(
                "§7던진 궤적을 따라",
                "§f양털 다리§7가 생성됩니다 (지속시간 20초)"
        ));
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
        it.setItemMeta(meta);
        return it;
    }

    @Override
    public void onRightClick(JavaPlugin plugin, Player p, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // 우리가 직접 발사
        Egg egg = p.launchProjectile(Egg.class);
        egg.getPersistentDataContainer().set(bridgeProjKey, PersistentDataType.BYTE, (byte) 1);

        UUID eggId = egg.getUniqueId();
        rollbacks.put(eggId, new ArrayList<>());

        p.playSound(p.getLocation(), Sound.ENTITY_EGG_THROW, 1.0f, 1.2f);

        // 아이템 소모
        consumeOne(e);
        e.setCancelled(true);

        // 궤적 추적하며 다리 설치
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                if (!egg.isValid() || egg.isDead() || ticks > MAX_TICKS) {
                    cancel();
                    scheduleRollback(plugin, eggId);
                    return;
                }
                placeBridgeAt(egg.getLocation(), eggId);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    @Override
    public void onProjectileHit(JavaPlugin plugin, ProjectileHitEvent e) {
        Entity ent = e.getEntity();
        if (!(ent instanceof Egg egg)) return;

        Byte tag = egg.getPersistentDataContainer().get(bridgeProjKey, PersistentDataType.BYTE);
        if (tag == null || tag != (byte) 1) return;

        UUID eggId = egg.getUniqueId();
        // 충돌 지점에도 한 번 더 설치 시도
        placeBridgeAt(egg.getLocation(), eggId);
        scheduleRollback(plugin, eggId);
    }

    private void placeBridgeAt(Location loc, UUID eggId) {
        World w = loc.getWorld();
        if (w == null) return;

        // 계란 위치 "아래 한 칸"을 다리로
        Block b = w.getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());

        if (!b.getType().isAir()) return;
        // 물/용암 위 같은 경우는 그대로 설치 가능하게 할지? -> 공기만 설치

        // 롤백 정보 저장
        List<BlockRollback> list = rollbacks.get(eggId);
        if (list == null) return;

        // 중복 저장 방지
        for (BlockRollback r : list) {
            if (r.x == b.getX() && r.y == b.getY() && r.z == b.getZ() && r.world.equals(w.getUID())) return;
        }

        list.add(new BlockRollback(w.getUID(), b.getX(), b.getY(), b.getZ(), b.getBlockData()));
        b.setType(BRIDGE_MAT, false);

        w.spawnParticle(Particle.CLOUD, b.getLocation().add(0.5, 1.0, 0.5), 2, 0.1, 0.05, 0.1, 0.0);
    }

    private void scheduleRollback(JavaPlugin plugin, UUID eggId) {
        List<BlockRollback> list = rollbacks.get(eggId);
        if (list == null || list.isEmpty()) {
            rollbacks.remove(eggId);
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                for (BlockRollback r : list) {
                    World w = Bukkit.getWorld(r.world);
                    if (w == null) continue;
                    Block b = w.getBlockAt(r.x, r.y, r.z);
                    // 아직도 우리 양털이면 원복(다른 사람이 바꿨을 수도 있으니)
                    if (b.getType() == BRIDGE_MAT) {
                        b.setBlockData(r.oldData, false);
                    }
                }
                rollbacks.remove(eggId);
            }
        }.runTaskLater(plugin, ROLLBACK_TICKS);
    }

    private void consumeOne(PlayerInteractEvent e) {
        ItemStack hand = e.getItem();
        if (hand == null) return;
        int amt = hand.getAmount();
        if (amt <= 1) hand.setAmount(0);
        else hand.setAmount(amt - 1);
    }

    private static class BlockRollback {
        final UUID world;
        final int x, y, z;
        final org.bukkit.block.data.BlockData oldData;

        BlockRollback(UUID world, int x, int y, int z, org.bukkit.block.data.BlockData oldData) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.oldData = oldData;
        }
    }
}