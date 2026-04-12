package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ReporterAbility implements Ability, Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey targetKey;

    private static final int EXPOSE_TICKS = 20 * 5;

    private static final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    private static final long EMBARGO_MS = 4 * 60 * 1000L; // 4분
    private static final Map<UUID, Long> embargoEndTimes = new ConcurrentHashMap<>();

    // 기자 능력 보유자
    private static final Set<UUID> holders = ConcurrentHashMap.newKeySet();

    // 현재 열린 GUI 추적
    private static final Map<UUID, ReporterMenu> openMenus = new ConcurrentHashMap<>();

    public ReporterAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        this.targetKey = new NamespacedKey(plugin, "reporter_target");
    }

    @Override
    public String id() {
        return "reporter";
    }

    @Override
    public String name() {
        return "기자";
    }

    @Override
    public int cooldownSeconds() {
        return 75;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        holders.add(player.getUniqueId());
        embargoEndTimes.put(player.getUniqueId(), System.currentTimeMillis() + EMBARGO_MS);

        player.sendMessage("§b기자 §f: 서바이벌 플레이어 1명을 지목해 능력을 모두에게 공개합니다.");
        player.sendMessage("§7- 능력이 밝혀진 플레이어는 5초간 발광과 구속에 걸립니다.");
        player.sendMessage("§7- 능력 사용 시 플레이어 머리 UI가 열립니다.");
        player.sendMessage("§7- 자기자신한테도 사용 가능합니다.(굳이?)");
        player.sendMessage("§c[엠바고] §f능력을 부여받고 4분 동안은 능력을 사용할 수 없습니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        holders.remove(player.getUniqueId());

        ReporterMenu menu = openMenus.remove(player.getUniqueId());
        if (menu != null) {
            Player p = Bukkit.getPlayer(menu.ownerId);
            if (p != null && p.isOnline()) {
                p.closeInventory();
            }
        }
        embargoEndTimes.remove(player.getUniqueId());
        systems.remove(player.getUniqueId());
        processing.remove(player.getUniqueId());
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        if (!holders.contains(player.getUniqueId())) return false;

        long now = System.currentTimeMillis();
        long embargoEnd = embargoEndTimes.getOrDefault(player.getUniqueId(), 0L);

        if (now < embargoEnd) {
            long remainSec = (embargoEnd - now + 999) / 1000;
            long min = remainSec / 60;
            long sec = remainSec % 60;

            player.sendMessage("§c[엠바고] §f게임 준비시간 동안은 능력을 사용할 수 없습니다. 남은 시간: §e" + min + "분 " + sec + "초");
            return false;
        }

        List<Player> targets = getAvailableTargets(player);
        if (targets.isEmpty()) {
            player.sendMessage("§c[기자] 공개할 수 있는 서바이벌 플레이어가 없습니다.");
            return false;
        }

        openReporterMenu(player, targets);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);

        // 현재 AbilitySystem 구조에서는 UI 여는 순간 쿨타임 시작
        return true;
    }

    private void openReporterMenu(Player reporter, List<Player> targets) {
        int size = getInventorySize(targets.size());
        ReporterMenu holder = new ReporterMenu(reporter.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, size, "§b[기자] 특종 대상 선택");

        for (int i = 0; i < targets.size(); i++) {
            Player target = targets.get(i);
            inv.setItem(i, createTargetHead(target));
        }

        // 빈칸 장식
        for (int i = targets.size(); i < size; i++) {
            inv.setItem(i, createFiller());
        }

        holder.inventory = inv;
        openMenus.put(reporter.getUniqueId(), holder);
        reporter.openInventory(inv);
    }

    private List<Player> getAvailableTargets(Player reporter) {
        List<Player> result = new ArrayList<>();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline() || p.isDead()) continue;
            if (p.getGameMode() != GameMode.SURVIVAL) continue;
            result.add(p);
        }

        result.sort(Comparator.comparing(Player::getName));
        return result;
    }

    private int getInventorySize(int amount) {
        int rows = (int) Math.ceil(amount / 9.0);
        rows = Math.max(1, rows);
        rows = Math.min(6, rows);
        return rows * 9;
    }

    private ItemStack createTargetHead(Player target) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, 1);
        ItemMeta rawMeta = item.getItemMeta();
        if (rawMeta instanceof SkullMeta meta) {
            meta.setOwningPlayer(target);
            meta.setDisplayName("§e" + target.getName());
            meta.setLore(List.of(
                    "§7클릭 시 이 플레이어의 능력을",
                    "§7모두에게 특종으로 공개합니다."
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, target.getUniqueId().toString());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isReporterTarget(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) return false;
        String raw = item.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        return raw != null && !raw.isEmpty();
    }

    private UUID getTargetId(ItemStack item) {
        if (!isReporterTarget(item)) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        if (raw == null) return null;

        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void revealTarget(AbilitySystem system, Player reporter, Player target) {
        Ability targetAbility = system.getState(target).getAbility();

        String abilityName = (targetAbility != null) ? targetAbility.name() : "무능력";
        String abilityId = (targetAbility != null) ? targetAbility.id() : "none";

        Bukkit.broadcastMessage("§7[특종] §c" + target.getName() + "§f님 의 능력이  §a" + abilityName + " §7 라는 소식입니다!(" + abilityId + ")");

        target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, EXPOSE_TICKS, 0, false, true, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, EXPOSE_TICKS, 10, false, true, true));

        reporter.playSound(reporter.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
        target.playSound(target.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.9f, 0.8f);

        reporter.sendMessage("§b[기자] §f" + target.getName() + "의 능력을 특종으로 공개했습니다.");
        target.sendMessage("§c[특종] §f당신의 능력이 공개되었습니다! 5초간 발광과 구속 상태입니다.");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player reporter)) return;
        if (!(event.getInventory().getHolder() instanceof ReporterMenu menu)) return;

        event.setCancelled(true);

        if (!menu.ownerId.equals(reporter.getUniqueId())) return;

        // 중복 실행 방지
        if (!processing.add(reporter.getUniqueId())) return;

        try {
            ItemStack clicked = event.getCurrentItem();
            if (!isReporterTarget(clicked)) return;

            UUID targetId = getTargetId(clicked);
            if (targetId == null) return;

            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline() || target.isDead()) {
                reporter.sendMessage("§c[기자] 대상 플레이어를 찾을 수 없습니다.");
                reporter.closeInventory();
                openMenus.remove(reporter.getUniqueId());
                return;
            }

            if (target.getGameMode() != GameMode.SURVIVAL) {
                reporter.sendMessage("§c[기자] 서바이벌 플레이어만 선택할 수 있습니다.");
                reporter.closeInventory();
                openMenus.remove(reporter.getUniqueId());
                return;
            }

            reporter.closeInventory();
            openMenus.remove(reporter.getUniqueId());

            Ability current = systemAbility(reporter);
            if (!(current instanceof ReporterAbility)) {
                reporter.sendMessage("§c[기자] 현재 기자 능력이 아닙니다.");
                return;
            }

            AbilitySystem system = findSystem(reporter);
            if (system == null) {
                reporter.sendMessage("§c[기자] AbilitySystem을 찾을 수 없습니다.");
                return;
            }

            revealTarget(system, reporter, target);
        } finally {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                processing.remove(reporter.getUniqueId());
            }, 1L);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof ReporterMenu)) return;

        openMenus.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        openMenus.remove(event.getPlayer().getUniqueId());
    }

    // 현재 구조상 click 이벤트에서 AbilitySystem 참조를 직접 받지 못하니까 저장
    private static final Map<UUID, AbilitySystem> systems = new ConcurrentHashMap<>();

    private AbilitySystem findSystem(Player player) {
        return systems.get(player.getUniqueId());
    }

    private Ability systemAbility(Player player) {
        AbilitySystem system = systems.get(player.getUniqueId());
        if (system == null) return null;
        return system.getState(player).getAbility();
    }

    @Override
    public void onMove(AbilitySystem system, org.bukkit.event.player.PlayerMoveEvent event) {
        // 기자 능력 보유자에게 system 참조 저장
        systems.put(event.getPlayer().getUniqueId(), system);
    }

    @Override
    public void onAttack(AbilitySystem system, org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            systems.put(p.getUniqueId(), system);
        }
    }

    @Override
    public void onDamage(AbilitySystem system, org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            systems.put(p.getUniqueId(), system);
        }
    }

    @Override
    public void onFish(AbilitySystem system, org.bukkit.event.player.PlayerFishEvent event) {
        systems.put(event.getPlayer().getUniqueId(), system);
    }

    @Override
    public void onConsume(AbilitySystem system, org.bukkit.event.player.PlayerItemConsumeEvent event) {
        systems.put(event.getPlayer().getUniqueId(), system);
    }

    private static class ReporterMenu implements InventoryHolder {
        private final UUID ownerId;
        private Inventory inventory;

        private ReporterMenu(UUID ownerId) {
            this.ownerId = ownerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}