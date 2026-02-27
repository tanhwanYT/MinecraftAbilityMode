package my.pkg;

import my.pkg.item.*;
import org.bukkit.BlockChangeDelegate;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class SupplyManager implements Listener {
    private final JavaPlugin plugin;

    private final NamespacedKey crateKey;
    private final NamespacedKey itemIdKey;

    private BukkitTask task;

    private final Map<String, SupplyItem> items = new HashMap<>();
    private final List<Weighted> loot = new ArrayList<>();

    public ItemStack createItemById(String id) {
        SupplyItem item = items.get(id);
        if (item == null) return null;
        return item.create(plugin);
    }

    public List<String> getAllItemIds() {
        return new ArrayList<>(items.keySet());
    }

    public SupplyManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.crateKey = new NamespacedKey(plugin, "supply_crate");
        this.itemIdKey = new NamespacedKey(plugin, "supply_item_id");

        registerItems();
        buildLootTable();
    }

    public void start() {
        stop();
        task = new BukkitRunnable() {
            @Override public void run() { spawnCrateNearRandomPlayer(); }
        }.runTaskTimer(plugin, 20 * 240, 20 * 100);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void registerItems() {
        add(new TrapItem(itemIdKey));
        add(new BridgeEggItem(itemIdKey));
        add(new ProtHelmetItem(itemIdKey));
        add(new FireTicketItem(itemIdKey));
        add(new EnderPearlItem());
        add(new MidasHandItem(itemIdKey));
        add(new GamblerDiamondItem(itemIdKey));
    }

    private void add(SupplyItem item) { items.put(item.id(), item); }

    private void buildLootTable() {
        // 가중치(밸런스는 나중에 조절)
        loot.add(new Weighted("ender_pearl", 20));
        loot.add(new Weighted("prot_helmet", 12));
        loot.add(new Weighted("bridge_egg", 12));
        loot.add(new Weighted("fire_ticket", 10));
        loot.add(new Weighted("trap", 10));
        loot.add(new Weighted("midas_hand", 6));
        loot.add(new Weighted("gambler_diamond", 4));
    }

    private void spawnCrateNearRandomPlayer() {
        List<Player> ps = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        if (ps.isEmpty()) return;

        Player base = ps.get(ThreadLocalRandom.current().nextInt(ps.size()));
        World w = base.getWorld();

        Location baseLoc = base.getLocation();

        int dx = ThreadLocalRandom.current().nextInt(-20, 21);
        int dz = ThreadLocalRandom.current().nextInt(-20, 21);

        Location loc = baseLoc.clone().add(dx, 0, dz);

        // 가장 높은 블록 위(안전하게)
        Location ground = w.getHighestBlockAt(loc).getLocation().add(0, 1, 0);

        // 상자 설치
        Block b = ground.getBlock();
        b.setType(Material.CHEST);

        if (b.getState() instanceof Chest chest) {
            chest.getPersistentDataContainer().set(crateKey, PersistentDataType.BYTE, (byte) 1);
            chest.update();
        }

        // 연출
        w.strikeLightningEffect(ground);
        w.playSound(ground, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.4f);

        // ✅ 위치 알림(좌표 공개)
        int x = ground.getBlockX();
        int y = ground.getBlockY();
        int z = ground.getBlockZ();

        Bukkit.broadcastMessage("§6[보급] §f보급 상자가 떨어졌습니다! §e(" + x + ", " + y + ", " + z + ")");

        // ✅ 모두가 알아차리게 파티클 “기둥”
        for (int i = 0; i < 80; i++) { // 80블록 높이
            w.spawnParticle(Particle.END_ROD, ground.clone().add(0.5, i * 0.5, 0.5), 2, 0.1, 0.0, 0.1, 0.0);
        }

        // ✅ 모든 플레이어에게 소리 + 방향감(가까우면 크게)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(w)) continue;
            double dist = p.getLocation().distance(ground);
            float vol = (float) Math.max(0.3, Math.min(1.0, 1.0 - (dist / 200.0)));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, vol, 1.2f);
        }
    }

    // 보급 상자 열기(우클릭)
    @EventHandler
    public void onCrateOpen(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block b = e.getClickedBlock();
        if (b.getType() != Material.CHEST) return;

        BlockState st = b.getState();
        if (!(st instanceof Chest chest)) return;

        Byte tag = chest.getPersistentDataContainer().get(crateKey, PersistentDataType.BYTE);
        if (tag == null || tag != (byte)1) return;

        e.setCancelled(true);

        Player p = e.getPlayer();
        // 보상 지급
        ItemStack reward = rollReward();
        p.getInventory().addItem(reward);
        p.sendMessage("§e[보급] 보상을 획득했습니다!");

        // 상자 제거
        b.setType(Material.AIR);
    }

    private ItemStack rollReward() {
        String id = weightedPick();
        SupplyItem item = items.get(id);
        return item.create(plugin);
    }

    private String weightedPick() {
        int total = loot.stream().mapToInt(w -> w.weight).sum();
        int r = ThreadLocalRandom.current().nextInt(total);
        int acc = 0;
        for (Weighted w : loot) {
            acc += w.weight;
            if (r < acc) return w.id;
        }
        return loot.get(0).id;
    }

    private boolean isSupplyItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        return pdc.has(itemIdKey, PersistentDataType.STRING);
    }

    private String getSupplyId(ItemStack stack) {
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        return pdc.get(itemIdKey, PersistentDataType.STRING);
    }

    // ---- 이벤트 라우팅(아이템용) ----

    @EventHandler
    public void onRightClickItem(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack hand = e.getItem();
        if (!isSupplyItem(hand)) return;

        String id = getSupplyId(hand);
        SupplyItem item = items.get(id);
        if (item != null) item.onRightClick(plugin, e.getPlayer(), e);
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;

        ItemStack hand = attacker.getInventory().getItemInMainHand();
        if (!isSupplyItem(hand)) return;

        String id = getSupplyId(hand);
        SupplyItem item = items.get(id);
        if (item != null) item.onHitEntity(plugin, attacker, victim, e);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        // 특정 아이템이 던진 프로젝타일인지 PDC로 구분하는게 깔끔
        for (SupplyItem item : items.values()) item.onProjectileHit(plugin, e);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        for (SupplyItem item : items.values()) item.onPlayerMove(plugin, e);
    }

    private static class Weighted {
        final String id;
        final int weight;
        Weighted(String id, int weight) { this.id = id; this.weight = weight; }
    }
}