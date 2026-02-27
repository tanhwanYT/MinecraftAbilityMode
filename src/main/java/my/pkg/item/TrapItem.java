package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class TrapItem implements SupplyItem {

    private final NamespacedKey itemIdKey;

    // 설치된 함정들: 블록 위치(월드UUID+xyz) -> 설치자UUID
    private final Map<TrapPos, UUID> traps = new HashMap<>();
    private final Map<TrapPos, Long> expireAt = new HashMap<>();

    // 설정
// 설정
    private static final int PLACE_RANGE = 6;
    private static final long DURATION_MS = 60_000;

    // ✅ 발동 범위: 함정 중심으로 반경 4칸 안에 들어오면 발동
    private static final int TRIGGER_RADIUS = 4;

    // ✅ 파지는 넓이/깊이 (기존보다 4칸 정도 강화)
    private static final int HOLE_RADIUS = 2; // 5x5 (반경2)
    private static final int HOLE_DEPTH = 7;  // 기존 3 -> 7

    // ✅ 피해량 (하트 1칸 = 2.0)
    private static final double TRAP_DAMAGE = 3.0; // 1.5 하트

    public TrapItem(NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
    }

    @Override
    public String id() {
        return "trap";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack it = new ItemStack(Material.STRING, 1);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§c함정 설치 키트");
        meta.setLore(List.of(
                "§7우클릭: 보이지 않는 함정 설치",
                "§7상대가 밟으면 땅이 파입니다"
        ));
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
        it.setItemMeta(meta);
        return it;
    }

    @Override
    public void onRightClick(JavaPlugin plugin, Player p, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;

        // 설치 위치: 바라보는 블록(가장 가까운 블록) 기준으로 그 위 칸(발판) 설치
        Block target = p.getTargetBlockExact(PLACE_RANGE);
        if (target == null) {
            p.sendMessage("§c[함정] 너무 멀어서 설치할 수 없습니다.");
            return;
        }

        Block place = target.getLocation().add(0, 1, 0).getBlock();
        if (!place.getType().isAir()) {
            p.sendMessage("§c[함정] 그 위치에 설치할 공간이 없습니다.");
            return;
        }

        TrapPos pos = TrapPos.of(place.getLocation());
        traps.put(pos, p.getUniqueId());
        expireAt.put(pos, System.currentTimeMillis() + DURATION_MS);

        // 연출(설치자는 위치를 알 수 있게 아주 약하게)
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.3f);
        p.spawnParticle(Particle.SMOKE, place.getLocation().add(0.5, 0.2, 0.5), 6, 0.15, 0.05, 0.15, 0.0);
        p.sendMessage("§a[함정] 설치 완료!");

        // 아이템 1개 소모
        consumeOne(e);

        // 우클릭 기본 행동 방지(불필요한 상호작용 제거)
        e.setCancelled(true);
    }

    @Override
    public void onPlayerMove(JavaPlugin plugin, PlayerMoveEvent e) {
        Player p = e.getPlayer();

        cleanupExpired();

        Location loc = p.getLocation();
        UUID worldId = loc.getWorld().getUID();

        // ✅ 현재 위치 기준 반경 4칸(정사각형) 안에 함정이 있으면 발동
        TrapPos found = null;
        UUID owner = null;

        int px = loc.getBlockX();
        int py = loc.getBlockY();
        int pz = loc.getBlockZ();

        for (int dx = -TRIGGER_RADIUS; dx <= TRIGGER_RADIUS && found == null; dx++) {
            for (int dz = -TRIGGER_RADIUS; dz <= TRIGGER_RADIUS && found == null; dz++) {
                // 플레이어 발 높이 기준으로 y는 그대로(원하면 py-1도 같이 체크 가능)
                TrapPos pos = new TrapPos(worldId, px + dx, py, pz + dz);
                UUID o = traps.get(pos);
                if (o != null) {
                    found = pos;
                    owner = o;
                }
            }
        }

        if (found == null) return;
        if (p.getUniqueId().equals(owner)) return; // 설치자는 제외

        // ✅ 발동! (함정 중심 위치로 파기)
        Location base = new Location(loc.getWorld(), found.x(), found.y(), found.z());
        triggerTrap(p, base);

        traps.remove(found);
        expireAt.remove(found);
    }

    private void triggerTrap(Player victim, Location base) {
        World w = base.getWorld();
        if (w == null) return;

        // ✅ 피해
        victim.damage(TRAP_DAMAGE);

        // ✅ 5x5 넓이 + 깊이 HOLE_DEPTH 만큼 파기
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        for (int y = 0; y < HOLE_DEPTH; y++) {
            for (int x = -HOLE_RADIUS; x <= HOLE_RADIUS; x++) {
                for (int z = -HOLE_RADIUS; z <= HOLE_RADIUS; z++) {
                    Block b = w.getBlockAt(bx + x, by - y, bz + z);

                    // 중요한 블록 예외
                    if (b.getType() == Material.BEDROCK) continue;
                    if (b.getType() == Material.BARRIER) continue;

                    b.setType(Material.AIR, false);
                }
            }
        }

        w.playSound(base, Sound.BLOCK_GRAVEL_BREAK, 1.0f, 0.8f);

        // 파티클(중심에서)
        w.spawnParticle(Particle.BLOCK, base.clone().add(0.5, 0.1, 0.5),
                60, 0.8, 0.2, 0.8, Material.DIRT.createBlockData());

        victim.sendMessage("§c[함정] 함정에 걸렸다! §7(-" + (TRAP_DAMAGE / 2.0) + "❤)");
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        // 너무 자주 돌지 않게 하려면 throttle 가능하지만 일단 단순 구현
        Iterator<Map.Entry<TrapPos, Long>> it = expireAt.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (en.getValue() <= now) {
                TrapPos pos = en.getKey();
                it.remove();
                traps.remove(pos);
            }
        }
    }

    private void consumeOne(PlayerInteractEvent e) {
        ItemStack hand = e.getItem();
        if (hand == null) return;
        int amt = hand.getAmount();
        if (amt <= 1) {
            hand.setAmount(0);
        } else {
            hand.setAmount(amt - 1);
        }
    }

    private record TrapPos(UUID world, int x, int y, int z) {
        static TrapPos of(Location loc) {
            return new TrapPos(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }
    }
}