package my.pkg;

import my.pkg.item.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class SupplyManager implements Listener {
    private final JavaPlugin plugin;

    private final NamespacedKey crateKey;
    private final NamespacedKey itemIdKey;

    private BukkitTask task;

    private final Map<Location, BukkitTask> crateBeamTasks = new HashMap<>();
    private final Map<String, SupplyItem> items = new HashMap<>();
    private final List<Weighted> loot = new ArrayList<>();

    public ItemStack createItemById(String id) {
        if (id == null) return null;

        SupplyItem item = items.get(id.toLowerCase());
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
        this.markerKey = new NamespacedKey(plugin, "supply_marker");

        registerItems();
        buildLootTable();

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void giveSupplyItem(Player player, String id) {
        ItemStack item = createItemById(id);
        if (item == null) {
            player.sendMessage("§c보급 아이템을 찾을 수 없습니다: " + id);
            return;
        }

        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            leftover.values().forEach(it ->
                    player.getWorld().dropItemNaturally(player.getLocation(), it)
            );
        }
    }

    private final NamespacedKey markerKey;
    private final Map<Location, UUID> crateMarkers = new HashMap<>();

    public void start() {
        stop();
        task = new BukkitRunnable() {
            @Override public void run() { spawnCrateNearRandomPlayer(); }
        }.runTaskTimer(plugin, 20 * 240, 20 * 100);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }

        for (BukkitTask beamTask : crateBeamTasks.values()) {
            if (beamTask != null) beamTask.cancel();
        }
        crateBeamTasks.clear();
    }

    private void registerItems() {
        add(new TrapItem(itemIdKey));
        add(new BridgeEggItem(itemIdKey));
        add(new ProtHelmetItem(itemIdKey));
        add(new FireTicketItem(itemIdKey));
        add(new EnderPearlItem());
        add(new MidasHandItem(itemIdKey));
        add(new GamblerDiamondItem(itemIdKey));
        add(new ScientistSecretItem(itemIdKey));
        add(new AdaptiveShieldItem(itemIdKey));
        add(new OldPunishmentPostcardItem(itemIdKey));
        add(new ThorTridentItem(itemIdKey, new NamespacedKey(plugin, "thor_trident_projectile")));
    }

    private void add(SupplyItem item) { items.put(item.id(), item); }

    private void buildLootTable() {
        loot.clear();

        loot.add(new Weighted("ender_pearl", 10));
        loot.add(new Weighted("prot_helmet", 10));
        loot.add(new Weighted("bridge_egg", 10));
        loot.add(new Weighted("fire_ticket", 10));
        loot.add(new Weighted("trap", 10));
        loot.add(new Weighted("midas_hand", 10));
        loot.add(new Weighted("gambler_diamond", 10));
        loot.add(new Weighted("scientist_secret", 10));
        loot.add(new Weighted("adaptive_shield", 10));
        loot.add(new Weighted("old_punishment_postcard", 10));
        loot.add(new Weighted("thor_trident", 10));
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
        w.playSound(ground, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.2f);
        startBeaconBeam(ground);

        // ✅ 위치 알림(좌표 공개)
        int x = ground.getBlockX();
        int y = ground.getBlockY();
        int z = ground.getBlockZ();

        Bukkit.broadcastMessage("§6[보급] §f보급 상자가 떨어졌습니다! §e(" + x + ", " + y + ", " + z + ")");

        ArmorStand marker = (ArmorStand) w.spawnEntity(ground.clone().add(0.5, 1.2, 0.5), EntityType.ARMOR_STAND);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setGravity(false);
        marker.setMarker(true);
        marker.setGlowing(true);
        marker.setCustomName("§6§l보급 상자");
        marker.setCustomNameVisible(true);
        marker.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);

        crateMarkers.put(ground.getBlock().getLocation(), marker.getUniqueId());

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

    private void startBeaconBeam(Location ground) {
        Location blockLoc = ground.getBlock().getLocation();

        // 혹시 기존 태스크 있으면 제거
        BukkitTask old = crateBeamTasks.remove(blockLoc);
        if (old != null) old.cancel();

        BukkitTask beamTask = new BukkitRunnable() {
            @Override
            public void run() {
                Block block = blockLoc.getBlock();

                // 상자가 사라졌으면 종료
                if (block.getType() != Material.CHEST) {
                    cancel();
                    crateBeamTasks.remove(blockLoc);
                    return;
                }

                if (!(block.getState() instanceof Chest chest)) {
                    cancel();
                    crateBeamTasks.remove(blockLoc);
                    return;
                }

                Byte tag = chest.getPersistentDataContainer().get(crateKey, PersistentDataType.BYTE);
                if (tag == null || tag != (byte) 1) {
                    cancel();
                    crateBeamTasks.remove(blockLoc);
                    return;
                }

                World w = blockLoc.getWorld();
                if (w == null) return;

                double x = blockLoc.getX() + 0.5;
                double z = blockLoc.getZ() + 0.5;

                // 신호기 느낌의 수직 빛기둥
                for (double y = 0; y <= 40; y += 0.75) {
                    Location loc = new Location(w, x, blockLoc.getY() + y, z);

                    // 중심 빛
                    w.spawnParticle(Particle.END_ROD, loc, 2, 0.03, 0.1, 0.03, 0.0);

                    // 바깥 은은한 빛
                    w.spawnParticle(Particle.DUST, loc, 3, 0.12, 0.12, 0.12,
                            new Particle.DustOptions(Color.AQUA, 1.4f));
                }
            }
        }.runTaskTimer(plugin, 0L, 10L); // 0.5초마다 갱신

        crateBeamTasks.put(blockLoc, beamTask);
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

        Location chestLoc = b.getLocation();
        UUID markerId = crateMarkers.remove(chestLoc);
        if (markerId != null) {
            Entity marker = Bukkit.getEntity(markerId);
            if (marker != null) marker.remove();
        }

        BukkitTask beamTask = crateBeamTasks.remove(chestLoc);
        if (beamTask != null) {
            beamTask.cancel();
        }

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
    public void onResurrect(EntityResurrectEvent e) {
        for (SupplyItem item : items.values()) {
            item.onResurrect(plugin, e);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        for (SupplyItem item : items.values()) {
            item.onInventoryClick(plugin, e);
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        for (SupplyItem item : items.values()) {
            item.onSwapHand(plugin, e);
        }
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