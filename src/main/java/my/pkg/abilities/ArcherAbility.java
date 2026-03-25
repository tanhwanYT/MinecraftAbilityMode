package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class ArcherAbility implements Ability, Listener {

    private final NamespacedKey bowKey;
    private final NamespacedKey arrowKey;
    private final NamespacedKey potionArrowKey;

    private static final int START_ARROW_AMOUNT = 32;
    private static final int KILL_REWARD_ARROW_AMOUNT = 10;

    private static final Set<UUID> holders = ConcurrentHashMap.newKeySet();

    public ArcherAbility(NamespacedKey bowKey, NamespacedKey arrowKey, NamespacedKey potionArrowKey) {
        this.bowKey = bowKey;
        this.arrowKey = arrowKey;
        this.potionArrowKey = potionArrowKey;
    }

    @Override
    public String id() {
        return "archer";
    }

    @Override
    public String name() {
        return "아처";
    }

    @Override
    public int cooldownSeconds() {
        return 20;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        holders.add(player.getUniqueId());

        player.sendMessage("§a아처 : 근접 공격 피해가 0이 됩니다.");
        player.sendMessage("§7전용 활과 화살 32개를 지급받습니다.");
        player.sendMessage("§7플레이어를 처치할 때마다 화살 10개를 얻습니다.");
        player.sendMessage("§7능력 사용 시 랜덤 포션 화살을 얻습니다.");

        giveArcherItems(player);
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        holders.remove(player.getUniqueId());
        removeArcherItems(player);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        ItemStack potionArrow = createRandomPotionArrow();
        player.getInventory().addItem(potionArrow);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        player.sendMessage("§a[아처] §f랜덤 포션 화살을 획득했습니다!");
        return true;
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!holders.contains(attacker.getUniqueId())) return;

        ItemStack hand = attacker.getInventory().getItemInMainHand();
        Material type = hand.getType();

        // 맨손 or 검류 근접 피해 0
        if (type == Material.AIR
                || type == Material.WOODEN_SWORD
                || type == Material.STONE_SWORD
                || type == Material.IRON_SWORD
                || type == Material.GOLDEN_SWORD
                || type == Material.DIAMOND_SWORD
                || type == Material.NETHERITE_SWORD) {
            event.setDamage(0.0);
        }
    }

    private void giveArcherItems(Player player) {
        boolean hasBow = false;
        boolean hasArrow = false;

        for (ItemStack item : player.getInventory().getContents()) {
            if (isArcherBow(item)) hasBow = true;
            if (isArcherArrow(item)) hasArrow = true;
        }

        if (!hasBow) {
            player.getInventory().addItem(createArcherBow());
        }
        if (!hasArrow) {
            player.getInventory().addItem(createArcherArrow(START_ARROW_AMOUNT));
        }
    }

    private void removeArcherItems(Player player) {
        PlayerInventory inv = player.getInventory();

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null) continue;

            if (isArcherBow(item) || isArcherArrow(item) || isPotionArrow(item)) {
                inv.setItem(i, null);
            }
        }

        player.updateInventory();
    }

    private ItemStack createArcherBow() {
        ItemStack item = new ItemStack(Material.BOW, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a아처의 활");
            meta.setLore(List.of(
                    "§7버릴 수 없는 전용 활",
                    "§7빗나갈때는 활탓을 해보세요"
            ));
            meta.getPersistentDataContainer().set(bowKey, PersistentDataType.BYTE, (byte) 1);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createArcherArrow(int amount) {
        ItemStack item = new ItemStack(Material.ARROW, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§f아처의 화살");
            meta.setLore(List.of(
                    "§7버릴 수 없는 전용 화살",
                    "§7능력 교체 시 함께 사라집니다."
            ));
            meta.getPersistentDataContainer().set(arrowKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createRandomPotionArrow() {
        PotionType[] pool = {
                PotionType.POISON,
                PotionType.SLOWNESS,
                PotionType.WEAKNESS,
                PotionType.HARMING,
                PotionType.HEALING
        };

        PotionType picked = pool[ThreadLocalRandom.current().nextInt(pool.length)];

        ItemStack item = new ItemStack(Material.TIPPED_ARROW, 1);
        ItemMeta rawMeta = item.getItemMeta();
        if (rawMeta instanceof PotionMeta meta) {
            meta.setDisplayName("§d랜덤 포션 화살");
            meta.setLore(List.of(
                    "§7능력으로 획득한 포션 화살",
                    "§7효과: " + potionName(picked)
            ));
            meta.setBasePotionType(picked);
            meta.getPersistentDataContainer().set(potionArrowKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String potionName(PotionType type) {
        return switch (type) {
            case POISON -> "독";
            case SLOWNESS -> "감속";
            case WEAKNESS -> "나약함";
            case HARMING -> "즉시 피해";
            case HEALING -> "즉시 회복";
            default -> type.name();
        };
    }

    private boolean isArcherBow(ItemStack item) {
        if (item == null || item.getType() != Material.BOW || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(bowKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private boolean isArcherArrow(ItemStack item) {
        if (item == null || item.getType() != Material.ARROW || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(arrowKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private boolean isPotionArrow(ItemStack item) {
        if (item == null || item.getType() != Material.TIPPED_ARROW || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(potionArrowKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;
        if (!holders.contains(killer.getUniqueId())) return;

        ItemStack reward = createArcherArrow(KILL_REWARD_ARROW_AMOUNT);
        killer.getInventory().addItem(reward);
        killer.playSound(killer.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 0.8f, 1.3f);
        killer.sendMessage("§a[아처] §f플레이어 처치 보상으로 화살 §e" + KILL_REWARD_ARROW_AMOUNT + "개§f를 획득했습니다!");
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();

        if (isArcherBow(item) || isArcherArrow(item)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c[아처] 전용 장비는 버릴 수 없습니다.");
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (isArcherBow(current) || isArcherArrow(current)
                || isArcherBow(cursor) || isArcherArrow(cursor)) {

            if (event.isShiftClick() || event.getClick().isKeyboardClick()) {
                event.setCancelled(true);
            }

            if (event.getSlot() == -999) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        Arrow arrow = (Arrow) event.getArrow();
        AbstractArrow.PickupStatus status = arrow.getPickupStatus();

        if (status == AbstractArrow.PickupStatus.DISALLOWED) {
            event.setCancelled(true);
        }
    }
}